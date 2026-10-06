import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, MetaData, Sequence, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Единые имена ограничений — чтобы автогенерация миграций давала стабильные, предсказуемые имена.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


# Общая последовательность версий для синхронизации (docs/03_DATA_MODEL.md): каждая запись
# синхронизируемой строки получает следующее значение; курсор телефона — наибольшее полученное.
# Время для этого не годится: у двух записей в одну миллисекунду оно совпадёт.
SYNC_VERSION_SEQ = Sequence("sync_version_seq", metadata=Base.metadata)


class UUIDPkMixin:
    # id может прийти с телефона (docs/03_DATA_MODEL.md); uuid4 — только если его не передали.
    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class SyncMixin:
    """Поля синхронизации: `version` — новый при вставке и каждом ORM-обновлении строки,
    `deleted_at` — мягкое удаление (строка остаётся, чтобы другой телефон узнал об удалении)."""

    version: Mapped[int] = mapped_column(
        BigInteger,
        server_default=SYNC_VERSION_SEQ.next_value(),
        onupdate=SYNC_VERSION_SEQ.next_value(),
        index=True,
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
