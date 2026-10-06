"""PUT /me/password — пароль задаёт сам пользователь (docs/04_API_CONTRACT.md, 05_AUTH_SMS.md)."""

from typing import Any

import pytest
from httpx import AsyncClient, Response

from tests.conftest import ALLOWED_PHONE, bearer, login
from tests.fakes import FakeClock, FakeSmsSender

URL = "/api/v1/me/password"
FIRST = "первый-пароль"
SECOND = "второй-пароль"


@pytest.fixture
async def headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    return bearer(await login(api, sms))


async def _put(api: AsyncClient, headers: dict[str, str], **body: Any) -> Response:
    return await api.put(URL, json=body, headers=headers)


async def _login_status(api: AsyncClient, password: str) -> int:
    response = await api.post(
        "/api/v1/auth/login", json={"phone": ALLOWED_PHONE, "password": password}
    )
    return response.status_code


async def _has_password(api: AsyncClient, headers: dict[str, str]) -> bool:
    return (await api.get("/api/v1/me", headers=headers)).json()["has_password"]


async def test_first_password_without_current(api: AsyncClient, headers: dict[str, str]) -> None:
    assert await _has_password(api, headers) is False

    response = await _put(api, headers, current_password=None, new_password=FIRST)

    assert response.status_code == 204
    assert await _has_password(api, headers) is True
    # Вход по паролю без SMS — с тем самым паролем, в ответе has_password = true.
    login = await api.post("/api/v1/auth/login", json={"phone": ALLOWED_PHONE, "password": FIRST})
    assert login.status_code == 200
    assert login.json()["user"]["has_password"] is True


async def test_change_with_correct_current(api: AsyncClient, headers: dict[str, str]) -> None:
    await _put(api, headers, new_password=FIRST)

    response = await _put(api, headers, current_password=FIRST, new_password=SECOND)

    assert response.status_code == 204
    assert await _login_status(api, SECOND) == 200
    assert await _login_status(api, FIRST) == 401


@pytest.mark.parametrize("current", [None, "не-тот-пароль"])
async def test_wrong_or_missing_current_is_400(
    api: AsyncClient, headers: dict[str, str], current: str | None
) -> None:
    await _put(api, headers, new_password=FIRST)

    response = await _put(api, headers, current_password=current, new_password=SECOND)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert response.json()["error"]["details"] == {"current_password": "Неверный пароль"}
    assert await _login_status(api, FIRST) == 200  # пароль не сменился


async def test_wrong_current_counts_as_failed_login(
    api: AsyncClient, headers: dict[str, str], clock: FakeClock, sms: FakeSmsSender
) -> None:
    """5 ошибок подряд (смена пароля + вход — общий счётчик) → блокировка на 15 минут."""
    await _put(api, headers, new_password=FIRST)
    for _ in range(3):
        await _put(api, headers, current_password="мимо-мимо", new_password=SECOND)
    assert await _login_status(api, "мимо-мимо") == 401  # четвёртая ошибка
    await _put(api, headers, current_password="мимо-мимо", new_password=SECOND)  # пятая

    locked = await _put(api, headers, current_password=FIRST, new_password=SECOND)
    assert locked.status_code == 429
    assert locked.json()["error"]["code"] == "RATE_LIMITED"
    assert await _login_status(api, FIRST) == 429

    clock.advance(minutes=16)
    headers = bearer(await login(api, sms))  # access-токен живёт 15 минут — входим заново
    assert (
        await _put(api, headers, current_password=FIRST, new_password=SECOND)
    ).status_code == 204


async def test_success_resets_failed_attempts(api: AsyncClient, headers: dict[str, str]) -> None:
    await _put(api, headers, new_password=FIRST)
    for _ in range(4):
        await _put(api, headers, current_password="мимо-мимо", new_password=SECOND)

    await _put(api, headers, current_password=FIRST, new_password=SECOND)

    # После успешной смены счётчик обнулён: одна ошибка входа не блокирует.
    assert await _login_status(api, "мимо-мимо") == 401
    assert await _login_status(api, SECOND) == 200


@pytest.mark.parametrize(
    "new_password",
    ["1234567", "я" * 65, "        ", ""],
    ids=["7-chars", "65-chars", "spaces", "empty"],
)
async def test_bad_new_password_is_400(
    api: AsyncClient, headers: dict[str, str], new_password: str
) -> None:
    response = await _put(api, headers, new_password=new_password)

    assert response.status_code == 400
    assert "new_password" in response.json()["error"]["details"]
    assert await _has_password(api, headers) is False


async def test_boundary_lengths_are_ok(api: AsyncClient, headers: dict[str, str]) -> None:
    assert (await _put(api, headers, new_password="12345678")).status_code == 204
    long = "я" * 64
    assert (
        await _put(api, headers, current_password="12345678", new_password=long)
    ).status_code == 204
    assert await _login_status(api, long) == 200


async def test_without_token_is_401(api: AsyncClient) -> None:
    response = await api.put(URL, json={"new_password": FIRST})

    assert response.status_code == 401
