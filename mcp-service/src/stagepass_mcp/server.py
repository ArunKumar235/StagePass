"""
StagePass MCP Server — Entry Point

FastMCP server that exposes the StagePass event ticketing platform API
as MCP tools. Connects to the API Gateway and handles authentication
transparently via MCP elicitation.

Usage:
    python -m stagepass_mcp.server
"""

import logging
import sys

from stagepass_mcp.mcp_app import mcp

# ── Logging Configuration ──────────────────────────────────────────────
# When using stdio transport, NEVER use print() — it corrupts JSON-RPC
# communication. All logging goes to stderr.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger(__name__)

# ── Tool Registration ──────────────────────────────────────────────────
# Each tool module registers its tools using the shared `mcp` instance.
# Import them here so they execute their @mcp.tool() decorators at startup.

from stagepass_mcp.tools import auth_tools
from stagepass_mcp.tools import venue_tools
from stagepass_mcp.tools import event_tools
from stagepass_mcp.tools import seat_tools
from stagepass_mcp.tools import booking_tools


def main():
    """Starts the MCP server using stdio transport."""
    logger.info("Starting StagePass MCP server...")
    mcp.run()


if __name__ == "__main__":
    main()
