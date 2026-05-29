package org.stagepass.paymentservice.security;

import org.stagepass.paymentservice.exception.InvalidWebhookException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * WEBHOOK SIGNATURE VERIFIER
 *
 * Verifies the HMAC-SHA256 signature that Razorpay attaches to every webhook request.
 *
 * ── WHY THIS IS CRITICAL ─────────────────────────────────────────────────────
 * Without signature verification, anyone on the internet can POST a fake
 * "payment.captured" event to /payments/webhook and:
 * - Trigger ticket generation without paying
 * - Fake refund confirmations
 * - Cause bookings to be marked as confirmed without actual payment
 *
 * This is not theoretical — payment webhook forgery is a real attack vector.
 * This class is the only thing standing between StagePass and fraudulent
 * payment confirmations from external attackers.
 *
 * ── HOW RAZORPAY SIGNING WORKS ───────────────────────────────────────────────
 * 1. Razorpay takes the raw webhook body (JSON string)
 * 2. Computes HMAC-SHA256 using your webhook secret key
 * 3. Encodes the result as a lowercase hex string
 * 4. Sends it in the X-Razorpay-Signature header
 *
 * To verify:
 * 1. Take the raw request body (MUST be raw — parsed JSON may differ in whitespace)
 * 2. Compute HMAC-SHA256 using your webhook secret
 * 3. Compare computed signature to received signature
 * 4. If equal → legitimate Razorpay webhook → process
 * 5. If different → reject with 400 and alert
 *
 * ── CONSTANT-TIME COMPARISON ─────────────────────────────────────────────────
 * We use MessageDigest.isEqual() instead of String.equals() or Arrays.equals().
 *
 * Why? Timing attacks:
 * String.equals() short-circuits on the first differing character.
 * An attacker can measure response times to guess valid signatures byte by byte.
 * MessageDigest.isEqual() always takes the same amount of time regardless
 * of where the strings differ — timing attack is impossible.
 *
 * This is not theoretical paranoia — it is standard cryptographic practice.
 * Every HMAC comparison in production code must use constant-time comparison.
 *
 * ── RAW BODY REQUIREMENT ─────────────────────────────────────────────────────
 * The HMAC is computed on the raw bytes of the request body.
 * The WebhookController reads the body as a raw String BEFORE JSON parsing.
 * If we parsed JSON first, Jackson might reorder keys or change whitespace,
 * and our computed HMAC would not match Razorpay's.
 */
@Component
public class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);

    private static final String ALGORITHM = "HmacSHA256";

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    /**
     * Verifies that the webhook signature matches the raw body.
     *
     * @param rawBody   the raw request body string (NOT parsed JSON)
     * @param signature the X-Razorpay-Signature header value
     * @return true if signature is valid, false if verification fails
     */
    public boolean verify(String rawBody, String signature) {
        if (rawBody == null || rawBody.isBlank()) {
            log.warn("Webhook verification failed: empty request body");
            return false;
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook verification failed: empty signature");
            return false;
        }

        try {
            // Step 1: Compute expected HMAC using our webhook secret
            String computed = computeHmac(rawBody);

            // Step 2: Constant-time comparison — never use String.equals() here
            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );

            if (!valid) {
                log.warn("Webhook signature mismatch. " +
                                "Expected: {}... Received: {}...",
                        computed.substring(0, Math.min(8, computed.length())),
                        signature.substring(0, Math.min(8, signature.length())));
            }

            return valid;

        } catch (NoSuchAlgorithmException e) {
            // HmacSHA256 is guaranteed to exist in all JVM implementations
            // This should never happen — if it does, something is very wrong
            log.error("HmacSHA256 algorithm not available: {}", e.getMessage());
            throw new InvalidWebhookException("Signature verification unavailable");
        } catch (InvalidKeyException e) {
            log.error("Invalid webhook secret key: {}", e.getMessage());
            throw new InvalidWebhookException("Webhook secret key configuration error");
        }
    }

    /**
     * Computes HMAC-SHA256 of the input string using the webhook secret.
     * Returns the result as a lowercase hex-encoded string.
     *
     * Razorpay encodes their HMAC as lowercase hex — we must match exactly.
     *
     * @param data the raw string to sign
     * @return lowercase hex-encoded HMAC-SHA256
     */
    private String computeHmac(String data)
            throws NoSuchAlgorithmException, InvalidKeyException {

        Mac mac = Mac.getInstance(ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        mac.init(keySpec);

        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // Convert bytes to lowercase hex string
        StringBuilder hexBuilder = new StringBuilder(rawHmac.length * 2);
        for (byte b : rawHmac) {
            hexBuilder.append(String.format("%02x", b));
        }
        return hexBuilder.toString();
    }
}