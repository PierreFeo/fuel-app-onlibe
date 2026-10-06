from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String, true
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPkMixin


class User(UUIDPkMixin, TimestampMixin, Base):
    __tablename__ = "users"

    phone: Mapped[str] = mapped_column(String(16), unique=True)  # E.164: +79991234567
    name: Mapped[str | None] = mapped_column(String(100))
    # Версия имени для синхронизации — из sync_version_seq при каждой смене имени
    # (docs/03_DATA_MODEL.md); NULL — имя ещё не задавали.
    name_version: Mapped[int | None] = mapped_column(BigInteger)
    is_active: Mapped[bool] = mapped_column(default=True, server_default=true())

    # Запасной вход по паролю (docs/05_AUTH_SMS.md). NULL — вход по паролю выключен.
    password_hash: Mapped[str | None] = mapped_column(String(255))
    failed_login_attempts: Mapped[int] = mapped_column(default=0, server_default="0")
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
