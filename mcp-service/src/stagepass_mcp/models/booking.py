"""
Pydantic models for Booking Service DTOs.

Maps to Java records in:
  booking-service/src/main/java/org/stagepass/bookingservice/dto/
  booking-service/src/main/java/org/stagepass/bookingservice/entity/
"""

from datetime import datetime
from decimal import Decimal
from enum import Enum
from uuid import UUID

from pydantic import BaseModel, Field


# ── Enums ──────────────────────────────────────────────────────────────


class BookingStatus(str, Enum):
    """Booking lifecycle status — maps to Java BookingStatus enum."""
    PENDING = "PENDING"
    CONFIRMED = "CONFIRMED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


# ── Request Models ─────────────────────────────────────────────────────


class CreateBookingRequest(BaseModel):
    """
    Initiate a ticket booking.

    The booking SAGA flow:
      validate seats → lock in Redis → charge payment → confirm

    Maps to Java: CreateBookingRequest(eventId, seatIds, paymentMethod, paymentToken)
    """
    eventId: UUID
    seatIds: list[UUID] = Field(min_length=1, max_length=10)
    paymentMethod: str
    paymentToken: str | None = None


# ── Response Models ────────────────────────────────────────────────────


class BookingItemResponse(BaseModel):
    """
    A single seat/ticket within a booking.

    Maps to Java: BookingItemResponse(seatId, sectionId, rowLabel, seatNumber, tier, sectionName, price)
    """
    seatId: UUID
    sectionId: UUID
    rowLabel: str
    seatNumber: int
    tier: str
    sectionName: str
    price: Decimal


class BookingResponse(BaseModel):
    """
    Full booking details — returned by create, get, and list endpoints.

    Status values:
      PENDING   — booking initiated, payment in progress
      CONFIRMED — payment succeeded, ticket issued
      FAILED    — payment failed, seats released
      CANCELLED — user cancelled, refund may be in progress

    Maps to Java: BookingResponse(bookingId, eventId, userId, status, items, totalAmount, paymentId, paymentMethod, createdAt, expiresAt, confirmedAt, cancelledAt)
    """
    bookingId: UUID
    eventId: UUID
    userId: UUID
    status: BookingStatus
    items: list[BookingItemResponse]
    totalAmount: Decimal
    paymentId: str | None = None
    paymentMethod: str
    createdAt: datetime | None = None
    expiresAt: datetime | None = None
    confirmedAt: datetime | None = None
    cancelledAt: datetime | None = None
