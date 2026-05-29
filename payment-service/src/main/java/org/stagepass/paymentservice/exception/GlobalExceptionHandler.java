package org.stagepass.paymentservice.exception;

import org.stagepass.paymentservice.dto.ChargeResponse;
import org.stagepass.paymentservice.entity.PaymentRecord;
import org.stagepass.paymentservice.entity.PaymentStatus;
import org.stagepass.paymentservice.repository.PaymentRecordRepository;
import org.stagepass.paymentservice.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GLOBAL EXCEPTION HANDLER
 *
 * Centralises all exception → HTTP response mapping for the Payment Service.
 *
 * ── CRITICAL DESIGN DECISION: PaymentFailedException → HTTP 200 ──────────────
 *
 * When a payment fails (card declined, gateway error), this handler returns
 * HTTP 200 with a ChargeResponse body containing status="FAILED".
 *
 * Why NOT return HTTP 402 Payment Required?
 * Booking Service calls this via Feign. By default, Feign maps 4xx/5xx
 * responses to FeignException and throws — bypassing BookingService's
 * normal if/else status check logic.
 * To handle 402 correctly, BookingService would need a custom Feign
 * ErrorDecoder that manually reads the body and constructs a ChargeResponse.
 * That's complex, fragile, and error-prone.
 *
 * Returning HTTP 200 with status=FAILED keeps the contract simple:
 * Booking Service always reads paymentResponse.getStatus() to decide
 * whether to confirm or compensate — regardless of payment outcome.
 *
 * This is a deliberate API contract between Payment Service and its callers.
 * Document it clearly in the service's API spec.
 *
 * ── DUPLICATE PAYMENT: HTTP 200 with cached response ────────────────────────
 * DuplicatePaymentException → return the cached ChargeResponse directly.
 * Idempotency is transparent to the caller — they get the same response
 * whether it's the first call or the tenth retry.
 *
 * ── INVALID WEBHOOK: HTTP 400 ────────────────────────────────────────────────
 * InvalidWebhookException → 400 Bad Request.
 * Unlike payment failures, a bad webhook signature is genuinely bad input.
 * Razorpay does not retry on 400 — correct for invalid (potentially forged) requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired(required = false)
    private UserContext userContext;

    @Autowired
    private PaymentRecordRepository paymentRepo;

    // ── PAYMENT FAILED: HTTP 200 (by design) ─────────────────────────────────

    /**
     * PaymentFailedException → HTTP 200 with FAILED status in body.
     *
     * Returns ChargeResponse (not ErrorResponse) so Booking Service can
     * read the status field with its standard response-handling code.
     * The gatewayErrorCode and retriable fields help the frontend show
     * appropriate messaging to the user.
     */
    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ChargeResponse> handlePaymentFailed(
            PaymentFailedException ex, HttpServletRequest request) {

        log.warn("Payment failed: code={} message={} path={}",
                ex.getGatewayErrorCode(), ex.getMessage(), request.getRequestURI());

        ChargeResponse response = ChargeResponse.failed(null, ex.getMessage());
//        response.setGatewayErrorCode(ex.getGatewayErrorCode());
//        response.setRetriable(ex.isRetriable());

        // HTTP 200 — Booking Service reads response.status to determine outcome
        return ResponseEntity.ok(response);
    }

    // ── DUPLICATE PAYMENT: HTTP 200 with cached result ────────────────────────

    /**
     * DuplicatePaymentException → HTTP 200.
     * Idempotency is transparent — caller gets the same result as the first call.
     * We construct a generic FAILED response here; in practice, PaymentService
     * returns the cached result directly before throwing this exception.
     */
    @ExceptionHandler(DuplicatePaymentException.class)
    public ResponseEntity<ChargeResponse> handleDuplicatePayment(
            DuplicatePaymentException ex, HttpServletRequest request) {

        log.info("Duplicate payment request detected: bookingId={} path={}",
                ex.getBookingId(), request.getRequestURI());

        // Idempotent 200 — in practice PaymentService handles this before
        // this handler is reached (returns cached result from IdempotencyService)
        ChargeResponse response = ChargeResponse.builder()
            .status("DUPLICATE")
            .failureReason(ex.getMessage())
            .build();

        return ResponseEntity.ok(response);
    }

    // ── INVALID WEBHOOK: HTTP 400 ─────────────────────────────────────────────

    /**
     * InvalidWebhookException → HTTP 400.
     * Bad or forged webhook signature — reject with 400.
     * Razorpay does not retry on 400 (correct behaviour for invalid requests).
     */
    @ExceptionHandler(InvalidWebhookException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhook(
            InvalidWebhookException ex, HttpServletRequest request) {

        log.warn("Invalid webhook: message={} remoteAddr={}",
                ex.getMessage(), request.getRemoteAddr());

        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        log.warn("Access denied: path={} method={}",
                request.getRequestURI(), request.getMethod());

        return build(HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action.", request);
    }

    // ── 409 CONFLICT ─────────────────────────────────────────────────────────

    /**
     * IllegalStateException — invalid state transitions.
     * Example: attempting to refund a FAILED payment.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {

        log.warn("Illegal state: {} path={}", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * IllegalArgumentException — invalid input.
     * Example: payment not found for a refund request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("Illegal argument: {} path={}", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // ── 400 BAD REQUEST ───────────────────────────────────────────────────────

    /**
     * @Valid validation failure — returns field-level errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        log.warn("Validation failed: fields={} path={}",
                fieldErrors.keySet(), request.getRequestURI());

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, "Input validation failed", request);
        response.setFieldErrors(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Wrong type in path variable — e.g. non-UUID bookingId.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format("Invalid value '%s' for parameter '%s'",
                ex.getValue(), ex.getName());

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    // ── 500 INTERNAL SERVER ERROR ─────────────────────────────────────────────

    /**
     * Handles Optimistic Locking Failure when a concurrent webhook update successfully
     * commits the transaction before the synchronous charge thread does.
     *
     * In this case, the database record is already in a SUCCESS state. We intercept the
     * exception, check if the record is indeed SUCCESS, and if so, return a standard
     * HTTP 200 SUCCESS response to the client instead of a 500 Internal Server Error.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLocking(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {

        Object identifier = ex.getIdentifier();
        log.info("Optimistic locking failure detected: entity={} id={}. Checking database status...",
                ex.getPersistentClassName(), identifier);

        if (identifier != null) {
            UUID paymentId = null;
            if (identifier instanceof UUID) {
                paymentId = (UUID) identifier;
            } else {
                try {
                    paymentId = UUID.fromString(identifier.toString());
                } catch (IllegalArgumentException ignored) {}
            }

            if (paymentId != null) {
                Optional<PaymentRecord> recordOpt = paymentRepo.findById(paymentId);
                if (recordOpt.isPresent() && recordOpt.get().getStatus() == PaymentStatus.SUCCESS) {
                    log.info("Concurrent transaction (webhook) has already successfully updated paymentId={} to SUCCESS. " +
                            "Bypassing conflict and returning SUCCESS response.", paymentId);
                    ChargeResponse response = toChargeResponse(recordOpt.get());
                    return ResponseEntity.ok(response);
                }
            }
        }

        // If not successful or not found, fall back to standard 500
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "A conflict occurred during payment processing. Please try again.", request);
    }

    /**
     * Catch-all — stack trace logged server-side, never in response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled exception: path={} error={}",
                request.getRequestURI(), ex.getMessage(), ex);

        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

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

    // ── PRIVATE BUILDERS ──────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(buildErrorResponse(status, message, request));
    }

    private ErrorResponse buildErrorResponse(
            HttpStatus status, String message, HttpServletRequest request) {

        ErrorResponse response = new ErrorResponse();
        response.setStatus(status.value());
        response.setError(status.getReasonPhrase());
        response.setMessage(message);
        response.setPath(request.getRequestURI());
        response.setTimestamp(Instant.now().toString());

        if (userContext != null && userContext.getTraceId() != null) {
            response.setTraceId(userContext.getTraceId());
        } else {
            String traceId = request.getHeader("X-Trace-Id");
            response.setTraceId(traceId != null ? traceId : "unavailable");
        }

        return response;
    }
}