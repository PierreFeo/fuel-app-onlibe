"""SMS_PROVIDER=off — вход с кодом 000000 без SMS (docs/05_AUTH_SMS.md, «SMS отключены»)."""

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.models import OtpCode, User
from app.services.password_service import hash_password
from tests.conftest import ALLOWED_PHONE
from tests.fakes import FakeSmsSender

REQUEST_URL = "/api/v1/auth/request-code"
VERIFY_URL = "/api/v1/auth/verify-code"


async def test_request_code_sends_nothing_and_says_fixed(
    api: AsyncClient, settings: Settings, sms: FakeSmsSender, db: AsyncSession
) -> None:
    settings.sms_provider = "off"

    response = await api.post(REQUEST_URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 200
    assert response.json()["delivery"] == "fixed"
    assert sms.sent == []
    assert (await db.scalars(select(OtpCode))).all() == []


async def test_request_code_still_checks_whitelist(api: AsyncClient, settings: Settings) -> None:
    settings.sms_provider = "off"

    response = await api.post(REQUEST_URL, json={"phone": "+79990000001"})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PHONE_NOT_ALLOWED"


async def test_sms_mode_says_sms(api: AsyncClient) -> None:
    response = await api.post(REQUEST_URL, json={"phone": ALLOWED_PHONE})

    assert response.json()["delivery"] == "sms"


async def test_000000_logs_in_new_user(api: AsyncClient, settings: Settings) -> None:
    settings.sms_provider = "off"

    response = await api.post(VERIFY_URL, json={"phone": ALLOWED_PHONE, "code": "000000"})

    assert response.status_code == 200
    body = response.json()
    assert body["user"]["phone"] == ALLOWED_PHONE
    assert body["user"]["has_password"] is False
    assert body["is_new_user"] is True


async def test_other_code_is_rejected(api: AsyncClient, settings: Settings) -> None:
    settings.sms_provider = "off"

    response = await api.post(VERIFY_URL, json={"phone": ALLOWED_PHONE, "code": "123456"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "OTP_INVALID"


async def test_number_with_password_must_use_password(
    api: AsyncClient, settings: Settings, db: AsyncSession
) -> None:
    db.add(User(phone=ALLOWED_PHONE, name="Иван", password_hash=hash_password("k7Fm2xQp9a")))
    await db.commit()
    settings.sms_provider = "off"

    response = await api.post(VERIFY_URL, json={"phone": ALLOWED_PHONE, "code": "000000"})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PASSWORD_REQUIRED"


async def test_verify_without_request_still_checks_whitelist(
    api: AsyncClient, settings: Settings
) -> None:
    settings.sms_provider = "off"

    response = await api.post(VERIFY_URL, json={"phone": "+79990000001", "code": "000000"})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PHONE_NOT_ALLOWED"


async def test_disabled_user_is_rejected(
    api: AsyncClient, settings: Settings, db: AsyncSession
) -> None:
    db.add(User(phone=ALLOWED_PHONE, is_active=False))
    await db.commit()
    settings.sms_provider = "off"

    response = await api.post(VERIFY_URL, json={"phone": ALLOWED_PHONE, "code": "000000"})

    assert response.status_code == 403


async def test_000000_does_not_work_when_sms_on(api: AsyncClient) -> None:
    response = await api.post(VERIFY_URL, json={"phone": ALLOWED_PHONE, "code": "000000"})

    assert response.status_code == 401
