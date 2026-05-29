package org.stagepass.paymentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.stagepass.paymentservice.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeRequest (

    @NotNull
    UUID bookingId,

    @NotNull
    BigDecimal amount,

    @NotBlank
    String currency,

    @NotNull
    PaymentMethod paymentMethod,

    @NotBlank
    String paymentToken,

    @NotNull
    UUID userId

) { }
