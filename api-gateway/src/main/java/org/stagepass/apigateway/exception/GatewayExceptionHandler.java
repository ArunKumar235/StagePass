package org.stagepass.apigateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.util.StringUtils;
import org.jspecify.annotations.NonNull;
import org.stagepass.apigateway.model.ErrorResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Maps downstream failures to friendly gateway responses.
 */
@Component
public class GatewayExceptionHandler implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String RETRY_AFTER_SECONDS = "30";
    private static final String DOWNSTREAM_UNAVAILABLE_MESSAGE = "The downstream service is temporarily unavailable. Please try again shortly.";
    private static final String DOWNSTREAM_TIMEOUT_MESSAGE = "The downstream service timed out. Please retry your request.";
    private static final String DOWNSTREAM_RESOLUTION_MESSAGE = "The gateway could not resolve the target service.";

    private final ObjectMapper objectMapper;

    public GatewayExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull GatewayFilterChain chain) {
        ServerWebExchange mutatedExchange = exchange.mutate()
                .response(decorateResponse(exchange))
                .build();

        return chain.filter(mutatedExchange)
                .onErrorResume(ex -> handleDownstreamError(mutatedExchange, unwrap(ex), ex));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private ServerHttpResponseDecorator decorateResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();

        return new ServerHttpResponseDecorator(response) {
            @Override
            public @NonNull Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
                if (getStatusCode() != null && getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                    return writeFriendlyResponse(exchange, this,
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Service Unavailable",
                            downstreamUnavailableMessage(exchange),
                            true);
                }

                return super.writeWith(body);
            }

            @Override
            public @NonNull Mono<Void> writeAndFlushWith(@NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).flatMapSequential(publisher -> publisher));
            }
        };
    }

    private Mono<Void> handleDownstreamError(ServerWebExchange exchange, Throwable root, Throwable original) {
        if (isServiceUnavailable(root)) {
            log.warn("Downstream service unavailable: {}", serviceName(exchange));
            return writeFriendlyResponse(exchange, exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable", downstreamUnavailableMessage(exchange), true);
        }

        if (isServiceResolutionFailure(root)) {
            log.warn("Unable to resolve downstream service: {}", serviceName(exchange));
            return writeFriendlyResponse(exchange, exchange.getResponse(), HttpStatus.BAD_GATEWAY,
                    "Bad Gateway", DOWNSTREAM_RESOLUTION_MESSAGE, false);
        }

        if (isTimeout(root)) {
            log.warn("Downstream call timed out for service: {}", serviceName(exchange));
            return writeFriendlyResponse(exchange, exchange.getResponse(), HttpStatus.GATEWAY_TIMEOUT,
                    "Gateway Timeout", DOWNSTREAM_TIMEOUT_MESSAGE, false);
        }

        return Mono.error(original);
    }

    private Mono<Void> writeFriendlyResponse(ServerWebExchange exchange,
                                             ServerHttpResponse response,
                                             HttpStatus status,
                                             String error,
                                             String message,
                                             boolean addRetryAfter) {
        String traceId = resolveTraceId(exchange);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(TRACE_HEADER, traceId);

        if (addRetryAfter) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }

        ErrorResponse payload = ErrorResponse.of(status.value(), error, message, traceId);
        byte[] body = toJsonBytes(payload);

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private byte[] toJsonBytes(ErrorResponse payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            String fallback = String.format(
                    "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\",\"timestamp\":\"%s\"}",
                    payload.status(),
                    payload.error(),
                    escape(payload.message()),
                    escape(payload.traceId()),
                    payload.timestamp().toString());
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (!StringUtils.hasText(traceId)) {
            Object attr = exchange.getAttribute(TRACE_HEADER);
            traceId = attr != null ? String.valueOf(attr) : null;
        }
        return StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
    }

    private String downstreamUnavailableMessage(ServerWebExchange exchange) {
        String service = serviceName(exchange);
        return StringUtils.hasText(service)
                ? "The service '" + service + "' is temporarily unavailable. Please try again shortly."
                : DOWNSTREAM_UNAVAILABLE_MESSAGE;
    }

    private String serviceName(ServerWebExchange exchange) {
        Object routeAttr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (routeAttr instanceof Route route && StringUtils.hasText(route.getId())) {
            return route.getId();
        }
        return null;
    }

    private Throwable unwrap(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isServiceUnavailable(Throwable ex) {
        return ex instanceof ResponseStatusException rse
                && rse.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    private boolean isServiceResolutionFailure(Throwable ex) {
        return ex instanceof NotFoundException
                || contains(ex, "NoSuchServiceInstance")
                || contains(ex, "Unable to find instance")
                || contains(ex, "No instances available")
                || contains(ex, "ServiceInstanceListSupplier");
    }

    private boolean isTimeout(Throwable ex) {
        return ex instanceof TimeoutException
                || ex instanceof SocketTimeoutException
                || ex instanceof ReadTimeoutException
                || ex instanceof ConnectTimeoutException
                || contains(ex, "TimeoutException")
                || contains(ex, "ReadTimeout")
                || contains(ex, "ConnectTimeout");
    }

    private boolean contains(Throwable ex, String needle) {
        Throwable current = ex;
        while (current != null && current != current.getCause()) {
            String message = current.getMessage();
            if (current.getClass().getName().contains(needle)) {
                return true;
            }
            if (message != null && message.contains(needle)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}




