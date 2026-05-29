package org.stagepass.bookingservice.dto;

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
}

