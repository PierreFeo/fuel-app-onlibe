"""POST /auth/verify-code — docs/04_API_CONTRACT.md, docs/05_AUTH_SMS.md."""

import uuid

import jwt
import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.models import RefreshToken, User
from tests.conftest import ALLOWED_PHONE
from tests.fakes import FakeClock, FakeSmsSender

REQUEST_URL = "/api/v1/auth/request-code"
URL = "/api/v1/auth/verify-code"
# Фейковые часы стоят в прошлом — срок токена здесь не проверяем, только содержимое.
NO_EXP = {"verify_exp": False}


async def _request_code(api: AsyncClient, sms: FakeSmsSender) -> str:
    assert (await api.post(REQUEST_URL, json={"phone": ALLOWED_PHONE})).status_code == 200
    return sms.last_code()


def _wrong(code: str) -> str:
    return f"{(int(code) + 1) % 1_000_000:06d}"


async def test_correct_code_creates_user_and_returns_tokens(
    api: AsyncClient, sms: FakeSmsSender, settings: Settings, db: AsyncSession
) -> None:
    code = await _request_code(api, sms)

    response = await api.post(URL, json={"phone": "89991234567", "code": code})

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {
        "access_token",
        "refresh_token",
        "token_type",
        "expires_in_sec",
        "user",
        "is_new_user",
    }
    assert body["token_type"] == "bearer"
    assert body["expires_in_sec"] == 900
    assert body["is_new_user"] is True
    assert body["user"]["phone"] == ALLOWED_PHONE
    assert body["user"]["name"] is None

    user = await db.scalar(select(User).where(User.phone == ALLOWED_PHONE))
    assert user is not None
    assert body["user"]["id"] == str(user.id)

    access = jwt.decode(
        body["access_token"], settings.jwt_secret, algorithms=["HS256"], options=NO_EXP
    )
    assert access["sub"] == str(user.id)
    assert access["type"] == "access"
    refresh = jwt.decode(
        body["refresh_token"], settings.jwt_secret, algorithms=["HS256"], options=NO_EXP
    )
    assert refresh["type"] == "refresh"
    stored = await db.get(RefreshToken, uuid.UUID(refresh["jti"]))
    assert stored is not None
    assert stored.user_id == user.id
    assert stored.revoked_at is None


async def test_existing_user_is_not_new(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    db.add(User(phone=ALLOWED_PHONE, name="Иван Петров"))
    await db.commit()
    code = await _request_code(api, sms)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})

    assert response.status_code == 200
    assert response.json()["is_new_user"] is False
    assert response.json()["user"]["name"] == "Иван Петров"


async def test_wrong_code_is_401_invalid(api: AsyncClient, sms: FakeSmsSender) -> None:
    code = await _request_code(api, sms)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": _wrong(code)})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_INVALID"


async def test_sixth_attempt_is_expired_even_with_correct_code(
    api: AsyncClient, sms: FakeSmsSender
) -> None:
    code = await _request_code(api, sms)
    for _ in range(5):
        response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": _wrong(code)})
        assert response.json()["error"]["code"] == "OTP_INVALID"

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_EXPIRED"


async def test_code_expires_after_5_minutes(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    code = await _request_code(api, sms)
    clock.advance(minutes=5)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_EXPIRED"


async def test_code_still_valid_just_before_expiry(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    code = await _request_code(api, sms)
    clock.advance(minutes=4, seconds=59)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})

    assert response.status_code == 200


async def test_code_cannot_be_used_twice(api: AsyncClient, sms: FakeSmsSender) -> None:
    code = await _request_code(api, sms)
    assert (await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})).status_code == 200

    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": code})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_EXPIRED"


async def test_old_code_is_invalid_after_new_request(
    api: AsyncClient, sms: FakeSmsSender, clock: FakeClock
) -> None:
    old_code = await _request_code(api, sms)
    clock.advance(seconds=60)
    new_code = await _request_code(api, sms)

    if old_code != new_code:  # с вероятностью 1e-6 коды совпадут — тогда проверять нечего
        response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": old_code})
        assert response.json()["error"]["code"] == "OTP_INVALID"
    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": new_code})
    assert response.status_code == 200


async def test_no_code_requested_is_expired(api: AsyncClient) -> None:
    response = await api.post(URL, json={"phone": ALLOWED_PHONE, "code": "123456"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_EXPIRED"


@pytest.mark.parametrize(
    "payload",
    [
        {"phone": ALLOWED_PHONE, "code": "12345"},
        {"phone": ALLOWED_PHONE, "code": "abcdef"},
        {"phone": ALLOWED_PHONE},
        {"phone": "12345", "code": "123456"},
    ],
)
async def test_invalid_payload_is_400(api: AsyncClient, payload: dict[str, str]) -> None:
    response = await api.post(URL, json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_dev_test_account_logs_in_with_000000(api: AsyncClient) -> None:
    await api.post(REQUEST_URL, json={"phone": "+70000000000"})

    response = await api.post(URL, json={"phone": "+70000000000", "code": "000000"})

    assert response.status_code == 200
    assert response.json()["user"]["phone"] == "+70000000000"


async def test_dev_test_account_rejects_other_code(api: AsyncClient) -> None:
    response = await api.post(URL, json={"phone": "+70000000000", "code": "111111"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_INVALID"


async def test_dev_test_account_disabled_in_prod(api: AsyncClient, settings: Settings) -> None:
    settings.env = "prod"

    response = await api.post(URL, json={"phone": "+70000000000", "code": "000000"})

    assert response.status_code == 400
