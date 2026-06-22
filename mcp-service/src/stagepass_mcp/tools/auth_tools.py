"""
Auth tools for StagePass MCP.

Covers registration, profile management, and fallback login/refresh.
"""

from mcp.server.fastmcp import Context
from mcp.server.fastmcp.exceptions import ToolError

from pydantic import BaseModel, Field
import httpx

from stagepass_mcp.auth import auth_session
from stagepass_mcp.client import authenticated_request, format_error_response, public_request
from stagepass_mcp.mcp_app import mcp
from stagepass_mcp.models.user import LoginRequest, RegisterRequest, UpdateProfileRequest


@mcp.tool()
async def register_user(request: RegisterRequest, ctx: Context) -> str:
    """
    Register a new StagePass account.

    You only need to provide the email and username. The tool will automatically
    prompt the user securely for their password via the MCP client UI. Do NOT
    ask for the password in the chat.
    """
    # 1. Elicit password securely
    class RegisterPassword(BaseModel):
        password: str = Field(description="Must be at least 8 characters")

    result = await ctx.elicit(
        message=f"🔐 Set a password for your new account ({request.email})",
        schema=RegisterPassword,
    )

    if result.action != "accept":
        raise ToolError("Registration cancelled: User declined to provide a password.")

    password = result.data.password

    # 2. Call the API
    payload = request.model_dump(mode="json", exclude_none=True)
    payload["password"] = password

    response = await public_request(
        "POST",
        "/auth/register",
        json=payload,
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Successfully registered user! User ID: {response.text}"


@mcp.tool()
async def login(request: LoginRequest) -> str:
    """
    [FALLBACK ONLY] Manually log in to StagePass.

    NOTE: You usually DO NOT need to call this. Protected tools will automatically
    prompt the user for credentials if they are not logged in.
    Only use this tool if the user explicitly asks you to log them in and
    provides their credentials in the chat.
    """
    try:
        data = await auth_session.login(request.email, request.password)
        return f"Login successful. Role: {data.get('role')}"
    except httpx.HTTPStatusError as e:
        raise ToolError(format_error_response(e.response)) from e
    except Exception as e:
        raise ToolError(f"Login failed: {str(e)}") from e


@mcp.tool()
async def get_my_profile(ctx: Context) -> str:
    """
    Get the currently logged in user's profile.
    Automatically handles login if not authenticated.
    """
    response = await authenticated_request(ctx, "GET", "/users/me")

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return response.text


@mcp.tool()
async def update_my_profile(request: UpdateProfileRequest, ctx: Context) -> str:
    """
    Update the currently logged in user's profile.
    Automatically handles login if not authenticated.
    """
    payload = request.model_dump(mode="json", exclude_none=True)

    response = await authenticated_request(
        ctx,
        "PUT",
        "/users/me",
        json=payload,
    )

    if not response.is_success:
        raise ToolError(format_error_response(response))
    return f"Profile updated successfully:\n{response.text}"


@mcp.tool()
async def refresh_session() -> str:
    """
    [FALLBACK ONLY] Manually refresh the authentication session.

    NOTE: You usually DO NOT need to call this. Protected tools handle token
    expiration automatically.
    """
    try:
        data = await auth_session.refresh()
        return f"Session refreshed successfully. Role: {data.get('role')}"
    except httpx.HTTPStatusError as e:
        raise ToolError(format_error_response(e.response)) from e
    except Exception as e:
        raise ToolError(f"Refresh failed: {str(e)}") from e


@mcp.tool()
async def logout() -> str:
    """
    Log out the currently authenticated user by clearing the session.
    """
    auth_session.logout()
    return "Successfully logged out."
