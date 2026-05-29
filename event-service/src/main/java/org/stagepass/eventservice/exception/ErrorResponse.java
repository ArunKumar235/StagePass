package org.stagepass.eventservice.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * ERROR RESPONSE
 *
 * The standard error body returned for every API error in the event service.
 * All exception handlers in GlobalExceptionHandler produce this shape.
 *
 * Using a consistent shape across all services (copy this to Booking Service,
 * Payment Service etc.) means the frontend error handling code is written once.
 *
 * @JsonInclude(NON_NULL) means null fields are omitted from the JSON output.
 * Example: fieldErrors is null for most errors (not a validation error),
 * so it won't appear in the response — keeps the payload clean.
 *
 * Example responses:
 *
 * 404 — Event not found:
 * {
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Event not found: 550e8400-...",
 *   "path": "/events/550e8400-...",
 *   "traceId": "abc-123",
 *   "timestamp": "2025-06-01T10:30:00Z"
 * }
 *
 * 400 — Validation failure:
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Input validation failed",
 *   "path": "/events",
 *   "traceId": "abc-123",
 *   "timestamp": "2025-06-01T10:30:00Z",
 *   "fieldErrors": {
 *     "title": "must not be blank",
 *     "eventDate": "must be a future date"
 *   }
 * }
 */
@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /** HTTP status code (e.g. 404, 409, 500) */
    private int status;

    /** HTTP status reason phrase (e.g. "Not Found", "Conflict") */
    private String error;

    /** Human-readable error message — safe to show to end users */
    private String message;

    /** The request path that triggered the error */
    private String path;

    /**
     * Trace ID from X-Trace-Id header — unique per request.
     * Frontend should include this in bug reports so engineers can
     * find the full request trace in Zipkin/ELK logs.
     */
    private String traceId;

    /** ISO-8601 timestamp of when the error occurred */
    private String timestamp;

    /**
     * Field-level validation errors — only present for 400 validation failures.
     * Key = field name, Value = validation message.
     * Null for all other error types (omitted from JSON by @JsonInclude).
     */
    private Map<String, String> fieldErrors;

    // ── CONSTRUCTORS ──────────────────────────────────────────────────────────

    public ErrorResponse() {}

    public ErrorResponse(int status, String error, String message,
                         String path, String traceId, String timestamp) {
        this.status    = status;
        this.error     = error;
        this.message   = message;
        this.path      = path;
        this.traceId   = traceId;
        this.timestamp = timestamp;
    }

}