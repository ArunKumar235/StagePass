package org.stagepass.paymentservice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.stagepass.paymentservice.dto.ChargeResponse;
import org.stagepass.paymentservice.dto.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * IDEMPOTENCY SERVICE
 *
 * Provides idempotency for charge and refund operations using Redis.
 * Prevents double-charging and double-refunding when callers retry after
 * a network timeout or connection failure.
 *
 * ── KEY STRUCTURE ────────────────────────────────────────────────────────────
 * Charge:  "payment:idempotency:{bookingId}"  → ChargeResponse (JSON)
 * Refund:  "refund:idempotency:{paymentId}"   → RefundResponse (JSON)
 *
 * ── TTL ──────────────────────────────────────────────────────────────────────
 * 1 hours (configurable).
 * Booking Service's Feign retry window is seconds-to-minutes — 1h is vastly
 * longer than needed, but provides a safety buffer for late retries.
 * After 1h, Redis auto-evicts the key and a fresh payment attempt is allowed.
 *
 * ── IDEMPOTENCY FOR FAILURES TOO ────────────────────────────────────────────
 * Critically, we cache BOTH success AND failure results.
 * If a charge failed (card declined), and Booking Service retries,
 * we return FAILED again without hitting Razorpay.
 * The user must create a new booking with a different payment method to retry.
 * This prevents the user from hammering the same failed payment repeatedly.
 *
 * ── REDIS RESTART EDGE CASE ─────────────────────────────────────────────────
 * If Redis restarts and loses idempotency keys, PaymentService falls back to
 * the DB check (findByBookingId) before hitting Razorpay. This belt-and-suspenders
 * approach ensures correctness even when Redis is temporarily unavailable.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final String CHARGE_KEY_PREFIX = "payment:idempotency:";
    private static final String REFUND_KEY_PREFIX  = "refund:idempotency:";

    @Value("${stagepass.payment.idempotency-ttl-hours}")
    private long idempotencyTtlHours;

    @Qualifier("idempotencyRedisTemplate")
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ── CHARGE ────────────────────────────────────────────────────────────────

    /**
     * Checks if a charge result already exists for this bookingId.
     *
     * @param bookingId the booking UUID string used as idempotency key
     * @return Optional containing the cached ChargeResponse, or empty if not found
     */
    public Optional<ChargeResponse> getChargeResult(String bookingId) {
        try {
            Object cached = redisTemplate.opsForValue()
                    .get(CHARGE_KEY_PREFIX + bookingId);

            if (cached instanceof ChargeResponse) {
                return Optional.of((ChargeResponse) cached);
            }
            return Optional.empty();
        } catch (Exception e) {
            // Redis failure — fall through to DB check in PaymentService
            log.warn("Redis read failed for charge idempotency key: bookingId={} error={}",
                    bookingId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores the charge result for this bookingId with 1h TTL.
     * Called after every charge attempt — success or failure.
     *
     * @param bookingId the booking UUID string
     * @param response  the ChargeResponse to cache
     */
    public void storeChargeResult(String bookingId, ChargeResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    CHARGE_KEY_PREFIX + bookingId,
                    response,
                    Duration.ofHours(idempotencyTtlHours)
            );
            log.debug("Charge idempotency stored: bookingId={} status={} ttl={}h",
                    bookingId, response.status(), idempotencyTtlHours);
        } catch (Exception e) {
            // Log but don't fail — DB is the fallback
            log.warn("Failed to store charge idempotency key: bookingId={} error={}",
                    bookingId, e.getMessage());
        }
    }

    // ── REFUND ────────────────────────────────────────────────────────────────

    /**
     * Checks if a refund result already exists for this paymentId.
     *
     * @param paymentId the payment UUID string used as idempotency key
     * @return Optional containing the cached RefundResponse, or empty if not found
     */
    public Optional<RefundResponse> getRefundResult(String paymentId) {
        try {
            Object cached = redisTemplate.opsForValue()
                    .get(REFUND_KEY_PREFIX + paymentId);

            if (cached instanceof RefundResponse) {
                return Optional.of((RefundResponse) cached);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis read failed for refund idempotency key: paymentId={} error={}",
                    paymentId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores the refund result for this paymentId with 1h TTL.
     *
     * @param paymentId the payment UUID string
     * @param response  the RefundResponse to cache
     */
    public void storeRefundResult(String paymentId, RefundResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    REFUND_KEY_PREFIX + paymentId,
                    response,
                    Duration.ofHours(idempotencyTtlHours)
            );
            log.debug("Refund idempotency stored: paymentId={} status={}",
                    paymentId, response.status());
        } catch (Exception e) {
            log.warn("Failed to store refund idempotency key: paymentId={} error={}",
                    paymentId, e.getMessage());
        }
    }

    // ── EVICTION (admin use) ──────────────────────────────────────────────────

    /**
     * Manually evicts a charge idempotency key.
     * Used in admin tools to allow re-attempting a failed payment for the same booking.
     * Should only be called after explicit admin review — not as a routine operation.
     */
    public void evictChargeKey(String bookingId) {
        redisTemplate.delete(CHARGE_KEY_PREFIX + bookingId);
        log.info("Charge idempotency key evicted: bookingId={}", bookingId);
    }
}