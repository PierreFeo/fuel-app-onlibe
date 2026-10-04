"""Пароли для запасного входа (docs/05_AUTH_SMS.md, «Запасной вход по паролю»)."""

import base64
import hashlib
import hmac
import secrets
from functools import lru_cache

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User

# Без похожих символов 0 O o 1 l I — пароль диктуют/переписывают вручную.
PASSWORD_ALPHABET = "".join(
    c for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" if c not in "0Oo1lI"
)
PASSWORD_LENGTH = 10

SCRYPT_N, SCRYPT_R, SCRYPT_P, SCRYPT_DKLEN = 2**14, 8, 1, 32


def generate_password() -> str:
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(PASSWORD_LENGTH))


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def hash_password(password: str) -> str:
    """`scrypt$N$r$p$<соль>$<хэш>` — параметры хранятся рядом, чтобы их можно было усилить позже."""
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode(), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=SCRYPT_DKLEN
    )
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${_b64(salt)}${_b64(digest)}"


def verify_password(password: str, password_hash: str) -> bool:
    try:
        algo, n, r, p, salt_b64, digest_b64 = password_hash.split("$")
        if algo != "scrypt":
            return False
        expected = base64.b64decode(digest_b64)
        actual = hashlib.scrypt(
            password.encode(),
            salt=base64.b64decode(salt_b64),
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
    except ValueError:
        return False
    return hmac.compare_digest(actual, expected)


@lru_cache
def _dummy_hash() -> str:
    return hash_password(secrets.token_urlsafe(16))


def burn_time() -> None:
    """Потратить столько же времени, сколько проверка пароля, — когда проверять нечего.

    Иначе по скорости ответа можно было бы понять, есть ли у номера пароль.
    """
    verify_password("dummy-password", _dummy_hash())


async def set_password(session: AsyncSession, phone: str) -> tuple[str, bool]:
    """Выдать новый пароль номеру (E.164). Создаёт пользователя, если его нет.

    Возвращает (пароль, создан_ли_пользователь). Сбрасывает счётчик ошибок и блокировку.
    """
    user = await session.scalar(select(User).where(User.phone == phone))
    created = user is None
    if user is None:
        user = User(phone=phone)
        session.add(user)
    password = generate_password()
    user.password_hash = hash_password(password)
    user.failed_login_attempts = 0
    user.locked_until = None
    await session.commit()
    return password, created


async def disable_password(session: AsyncSession, phone: str) -> bool:
    """Выключить вход по паролю. False — такого пользователя нет."""
    user = await session.scalar(select(User).where(User.phone == phone))
    if user is None:
        return False
    user.password_hash = None
    user.failed_login_attempts = 0
    user.locked_until = None
    await session.commit()
    return True
