"""POST /auth/login — запасной вход по паролю (docs/04_API_CONTRACT.md, docs/05_AUTH_SMS.md)."""

import uuid
from datetime import timedelta

import jwt
import pytest
from httpx import AsyncClient, Response
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.models import RefreshToken, User
from app.services.password_service import hash_password
from tests.conftest import ALLOWED_PHONE, bearer
from tests.fakes import FakeClock

URL = "/api/v1/auth/login"
PASSWORD = "k7Fm2xQp9a"
NO_EXP = {"verify_exp": False}


@pytest.fixture
async def user(db: AsyncSession) -> User:
    user = User(phone=ALLOWED_PHONE, password_hash=hash_password(PASSWORD))
    db.add(user)
    await db.commit()
    return user


async def _login(
    api: AsyncClient, password: str = PASSWORD, phone: str = ALLOWED_PHONE
) -> Response:
    return await api.post(URL, json={"phone": phone, "password": password})


def _assert_invalid_credentials(response: Response) -> None:
    assert response.status_code == 401
    assert response.json()["error"] == {
        "code": "INVALID_CREDENTIALS",
        "message": "Неверный номер или пароль",
        "details": {},
    }


async def _reload(db: AsyncSession, user: User) -> User:
    user_id = user.id  # запомнить до expire_all — потом атрибуты объекта недоступны
    db.expire_all()
    fresh = await db.get(User, user_id)
    assert fresh is not None
    return fresh


# --- успешный вход ---


async def test_correct_password_returns_tokens(
    api: AsyncClient, user: User, settings: Settings, db: AsyncSession
) -> None:
    response = await _login(api, phone="8 999 123-45-67")

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
    assert body["user"] == {
        "id": str(user.id),
        "phone": ALLOWED_PHONE,
        "name": None,
        "has_password": True,
    }
    assert body["is_new_user"] is True  # имя ещё не заполнено → приложение спросит его
    refresh = jwt.decode(
        body["refresh_token"], settings.jwt_secret, algorithms=["HS256"], options=NO_EXP
    )
    assert await db.get(RefreshToken, uuid.UUID(refresh["jti"])) is not None
    assert (await api.get("/api/v1/me", headers=bearer(body))).status_code == 200


async def test_user_with_name_is_not_new(api: AsyncClient, user: User, db: AsyncSession) -> None:
    await db.execute(update(User).values(name="Иван"))
    await db.commit()

    response = await _login(api)

    assert response.json()["is_new_user"] is False


async def test_tokens_from_password_login_can_be_refreshed(api: AsyncClient, user: User) -> None:
    tokens = (await _login(api)).json()

    response = await api.post(
        "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )

    assert response.status_code == 200


# --- одинаковый ответ для всех неудач ---


async def test_wrong_password_is_401(api: AsyncClient, user: User) -> None:
    _assert_invalid_credentials(await _login(api, password="wrong-password"))


async def test_unknown_phone_is_401(api: AsyncClient) -> None:
    _assert_invalid_credentials(await _login(api, phone="+79997654321"))


async def test_user_without_password_is_401(api: AsyncClient, db: AsyncSession) -> None:
    db.add(User(phone=ALLOWED_PHONE))
    await db.commit()

    _assert_invalid_credentials(await _login(api))


async def test_inactive_user_is_401_even_with_correct_password(
    api: AsyncClient, user: User, db: AsyncSession
) -> None:
    await db.execute(update(User).values(is_active=False))
    await db.commit()

    _assert_invalid_credentials(await _login(api))


async def test_whitelist_is_not_checked(api: AsyncClient, db: AsyncSession) -> None:
    # номер не в ALLOWED_PHONES, но пароль ему выдали командой — значит, разрешён
    db.add(User(phone="+79997654321", password_hash=hash_password(PASSWORD)))
    await db.commit()

    assert (await _login(api, phone="+79997654321")).status_code == 200


# --- блокировка после неудачных попыток ---


async def test_five_wrong_passwords_lock_login_for_15_minutes(
    api: AsyncClient, user: User, clock: FakeClock, db: AsyncSession
) -> None:
    for _ in range(5):
        _assert_invalid_credentials(await _login(api, password="wrong-password"))

    response = await _login(api)  # даже верный пароль не пускает

    assert response.status_code == 429
    assert response.json()["error"] == {
        "code": "RATE_LIMITED",
        "message": "Слишком много запросов, повторите позже",
        "details": {"retry_after_sec": 15 * 60},
    }
    fresh = await _reload(db, user)
    assert fresh.locked_until == clock.now + timedelta(minutes=15)
    assert fresh.failed_login_attempts == 0


async def test_lock_expires(api: AsyncClient, user: User, clock: FakeClock) -> None:
    for _ in range(5):
        await _login(api, password="wrong-password")
    clock.advance(minutes=14, seconds=59)
    assert (await _login(api)).status_code == 429

    clock.advance(seconds=1)

    assert (await _login(api)).status_code == 200


async def test_success_resets_failed_attempts(
    api: AsyncClient, user: User, db: AsyncSession
) -> None:
    for _ in range(4):
        await _login(api, password="wrong-password")
    assert (await _login(api)).status_code == 200

    assert (await _reload(db, user)).failed_login_attempts == 0
    # после сброса снова доступны 4 ошибки без блокировки
    for _ in range(4):
        _assert_invalid_credentials(await _login(api, password="wrong-password"))
    assert (await _login(api)).status_code == 200


async def test_ip_limit_20_attempts_per_hour(
    api: AsyncClient, user: User, clock: FakeClock
) -> None:
    for i in range(20):
        await _login(api, phone=f"+7999000{i:04d}")  # разные номера — блокировка номера ни при чём

    response = await _login(api)

    assert response.status_code == 429
    assert response.json()["error"]["details"] == {"retry_after_sec": 3600}


# --- формат запроса ---


@pytest.mark.parametrize(
    "payload",
    [
        {"phone": ALLOWED_PHONE},
        {"password": PASSWORD},
        {"phone": ALLOWED_PHONE, "password": ""},
        {"phone": "12345", "password": PASSWORD},
    ],
)
async def test_invalid_payload_is_400(api: AsyncClient, payload: dict[str, str]) -> None:
    response = await api.post(URL, json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_password_is_never_logged(
    api: AsyncClient, user: User, caplog: pytest.LogCaptureFixture
) -> None:
    await _login(api, password="wrong-secret-xyz")
    await _login(api)

    assert "wrong-secret-xyz" not in caplog.text
    assert PASSWORD not in caplog.text
    assert "Password login failed" in caplog.text


async def test_no_refresh_tokens_issued_on_failure(
    api: AsyncClient, user: User, db: AsyncSession
) -> None:
    await _login(api, password="wrong-password")

    assert (await db.scalars(select(RefreshToken))).all() == []


# --- тестовый номер (dev) ---


@pytest.fixture
async def dev_user(db: AsyncSession) -> User:
    user = User(phone="+70000000000", password_hash=hash_password(PASSWORD))
    db.add(user)
    await db.commit()
    return user


async def test_dev_test_phone_logs_in_with_password(api: AsyncClient, dev_user: User) -> None:
    """Номер разбирается так же, как в request-code: тестовый номер в dev разрешён."""
    response = await _login(api, phone="+7 000 000-00-00")

    assert response.status_code == 200
    assert response.json()["user"]["phone"] == "+70000000000"


async def test_dev_test_phone_rejected_in_prod(
    api: AsyncClient, dev_user: User, settings: Settings
) -> None:
    settings.env = "prod"

    response = await _login(api, phone="+70000000000")

    assert response.status_code == 400
    assert response.json()["error"]["details"] == {"phone": "Неверный номер телефона"}
