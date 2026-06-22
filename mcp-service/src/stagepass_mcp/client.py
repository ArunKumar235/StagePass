"""
Centralized HTTP client for StagePass API Gateway.

Provides two request methods:
  - public_request()          — for unauthenticated endpoints (GET /events, etc.)
  - authenticated_request()   — auto-injects JWT and triggers MCP elicitation on 401

All tools should use these methods instead of making raw httpx calls.
"""
import logging
import os
import httpx


from mcp.server.fastmcp import Context
from mcp.server.fastmcp.exceptions import ToolError

from pydantic import BaseModel, Field

from dotenv import load_dotenv

from stagepass_mcp.auth import auth_session

load_dotenv()

logger = logging.getLogger(__name__)

GATEWAY_URL = os.getenv("STAGEPASS_GATEWAY_URL", "http://localhost:8080")
REQUEST_TIMEOUT = 30.0  # seconds — matches gateway's timelimiter for payment calls

# Reusable async client — created once, shared across all tool calls
_client: httpx.AsyncClient | None = None


def _get_client() -> httpx.AsyncClient:
    """
    Returns a shared httpx.AsyncClient instance.

    We lazily initialize and reuse a single client to benefit from
    connection pooling across multiple tool calls in the same session.
    """
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            base_url=GATEWAY_URL,
            timeout=REQUEST_TIMEOUT,
        )
    return _client


async def public_request(
    method: str,
    url: str,
    **kwargs,
) -> httpx.Response:
    """
    Makes an HTTP request to a public (unauthenticated) endpoint.

    Use this for routes that don't require JWT:
      GET /events, GET /venues, GET /events/{id}/seats, etc.

    Args:
        method: HTTP method (GET, POST, etc.)
        url: Path relative to gateway base URL (e.g., "/events")
        **kwargs: Passed through to httpx (params, json, headers, etc.)

    Returns:
        httpx.Response

    Raises:
        ToolError: On network failure or unexpected errors.
    """
    client = _get_client()
    try:
        response = await client.request(method, url, **kwargs)
        return response
    except httpx.ConnectError as exc:
        raise ToolError(
            "Could not reach the StagePass API. "
            f"Is the server running at {GATEWAY_URL}?"
        ) from exc
    except httpx.TimeoutException as exc:
        raise ToolError(
            "Request to StagePass API timed out. The server may be under heavy load."
        ) from exc


async def authenticated_request(
    ctx: Context,
    method: str,
    url: str,
    **kwargs,
) -> httpx.Response:
    """
    Makes an HTTP request with JWT auth, auto-triggering elicitation on 401.

    Flow:
      1. Inject stored access token (if available) into Authorization header
      2. Send request to gateway
      3. If 401 Unauthorized:
         a. Trigger MCP elicitation — user enters credentials in a secure form
         b. Call auth_session.login() with the credentials
         c. Retry the original request with the new token
      4. Return the response

    The user's password NEVER passes through the LLM — it flows directly
    from the MCP client UI → this server → StagePass API.

    Args:
        ctx: MCP Context — required for triggering elicitation.
        method: HTTP method (GET, POST, PUT, DELETE, PATCH)
        url: Path relative to gateway base URL (e.g., "/bookings/my")
        **kwargs: Passed through to httpx (params, json, headers, etc.)

    Returns:
        httpx.Response

    Raises:
        ToolError: On network failure, login cancellation, or login failure.
    """
    client = _get_client()

    # Build headers — inject token if we have one
    headers = dict(kwargs.pop("headers", {}))
    if auth_session.token:
        headers["Authorization"] = f"Bearer {auth_session.token}"

    try:
        response = await client.request(method, url, headers=headers, **kwargs)
    except httpx.ConnectError as exc:
        raise ToolError(
            "Could not reach the StagePass API. "
            f"Is the server running at {GATEWAY_URL}?"
        ) from exc
    except httpx.TimeoutException as exc:
        raise ToolError(
            "Request to StagePass API timed out. The server may be under heavy load."
        ) from exc

    # If not 401, return immediately (even if it's another error — tools handle that)
    if response.status_code != 401:
        return response

    # ── 401 Unauthorized — trigger MCP elicitation ─────────────────────
    logger.info("Received 401 — triggering elicitation for login credentials.")


    class LoginCredentials(BaseModel):
        email: str = Field(description="Your StagePass account email")
        password: str = Field(description="Your StagePass account password")

    result = await ctx.elicit(
        message="🔐 Login required. Enter your StagePass credentials.",
        schema=LoginCredentials,
    )

    # User cancelled the elicitation form
    if result.action != "accept":
        raise ToolError("Login cancelled. This tool requires authentication.")

    # Attempt login
    try:
        await auth_session.login(result.data.email, result.data.password)
    except httpx.HTTPStatusError as e:
        status = e.response.status_code
        if status == 401:
            raise ToolError("Login failed: Invalid email or password.") from e
        if status == 400:
            body = e.response.json() if e.response.headers.get("content-type", "").startswith("application/json") else {}
            msg = body.get("message", "Bad request during login.")
            raise ToolError(f"Login failed: {msg}") from e
        raise ToolError(f"Login failed with unexpected status {status}.") from e

    # ── Retry the original request with the new token ──────────────────
    headers["Authorization"] = f"Bearer {auth_session.token}"

    try:
        response = await client.request(method, url, headers=headers, **kwargs)
    except httpx.ConnectError as exc:
        raise ToolError(
            "Could not reach the StagePass API. "
            f"Is the server running at {GATEWAY_URL}?"
        ) from exc
    except httpx.TimeoutException as exc:
        raise ToolError(
            "Request to StagePass API timed out after login. The server may be under heavy load."
        ) from exc

    return response


def format_error_response(response: httpx.Response) -> str:
    """
    Converts an HTTP error response into a human-readable string for the LLM.

    This is called by individual tools when they receive a non-2xx response.
    Returns a clean error message instead of raw Spring Boot error JSON.
    """
    status = response.status_code

    # Try to extract a message from the JSON body
    message = None
    try:
        body = response.json()
        # Spring Boot error format: { "message": "...", "error": "..." }
        message = body.get("message") or body.get("error") or body.get("detail")
    except Exception:
        pass

    if status == 400:
        return f"Error: Bad request. {message or 'Check your input parameters.'}"
    if status == 403:
        return f"Error: Forbidden. {message or 'Your role does not have permission for this action.'}"
    if status == 404:
        return f"Error: {message or 'Resource not found.'}"
    if status == 409:
        return f"Error: Conflict. {message or 'Resource already exists or is in a conflicting state.'}"
    if status == 429:
        return "Error: Rate limited. Try again in a moment."
    if status >= 500:
        return f"Error: StagePass server error ({status}). {message or 'Please try again later.'}"
    return f"Error: Unexpected response ({status}). {message or response.text[:200]}"
