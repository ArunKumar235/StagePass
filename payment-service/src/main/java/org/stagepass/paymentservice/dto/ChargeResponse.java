package org.stagepass.paymentservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ChargeResponse(

        String paymentId,

        String gatewayPaymentId,

        String status,

        String failureReason,

        BigDecimal amountCharged,

        LocalDateTime processedAt

) {

    public static ChargeResponse failed(String paymentId, String failureReason) {
        return new ChargeResponse(paymentId, null, "FAILED", failureReason, BigDecimal.ZERO, LocalDateTime.now());
    }
}
