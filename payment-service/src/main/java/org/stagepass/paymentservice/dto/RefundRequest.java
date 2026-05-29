package org.stagepass.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundRequest(

        @NotBlank
        String paymentId,

        @NotNull
        BigDecimal amount,

        String reason

) {
}
