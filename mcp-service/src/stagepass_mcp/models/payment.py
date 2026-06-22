"""
Pydantic models for Payment Service DTOs.

Maps to Java records in:
  payment-service/src/main/java/org/stagepass/paymentservice/dto/
  payment-service/src/main/java/org/stagepass/paymentservice/entity/
"""

from datetime import datetime
from decimal import Decimal
from enum import Enum

from pydantic import BaseModel


# ── Enums ──────────────────────────────────────────────────────────────


class PaymentMethod(str, Enum):
    """Payment methods supported by the gateway."""
    CARD = "CARD"
    UPI = "UPI"
    WALLET = "WALLET"
    NETBANKING = "NETBANKING"


# ── Response Models ────────────────────────────────────────────────────


class ChargeResponse(BaseModel):
    """
    Result of a charge attempt.

    Maps to Java: ChargeResponse(paymentId, gatewayPaymentId, status, failureReason, amountCharged, processedAt)
    """
    paymentId: str
    gatewayPaymentId: str | None = None
    status: str
    failureReason: str | None = None
    amountCharged: Decimal
    processedAt: datetime


class RefundResponse(BaseModel):
    """
    Result of a refund attempt.

    Maps to Java: RefundResponse(refundId, gatewayRefundId, status, failureReason, amountRefunded, processedAt)
    """
    refundId: str
    gatewayRefundId: str | None = None
    status: str
    failureReason: str | None = None
    amountRefunded: Decimal
    processedAt: datetime
