"""
FastMCP instance module.
"""

from mcp.server.fastmcp import FastMCP

mcp = FastMCP(
    "stagepass",
    instructions="""StagePass MCP Server — a premium event ticketing platform API.

AUTHENTICATION:
  Protected tools handle login automatically. When you call a protected
  tool without being logged in, the user will be prompted directly for
  their credentials via a secure form. You do NOT need to ask for or
  handle passwords yourself.

PUBLIC TOOLS (no login needed):
  list_events, get_event, list_venues, get_venue,
  get_seat_map, get_available_seat_count, get_seats_by_section,
  validate_seats, register_user

PROTECTED TOOLS (auto-login via elicitation):
  get_my_profile, update_my_profile, create_booking, get_booking,
  get_my_bookings, cancel_booking, create_event, update_event,
  publish_event, cancel_event, create_venue, update_venue,
  admin_get_event_bookings, get_payment_by_booking

REGISTRATION:
  The register_user tool collects email and username as parameters.
  The password is collected securely via a direct prompt to the user —
  you do NOT need to ask for the password in chat.
""",
)
