"""
Pydantic models for Event Service DTOs.

Maps to Java records in:
  event-service/src/main/java/org/stagepass/eventservice/dto/
  event-service/src/main/java/org/stagepass/eventservice/entity/
"""

from datetime import date, datetime, time
from decimal import Decimal
from enum import Enum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


# ── Enums ──────────────────────────────────────────────────────────────


class SeatTier(str, Enum):
    """Seat pricing tiers — maps to Java SeatTier enum."""
    GENERAL = "GENERAL"
    PREMIUM = "PREMIUM"
    VIP = "VIP"


class SeatStatus(str, Enum):
    """Seat availability status — maps to Java SeatStatus enum."""
    AVAILABLE = "AVAILABLE"
    LOCKED = "LOCKED"
    BOOKED = "BOOKED"


class EventStatus(str, Enum):
    """Event lifecycle status — maps to Java EventStatus enum."""
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"
    SOLD_OUT = "SOLD_OUT"
    CANCELLED = "CANCELLED"
    COMPLETED = "COMPLETED"


# ── Venue Models ───────────────────────────────────────────────────────


class VenueRequest(BaseModel):
    """
    Create/update venue request body.

    Maps to Java: VenueRequest(name, address, city, state, country, totalCapacity, mapImageUrl)
    """
    name: str
    address: str
    city: str
    state: str
    country: str
    totalCapacity: int
    mapImageUrl: str | None = None


class VenueResponse(BaseModel):
    """
    Venue details returned by the API.

    Maps to Java: VenueResponse(id, name, address, city, state, country, totalCapacity, mapImageUrl, eventCount)
    """
    id: UUID
    name: str
    address: str
    city: str
    state: str
    country: str
    totalCapacity: int
    mapImageUrl: str | None = None
    eventCount: int


# ── Seat Tier Pricing (used in event creation) ─────────────────────────


class SeatTierPricing(BaseModel):
    """
    Defines a seating section with its tier, pricing, and capacity.
    Used when creating or updating events.

    Maps to Java: SeatTierPricing(sectionName, tier, price, rowCount, seatsPerRow)
    """
    sectionName: str = Field(min_length=2, max_length=100)
    tier: SeatTier
    price: Decimal = Field(ge=0)
    rowCount: int = Field(ge=1, le=100)
    seatsPerRow: int = Field(ge=1, le=200)


# ── Event Request Models ───────────────────────────────────────────────


class CreateEventRequest(BaseModel):
    """
    Create event request body.

    Maps to Java: CreateEventRequest(title, description, category, venueId, eventDate, doorsOpenTime, tierPricing, bannerImageURL)
    """
    title: str
    description: str
    category: str | None = None
    venueId: UUID
    eventDate: date
    doorsOpenTime: time | None = None
    tierPricing: list[SeatTierPricing]
    bannerImageURL: str | None = None


class UpdateEventRequest(BaseModel):
    """
    Update event request body — all fields optional.

    Maps to Java: UpdateEventRequest(title, description, category, venueId, eventDate, doorsOpenTime, tierPricing, bannerImageURL)
    """
    title: str | None = None
    description: str | None = None
    category: str | None = None
    venueId: UUID | None = None
    eventDate: date | None = None
    doorsOpenTime: time | None = None
    tierPricing: list[SeatTierPricing] | None = None
    bannerImageURL: str | None = None


class EventFilterRequest(BaseModel):
    """
    Query parameters for filtering the event listing.
    All fields are optional — unset fields are not sent as query params.

    Maps to Java: EventFilterRequest(city, category, fromDate, toDate, maxPrice, keyword, page, size, sortBy)
    """
    city: str | None = None
    category: str | None = None
    fromDate: date | None = None
    toDate: date | None = None
    maxPrice: Decimal | None = None
    keyword: str | None = None
    page: int | None = None
    size: int | None = None
    sortBy: str | None = None


# ── Event Response Models ──────────────────────────────────────────────


class SeatTierCount(BaseModel):
    """
    Available seat count for a specific tier.

    Maps to Java: SeatTierCount(tier, seatsAvailable)
    """
    tier: SeatTier
    seatsAvailable: int


class EventResponse(BaseModel):
    """
    Full event details returned by the API.

    Maps to Java: EventResponse(id, title, description, eventDate, doorsOpenTime, status, category, bannerImageURL, venue, availableSeatCount, lowestPrice, seatTierCounts, organizerID, createdAt)
    """
    id: UUID
    title: str
    description: str
    eventDate: date
    doorsOpenTime: time | None = None
    status: EventStatus
    category: str | None = None
    bannerImageURL: str | None = None
    venue: VenueResponse
    availableSeatCount: int
    lowestPrice: int
    seatTierCounts: list[SeatTierCount]
    organizerID: UUID
    createdAt: datetime


# ── Seat Map Models ────────────────────────────────────────────────────


class SeatResponse(BaseModel):
    """
    Individual seat in the seat map.

    Maps to Java: SeatResponse(seatId, seatNumber, status, price)
    """
    seatId: UUID
    seatNumber: int
    status: SeatStatus
    price: Decimal


class RowResponse(BaseModel):
    """
    A row of seats within a section.

    Maps to Java: RowResponse(rowLabel, seats)
    """
    rowLabel: str
    seats: list[SeatResponse]


class SectionResponse(BaseModel):
    """
    A seating section containing rows.

    Maps to Java: SectionResponse(sectionName, seatTier, rows)
    """
    sectionName: str
    seatTier: SeatTier
    rows: list[RowResponse]


class SeatMapResponse(BaseModel):
    """
    Complete seat map for an event — sections → rows → seats.

    Maps to Java: SeatMapResponse(eventId, sections)
    """
    eventId: UUID
    sections: list[SectionResponse]


# ── Seat Validation Models ─────────────────────────────────────────────


class SeatDetail(BaseModel):
    """
    Details of a single validated seat.

    Maps to Java: SeatValidationResponse.SeatDetail(seatId, sectionId, rowLabel, seatNumber, tier, price)
    """
    seatId: UUID
    sectionId: UUID
    rowLabel: str
    seatNumber: int
    tier: str
    price: Decimal


class SeatValidationResponse(BaseModel):
    """
    Result of seat validation — confirms availability and pricing.

    Maps to Java: SeatValidationResponse(availableAll, unavailableSeatIds, totalPrice, seatDetails, eventDate)
    """
    availableAll: bool
    unavailableSeatIds: list[UUID]
    totalPrice: Decimal
    seatDetails: list[SeatDetail]
    eventDate: date


# ── Paginated Response ─────────────────────────────────────────────────


class PagedResponse(BaseModel):
    """
    Generic paginated response wrapper.
    The `content` field contains the list of items (EventResponse, VenueResponse, etc.)

    Maps to Java: PagedResponse<T>(content, number, size, totalElements, totalPages, first, last, hasNext, hasPrevious, sortField, sortDirection)
    """
    content: list[Any]
    number: int
    size: int
    totalElements: int
    totalPages: int
    first: bool
    last: bool
    hasNext: bool
    hasPrevious: bool
    sortField: str | None = None
    sortDirection: str | None = None
