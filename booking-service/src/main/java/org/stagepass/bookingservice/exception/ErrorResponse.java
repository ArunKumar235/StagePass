package org.stagepass.bookingservice.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERROR RESPONSE
 *
 * Standard error body returned for all API errors in the Booking Service.
 * Matches the shape used in Event Service — consistent across all StagePass services.
 *
 * @JsonInclude(NON_NULL) omits null fields from JSON output.
 * Example: lockedSeatIds is null for non-seat-conflict errors and won't appear in response.
 *
 * Booking-specific addition: lockedSeatIds — when seats are unavailable,
 * the frontend uses this list to highlight which specific seats are taken
 * on the interactive seat map.
 */
@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private int                 status;
    private String              error;
    private String              message;
    private String              path;
    private String              traceId;
    private String              timestamp;

    /** Field-level validation errors — only for 400 responses */
    private Map<String, String> fieldErrors;

    /**
     * Seat IDs that are already locked/booked — only for 409 seat conflict responses.
     * Frontend uses this to highlight unavailable seats on the seat picker UI.
     */
    private List<UUID>          lockedSeatIds;

    // ── CONSTRUCTORS ──────────────────────────────────────────────────────────

    public ErrorResponse() {}

    // ── GETTERS & SETTERS ─────────────────────────────────────────────────────

}