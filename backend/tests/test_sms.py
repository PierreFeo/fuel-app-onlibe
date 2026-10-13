import base64
import json
import logging
from collections.abc import Callable, Iterator

import httpx
import pytest
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.services.sms import ConsoleSmsSender, SmsGateSender, SmsSender, get_sms_sender
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


def test_factory_returns_smsgate_sender(
    monkeypatch: pytest.MonkeyPatch, fresh_settings: None
) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "smsgate")
    monkeypatch.setenv("SMSGATE_USERNAME", "user")
    monkeypatch.setenv("SMSGATE_PASSWORD", "secret")

    assert isinstance(get_sms_sender(), SmsGateSender)


@pytest.mark.parametrize(("username", "password"), [("", "secret"), ("user", "")])
def test_smsgate_without_credentials_is_rejected(
    monkeypatch: pytest.MonkeyPatch, username: str, password: str
) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "smsgate")
    monkeypatch.setenv("SMSGATE_USERNAME", username)
    monkeypatch.setenv("SMSGATE_PASSWORD", password)

    with pytest.raises(ValidationError):
        Settings()


# --- SmsGateSender: вместо настоящего SMSGate — поддельный HTTP (httpx.MockTransport) ---

GATE_URL = "https://gate.test/3rdparty/v1"


def _gate_sender(handler: Callable[[httpx.Request], httpx.Response]) -> SmsGateSender:
    return SmsGateSender(
        base_url=GATE_URL + "/",  # лишний «/» в настройке не должен ломать адрес
        username="user",
        password="secret",
        transport=httpx.MockTransport(handler),
    )


async def test_smsgate_sends_message_as_in_contract() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(202, json={"id": "msg-1", "state": "Pending"})

    await _gate_sender(handler).send("+79991234567", "Код входа: 123456")

    [request] = requests
    assert request.method == "POST"
    assert str(request.url) == GATE_URL + "/messages"
    expected_auth = "Basic " + base64.b64encode(b"user:secret").decode()
    assert request.headers["Authorization"] == expected_auth
    assert json.loads(request.content) == {
        "textMessage": {"text": "Код входа: 123456"},
        "phoneNumbers": ["+79991234567"],
    }


async def test_smsgate_does_not_log_sms_text(caplog: pytest.LogCaptureFixture) -> None:
    sender = _gate_sender(lambda _: httpx.Response(202, json={"id": "msg-1"}))

    with caplog.at_level(logging.INFO):
        await sender.send("+79991234567", "Код входа: 123456")

    assert "msg-1" in caplog.text
    assert "123456" not in caplog.text


@pytest.mark.parametrize("status", [400, 401, 500, 502])
async def test_smsgate_error_status_raises(status: int) -> None:
    sender = _gate_sender(lambda _: httpx.Response(status, json={"message": "nope"}))

    with pytest.raises(httpx.HTTPStatusError):
        await sender.send("+79991234567", "текст")


async def test_smsgate_timeout_raises() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timeout", request=request)

    with pytest.raises(httpx.TimeoutException):
        await _gate_sender(handler).send("+79991234567", "текст")


def test_unknown_sms_provider_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SMS_PROVIDER", "pigeon")

    with pytest.raises(ValidationError):
        Settings()
