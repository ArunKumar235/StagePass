package org.stagepass.apigateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.stagepass.apigateway.model.ErrorResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Global WebFlux error handler that prevents the default Whitelabel error page.
 */
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler, Ordered {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String GENERIC_MESSAGE = "An unexpected error occurred";

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException responseStatusException) {
            return writeError(exchange,
                    responseStatusException.getStatusCode().value(),
                    reasonPhrase(responseStatusException.getStatusCode().value()),
                    defaultMessage(responseStatusException));
        }

        return writeError(exchange,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                GENERIC_MESSAGE);
    }

    @Override
    public int getOrder() {
        return -2;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, int status, String error, String message) {
        ServerHttpResponse response = exchange.getResponse();
        String traceId = resolveTraceId(exchange);

        HttpStatus resolvedStatus = HttpStatus.resolve(status);
        response.setStatusCode(resolvedStatus != null ? resolvedStatus : HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(TRACE_HEADER, traceId);

        ErrorResponse payload = ErrorResponse.of(status, error, message, traceId);

        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(json)));
        } catch (Exception serializationError) {
            String fallback = String.format(
                    "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\",\"timestamp\":\"%s\"}",
                    status,
                    error,
                    escapeJson(message),
                    escapeJson(traceId),
                    payload.timestamp().toString()
            );
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            Object attr = exchange.getAttribute(TRACE_HEADER);
            traceId = attr != null ? String.valueOf(attr) : null;
        }
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }

    private String defaultMessage(ResponseStatusException ex) {
        String reason = ex.getReason();
        return (reason == null || reason.isBlank()) ? reasonPhrase(ex.getStatusCode().value()) : reason;
    }

    private String reasonPhrase(int status) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        return httpStatus != null ? httpStatus.getReasonPhrase() : "Error";
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

