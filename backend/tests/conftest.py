from collections.abc import AsyncIterator, Callable
from datetime import timedelta

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import NullPool, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app import models  # noqa: F401  — регистрирует таблицы в Base.metadata
from app.core.clock import get_now
from app.core.config import Settings, get_settings
from app.db.base import Base
from app.db.session import get_db
from app.main import app
from app.services.rate_limit import (
    SlidingWindowLimiter,
    get_otp_ip_limiter,
    get_password_ip_limiter,
)
from app.services.sms import get_sms_sender
from tests.fakes import FakeClock, FakeSmsSender

ALLOWED_PHONE = "+79991234567"


@pytest.fixture
async def client() -> AsyncIterator[AsyncClient]:
    """Клиент к приложению без подмен — для эндпоинтов, которым не нужна БД."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
def settings() -> Settings:
    """Предсказуемые настройки тестов — не зависят от содержимого .env."""
    return Settings(
        env="dev",
        jwt_secret="test-secret-test-secret-test-secret!",
        otp_pepper="test-pepper",
        registration_mode="whitelist",
        allowed_phones=ALLOWED_PHONE,
        default_phone_region="RU",
    )


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """Тестовая БД fuel_test: таблицы создаются при необходимости и очищаются после теста."""
    # NullPool: соединения не переиспользуются между тестами (и их event loop'ами).
    engine = create_async_engine(get_settings().test_database_url, poolclass=NullPool)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield async_sessionmaker(engine, expire_on_commit=False)
    async with engine.begin() as conn:
        tables = ", ".join(t.name for t in Base.metadata.sorted_tables)
        await conn.execute(text(f"TRUNCATE {tables} CASCADE"))
    await engine.dispose()


@pytest.fixture
async def db(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIterator[AsyncSession]:
    """Сессия для проверки содержимого БД прямо из теста."""
    async with session_factory() as session:
        yield session


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def sms() -> FakeSmsSender:
    return FakeSmsSender()


@pytest.fixture
async def api(
    settings: Settings,
    session_factory: async_sessionmaker[AsyncSession],
    clock: FakeClock,
    sms: FakeSmsSender,
) -> AsyncIterator[AsyncClient]:
    """Клиент к API с тестовой БД, фейковыми SMS, управляемыми часами и свежим лимитом по IP."""

    async def _get_db() -> AsyncIterator[AsyncSession]:
        async with session_factory() as session:
            yield session

    ip_limiter = SlidingWindowLimiter(settings.otp_max_per_ip_hour, timedelta(hours=1))
    password_ip_limiter = SlidingWindowLimiter(
        settings.password_max_per_ip_hour, timedelta(hours=1)
    )
    overrides: dict[Callable[..., object], Callable[..., object]] = {
        get_db: _get_db,
        get_settings: lambda: settings,
        get_now: clock,
        get_sms_sender: lambda: sms,
        get_otp_ip_limiter: lambda: ip_limiter,
        get_password_ip_limiter: lambda: password_ip_limiter,
    }
    app.dependency_overrides.update(overrides)
    try:
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac
    finally:
        app.dependency_overrides.clear()


async def login(api: AsyncClient, sms: FakeSmsSender, phone: str = ALLOWED_PHONE) -> dict:
    """Пройти вход по SMS и вернуть ответ verify-code (токены + user)."""
    response = await api.post("/api/v1/auth/request-code", json={"phone": phone})
    assert response.status_code == 200, response.text
    response = await api.post(
        "/api/v1/auth/verify-code", json={"phone": phone, "code": sms.last_code()}
    )
    assert response.status_code == 200, response.text
    return response.json()


def bearer(tokens: dict) -> dict[str, str]:
    return {"Authorization": f"Bearer {tokens['access_token']}"}
