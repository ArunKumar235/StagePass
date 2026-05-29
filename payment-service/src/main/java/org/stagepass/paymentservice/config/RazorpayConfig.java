package org.stagepass.paymentservice.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAZORPAY CONFIG
 *
 * Initialises the official Razorpay Java SDK client.
 * This is the single place in StagePass where Razorpay credentials are wired in.
 *
 * SECURITY RULES:
 * - Key ID and Secret are ALWAYS injected via environment variables
 * - NEVER hardcode them in this file or any other file
 * - NEVER log them — not even partially
 * - NEVER commit them to version control
 *
 * Test mode vs Live mode:
 * - Razorpay provides separate test credentials (rzp_test_xxx) and live credentials (rzp_live_xxx)
 * - Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET appropriately per environment
 * - Test mode charges are not real — safe for development and integration tests
 *
 * The RazorpayClient bean is injected into RazorpayGatewayService — the only
 * class that directly calls the Razorpay API. All other classes go through
 * RazorpayGatewayService, keeping the Razorpay dependency isolated.
 */
@Configuration
public class RazorpayConfig {

    private static final Logger log = LoggerFactory.getLogger(RazorpayConfig.class);

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    /**
     * Razorpay Java SDK client.
     *
     * RazorpayClient is thread-safe and reusable — safe to use as a singleton bean.
     * The SDK internally manages connection pooling for HTTP calls to Razorpay's API.
     *
     * If initialisation fails (invalid credentials), the application will fail
     * to start — which is the correct behaviour. Better to fail fast at startup
     * than to fail silently during the first payment attempt.
     *
     * @throws IllegalStateException if Razorpay client cannot be initialised
     */
    @Bean
    public RazorpayClient razorpayClient() {
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            log.info("Razorpay client initialised successfully. KeyId: {}***",
                    keyId.substring(0, Math.min(8, keyId.length())));
            return client;
        } catch (RazorpayException e) {
            log.error("Failed to initialise Razorpay client: {}", e.getMessage());
            throw new IllegalStateException(
                    "Could not initialise Razorpay client. " +
                            "Check RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET environment variables.", e);
        }
    }
}