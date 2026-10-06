"""GET /me, PATCH /me — docs/04_API_CONTRACT.md, «Профиль»."""

import pytest
from httpx import AsyncClient

from tests.conftest import ALLOWED_PHONE, bearer, login
from tests.fakes import FakeSmsSender

ME = "/api/v1/me"


async def test_get_me(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.get(ME, headers=bearer(tokens))

    assert response.status_code == 200
    assert response.json() == {
        "id": tokens["user"]["id"],
        "phone": ALLOWED_PHONE,
        "name": None,
        "has_password": False,
    }


async def test_patch_me_sets_trimmed_name(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.patch(ME, json={"name": "  Иван Петров "}, headers=bearer(tokens))

    assert response.status_code == 200
    assert response.json()["name"] == "Иван Петров"
    assert (await api.get(ME, headers=bearer(tokens))).json()["name"] == "Иван Петров"


async def test_name_is_returned_after_refresh(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)
    await api.patch(ME, json={"name": "Иван"}, headers=bearer(tokens))

    response = await api.post(
        "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )

    assert response.json()["user"]["name"] == "Иван"


async def test_name_of_100_chars_is_ok(api: AsyncClient, sms: FakeSmsSender) -> None:
    tokens = await login(api, sms)

    response = await api.patch(ME, json={"name": "я" * 100}, headers=bearer(tokens))

    assert response.status_code == 200


@pytest.mark.parametrize("payload", [{"name": ""}, {"name": "   "}, {"name": "я" * 101}, {}])
async def test_invalid_name_is_400(
    api: AsyncClient, sms: FakeSmsSender, payload: dict[str, str]
) -> None:
    tokens = await login(api, sms)

    response = await api.patch(ME, json=payload, headers=bearer(tokens))

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert "name" in response.json()["error"]["details"]


@pytest.mark.parametrize("method", ["GET", "PATCH"])
async def test_me_without_token_is_401(api: AsyncClient, method: str) -> None:
    response = await api.request(method, ME, json={"name": "Иван"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
