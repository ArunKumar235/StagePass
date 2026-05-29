package org.stagepass.eventservice.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * EVENT NOT FOUND EXCEPTION
 *
 * Thrown when:
 * - An event ID does not exist in the database
 * - An event exists but is not in PUBLISHED status (e.g. still DRAFT or CANCELLED)
 *   and a public caller is trying to access it
 * - A venue ID does not exist (reused for venue lookups for simplicity)
 *
 * Caught by GlobalExceptionHandler and mapped to HTTP 404 Not Found.
 *
 * Extends RuntimeException (unchecked) so it propagates naturally through
 * the service layer without forcing callers to declare throws clauses.
 */
@Getter
public class EventNotFoundException extends RuntimeException {

    private final String resourceId;
    private final String resourceType;

    /**
     * Constructor for event not found by UUID.
     * Example: throw new EventNotFoundException(eventId)
     */
    public EventNotFoundException(UUID eventId) {
        super("Event not found: " + eventId);
        this.resourceId   = eventId.toString();
        this.resourceType = "Event";
    }

    /**
     * Constructor for generic resource not found (venues, etc.)
     * Example: throw new EventNotFoundException("Venue not found: " + venueId)
     */
    public EventNotFoundException(String message) {
        super(message);
        this.resourceId   = null;
        this.resourceType = "Resource";
    }

    /**
     * Constructor for specific resource type with ID.
     * Example: throw new EventNotFoundException("Venue", venueId.toString())
     */
    public EventNotFoundException(String resourceType, String resourceId) {
        super(resourceType + " not found: " + resourceId);
        this.resourceId   = resourceId;
        this.resourceType = resourceType;
    }

}
