package org.stagepass.bookingservice.exception;

import org.stagepass.bookingservice.security.UserContext;
import feign.FeignException;
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

/**
 * GLOBAL EXCEPTION HANDLER
 *
 * Centralises all exception → HTTP response mapping for the Booking Service.
 *
 * Booking-specific additions vs Event Service handler:
 * - SeatAlreadyLockedException → 409 with lockedSeatIds in response body
 * - BookingNotFoundException   → 404
 * - FeignException             → mapped to meaningful status (not raw Feign error)
 *
 * The lockedSeatIds field in ErrorResponse is unique to this service —
 * it lets the frontend highlight exactly which seats are taken on the seat map,
 * giving users actionable feedback instead of a generic error message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired(required = false)
    private UserContext userContext;

    // ── 409 CONFLICT ──────────────────────────────────────────────────────────

    /**
     * SeatAlreadyLockedException — one or more seats are LOCKED or BOOKED.
     * Returns the specific seatIds so the frontend can highlight them on the seat map.
     */
    @ExceptionHandler(SeatAlreadyLockedException.class)
    public ResponseEntity<ErrorResponse> handleSeatAlreadyLocked(
            SeatAlreadyLockedException ex, HttpServletRequest request) {

        log.warn("Seat conflict: lockedSeats={} path={}",
                ex.getLockedSeatIds(), request.getRequestURI());

        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request
        );
        response.setLockedSeatIds(ex.getLockedSeatIds()); // Booking-specific field

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * IllegalStateException — invalid booking state transitions.
     * Examples: cancelling an already-cancelled booking, publishing wrong-state event.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {

        log.warn("Illegal state: {} path={}", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * IllegalArgumentException — invalid arguments passed to a service method.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("Illegal argument: {} path={}", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // ── 404 NOT FOUND ─────────────────────────────────────────────────────────

    /**
     * BookingNotFoundException — booking not found OR belongs to another user.
     * Both cases return 404 — avoids revealing that the booking exists for someone else.
     */
    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(
            BookingNotFoundException ex, HttpServletRequest request) {

        log.warn("Booking not found: bookingId={} path={}",
                ex.getBookingId(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * AccessDeniedException — Spring Security role check failed.
     * Example: non-ADMIN calling /bookings/admin/**.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        log.warn("Access denied: path={} method={}",
                request.getRequestURI(), request.getMethod());
        return build(
                HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action.",
                request
        );
    }

    // ── 400 BAD REQUEST ───────────────────────────────────────────────────────

    /**
     * MethodArgumentNotValidException — @Valid on request body failed.
     * Returns field-level error details for each invalid field.
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
     * MethodArgumentTypeMismatchException — invalid path variable type.
     * Example: GET /bookings/not-a-valid-uuid
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format(
                "Invalid value '%s' for parameter '%s'",
                ex.getValue(), ex.getName());

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    // ── 502 BAD GATEWAY (Feign failures) ─────────────────────────────────────

    /**
     * FeignException — a downstream service (Event or Payment) returned an error.
     *
     * Maps Feign exceptions to meaningful HTTP responses:
     * - FeignException.NotFound (404 from downstream) → 409 "Event or seat not found"
     * - FeignException.ServiceUnavailable             → 503
     * - Other Feign errors                            → 502 Bad Gateway
     *
     * Never expose raw Feign exception messages to the client — they contain
     * internal URLs, service names, and stack traces.
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignException(
            FeignException ex, HttpServletRequest request) {

        log.error("Feign call failed: status={} message={} path={}",
                ex.status(), ex.getMessage(), request.getRequestURI());

        if (ex.status() == 400) {
            return build(HttpStatus.BAD_REQUEST,
                    "The upstream service rejected the request. Please check the input and try again.",
                    request);
        }
        if (ex.status() == 404 || ex.status() == 409) {
            return build(HttpStatus.CONFLICT,
                    "The requested resource is unavailable in the upstream service. Please refresh and try again.",
                    request);
        }
        if (ex.status() == 500) {
            return build(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The upstream service encountered an internal error. Please try again later.",
                    request);
        }
        if (ex.status() == 503) {
            return build(HttpStatus.SERVICE_UNAVAILABLE,
                    "A required service is temporarily unavailable. Please try again.", request);
        }

        return build(HttpStatus.BAD_GATEWAY,
                "An error occurred communicating with an upstream service. Please try again.",
                request);
    }

    // ── 500 INTERNAL SERVER ERROR ─────────────────────────────────────────────

    /**
     * Catch-all. Stack trace logged server-side — NEVER returned to client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled exception: path={} error={}",
                request.getRequestURI(), ex.getMessage(), ex);

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request
        );
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

        // Include traceId from UserContext (populated by HeaderAuthFilter)
        if (userContext != null && userContext.getTraceId() != null) {
            response.setTraceId(userContext.getTraceId());
        } else {
            String traceId = request.getHeader("X-Trace-Id");
            response.setTraceId(traceId != null ? traceId : "unavailable");
        }

        return response;
    }
}