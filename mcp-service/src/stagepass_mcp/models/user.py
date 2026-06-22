"""
Pydantic models for User Service DTOs.

Maps to Java records in:
  user-service/src/main/java/org/stagepass/userservice/dto/
  user-service/src/main/java/org/stagepass/userservice/entity/
"""

from datetime import datetime
from enum import Enum
from uuid import UUID

from pydantic import BaseModel, EmailStr, Field


# ── Enums ──────────────────────────────────────────────────────────────


class Role(str, Enum):
    """User roles — maps to Java Role enum."""
    USER = "USER"
    ADMIN = "ADMIN"
    ORGANISER = "ORGANISER"


class AuthProvider(str, Enum):
    """Authentication providers — maps to Java AuthProvider enum."""
    LOCAL = "LOCAL"
    GOOGLE = "GOOGLE"
    GITHUB = "GITHUB"


# ── Request Models ─────────────────────────────────────────────────────


class RegisterRequest(BaseModel):
    """
    Registration request — email and username only.

    Password is NOT included here — it is collected via MCP elicitation
    at tool call time so it never passes through the LLM context.

    Maps to Java: RegisterRequest(email, username, password)
    The password field is added programmatically in the tool before sending.
    """
    email: EmailStr
    username: str = Field(min_length=3, max_length=30)


class LoginRequest(BaseModel):
    """
    Login credentials.

    Used only by the fallback login tool for clients without elicitation.
    The primary auth flow uses elicitation and never constructs this model.

    Maps to Java: LoginRequest(email, password)
    """
    email: EmailStr
    password: str = Field(min_length=8)


class UpdateProfileRequest(BaseModel):
    """
    Update user profile — currently only username is mutable.

    Maps to Java: UpdateUserProfileRequest(username)
    """
    username: str = Field(min_length=3, max_length=30)


# ── Response Models ────────────────────────────────────────────────────


class AuthResponse(BaseModel):
    """
    Authentication response returned by login and refresh.

    Maps to Java: AuthResponse(accessToken, role)
    """
    accessToken: str
    role: str


class UserProfile(BaseModel):
    """
    Full user profile returned by GET /users/me.

    Maps to Java: UserProfileDto(id, email, username, role, authProvider, createdAt, updatedAt)
    """
    id: UUID
    email: str
    username: str
    role: Role
    authProvider: AuthProvider
    createdAt: datetime
    updatedAt: datetime
