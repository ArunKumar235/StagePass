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

    // ── CHARGE ────────────────────────────────────────────────────────────────

    @Transactional
    public ChargeResponse charge(ChargeRequest request) {
        String bookingId = request.bookingId().toString();

        log.info("Charge request: bookingId={} amount={} method={}",
                bookingId, request.amount(), request.paymentMethod());

        // ── STEP 1: Redis idempotency check ───────────────────────────────
        Optional<ChargeResponse> cached = idempotencyService.getChargeResult(bookingId);
        if (cached.isPresent()) {
            log.info("Idempotency hit (Redis): bookingId={} — returning cached result",
                    bookingId);
            return cached.get();
        }

        // ── STEP 2: DB guard — catches Redis cache miss (e.g. Redis restart) ──
        Optional<PaymentRecord> existing = paymentRepo.findByBookingId(request.bookingId());
        if (existing.isPresent()) {
            log.info("Idempotency hit (DB): bookingId={} — returning existing record", bookingId);
            ChargeResponse response = toChargeResponse(existing.get());
            idempotencyService.storeChargeResult(bookingId, response); // re-populate Redis
            return response;
        }

        // ── STEP 3: Reject reused Razorpay payment token ──────────────────────
        String gatewayPaymentId = request.paymentToken();
        Optional<PaymentRecord> tokenOwner = paymentRepo.findByGatewayPaymentId(gatewayPaymentId);
        if (tokenOwner.isPresent()) {
            PaymentRecord record = tokenOwner.get();
            if (record.getBookingId().equals(request.bookingId())) {
                log.info("Payment token already processed for the same booking: bookingId={} token={}",
                        bookingId, gatewayPaymentId);
                ChargeResponse response = toChargeResponse(record);
                idempotencyService.storeChargeResult(bookingId, response);
                return response;
            }

            throw new PaymentFailedException(
                    "Payment token already used by another payment: " + gatewayPaymentId,
                    "DUPLICATE_PAYMENT_TOKEN");
        }

        // ── STEP 4: Create PENDING PaymentRecord ──────────────────────────
        PaymentRecord record = new PaymentRecord();
        record.setBookingId(request.bookingId());
        record.setUserId(request.userId());
        record.setAmount(request.amount());
        record.setCurrency(request.currency());
        record.setStatus(PaymentStatus.PENDING);
        record.setMethod(request.paymentMethod());
        record.setGatewayPaymentId(gatewayPaymentId);
        LocalDateTime now = LocalDateTime.now();
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        try {
            record = paymentRepo.save(record);
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate payment token rejected by database: bookingId={} token={}",
                    bookingId, gatewayPaymentId);
            throw new PaymentFailedException(
                    "Payment token already used by another payment: " + gatewayPaymentId,
                    "DUPLICATE_PAYMENT_TOKEN");
        }

        // ── STEP 5: Call Razorpay ─────────────────────────────────────────
        ChargeResponse response;
        try {
            response = gatewayService.charge(request, record.getId());

            // ── STEP 6: Update record based on gateway result ─────────────
            if ("SUCCESS".equals(response.status())) {
                record.setStatus(PaymentStatus.SUCCESS);
                record.setGatewayPaymentId(response.gatewayPaymentId());
                record.setUpdatedAt(LocalDateTime.now());
            } else {
                record.setStatus(PaymentStatus.FAILED);
                record.setFailureReason(response.failureReason());
                record.setUpdatedAt(LocalDateTime.now());
            }

        } catch (PaymentFailedException e) {
            log.error("Payment gateway error: bookingId={} error={}", bookingId, e.getMessage());
            record.setStatus(PaymentStatus.FAILED);
            record.setFailureReason(e.getMessage());
            record.setUpdatedAt(LocalDateTime.now());

            response = ChargeResponse.failed(
                    record.getId().toString(), e.getMessage());
        }

        paymentRepo.save(record);

        // ── STEP 7: Store in idempotency cache ────────────────────────────
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