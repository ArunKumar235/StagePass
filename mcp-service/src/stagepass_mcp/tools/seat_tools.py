"""
Seat tools for StagePass MCP.

Covers seat maps, section filtering, availability counts, and seat validation.
All seat endpoints are public (auth is handled during booking).
"""

from uuid import UUID

from mcp.server.fastmcp.exceptions import ToolError

from stagepass_mcp.client import format_error_response, public_request
from stagepass_mcp.mcp_app import mcp


@mcp.tool()
async def get_seat_map(event_id: UUID) -> str:
    """
    Get the complete seat map for an event. No login required.
    Returns sections, rows, and individual seats with their status and pricing.

    Args:
        event_id: UUID of the event
    """
    response = await public_request("GET", f"/events/{event_id}/seats")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_available_seat_count(event_id: UUID) -> str:
    """
    Get the count of available seats grouped by tier (GENERAL, VIP, etc.). No login required.

    Args:
        event_id: UUID of the event
    """
    response = await public_request("GET", f"/events/{event_id}/seats/available-count")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_seats_by_section(event_id: UUID, section_id: UUID) -> str:
    """
    Get seats for a single section within an event. No login required.

    Args:
        event_id: UUID of the event
        section_id: UUID of the section
    """
    response = await public_request(
        "GET", f"/events/{event_id}/seats/sections/{section_id}"
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def validate_seats(event_id: UUID, seat_ids: list[UUID]) -> str:
    """
    Validate if specific seats are available and get their pricing. No login required.

    Args:
        event_id: UUID of the event
        seat_ids: Array of seat UUIDs to validate
    """
    response = await public_request(
        "GET",
        f"/events/{event_id}/seats/validate",
        params={"seatIds": ",".join(str(s) for s in seat_ids)},
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def find_seat_uuids(event_id: UUID, seat_labels: list[str]) -> str:
    """
    Safely find the UUIDs for specific seats across any section/row.
    Use this tool to lookup seat UUIDs before booking to avoid context loss.

    Args:
        event_id: UUID of the event
        seat_labels: List of seat strings in the format 'SectionName-RowLabel-SeatNumber'
                     (e.g., ['VIP-A-1', 'VIP-A-2', 'GENERAL-B-5'])
    """
    response = await public_request("GET", f"/events/{event_id}/seats")

    if not response.is_success:
        raise ToolError(format_error_response(response))

    seat_map = response.json()

    # Normalize requested labels for easy O(1) lookup
    requested = {label.strip().upper() for label in seat_labels}
    found_seats = []

    # Flatten and filter the seat map in a single pass
    for section in seat_map.get("sections", []):
        sec_name = section.get("sectionName", "").upper()
        for row in section.get("rows", []):
            row_lbl = row.get("rowLabel", "").upper()
            for seat in row.get("seats", []):
                seat_num = str(seat.get("seatNumber"))

                # Construct the label (e.g. VIP-A-1)
                current_label = f"{sec_name}-{row_lbl}-{seat_num}"

                if current_label in requested:
                    found_seats.append({
                        "label": current_label,
                        "seatId": seat.get("seatId"),
                        "status": seat.get("status"),
                        "price": seat.get("price"),
                    })

    if not found_seats:
        return f"No seats found matching labels: {seat_labels}."

    import json
    return f"Found Seats:\n{json.dumps(found_seats, indent=2)}\n\nUse these seatIds to proceed with further process."
