"""
Venue tools for StagePass MCP.

Covers listing, fetching, creating, and updating venues.
"""

from uuid import UUID

from mcp.server.fastmcp import Context
from mcp.server.fastmcp.exceptions import ToolError

from stagepass_mcp.client import authenticated_request, format_error_response, public_request
from stagepass_mcp.mcp_app import mcp
from stagepass_mcp.models.event import VenueRequest


@mcp.tool()
async def list_venues(page: int = 0, size: int = 20, sort_by: str = "name") -> str:
    """
    Get a paginated list of all venues. No login required.

    Args:
        page: Page number (0-indexed, default 0)
        size: Number of items per page (default 20)
        sort_by: Field to sort by (default 'name')
    """
    response = await public_request(
        "GET",
        "/venues",
        params={"page": page, "size": size, "sortBy": sort_by},
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def get_venue(venue_id: UUID) -> str:
    """
    Get details of a specific venue by ID. No login required.

    Args:
        venue_id: The UUID of the venue
    """
    response = await public_request("GET", f"/venues/{venue_id}")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def create_venue(request: VenueRequest, ctx: Context) -> str:
    """
    Create a new venue. Requires ADMIN role.
    Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    response = await authenticated_request(ctx, "POST", "/venues", json=payload)

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Venue created successfully:\n{response.text}"


@mcp.tool()
async def update_venue(venue_id: UUID, request: VenueRequest, ctx: Context) -> str:
    """
    Update an existing venue. Requires ADMIN role.
    Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    response = await authenticated_request(
        ctx, "PUT", f"/venues/{venue_id}", json=payload
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Venue updated successfully:\n{response.text}"
