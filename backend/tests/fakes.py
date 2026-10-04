from dataclasses import dataclass, field


@dataclass
class SentSms:
    phone: str
    text: str


@dataclass
class FakeSmsSender:
    """SMS для тестов: ничего не отправляет, запоминает сообщения в `sent`."""

    sent: list[SentSms] = field(default_factory=list)

    async def send(self, phone: str, text: str) -> None:
        self.sent.append(SentSms(phone=phone, text=text))
