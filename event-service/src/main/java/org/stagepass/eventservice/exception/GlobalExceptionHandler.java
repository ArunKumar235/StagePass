package org.stagepass.eventservice.exception;

import org.stagepass.eventservice.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * GLOBAL EXCEPTION HANDLER
 *
 * Centralises all exception → HTTP response mapping.
 * Every controller in this service benefits from this without any extra code.
 *
 * Why this matters:
 * - Without this, Spring Boot returns its Whitelabel error page or a raw stack trace
 * - Consistent error shape across all endpoints makes frontend error handling trivial
 * - Sensitive internals (stack traces, SQL errors) are never exposed to callers
 * - Every error response includes a traceId so the client can report it for debugging
 *
 * Error response shape (consistent across all error types):
 * {
 *   "status":    404,
 *   "error":     "Not Found",
 *   "message":   "Event not found: 550e8400-e29b-41d4-a716-446655440000",
 *   "path":      "/events/550e8400-e29b-41d4-a716-446655440000",
 *   "traceId":   "abc123",
 *   "timestamp": "2025-06-01T10:30:00Z"
 * }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired(required = false) // not available in async/Kafka context
    private UserContext userContext;

    // ── 404 NOT FOUND ─────────────────────────────────────────────────────────

    /**
     * EventNotFoundException — event or venue not found.
     * Also covers "event exists but is DRAFT/CANCELLED" cases.
     */
    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(
            EventNotFoundException ex, HttpServletRequest request) {

        log.warn("Resource not found: {} path={}",
                ex.getMessage(), request.getRequestURI());

        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    // ── 409 CONFLICT ──────────────────────────────────────────────────────────

    /**
     * SeatNotAvailableException — seat is already LOCKED or BOOKED.
     */
    @ExceptionHandler(SeatNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatNotAvailable(
            SeatNotAvailableException ex, HttpServletRequest request) {

        log.warn("Seat not available: seatId={} status={} path={}",
                ex.getSeatId(), ex.getCurrentStatus(), request.getRequestURI());

        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * OptimisticLockingFailureException — concurrent modification of the same seat.
     *
     * This happens when two booking confirmations race for the same seat and
     * the @Version check fails for one of them. The client should retry.
     *
     * Both Spring's ObjectOptimisticLockingFailureException and JPA's
     * OptimisticLockException are caught here via the parent class.
     */
    @ExceptionHandler({
            OptimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            RuntimeException ex, HttpServletRequest request) {

        log.warn("Optimistic lock conflict on path={} — concurrent seat update detected",
                request.getRequestURI());

        return build(
                HttpStatus.CONFLICT,
                "Resource was modified concurrently. Please retry your request.",
                request
        );
    }

    /**
     * IllegalStateException — invalid state transitions.
     * Examples: publishing a CANCELLED event, changing date of a PUBLISHED event.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {

        log.warn("Illegal state: {} path={}", ex.getMessage(), request.getRequestURI());

        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * IllegalArgumentException — invalid arguments.
     * Examples: duplicate venue name+city, invalid tier pricing.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("Illegal argument: {} path={}", ex.getMessage(), request.getRequestURI());

        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * UnauthorizedEventAccessException — organiser trying to modify another's event.
     */
    @ExceptionHandler(UnauthorizedEventAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedAccess(
            UnauthorizedEventAccessException ex, HttpServletRequest request) {

        log.warn("Unauthorized event access: {} path={}",
                ex.getMessage(), request.getRequestURI());

        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    /**
     * AccessDeniedException — Spring Security role check failed.
     * Happens when a USER tries to call a POST /events (ORGANISER-only) endpoint.
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
     * MethodArgumentNotValidException — @Valid validation failed on a request body.
     *
     * Returns field-level error details so the frontend can highlight the exact
     * invalid field:
     * {
     *   "status": 400,
     *   "error":  "Validation Failed",
     *   "message": "Input validation failed",
     *   "errors": { "title": "must not be blank", "eventDate": "must be a future date" }
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        log.warn("Validation failed: fields={} path={}", fieldErrors.keySet(), request.getRequestURI());

        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Input validation failed",
                request
        );
        response.setFieldErrors(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * MethodArgumentTypeMismatchException — wrong type in path variable.
     * Example: GET /events/not-a-uuid → "Failed to convert 'not-a-uuid' to UUID"
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format(
                "Invalid value '%s' for parameter '%s'. Expected type: %s",
                ex.getValue(),
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown"
        );

        log.warn("Type mismatch: {} path={}", message, request.getRequestURI());

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * HttpMediaTypeNotSupportedException — request Content-Type is not supported.
     * Example: sending text/plain to an endpoint that only accepts application/json.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        String contentType = String.valueOf(ex.getContentType());
        String message = contentType == null
                ? "Unsupported Content-Type. Please use application/json."
                : String.format("Unsupported Content-Type '%s'. Please use application/json.", contentType);

        log.warn("Unsupported media type: path={} method={} contentType={}",
                request.getRequestURI(), request.getMethod(), contentType);

        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message, request);
    }

    // ── 500 INTERNAL SERVER ERROR ─────────────────────────────────────────────

    /**
     * Catch-all for any unhandled exception.
     *
     * IMPORTANT: The stack trace is logged server-side but NEVER returned in the
     * response. Exposing stack traces is a security vulnerability — they reveal
     * internal class names, DB schema details, and library versions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled exception: path={} method={} error={}",
                request.getRequestURI(),
                request.getMethod(),
                ex.getMessage(),
                ex // full stack trace in logs only
        );

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

        // Include traceId if available (stamped by Gateway via HeaderAuthFilter → UserContext)
        if (userContext != null && userContext.getTraceId() != null) {
            response.setTraceId(userContext.getTraceId());
        } else {
            // Fall back to request header if UserContext not populated
            String traceId = request.getHeader("X-Trace-Id");
            response.setTraceId(traceId != null ? traceId : "unavailable");
        }

        return response;
    }
}
