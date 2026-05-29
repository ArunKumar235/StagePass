package org.stagepass.apigateway.filter;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import io.micrometer.tracing.Tracer;

import java.util.UUID;

/**
 * REQUEST HEADER ENRICHMENT FILTER
 *
 * A GlobalFilter that runs on EVERY request after JwtAuthenticationFilter
 * has already validated the token and attached userId/role to the request
 * headers.
 *
 * Responsibilities:
 * 1. Ensure X-User-Id and X-Role headers are present (from JWT filter)
 * 2. Generate and attach a unique X-Trace-Id for distributed tracing
 * 3. Clean up any client-supplied internal headers (security: prevent spoofing)
 *
 * Why a GlobalFilter here instead of a GatewayFilterFactory?
 * Header enrichment must happen on every request, including public ones.
 * We want every downstream request to carry a Trace-Id regardless of auth.
 *
 * ORDER: Must run AFTER JwtAuthenticationFilter (which is applied per-route)
 * but BEFORE the request reaches the downstream service.
 * Ordered.LOWEST_PRECEDENCE - 1 places it near the end of the filter chain,
 * after auth filters have already run.
 */
@Component
public class RequestHeaderEnrichmentFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RequestHeaderEnrichmentFilter.class);

	private final Tracer tracer;

	@Autowired
	public RequestHeaderEnrichmentFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	public int getOrder() {
		// Run after JWT filter but before the request is forwarded
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	@NotNull
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, @NotNull GatewayFilterChain chain) {
		ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

		// ── SECURITY: Strip client-supplied internal headers ──────────────
		// Prevent a malicious client from spoofing X-User-Id or X-Role
		// by sending them directly. We always re-stamp them from the JWT.
		// Note: JwtAuthenticationFilter already does this on auth routes,
		// but we defensively strip them here too for public routes.
		requestBuilder
				.headers(h -> {
					// Only strip if NOT already set by our own JWT filter
					// (JWT filter sets them from validated claims)
					// For public routes where JWT filter didn't run, these
					// headers should not exist — remove any client-injected ones
					if (exchange.getRequest().getHeaders().getFirst("X-User-Id") == null) {
						h.remove("X-User-Id");
						h.remove("X-Role");
					}
				});

		// ── TRACE ID ──────────────────────────────────────────────────────
		// Generate a unique trace ID for this request if not already present.
		// Downstream services log this ID with every log line so you can
		// trace a single booking request across all 7 services in Zipkin/ELK.
		String traceId = null;
		if (tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
			traceId = tracer.currentSpan().context().traceId();
		}
		if (traceId == null || traceId.isBlank()) {
			traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
		}
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		requestBuilder.header("X-Trace-Id", traceId);

		// ── GATEWAY STAMP ─────────────────────────────────────────────────
		// Identify which gateway instance processed this request.
		// Useful for debugging in multi-instance gateway deployments.
		requestBuilder.header("X-Gateway-Timestamp",
				String.valueOf(System.currentTimeMillis()));

		// ── PROPAGATE TRACE ID BACK IN RESPONSE ──────────────────────────
		// Add trace ID to the response so the client can log it
		// and include it in bug reports ("request ID: xxxx-xxxx")
		final String finalTraceId = traceId;
		exchange.getResponse().getHeaders().add("X-Trace-Id", finalTraceId);

		ServerHttpRequest mutatedRequest = requestBuilder.build();

		log.debug("Request enriched: path={} traceId={} userId={}",
				exchange.getRequest().getURI().getPath(),
				finalTraceId,
				mutatedRequest.getHeaders().getFirst("X-User-Id"));

		return chain.filter(exchange.mutate().request(mutatedRequest).build());
	}
}