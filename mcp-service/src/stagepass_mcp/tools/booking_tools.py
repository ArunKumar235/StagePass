"""
Booking tools for StagePass MCP.

Covers creating, fetching, and cancelling bookings, plus admin tools.
"""

from uuid import UUID

from mcp.server.fastmcp import Context
from mcp.server.fastmcp.exceptions import ToolError

from stagepass_mcp.client import authenticated_request, format_error_response
from stagepass_mcp.mcp_app import mcp
from stagepass_mcp.models.booking import CreateBookingRequest


@mcp.tool()
async def create_booking(request: CreateBookingRequest, ctx: Context) -> str:
    """
    Book tickets for an event. Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    response = await authenticated_request(ctx, "POST", "/bookings", json=payload)

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Booking initiated successfully:\n{response.text}"


@mcp.tool()
async def get_booking(booking_id: UUID, ctx: Context) -> str:
    """
    Get details of a specific booking by ID.
    Automatically handles login if not authenticated.

    Args:
        booking_id: UUID of the booking
    """
    response = await authenticated_request(ctx, "GET", f"/bookings/{booking_id}")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_my_bookings(ctx: Context) -> str:
    """
    Get all bookings for the currently logged in user.
    Automatically handles login if not authenticated.
    """
    response = await authenticated_request(ctx, "GET", "/bookings/my")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def cancel_booking(booking_id: UUID, ctx: Context) -> str:
    """
    Cancel an existing booking and release the seats.
    Automatically handles login if not authenticated.

    Args:
        booking_id: UUID of the booking to cancel
    """
    response = await authenticated_request(ctx, "DELETE", f"/bookings/{booking_id}")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Booking {booking_id} successfully cancelled."


@mcp.tool()
async def admin_get_event_bookings(event_id: UUID, ctx: Context) -> str:
    """
    Get all bookings for a specific event. Requires ADMIN role.
    Automatically handles login if not authenticated.

    Args:
        event_id: UUID of the event
    """
    response = await authenticated_request(
        ctx, "GET", f"/bookings/admin/event/{event_id}"
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_payment_by_booking(booking_id: UUID, ctx: Context) -> str:
    """
    Get the payment charge details for a specific booking. Requires ADMIN role.
    Automatically handles login if not authenticated.

    Args:
        booking_id: UUID of the booking
    """
    response = await authenticated_request(
        ctx, "GET", f"/payments/admin/booking/{booking_id}"
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text
