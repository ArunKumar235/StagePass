package org.stagepass.apigateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Shared error payload returned by the gateway for all rejected requests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(int status, String error, String message, String traceId) {
        return new ErrorResponse(status, error, message, traceId, Instant.now());
    }
}
