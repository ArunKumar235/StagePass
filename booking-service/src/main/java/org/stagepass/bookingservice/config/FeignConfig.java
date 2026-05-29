package org.stagepass.bookingservice.config;

import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * FEIGN CONFIG
 *
 * Global configuration for all Feign clients in the Booking Service.
 * Applied to EventServiceClient and PaymentServiceClient.
 *
 * Applied globally via @EnableFeignClients(defaultConfiguration = FeignConfig.class)
 * on the main application class.
 *
 * KEY FEATURES:
 *
 * 1. Trace propagation
 *    Forwards X-Trace-Id from the incoming booking request to all outbound
 *    Feign calls. This chains the trace across all services so Zipkin can
 *    show the full journey: client → gateway → booking → event → payment.
 *
 * 2. Timeouts
 *    connectTimeout: 2s — fail fast if the target service is unreachable
 *    readTimeout:    5s — Payment Service gets 5s to process the charge
 *    These are intentionally short — a hung payment call blocks a booking
 *    thread and holds the Redis seat lock open unnecessarily.
 *
 * 3. Retry
 *    Retries on connection failure only (not on HTTP errors like 4xx/5xx).
 *    3 attempts, 100ms initial interval, 1s max interval.
 *    DO NOT retry POST /payments/charge blindly — use PaymentService's
 *    idempotency key (bookingId) to make retries safe.
 *
 * 4. Logging
 *    BASIC level: logs method, URL, status, and duration for every call.
 *    Helps debug inter-service issues without logging full request/response bodies.
 */
@Configuration
public class FeignConfig {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(FeignConfig.class);

    /**
     * Propagates X-Trace-Id and X-User-Id from the current HTTP request
     * to all outbound Feign calls.
     *
     * Why: If a booking request has traceId=abc123, we want that same traceId
     * in the Event Service and Payment Service logs so we can trace the full
     * request in Zipkin/ELK. Without this interceptor, each service generates
     * its own independent trace and the chain is broken.
     */
    @Bean
    public RequestInterceptor traceIdPropagationInterceptor() {
        return requestTemplate -> {
            try {
                ServletRequestAttributes attributes =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

                if (attributes != null) {
                    String traceId = attributes.getRequest().getHeader("X-Trace-Id");
                    String userId  = attributes.getRequest().getHeader("X-User-Id");

                    if (traceId != null) {
                        requestTemplate.header("X-Trace-Id", traceId);
                    }
                    if (userId != null) {
                        // Forward userId so downstream services know who initiated the request
                        requestTemplate.header("X-User-Id", userId);
                    }
                }
            } catch (Exception e) {
                // Never let interceptor failure block a Feign call
                log.warn("Failed to propagate trace headers in Feign interceptor: {}",
                        e.getMessage());
            }
        };
    }

    /**
     * Request timeouts.
     * connectTimeout: 2s — how long to wait for TCP connection
     * readTimeout:    10s — how long to wait for the response body
     *
     * Payment Service gets the full 10s because external payment gateway calls
     * can be slow. Event Service is internal — 10s is generous.
     */
    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(
                2, TimeUnit.SECONDS,  // connectTimeout
                10, TimeUnit.SECONDS,  // readTimeout
                true                  // followRedirects
        );
    }

    /**
     * Retry on connection failure.
     * period:    100ms initial interval between retries
     * maxPeriod: 1000ms max interval (exponential backoff capped at 1s)
     * maxAttempts: 3
     *
     * IMPORTANT: Feign retry is only safe for idempotent calls.
     * POST /payments/charge is safe because PaymentService uses bookingId
     * as an idempotency key — retrying the same bookingId returns the
     * same result rather than charging twice.
     */
    @Bean
    public Retryer feignRetryer() {
        return new Retryer.Default(100, 1000, 3);
    }

    /**
     * Log method, URL, status code, and response time for every Feign call.
     * Full request/response body logging (FULL level) is intentionally excluded
     * to avoid logging sensitive payment data.
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
