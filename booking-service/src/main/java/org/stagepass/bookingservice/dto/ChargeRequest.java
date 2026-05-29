package org.stagepass.bookingservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ChargeRequest(

        @NotNull
        UUID bookingId,

        @NotNull
        BigDecimal amount,

        @NotBlank
        String currency,

        @NotNull
        String paymentMethod,

        @NotBlank
        String paymentToken,

        @NotNull
        UUID userId
) {
}

