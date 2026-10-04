import re
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta


@dataclass
class SentSms:
    phone: str
    text: str


@dataclass
class FakeSmsSender:
    """SMS для тестов: ничего не отправляет, запоминает сообщения в `sent`."""

    sent: list[SentSms] = field(default_factory=list)
    fail: bool = False  # True — имитировать сбой шлюза

    async def send(self, phone: str, text: str) -> None:
        if self.fail:
            raise RuntimeError("SMS gateway is down")
        self.sent.append(SentSms(phone=phone, text=text))

    def last_code(self) -> str:
        """6 цифр кода из последней SMS."""
        return re.search(r"\d{6}", self.sent[-1].text).group()  # type: ignore[union-attr]


@dataclass
class FakeClock:
    """Управляемые часы: тест сам «перематывает» время."""

    now: datetime = field(default_factory=lambda: datetime(2026, 10, 2, 8, 0, tzinfo=UTC))

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs: float) -> None:
        self.now += timedelta(**kwargs)
