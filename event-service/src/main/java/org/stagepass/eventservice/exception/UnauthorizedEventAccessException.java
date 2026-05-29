package org.stagepass.eventservice.exception;

/**
 * UNAUTHORIZED EVENT ACCESS EXCEPTION
 *
 * Thrown when an authenticated ORGANISER tries to modify or publish
 * an event that belongs to a different organiser.
 *
 * Role-based access (ORGANISER vs USER) is handled by SecurityConfig.
 * Ownership-based access (can this ORGANISER touch THIS event?) is a
 * business rule enforced in EventService.getOwnedEvent().
 *
 * These are two different concerns — Spring Security handles the role,
 * the service layer handles ownership. This exception covers the latter.
 *
 * Caught by GlobalExceptionHandler → HTTP 403 Forbidden.
 *
 * Note: We return 403 (not 404) deliberately.
 * Returning 404 would hide the fact that the event exists but the caller
 * doesn't own it — which could be a useful UX signal.
 * In very high-security contexts (hide existence entirely), switch to 404.
 */
public class UnauthorizedEventAccessException extends RuntimeException {

    public UnauthorizedEventAccessException(String message) {
        super(message);
    }

    public UnauthorizedEventAccessException(String eventId, String organizerId) {
        super(String.format(
                "Organiser %s does not have permission to modify event %s",
                organizerId, eventId
        ));
    }
}
