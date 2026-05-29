package org.stagepass.paymentservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record RefundResponse(

        String refundId,

        String gatewayRefundId,

        String status,

        String failureReason,

        BigDecimal amountRefunded,

        LocalDateTime processedAt

) {
    public static RefundResponse failed(String refundId, String failureReason) {
        return new RefundResponse(refundId, null, "FAILED", failureReason, BigDecimal.ZERO, LocalDateTime.now());
    }
}
