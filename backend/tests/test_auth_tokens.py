"""Токены: get_current_user, POST /auth/refresh, POST /auth/logout (docs/05_AUTH_SMS.md)."""

import uuid

import pytest
from httpx import AsyncClient, Response
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.security import create_access_token
from app.models import RefreshToken, User
from tests.conftest import bearer, login
from tests.fakes import FakeClock, FakeSmsSender

ME = "/api/v1/me"
REFRESH = "/api/v1/auth/refresh"
LOGOUT = "/api/v1/auth/logout"


def _assert_unauthorized(response: Response) -> None:
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
    assert response.headers["WWW-Authenticate"] == "Bearer"


# --- get_current_user (на примере GET /me) ---


async def test_valid_access_token_is_accepted(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.get(ME, headers=bearer(tokens))

    assert response.status_code == 200
    assert response.json()["id"] == tokens["user"]["id"]


async def test_no_token_is_401(api: AsyncClient) -> None:
    _assert_unauthorized(await api.get(ME))


@pytest.mark.parametrize(
    "header", ["Bearer not-a-jwt", "Bearer ", "Basic dXNlcjpwYXNz", "eyJhbGciOiJIUzI1NiJ9"]
)
async def test_malformed_token_is_401(api: AsyncClient, header: str) -> None:
    _assert_unauthorized(await api.get(ME, headers={"Authorization": header}))


async def test_refresh_token_cannot_be_used_as_access(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.get(ME, headers={"Authorization": f"Bearer {tokens['refresh_token']}"})

    _assert_unauthorized(response)


async def test_access_token_expires_after_15_minutes(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    tokens = await login(api, sms)
    clock.advance(minutes=14, seconds=59)
    assert (await api.get(ME, headers=bearer(tokens))).status_code == 200

    clock.advance(seconds=1)

    _assert_unauthorized(await api.get(ME, headers=bearer(tokens)))


async def test_token_with_wrong_signature_is_401(
    api: AsyncClient, sms: FakeSmsSender, settings: Settings, clock: FakeClock
) -> None:
    tokens = await login(api, sms)
    forged = create_access_token(
        uuid.UUID(tokens["user"]["id"]),
        clock.now,
        settings.model_copy(update={"jwt_secret": "x" * 40}),
    )

    _assert_unauthorized(await api.get(ME, headers={"Authorization": f"Bearer {forged}"}))


async def test_token_of_unknown_user_is_401(
    api: AsyncClient, settings: Settings, clock: FakeClock
) -> None:
    token = create_access_token(uuid.uuid4(), clock.now, settings)

    _assert_unauthorized(await api.get(ME, headers={"Authorization": f"Bearer {token}"}))


async def test_inactive_user_is_401(api: AsyncClient, sms: FakeSmsSender, db: AsyncSession) -> None:
    tokens = await login(api, sms)
    await db.execute(update(User).values(is_active=False))
    await db.commit()

    _assert_unauthorized(await api.get(ME, headers=bearer(tokens)))


# --- POST /auth/refresh ---


async def test_refresh_returns_new_pair_and_revokes_old(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession, clock: FakeClock
) -> None:
    tokens = await login(api, sms)
    clock.advance(minutes=20)  # access уже истёк — именно для этого и нужен refresh

    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"access_token", "refresh_token", "token_type", "expires_in_sec", "user"}
    assert body["refresh_token"] != tokens["refresh_token"]
    assert body["user"] == tokens["user"]
    assert (await api.get(ME, headers=bearer(body))).status_code == 200
    revoked = (await db.scalars(select(RefreshToken.revoked_at))).all()
    assert sorted(r is None for r in revoked) == [False, True]  # старый отозван, новый активен


async def test_old_refresh_token_cannot_be_reused(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)
    await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})

    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


async def test_refresh_token_expires_after_90_days(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    tokens = await login(api, sms)
    clock.advance(days=89, hours=23)
    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})
    assert response.status_code == 200
    tokens = response.json()
    clock.advance(days=90)  # 90 дней без входа в приложение

    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


async def test_access_token_cannot_be_used_as_refresh(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.post(REFRESH, json={"refresh_token": tokens["access_token"]})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


async def test_garbage_refresh_token_is_401(api: AsyncClient) -> None:
    response = await api.post(REFRESH, json={"refresh_token": "garbage"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


async def test_refresh_for_inactive_user_is_401(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    tokens = await login(api, sms)
    await db.execute(update(User).values(is_active=False))
    await db.commit()

    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


@pytest.mark.parametrize("url", [REFRESH, LOGOUT])
async def test_missing_refresh_token_is_400(api: AsyncClient, url: str) -> None:
    response = await api.post(url, json={})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


# --- POST /auth/logout ---


async def test_logout_revokes_refresh_token(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.post(LOGOUT, json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 204
    assert response.content == b""
    response = await api.post(REFRESH, json={"refresh_token": tokens["refresh_token"]})
    assert response.json()["error"]["code"] == "REFRESH_INVALID"


async def test_logout_with_invalid_token_is_still_204(api: AsyncClient) -> None:
    response = await api.post(LOGOUT, json={"refresh_token": "garbage"})

    assert response.status_code == 204


async def test_logout_does_not_touch_other_sessions(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    phone_tokens = await login(api, sms)
    clock.advance(minutes=1)
    tablet_tokens = await login(api, sms)  # второй вход того же пользователя

    await api.post(LOGOUT, json={"refresh_token": phone_tokens["refresh_token"]})

    response = await api.post(REFRESH, json={"refresh_token": tablet_tokens["refresh_token"]})
    assert response.status_code == 200
