from datetime import datetime

from sqlalchemy import DateTime, String, true
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPkMixin


class User(UUIDPkMixin, TimestampMixin, Base):
    __tablename__ = "users"

    phone: Mapped[str] = mapped_column(String(16), unique=True)  # E.164: +79991234567
    name: Mapped[str | None] = mapped_column(String(100))
    is_active: Mapped[bool] = mapped_column(default=True, server_default=true())

    # Запасной вход по паролю (docs/05_AUTH_SMS.md). NULL — вход по паролю выключен.
    password_hash: Mapped[str | None] = mapped_column(String(255))
    failed_login_attempts: Mapped[int] = mapped_column(default=0, server_default="0")
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
