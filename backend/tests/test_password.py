"""Пароли и консольные команды (docs/05_AUTH_SMS.md, «Запасной вход по паролю»)."""

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.cli import run
from app.core.config import Settings
from app.models import User
from app.services.password_service import (
    PASSWORD_ALPHABET,
    generate_password,
    hash_password,
    verify_password,
)
from tests.conftest import ALLOWED_PHONE

# --- хэширование ---


def test_hash_does_not_contain_password_and_verifies() -> None:
    password_hash = hash_password("k7Fm2xQp9a")

    assert "k7Fm2xQp9a" not in password_hash
    assert password_hash.startswith("scrypt$16384$8$1$")
    assert verify_password("k7Fm2xQp9a", password_hash)
    assert not verify_password("k7Fm2xQp9b", password_hash)
    assert not verify_password("", password_hash)


def test_same_password_gives_different_hashes() -> None:
    # у каждого хэша своя случайная соль
    assert hash_password("same-password") != hash_password("same-password")


@pytest.mark.parametrize("broken", ["", "garbage", "bcrypt$1$2$3$4$5", "scrypt$x$8$1$AAAA$AAAA"])
def test_broken_hash_never_matches(broken: str) -> None:
    assert not verify_password("anything", broken)


def test_generated_password_is_readable() -> None:
    passwords = {generate_password() for _ in range(100)}

    assert len(passwords) == 100
    for p in passwords:
        assert len(p) == 10
        assert set(p) <= set(PASSWORD_ALPHABET)
    assert not set("0Oo1lI") & set(PASSWORD_ALPHABET)


# --- команды set-password / disable-password ---


async def _user(db: AsyncSession, phone: str = ALLOWED_PHONE) -> User | None:
    db.expire_all()
    return await db.scalar(select(User).where(User.phone == phone))


async def test_set_password_creates_user(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    db: AsyncSession,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = await run(["set-password", "--phone", "8 999 123-45-67"], session_factory, settings)

    assert code == 0
    out = capsys.readouterr().out
    assert f"Создан новый пользователь {ALLOWED_PHONE}" in out
    password = out.split(f"Пароль для {ALLOWED_PHONE}: ")[1].split()[0]
    user = await _user(db)
    assert user is not None
    assert user.password_hash is not None
    assert verify_password(password, user.password_hash)


async def test_set_password_resets_existing_password_and_lock(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    db: AsyncSession,
    capsys: pytest.CaptureFixture[str],
) -> None:
    from datetime import UTC, datetime

    db.add(
        User(
            phone=ALLOWED_PHONE,
            name="Иван",
            password_hash=hash_password("old-password"),
            failed_login_attempts=3,
            locked_until=datetime(2030, 1, 1, tzinfo=UTC),
        )
    )
    await db.commit()

    assert await run(["set-password", "--phone", ALLOWED_PHONE], session_factory, settings) == 0

    out = capsys.readouterr().out
    assert "Создан" not in out
    user = await _user(db)
    assert user is not None and user.password_hash is not None
    assert user.name == "Иван"
    assert not verify_password("old-password", user.password_hash)
    assert user.failed_login_attempts == 0
    assert user.locked_until is None


async def test_disable_password(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    db: AsyncSession,
    capsys: pytest.CaptureFixture[str],
) -> None:
    db.add(User(phone=ALLOWED_PHONE, password_hash=hash_password("secret-pass")))
    await db.commit()

    code = await run(["disable-password", "--phone", ALLOWED_PHONE], session_factory, settings)

    assert code == 0
    assert "выключен" in capsys.readouterr().out
    user = await _user(db)
    assert user is not None
    assert user.password_hash is None


async def test_disable_password_for_unknown_user_fails(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = await run(["disable-password", "--phone", ALLOWED_PHONE], session_factory, settings)

    assert code == 1
    assert "не найден" in capsys.readouterr().err


async def test_invalid_phone_fails(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    db: AsyncSession,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = await run(["set-password", "--phone", "12345"], session_factory, settings)

    assert code == 1
    assert "неверный номер" in capsys.readouterr().err
    assert (await db.scalars(select(User))).all() == []
