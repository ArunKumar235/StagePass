"""
Event tools for StagePass MCP.

Covers listing, fetching, creating, updating, publishing, and cancelling events.
"""

from uuid import UUID

from mcp.server.fastmcp import Context
from mcp.server.fastmcp.exceptions import ToolError

from stagepass_mcp.client import authenticated_request, format_error_response, public_request
from stagepass_mcp.mcp_app import mcp
from stagepass_mcp.models.event import CreateEventRequest, EventFilterRequest, UpdateEventRequest


@mcp.tool()
async def list_events(request: EventFilterRequest) -> str:
    """
    Get a paginated list of published events, optionally filtered. No login required.
    """
    params = request.model_dump(mode="json", exclude_none=True)

    response = await public_request("GET", "/events", params=params)

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_event(event_id: UUID) -> str:
    """
    Get details of a specific event by ID. No login required.

    Args:
        event_id: The UUID of the event
    """
    if not event_id or not event_id.strip():
        raise ToolError("event_id cannot be empty. Use list_events to find an event ID.")

    response = await public_request("GET", f"/events/{event_id}")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def create_event(request: CreateEventRequest, ctx: Context) -> str:
    """
    Create a new event in DRAFT status. Requires ORGANISER role.
    Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    response = await authenticated_request(ctx, "POST", "/events", json=payload)

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Event created successfully (DRAFT):\n{response.text}"


@mcp.tool()
async def update_event(event_id: UUID, request: UpdateEventRequest, ctx: Context) -> str:
    """
    Update an existing event. Requires ORGANISER role.
    Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    if not payload:
        raise ToolError("You must provide at least one field to update.")

    response = await authenticated_request(ctx, "PUT", f"/events/{event_id}", json=payload)

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Event updated successfully:\n{response.text}"


@mcp.tool()
async def publish_event(event_id: UUID, ctx: Context) -> str:
    """
    Publish an event (transitions from DRAFT to PUBLISHED). Requires ORGANISER role.
    Automatically handles login if not authenticated.

    Args:
        event_id: UUID of the event
    """
    response = await authenticated_request(ctx, "PATCH", f"/events/{event_id}/publish")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Event published successfully:\n{response.text}"


@mcp.tool()
async def cancel_event(event_id: UUID, ctx: Context) -> str:
    """
    Cancel an event (soft delete). Requires ADMIN role.
    Automatically handles login if not authenticated.

    Args:
        event_id: UUID of the event
    """
    response = await authenticated_request(ctx, "DELETE", f"/events/{event_id}")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Event {event_id} successfully cancelled."
