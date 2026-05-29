package org.stagepass.paymentservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.stagepass.paymentservice.entity.RefundRecord;
import org.stagepass.paymentservice.entity.RefundStatus;

import java.util.Optional;
import java.util.UUID;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, UUID> {

    Optional<RefundRecord> findByGatewayRefundId(String gatewayRefundId);

    Optional<RefundRecord> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);

}
