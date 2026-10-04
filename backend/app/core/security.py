"""Коды OTP и JWT-токены (docs/05_AUTH_SMS.md)."""

import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timedelta
from typing import Any

import jwt

from app.core.config import Settings

OTP_LENGTH = 6
JWT_ALGORITHM = "HS256"


def generate_otp() -> str:
    """6 случайных цифр. Только `secrets` — модуль `random` предсказуем."""
    return f"{secrets.randbelow(10**OTP_LENGTH):0{OTP_LENGTH}d}"


def hash_otp(code: str, pepper: str) -> str:
    """В БД хранится только HMAC-SHA256 кода, сам код — нигде."""
    return hmac.new(pepper.encode(), code.encode(), hashlib.sha256).hexdigest()


def otp_matches(code: str, code_hash: str, pepper: str) -> bool:
    # compare_digest сравнивает за одинаковое время — по скорости ответа не угадать символы.
    return hmac.compare_digest(hash_otp(code, pepper), code_hash)


def create_access_token(user_id: uuid.UUID, now: datetime, settings: Settings) -> str:
    payload = {
        "sub": str(user_id),
        "type": "access",
        "exp": now + timedelta(minutes=settings.access_token_ttl_min),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=JWT_ALGORITHM)


class InvalidTokenError(Exception):
    """Токен не прошёл проверку: подпись, формат, тип или срок."""


def decode_token(
    token: str, expected_type: str, now: datetime, settings: Settings
) -> dict[str, Any]:
    """Проверить подпись и тип токена. Срок сверяем с `now` (часы приложения), а не с системными."""
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[JWT_ALGORITHM],
            options={"verify_exp": False, "require": ["sub", "type", "exp"]},
        )
        user_id = uuid.UUID(claims["sub"])
    except (jwt.InvalidTokenError, ValueError) as exc:
        raise InvalidTokenError from exc
    if claims["type"] != expected_type or claims["exp"] <= now.timestamp():
        raise InvalidTokenError
    claims["sub"] = user_id
    return claims


def create_refresh_token(
    user_id: uuid.UUID, jti: uuid.UUID, expires_at: datetime, settings: Settings
) -> str:
    payload = {"sub": str(user_id), "type": "refresh", "jti": str(jti), "exp": expires_at}
    return jwt.encode(payload, settings.jwt_secret, algorithm=JWT_ALGORITHM)
