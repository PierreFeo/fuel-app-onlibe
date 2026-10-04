import logging
from collections.abc import Iterator

import pytest
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.services.sms import ConsoleSmsSender, SmsSender, get_sms_sender
from tests.fakes import FakeSmsSender, SentSms


@pytest.fixture
def fresh_settings() -> Iterator[None]:
    # get_settings кэшируется — сбрасываем кэш, чтобы подхватить переменные из monkeypatch.
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


async def test_console_sender_writes_sms_to_log(caplog: pytest.LogCaptureFixture) -> None:
    sender: SmsSender = ConsoleSmsSender()

    with caplog.at_level(logging.INFO):
        await sender.send("+79991234567", "Код входа: 123456")

    assert "[DEV SMS] +79991234567 : Код входа: 123456" in caplog.messages


async def test_fake_sender_remembers_messages() -> None:
    sender = FakeSmsSender()

    await sender.send("+79991234567", "первое")
    await sender.send("+79997654321", "второе")

    assert sender.sent == [
        SentSms(phone="+79991234567", text="первое"),
        SentSms(phone="+79997654321", text="второе"),
    ]


def test_factory_returns_console_sender(
    monkeypatch: pytest.MonkeyPatch, fresh_settings: None
) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "console")

    assert isinstance(get_sms_sender(), ConsoleSmsSender)


def test_factory_smsgate_not_implemented_yet(
    monkeypatch: pytest.MonkeyPatch, fresh_settings: None
) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "smsgate")

    with pytest.raises(NotImplementedError):
        get_sms_sender()


def test_unknown_sms_provider_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "pigeon")

    with pytest.raises(ValidationError):
        Settings()
