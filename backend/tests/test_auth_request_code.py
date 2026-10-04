"""POST /auth/request-code — docs/04_API_CONTRACT.md, docs/05_AUTH_SMS.md."""

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.models import OtpCode, User
from tests.conftest import ALLOWED_PHONE
from tests.fakes import FakeClock, FakeSmsSender

URL = "/api/v1/auth/request-code"


async def test_sends_code_by_sms(api: AsyncClient, sms: FakeSmsSender, db: AsyncSession) -> None:
    response = await api.post(URL, json={"phone": "8 999 123-45-67"})

    assert response.status_code == 200
    assert response.json() == {"expires_in_sec": 300, "resend_after_sec": 60}
    assert len(sms.sent) == 1
    assert sms.sent[0].phone == ALLOWED_PHONE
    code = sms.last_code()
    assert sms.sent[0].text == f"Код входа в Учёт топлива: {code}. Никому не сообщайте."
    # в БД — только хэш, не сам код
    otp = await db.scalar(select(OtpCode))
    assert otp is not None
    assert otp.phone == ALLOWED_PHONE
    assert code not in otp.code_hash


async def test_invalid_phone_is_400(api: AsyncClient, sms: FakeSmsSender) -> None:
    response = await api.post(URL, json={"phone": "12345"})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert "phone" in response.json()["error"]["details"]
    assert sms.sent == []


async def test_missing_phone_is_400(api: AsyncClient) -> None:
    response = await api.post(URL, json={})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_phone_not_in_whitelist_is_403(api: AsyncClient, sms: FakeSmsSender) -> None:
    response = await api.post(URL, json={"phone": "+79997654321"})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PHONE_NOT_ALLOWED"
    assert sms.sent == []


async def test_existing_user_passes_whitelist(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    db.add(User(phone="+79997654321"))
    await db.commit()

    response = await api.post(URL, json={"phone": "+79997654321"})

    assert response.status_code == 200
    assert len(sms.sent) == 1


async def test_inactive_user_is_403(api: AsyncClient, sms: FakeSmsSender, db: AsyncSession) -> None:
    db.add(User(phone=ALLOWED_PHONE, is_active=False))
    await db.commit()

    response = await api.post(URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "PHONE_NOT_ALLOWED"
    assert sms.sent == []


async def test_open_mode_allows_any_phone(api: AsyncClient, settings: Settings) -> None:
    settings.registration_mode = "open"

    response = await api.post(URL, json={"phone": "+79997654321"})

    assert response.status_code == 200


async def test_invalid_entry_in_allowed_phones_is_skipped(
    api: AsyncClient, settings: Settings
) -> None:
    settings.allowed_phones = f"oops, {ALLOWED_PHONE}"

    response = await api.post(URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 200


async def test_resend_earlier_than_60_sec_is_429(api: AsyncClient, clock: FakeClock) -> None:
    await api.post(URL, json={"phone": ALLOWED_PHONE})
    clock.advance(seconds=15)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 429
    assert response.json()["error"] == {
        "code": "RATE_LIMITED",
        "message": "Слишком много запросов, повторите позже",
        "details": {"retry_after_sec": 45},
    }
    clock.advance(seconds=45)
    assert (await api.post(URL, json={"phone": ALLOWED_PHONE})).status_code == 200


async def test_more_than_5_codes_per_hour_is_429(api: AsyncClient, clock: FakeClock) -> None:
    for _ in range(5):
        assert (await api.post(URL, json={"phone": ALLOWED_PHONE})).status_code == 200
        clock.advance(minutes=1)

    response = await api.post(URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 429
    # первый код был 5 минут назад — ещё 55 минут ждать
    assert response.json()["error"]["details"] == {"retry_after_sec": 55 * 60}
    clock.advance(minutes=55)
    assert (await api.post(URL, json={"phone": ALLOWED_PHONE})).status_code == 200


async def test_more_than_20_requests_per_ip_is_429(
    api: AsyncClient, settings: Settings, clock: FakeClock
) -> None:
    settings.registration_mode = "open"
    for i in range(20):
        phone = f"+7999000{i:04d}"
        assert (await api.post(URL, json={"phone": phone})).status_code == 200

    response = await api.post(URL, json={"phone": "+79990009999"})

    assert response.status_code == 429
    assert response.json()["error"]["details"] == {"retry_after_sec": 3600}


async def test_new_code_invalidates_previous(
    api: AsyncClient, clock: FakeClock, db: AsyncSession
) -> None:
    await api.post(URL, json={"phone": ALLOWED_PHONE})
    clock.advance(seconds=60)
    await api.post(URL, json={"phone": ALLOWED_PHONE})

    active = await db.scalar(
        select(func.count()).select_from(OtpCode).where(OtpCode.used_at.is_(None))
    )
    assert active == 1


async def test_sms_failure_is_502_and_code_is_unusable(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    sms.fail = True

    response = await api.post(URL, json={"phone": ALLOWED_PHONE})

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "SMS_SEND_FAILED"
    otp = await db.scalar(select(OtpCode))
    assert otp is not None
    assert otp.used_at is not None


@pytest.mark.parametrize("env", ["dev", "prod"])
async def test_dev_test_phone_sends_nothing_only_in_dev(
    api: AsyncClient, settings: Settings, sms: FakeSmsSender, env: str
) -> None:
    settings.env = env  # type: ignore[assignment]

    response = await api.post(URL, json={"phone": "+70000000000"})

    if env == "dev":
        assert response.status_code == 200
    else:
        assert response.status_code == 400  # в prod такого номера просто не существует
    assert sms.sent == []
