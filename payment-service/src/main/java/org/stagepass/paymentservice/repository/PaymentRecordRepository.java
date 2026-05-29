package org.stagepass.paymentservice.repository;

import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.stagepass.paymentservice.entity.PaymentRecord;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, UUID> {

    Optional<PaymentRecord> findByBookingId(@NotNull UUID uuid);

    Optional<PaymentRecord> findByGatewayPaymentId(String gatewayPaymentId);

}
