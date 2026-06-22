# StagePass MCP Server

A premium [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) Server that exposes the StagePass event ticketing microservices backend to Claude and other LLM clients.

This server acts as a secure, typed proxy. It translates natural language tool calls into structured HTTP requests to the StagePass API Gateway, while handling session management and secure credential elicitation completely transparently.

---

## 🌟 Key Features

* **Secure Authentication Elicitation**: No passwords in chat! If authentication is required and no refresh token exists, the server intercepts `401 Unauthorized` responses and uses MCP's native UI elicitation (`ctx.elicit`) to securely prompt users for their credentials before transparently resuming the tool execution.
* **Silent Session Refresh**: The HTTP client natively intercepts `401 Unauthorized` responses and attempts a silent background JWT token rotation. If successful, the original request is retried seamlessly without any user intervention.
* **Strict Type & UUID Validation**: Fully maps StagePass Java DTOs to Python `pydantic` models and native `UUID` objects. The AI client is forced to strictly conform to the expected schemas and valid UUID formats before any network requests are even fired.
* **Full Domain Coverage**: Exposes 26 highly specific tools covering everything from checking individual seat availability to generating booking payment charges.

## 📋 Prerequisites

* **Python 3.12+**
* [**uv**](https://docs.astral.sh/uv/) (Extremely fast Python package manager)
* The **StagePass API Gateway** running locally on `localhost:8080`.

## 🚀 Setup & Installation

This project utilizes `uv` and `hatchling` to manage the `src/` layout natively.

1. **Navigate to the MCP service directory:**
   ```bash
   cd <absolute/path/to/mcp-service>
   ```

2. **Install and link the package in editable mode:**
   ```bash
   uv sync
   ```
   *This resolves all dependencies and ensures the `stagepass_mcp` module is natively discoverable by Python.*

3. **Configure Environment Variables (Optional):**
   Copy the `.env.example` to `.env` if you need to point the server to a different gateway address.
   ```env
   STAGEPASS_GATEWAY_URL=http://localhost:8080
   ```

## 🛠️ Testing & Visualizing Tools

Before connecting the server to a full LLM client, you can use the official **MCP Inspector** to visually explore the available tools, their docstrings, and test executing them.

Run this command inside the `mcp-service` folder:
```bash
npx @modelcontextprotocol/inspector uv run -q python -m stagepass_mcp.server
```

## 🔌 Connecting to Claude

To use this server with Claude, you must add it to your client's configuration.

### Option A: Claude Code

If you are using the terminal-based **Claude Code**, run this command from the `<absolute/path/to/mcp-service>` folder to automatically add it to your configuration:

```bash
claude mcp add stagepass <absolute/path/to/mcp-service>/.venv/Scripts/python.exe -m stagepass_mcp.server
```
*(We use the absolute path to `.venv/Scripts/python.exe` to guarantee Claude Code bypasses any startup output pollution caused by `uv run`.)*

Make sure to set the `PYTHONPATH` environment variable in your `~/.claude.json` file if it was not picked up automatically:
```json
      "env": {
        "PYTHONPATH": "<absolute/path/to/mcp-service>/src"
      }
```

### Option B: Claude Desktop

If you are using the GUI-based **Claude Desktop**, edit your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "StagePass": {
      "command": "uv",
      "args": [
        "--directory",
        "<absolute/path/to/mcp-service>",
        "run",
        "-q",
        "python",
        "-m",
        "stagepass_mcp.server"
      ]
    }
  }
}
```

## 🧰 Available Tools Reference

The server exposes 27 total tools across 5 main domains:

### Authentication (`auth_tools.py`)
- `register_user`: Elicits a secure password prompt to register a new account.
- `login`, `logout`, `refresh_session`: Fallback manual auth triggers.
- `get_my_profile`, `update_my_profile`: Managed session profile actions.

### Venues (`venue_tools.py`)
- `list_venues`, `get_venue`: Browse physical venues.
- `create_venue`, `update_venue`: Requires `ADMIN` role.

### Events (`event_tools.py`)
- `list_events`, `get_event`: Filterable event search (category, price, dates).
- `create_event`, `update_event`, `publish_event`: Draft/Publish lifecycle. Requires `ORGANISER` role.
- `cancel_event`: Soft delete an event. Requires `ADMIN` role.

### Seats (`seat_tools.py`)
- `get_seat_map`, `get_seats_by_section`: Retrieve hierarchical seating layouts.
- `get_available_seat_count`: Aggregate totals by tier.
- `validate_seats`: Price-check specific seats before booking.
- `find_seat_uuids`: Resolve specific seat labels (e.g. `VIP-A-1`) to exact UUIDs to prevent context loss.

### Bookings (`booking_tools.py`)
- `create_booking`: Initiates a ticket reservation and returns the confirmation.
- `get_booking`, `get_my_bookings`, `cancel_booking`: Customer booking management.
- `admin_get_event_bookings`, `get_payment_by_booking`: Requires `ADMIN` role.
