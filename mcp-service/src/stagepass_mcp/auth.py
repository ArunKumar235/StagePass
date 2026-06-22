"""
Session manager for StagePass authentication.

Stores JWT access token and refresh token cookie in memory.
This module is called by client.py's elicitation flow — tools never
call login directly. They call authenticated_request(), which triggers
elicitation on 401 and delegates to this module.
"""

import logging
import os

import httpx

from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger(__name__)

GATEWAY_URL = os.getenv("STAGEPASS_GATEWAY_URL", "http://localhost:8080")


class AuthSession:
    """
    Singleton-style session manager for StagePass JWT authentication.

    Lifecycle:
      1. Tools call client.authenticated_request() → gets 401
      2. client.py triggers MCP elicitation → user enters credentials
      3. client.py calls auth_session.login(email, password)
      4. This class stores the accessToken + refreshToken cookie
      5. All subsequent requests use the stored token automatically
    """

    def __init__(self) -> None:
        self._access_token: str | None = None
        self._refresh_token: str | None = None

    @property
    def token(self) -> str | None:
        """Returns the current access token, or None if not logged in."""
        return self._access_token

    @property
    def is_authenticated(self) -> bool:
        """Returns True if an access token is currently stored."""
        return self._access_token is not None

    async def login(self, email: str, password: str) -> dict:
        """
        Authenticates with StagePass via POST /auth/login.
        Stores the accessToken in memory and the refreshToken cookie.

        Args:
            email: User's email address.
            password: User's password.

        Returns:
            The AuthResponse dict (accessToken, role).

        Raises:
            httpx.HTTPStatusError: If login fails (401, 400, etc.)
        """
        async with httpx.AsyncClient(base_url=GATEWAY_URL, timeout=30.0) as client:
            response = await client.post(
                "/auth/login",
                json={"email": email, "password": password},
            )
            response.raise_for_status()

            data = response.json()
            self._access_token = data.get("accessToken")

            # Extract refresh_token from Set-Cookie header if present
            for cookie_header in response.headers.get_list("set-cookie"):
                if cookie_header.startswith("refresh_token="):
                    # Parse the cookie value (before the first semicolon)
                    self._refresh_token = cookie_header.split("=", 1)[1].split(";")[0]
                    break

            logger.info("Login successful — role: %s", data.get("role"))
            return data

    async def refresh(self) -> dict:
        """
        Refreshes the session via POST /users/refresh using the stored
        refresh token cookie. Rotates both tokens.

        Returns:
            The AuthResponse dict (new accessToken, role).

        Raises:
            httpx.HTTPStatusError: If refresh fails.
            RuntimeError: If no refresh token is stored.
        """
        if not self._refresh_token:
            raise RuntimeError("No refresh token available. Login required.")

        async with httpx.AsyncClient(base_url=GATEWAY_URL, timeout=30.0) as client:
            response = await client.post(
                "/users/refresh",
                headers={"Authorization": f"Bearer {self._access_token}"},
                cookies={"refresh_token": self._refresh_token},
            )
            response.raise_for_status()

            data = response.json()
            self._access_token = data.get("accessToken")

            # Rotate refresh token if a new one is issued
            for cookie_header in response.headers.get_list("set-cookie"):
                if cookie_header.startswith("refresh_token="):
                    self._refresh_token = cookie_header.split("=", 1)[1].split(";")[0]
                    break

            logger.info("Session refreshed — role: %s", data.get("role"))
            return data

    def logout(self) -> None:
        """Clears all stored tokens."""
        self._access_token = None
        self._refresh_token = None
        logger.info("Session cleared.")


# Module-level singleton — shared across all tools
auth_session = AuthSession()
