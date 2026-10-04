import math
from collections import defaultdict, deque
from datetime import datetime, timedelta
from functools import lru_cache

from app.core.config import get_settings


class SlidingWindowLimiter:
    """Не больше `limit` событий за `window` на один ключ (например, IP).

    Хранится в памяти процесса: после перезапуска сервера счётчики обнуляются —
    для MVP с одним процессом этого достаточно (docs/05_AUTH_SMS.md).
    """

    def __init__(self, limit: int, window: timedelta) -> None:
        self.limit = limit
        self.window = window
        self._events: dict[str, deque[datetime]] = defaultdict(deque)

    def hit(self, key: str, now: datetime) -> int | None:
        """Учесть событие. Вернёт None, если можно, или через сколько секунд повторить."""
        events = self._events[key]
        while events and events[0] <= now - self.window:
            events.popleft()
        if len(events) >= self.limit:
            return max(1, math.ceil((events[0] + self.window - now).total_seconds()))
        events.append(now)
        return None


@lru_cache
def get_otp_ip_limiter() -> SlidingWindowLimiter:
    """Зависимость FastAPI: один общий счётчик запросов кода по IP."""
    return SlidingWindowLimiter(get_settings().otp_max_per_ip_hour, timedelta(hours=1))


@lru_cache
def get_password_ip_limiter() -> SlidingWindowLimiter:
    """Зависимость FastAPI: общий счётчик попыток входа по паролю по IP."""
    return SlidingWindowLimiter(get_settings().password_max_per_ip_hour, timedelta(hours=1))
