from datetime import UTC, datetime


def get_now() -> datetime:
    """Зависимость FastAPI «текущее время (UTC)». Тесты подменяют её, чтобы «перематывать» время."""
    return datetime.now(UTC)
