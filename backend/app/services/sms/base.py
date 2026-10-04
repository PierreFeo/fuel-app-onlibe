from typing import Protocol


class SmsSender(Protocol):
    """Отправщик SMS. Реализация выбирается настройкой SMS_PROVIDER (см. factory.py)."""

    async def send(self, phone: str, text: str) -> None: ...
