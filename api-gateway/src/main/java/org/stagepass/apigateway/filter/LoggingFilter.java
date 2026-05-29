package org.stagepass.apigateway.filter;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * LOGGING FILTER
 *
 * A GlobalFilter that logs every request entering and every response leaving
 * the gateway. Runs at the highest priority so it wraps the entire filter chain,
 * giving accurate end-to-end response time measurements.
 *
 * What it logs:
 *  INCOMING → method, path, X-User-Id (if present), X-Trace-Id, client IP
 *  OUTGOING → HTTP status, response time (ms), path, X-Trace-Id
 *
 * What it NEVER logs:
 *  - Authorization header or any token value (security)
 *  - Request/response bodies (performance + privacy)
 *  - Passwords or PII in query params
 *
 * Log format is structured (key=value) so it can be parsed by ELK/Loki/Grafana.
 *
 * ORDER: Ordered.HIGHEST_PRECEDENCE ensures this is the outermost filter,
 * so response time captures the complete duration including all other filters.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@NotNull
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		long startTime = System.currentTimeMillis();

		// ── LOG INCOMING REQUEST ──────────────────────────────────────────
		String userId  = request.getHeaders().getFirst("X-User-Id");
		String traceId = request.getHeaders().getFirst("X-Trace-Id");
		String clientIp = request.getRemoteAddress() != null
				? request.getRemoteAddress().getAddress().getHostAddress()
				: "unknown";

		log.info("GATEWAY_REQUEST method={} path={} userId={} traceId={} clientIp={}",
				request.getMethod(),
				request.getURI().getPath(),
				userId   != null ? userId   : "anonymous",
				traceId  != null ? traceId  : "none",
				clientIp
		);

		// ── LOG OUTGOING RESPONSE (after chain completes) ─────────────────
		// then() runs after the downstream call returns — Mono.fromRunnable
		// ensures the logging doesn't block the reactive pipeline
		return chain.filter(exchange).then(Mono.fromRunnable(() -> {
			ServerHttpResponse response = exchange.getResponse();
			long durationMs = System.currentTimeMillis() - startTime;

			// Re-read traceId from response (it's stamped there by header enrichment filter)
			String responseTraceId = response.getHeaders().getFirst("X-Trace-Id");

			log.info("GATEWAY_RESPONSE status={} durationMs={} path={} traceId={}",
					response.getStatusCode() != null
							? response.getStatusCode().value()
							: "unknown",
					durationMs,
					request.getURI().getPath(),
					responseTraceId != null ? responseTraceId : "none"
			);

			// Warn on slow responses — useful for catching performance regressions
			if (durationMs > 2000) {
				log.warn("SLOW_REQUEST durationMs={} path={} traceId={}",
						durationMs,
						request.getURI().getPath(),
						responseTraceId
				);
			}

			// Warn on 4xx/5xx responses
			if (response.getStatusCode() != null && response.getStatusCode().isError()) {
				log.warn("ERROR_RESPONSE status={} path={} userId={} traceId={}",
						response.getStatusCode().value(),
						request.getURI().getPath(),
						userId != null ? userId : "anonymous",
						responseTraceId
				);
			}
		}));
	}
}