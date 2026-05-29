package org.stagepass.paymentservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stagepass.paymentservice.dto.ChargeRequest;
import org.stagepass.paymentservice.entity.PaymentMethod;
import org.stagepass.paymentservice.entity.PaymentRecord;
import org.stagepass.paymentservice.entity.PaymentStatus;
import org.stagepass.paymentservice.exception.PaymentFailedException;
import org.stagepass.paymentservice.repository.PaymentRecordRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceDuplicateTokenTest {

    @Mock private PaymentRecordRepository paymentRepo;
    @Mock private RazorpayGatewayService gatewayService;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks private PaymentService paymentService;

    @Test
    void chargeRejectsReusedPaymentTokenFromAnotherBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID otherBookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String gatewayPaymentId = "pay_test_duplicate_token";

        when(idempotencyService.getChargeResult(bookingId.toString())).thenReturn(Optional.empty());
        when(paymentRepo.findByBookingId(bookingId)).thenReturn(Optional.empty());

        PaymentRecord existing = new PaymentRecord();
        existing.setBookingId(otherBookingId);
        existing.setGatewayPaymentId(gatewayPaymentId);
        existing.setStatus(PaymentStatus.SUCCESS);

        when(paymentRepo.findByGatewayPaymentId(gatewayPaymentId)).thenReturn(Optional.of(existing));

        ChargeRequest request = new ChargeRequest(
                bookingId,
                new BigDecimal("499.00"),
                "INR",
                PaymentMethod.CARD,
                gatewayPaymentId,
                userId);

        PaymentFailedException ex = assertThrows(PaymentFailedException.class,
                () -> paymentService.charge(request));

        assertEquals("DUPLICATE_PAYMENT_TOKEN", ex.getGatewayErrorCode());
        assertEquals("Payment token already used by another payment: " + gatewayPaymentId,
                ex.getMessage());

        verifyNoInteractions(gatewayService);
        verify(paymentRepo, never()).save(any());
        verify(idempotencyService, never()).storeChargeResult(anyString(), any());
    }
}

