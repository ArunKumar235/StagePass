package org.stagepass.bookingservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * SEAT LOCK SERVICE
 *
 * Implements distributed seat locking using Redis.
 * Prevents double-booking when thousands of users simultaneously
 * click "Book" for the same seat during a flash sale.
 *
 * ── HOW IT WORKS ────────────────────────────────────────────────
 *
 * LOCK:
 *   Redis command: SET seat:lock:{seatId} {userId} NX EX 600
 *   - NX = "only set if Not eXists" — atomic check-and-set
 *   - EX 600 = auto-expire after 600 seconds (10 minutes)
 *   - Returns true if lock acquired, false if already locked
 *
 *   Two users hitting "Book" simultaneously for seat ABC:
 *   - User A: SET seat:lock:ABC userA NX EX 600 → OK (lock acquired)
 *   - User B: SET seat:lock:ABC userB NX EX 600 → nil (already locked)
 *   User A proceeds to payment. User B gets 409 immediately.
 *   No race condition possible — SET NX is atomic at the Redis level.
 *
 * RELEASE:
 *   Cannot use simple DEL — what if UserA's lock expired and UserB acquired it?
 *   Deleting without checking would release UserB's lock — catastrophic.
 *
 *   Solution: Lua script executed atomically:
 *     if redis.call("GET", key) == userId then
 *       return redis.call("DEL", key)
 *     else
 *       return 0
 *     end
 *
 *   GET + conditional DEL in one atomic operation.
 *   Redis executes Lua scripts as a single uninterruptible command.
 *
 * TTL EXPIRY:
 *   If the user abandons checkout (closes browser), the lock auto-expires
 *   after 600 seconds. No manual cleanup needed for the happy path.
 *   ExpiredBookingScheduler handles the DB side of cleanup.
 *
 * ── LOCK KEY FORMAT ────────────────────────────────────────────
 *   "seat:lock:{seatId}"
 *   Example: "seat:lock:550e8400-e29b-41d4-a716-446655440000"
 *
 * ── WHY REDIS AND NOT DB LOCKING ──────────────────────────────
 *   DB-level locking (SELECT FOR UPDATE) holds a DB connection for the
 *   entire checkout duration (up to 10 min) — connection pool exhaustion
 *   under flash sale load. Redis TTL locks are connection-free after SET.
 */
@Service
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);

    private static final String LOCK_KEY_PREFIX = "seat:lock:";

    @Value("${stagepass.booking.seat-lock-ttl-seconds}")
    private long seatLockTtlSeconds;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * Lua script for atomic check-and-release.
     *
     * Logic:
     *   1. GET the lock value
     *   2. If it equals the requesting userId → DEL the key → return 1 (released)
     *   3. If it doesn't match → return 0 (not our lock, don't touch it)
     *
     * This entire script executes atomically — Redis blocks all other
     * commands while running a Lua script. No other thread can sneak in
     * between the GET and the DEL.
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('DEL', KEYS[1]) " +
                        "else " +
                        "  return 0 " +
                        "end"
        );
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    // ── LOCK ─────────────────────────────────────────────────────────────────

    /**
     * Acquires a distributed lock on a seat.
     *
     * @param seatId UUID of the seat to lock
     * @param userId UUID of the user attempting to lock
     * @return true if lock acquired, false if seat is already locked
     *
     * Internally executes: SET seat:lock:{seatId} {userId} NX EX {ttl}
     */
    public boolean lockSeat(UUID seatId, UUID userId) {
        String key   = lockKey(seatId);
        String value = userId.toString();

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofSeconds(seatLockTtlSeconds));

        boolean result = Boolean.TRUE.equals(acquired);

        if (result) {
            log.debug("Seat lock acquired: seatId={} userId={} ttl={}s",
                    seatId, userId, seatLockTtlSeconds);
        } else {
            log.debug("Seat lock FAILED (already locked): seatId={} requestingUserId={}",
                    seatId, userId);
        }

        return result;
    }

    /**
     * Acquires locks on multiple seats atomically (best-effort).
     *
     * Attempts to lock each seat in order. If any lock fails (seat already
     * taken), rolls back ALL previously acquired locks and returns false.
     *
     * This is NOT a true atomic multi-lock (Redis doesn't support that without
     * Redlock or Lua). But it's safe because:
     * - If we fail partway, we rollback before returning → no orphaned locks
     * - The booking is rejected → user sees 409 for specific unavailable seats
     *
     * @param seatIds list of seat UUIDs to lock
     * @param userId  the user acquiring the locks
     * @return true if ALL seats locked successfully, false if any failed
     */
    public boolean lockSeats(List<UUID> seatIds, UUID userId) {
        List<UUID> acquiredLocks = new ArrayList<>();

        for (UUID seatId : seatIds) {
            if (lockSeat(seatId, userId)) {
                acquiredLocks.add(seatId);
            } else {
                // Failed — rollback all locks acquired so far
                log.warn("Seat lock failed for seatId={}. Rolling back {} acquired locks.",
                        seatId, acquiredLocks.size());
                acquiredLocks.forEach(lockedId -> releaseLock(lockedId, userId));
                return false;
            }
        }

        log.info("All {} seat locks acquired for userId={}", seatIds.size(), userId);
        return true;
    }

    // ── RELEASE ───────────────────────────────────────────────────────────────

    /**
     * Releases a seat lock — but ONLY if the requesting user owns it.
     *
     * Executes the Lua script atomically:
     *   if GET key == userId → DEL key → return 1
     *   else                 → return 0 (someone else's lock — leave it)
     *
     * @param seatId UUID of the seat to unlock
     * @param userId UUID of the user releasing the lock
     * @return true if lock was released, false if userId didn't own the lock
     */
    public boolean releaseLock(UUID seatId, UUID userId) {
        String key   = lockKey(seatId);
        String value = userId.toString();

        Long result = redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                Collections.singletonList(key),
                value
        );

        boolean released = Long.valueOf(1L).equals(result);

        if (released) {
            log.debug("Seat lock released: seatId={} userId={}", seatId, userId);
        } else {
            log.warn("Seat lock release FAILED: seatId={} userId={} " +
                            "(lock not owned by this user or already expired)",
                    seatId, userId);
        }

        return released;
    }

    /**
     * Releases locks for multiple seats.
     * Used during Saga compensation (payment failed → release all seat locks).
     *
     * @param seatIds list of seat UUIDs to unlock
     * @param userId  the user releasing the locks
     */
    public void releaseLocks(List<UUID> seatIds, UUID userId) {
        seatIds.forEach(seatId -> releaseLock(seatId, userId));
        log.info("Released {} seat locks for userId={}", seatIds.size(), userId);
    }

    // ── CHECK ─────────────────────────────────────────────────────────────────

    /**
     * Checks whether a seat is currently locked in Redis.
     *
     * Used by ExpiredBookingScheduler to detect stale PENDING bookings
     * whose Redis TTL has already expired (lock gone) but the DB still
     * shows PENDING status.
     *
     * @param seatId UUID of the seat to check
     * @return true if the seat is locked, false if lock has expired or never existed
     */
    public boolean isLocked(UUID seatId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(seatId)));
    }

    /**
     * Returns the userId who currently holds the lock on a seat.
     * Null if no lock exists.
     * Used for debugging and admin tools only.
     *
     * @param seatId UUID of the seat
     * @return userId string or null
     */
    public String getLockHolder(UUID seatId) {
        return redisTemplate.opsForValue().get(lockKey(seatId));
    }

    /**
     * Returns the remaining TTL for a seat lock in seconds.
     * Returns -1 if key exists with no TTL, -2 if key doesn't exist.
     * Used by BookingController to return expiresAt in BookingResponse.
     *
     * @param seatId UUID of the seat
     * @return remaining TTL in seconds
     */
    public long getRemainingTtl(UUID seatId) {
        Duration ttl = Duration.ofSeconds(redisTemplate.getExpire(lockKey(seatId), TimeUnit.SECONDS));
        return ttl != null ? ttl.getSeconds() : -2L;
    }

    // ── PRIVATE ───────────────────────────────────────────────────────────────

    private String lockKey(UUID seatId) {
        return LOCK_KEY_PREFIX + seatId.toString();
    }
}