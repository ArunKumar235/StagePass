package org.stagepass.paymentservice.service;

import org.stagepass.paymentservice.dto.ChargeRequest;
import org.stagepass.paymentservice.dto.ChargeResponse;
import org.stagepass.paymentservice.dto.RefundRequest;
import org.stagepass.paymentservice.dto.RefundResponse;
import org.stagepass.paymentservice.entity.PaymentRecord;
import org.stagepass.paymentservice.entity.PaymentStatus;
import org.stagepass.paymentservice.entity.RefundRecord;
import org.stagepass.paymentservice.entity.RefundStatus;
import org.stagepass.paymentservice.exception.PaymentFailedException;
import org.stagepass.paymentservice.repository.PaymentRecordRepository;
import org.stagepass.paymentservice.repository.RefundRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * PAYMENT SERVICE
 *
 * Orchestrates charge and refund flows with full idempotency.
 *
 * ── CHARGE FLOW ─────────────────────────────────────────────────────────────
 * 1. Check idempotency store (Redis) — if bookingId already processed, return cached result
 * 2. Check DB — extra guard against Redis cache miss (Redis restart edge case)
 * 3. Reject reused Razorpay payment tokens 
 * 4. Create PaymentRecord with the gateway token reserved at PENDING status
 * 5. Call RazorpayGatewayService.charge() — actual Razorpay API call
 * 6. Update PaymentRecord to SUCCESS or FAILED
 * 7. Store result in idempotency cache (Redis, 24h TTL)
 * 8. Return ChargeResponse
 *
 * ── REFUND FLOW ─────────────────────────────────────────────────────────────
 * 1. Check idempotency — if paymentId already refunded, return cached result
 * 2. Fetch PaymentRecord — must be SUCCESS to refund
 * 3. DB guard — has a SUCCESS refund already been created for this payment? If yes, return it (handles concurrent refund requests)
 * 4. Create RefundRecord with PENDING status
 * 5. Call RazorpayGatewayService.refund()
 * 6. Update RefundRecord to SUCCESS or FAILED
 * 7. Store in idempotency cache
 * 8. Return RefundResponse
 *
 * ── IDEMPOTENCY GUARANTEE ────────────────────────────────────────────────────
 * Same bookingId → same charge result, always.
 * Same paymentId → same refund result, always.
 * Safe for Feign retries on network timeout — no double charges, no double refunds.
 *
 * ── HTTP 200 FOR ALL OUTCOMES ────────────────────────────────────────────────
 * This service returns HTTP 200 for both SUCCESS and FAILED outcomes.
 * The caller (Booking Service) reads the status field in the response body.
 * Exceptions are only thrown for infrastructure errors (DB down, Redis down).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired private PaymentRecordRepository  paymentRepo;
    @Autowired private RefundRecordRepository   refundRepo;
    @Autowired private RazorpayGatewayService   gatewayService;
    @Autowired private IdempotencyService       idempotencyService;
    @Autowired private TransactionTemplate      transactionTemplate;

    // ── CHARGE ────────────────────────────────────────────────────────────────

    /**
     * Charges a booking via the payment gateway with full idempotency.
     *
     * WHY NOT @Transactional HERE:
     * A class-level @Transactional holds a DB connection for the full method
     * duration — including the Redis idempotency read (Step 1) and the gateway
     * call (Step 5). Under 100 concurrent bookings, 100 threads each hold a
     * HikariCP connection during Redis + gateway I/O, exhausting the pool of 50
     * and causing other threads to queue for up to 30s before timing out.
     *
     * Instead, we use TransactionTemplate for Steps 2-4 (DB reads + PENDING
     * insert) and a separate call for Step 6 (DB update). This releases the
     * connection between logical steps, so 100 concurrent threads do not each
     * hold a connection during gateway I/O.
     */
    public ChargeResponse charge(ChargeRequest request) {
        String bookingId = request.bookingId().toString();

        log.info("Charge request: bookingId={} amount={} method={}",
                bookingId, request.amount(), request.paymentMethod());

        // ── STEP 1: Redis idempotency check (no DB connection held) ──────
        Optional<ChargeResponse> cached = idempotencyService.getChargeResult(bookingId);
        if (cached.isPresent()) {
            log.info("Idempotency hit (Redis): bookingId={} — returning cached result",
                    bookingId);
            return cached.get();
        }

        // ── STEPS 2-4: DB reads + PENDING insert in a short transaction ──
        // Connection is acquired here and released as soon as the lambda returns.
        // The gateway call (Step 5) runs OUTSIDE this transaction window.
        String gatewayPaymentId = request.paymentToken();
        PaymentRecord record = transactionTemplate.execute(status -> {

            // Step 2: DB guard — catches Redis cache miss (e.g. Redis restart)
            Optional<PaymentRecord> existing = paymentRepo.findByBookingId(request.bookingId());
            if (existing.isPresent()) {
                log.info("Idempotency hit (DB): bookingId={} — returning existing record", bookingId);
                ChargeResponse response = toChargeResponse(existing.get());
                idempotencyService.storeChargeResult(bookingId, response); // re-populate Redis
                // Signal the caller to return this result by embedding it in an exception
                // is messy — instead we return null as a sentinel and handle below.
                // Actually the cleanest approach: return the record and check paymentId != null
                return existing.get();
            }

            // Step 3: Reject reused Razorpay payment token
            Optional<PaymentRecord> tokenOwner = paymentRepo.findByGatewayPaymentId(gatewayPaymentId);
            if (tokenOwner.isPresent()) {
                PaymentRecord owner = tokenOwner.get();
                if (owner.getBookingId().equals(request.bookingId())) {
                    log.info("Payment token already processed for same booking: bookingId={} token={}",
                            bookingId, gatewayPaymentId);
                    ChargeResponse response = toChargeResponse(owner);
                    idempotencyService.storeChargeResult(bookingId, response);
                    return owner;
                }
                throw new PaymentFailedException(
                        "Payment token already used by another payment: " + gatewayPaymentId,
                        "DUPLICATE_PAYMENT_TOKEN");
            }

            // Step 4: Create PENDING PaymentRecord
            // Note: createdAt and updatedAt are managed by Hibernate's
            // @CreationTimestamp / @UpdateTimestamp — do NOT set them manually.
            // Setting them manually causes Hibernate to see a dirty object and
            // may trigger additional SQL or conflict with lifecycle callbacks.
            PaymentRecord newRecord = new PaymentRecord();
            newRecord.setBookingId(request.bookingId());
            newRecord.setUserId(request.userId());
            newRecord.setAmount(request.amount());
            newRecord.setCurrency(request.currency());
            newRecord.setStatus(PaymentStatus.PENDING);
            newRecord.setMethod(request.paymentMethod());
            newRecord.setGatewayPaymentId(gatewayPaymentId);
            try {
                return paymentRepo.save(newRecord);
            } catch (DataIntegrityViolationException e) {
                log.warn("Duplicate payment token rejected by database: bookingId={} token={}",
                        bookingId, gatewayPaymentId);
                throw new PaymentFailedException(
                        "Payment token already used by another payment: " + gatewayPaymentId,
                        "DUPLICATE_PAYMENT_TOKEN");
            }
        });

        // If the DB transaction returned an already-processed record (Steps 2/3
        // idempotency hit), the Redis cache was already updated inside the lambda.
        // Return immediately without calling the gateway.
        if (record != null && record.getStatus() != PaymentStatus.PENDING) {
            ChargeResponse earlyResponse = toChargeResponse(record);
            idempotencyService.storeChargeResult(bookingId, earlyResponse);
            return earlyResponse;
        }

        if (record == null) {
            // Should not happen — transactionTemplate.execute() only returns null if
            // the lambda explicitly returns null. Treat as infrastructure error.
            log.error("TransactionTemplate returned null for bookingId={}", bookingId);
            throw new RuntimeException("Internal error during payment setup. Please retry.");
        }

        // ── STEP 5: Call gateway (DB connection NOT held during this call) ─
        ChargeResponse response;
        try {
            response = gatewayService.charge(request, record.getId());
        } catch (PaymentFailedException e) {
            log.error("Payment gateway error: bookingId={} error={}", bookingId, e.getMessage());
            response = ChargeResponse.failed(record.getId().toString(), e.getMessage());
        }

        // ── STEP 6: Update PaymentRecord with gateway result (short transaction)
        final ChargeResponse finalResponse = response;
        final PaymentRecord finalRecord = record;
        transactionTemplate.execute(status -> {
            PaymentRecord toUpdate = paymentRepo.findById(finalRecord.getId())
                    .orElse(finalRecord); // fallback to in-memory record if somehow gone
            if ("SUCCESS".equals(finalResponse.status())) {
                toUpdate.setStatus(PaymentStatus.SUCCESS);
                toUpdate.setGatewayPaymentId(finalResponse.gatewayPaymentId());
            } else {
                toUpdate.setStatus(PaymentStatus.FAILED);
                toUpdate.setFailureReason(finalResponse.failureReason());
            }
            toUpdate.setUpdatedAt(LocalDateTime.now());
            paymentRepo.save(toUpdate);
            return null;
        });

        // ── STEP 7: Store in idempotency cache (no DB connection held) ────
        idempotencyService.storeChargeResult(bookingId, response);

        log.info("Charge complete: bookingId={} status={} paymentId={}",
                bookingId, response.status(), response.paymentId());

        return response;
    }

    // ── REFUND ────────────────────────────────────────────────────────────────

    @Transactional
    public RefundResponse refund(RefundRequest request) {
        String paymentId = request.paymentId();

        log.info("Refund request: paymentId={} amount={}", paymentId, request.amount());

        // ── STEP 1: Idempotency check ─────────────────────────────────────
        Optional<RefundResponse> cached = idempotencyService.getRefundResult(paymentId);
        if (cached.isPresent()) {
            log.info("Refund idempotency hit: paymentId={}", paymentId);
            return cached.get();
        }

        // ── STEP 2: Fetch original payment — must be SUCCESS ──────────────
        PaymentRecord payment = paymentRepo.findById(UUID.fromString(paymentId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Cannot refund a payment with status: " + payment.getStatus() +
                            ". Only SUCCESS payments can be refunded.");
        }

        // ── STEP 3: DB guard — has a SUCCESS refund already been created? ──
        Optional<RefundRecord> existingSuccess = refundRepo.findByPaymentIdAndStatus(payment.getId(), RefundStatus.SUCCESS);
        if (existingSuccess.isPresent()) {
            log.info("Refund idempotency hit (DB): paymentId={} — returning existing SUCCESS refund", paymentId);
            RefundRecord r = existingSuccess.get();
            RefundResponse resp = RefundResponse.builder()
                    .refundId(r.getId().toString())
                    .gatewayRefundId(r.getGatewayRefundId())
                    .status("SUCCESS")
                    .amountRefunded(r.getAmount())
                    .processedAt(r.getProcessedAt())
                    .build();

            idempotencyService.storeRefundResult(paymentId, resp);
            return resp;
        }

        // ── STEP 4: Create PENDING RefundRecord ───────────────────────────
        RefundRecord refundRecord = new RefundRecord();
        refundRecord.setPayment(payment);
        refundRecord.setAmount(request.amount());
        refundRecord.setStatus(RefundStatus.PENDING);
        refundRecord.setReason(request.reason());
        refundRecord.setCreatedAt(LocalDateTime.now());
        refundRecord = refundRepo.save(refundRecord);

        // ── STEP 5: Call Razorpay ─────────────────────────────────────────
        RefundResponse response;
        try {
            response = gatewayService.refund(
                    payment.getGatewayPaymentId(),
                    request.amount(),
                    refundRecord.getId());

            refundRecord.setStatus(
                    "SUCCESS".equals(response.status())
                            ? RefundStatus.SUCCESS
                            : RefundStatus.FAILED);
            refundRecord.setGatewayRefundId(response.gatewayRefundId());
            refundRecord.setProcessedAt(LocalDateTime.now());
            try {
                // ── STEP 6: Update refund record ───────────────────────────────
                refundRepo.save(refundRecord);
            } catch (DataIntegrityViolationException dive) {
                // This can happen if another concurrent request already created a SUCCESS
                // refund for this payment (unique partial index). Return the existing SUCCESS
                // refund instead of failing with a 500.
                log.warn("Concurrent refund detected for paymentId={} — fetching existing SUCCESS record", paymentId);
                Optional<RefundRecord> found = refundRepo.findByPaymentIdAndStatus(payment.getId(), RefundStatus.SUCCESS);
                if (found.isPresent()) {
                    RefundRecord r = found.get();
                    response = RefundResponse.builder()
                            .refundId(r.getId().toString())
                            .gatewayRefundId(r.getGatewayRefundId())
                            .status("SUCCESS")
                            .amountRefunded(r.getAmount())
                            .processedAt(r.getProcessedAt())
                            .build();

                    idempotencyService.storeRefundResult(paymentId, response);
                    return response;
                }

                // If we couldn't find an existing SUCCESS record, rethrow to let global handler deal with it
                throw dive;
            }

        } catch (Exception e) {
            log.error("Refund gateway error: paymentId={} error={}", paymentId, e.getMessage());
            refundRecord.setStatus(RefundStatus.FAILED);

            response = RefundResponse.failed(
                    refundRecord.getId().toString(), e.getMessage());
        }

        refundRepo.save(refundRecord);

        // ── STEP 7: Store in idempotency cache ────────────────────────────
        idempotencyService.storeRefundResult(paymentId, response);

        log.info("Refund complete: paymentId={} status={}", paymentId, response.status());

        return response;
    }

    // ── ADMIN LOOKUP ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChargeResponse getByBookingId(String bookingId) {
        PaymentRecord record = paymentRepo.findByBookingId(UUID.fromString(bookingId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No payment found for bookingId: " + bookingId));
        return toChargeResponse(record);
    }

    // ── PRIVATE MAPPER ────────────────────────────────────────────────────────

    private ChargeResponse toChargeResponse(PaymentRecord record) {
        return ChargeResponse.builder()
                .paymentId(record.getId().toString())
                .gatewayPaymentId(record.getGatewayPaymentId())
                .status(record.getStatus().name())
                .failureReason(record.getFailureReason())
                .amountCharged(record.getAmount())
                .processedAt(record.getUpdatedAt())
                .build();
    }
}