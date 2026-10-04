import uuid
from typing import Literal

from pydantic import BaseModel, Field


class RequestCodeIn(BaseModel):
    phone: str = Field(min_length=1, max_length=32, examples=["+79991234567"])


class RequestCodeOut(BaseModel):
    expires_in_sec: int
    resend_after_sec: int


class VerifyCodeIn(BaseModel):
    phone: str = Field(min_length=1, max_length=32, examples=["+79991234567"])
    code: str = Field(pattern=r"^\d{6}$", examples=["123456"])


class UserOut(BaseModel):
    id: uuid.UUID
    phone: str
    name: str | None


class TokensOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in_sec: int
    user: UserOut


class VerifyCodeOut(TokensOut):
    is_new_user: bool
