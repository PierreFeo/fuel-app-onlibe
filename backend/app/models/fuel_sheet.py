import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    Index,
    Numeric,
    SmallInteger,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, SyncMixin, TimestampMixin, UUIDPkMixin
from app.models.enums import Season, SheetStatus
from app.models.refueling import Refueling


class FuelSheet(UUIDPkMixin, TimestampMixin, SyncMixin, Base):
    """ЛУТ — лист учёта топлива за один месяц по одному авто."""

    __tablename__ = "fuel_sheets"
    __table_args__ = (
        # Один лист на авто на месяц — среди неудалённых: удалённый на телефоне лист не мешает
        # завести новый за тот же месяц.
        Index(
            "uq_fuel_sheets_car_id_year_month",
            "car_id",
            "year",
            "month",
            unique=True,
            postgresql_where=text("deleted_at IS NULL"),
        ),
        CheckConstraint("year BETWEEN 2020 AND 2100", name="year_range"),
        CheckConstraint("month BETWEEN 1 AND 12", name="month_range"),
        CheckConstraint("odometer_start_km >= 0", name="odometer_start_non_negative"),
        CheckConstraint(
            "odometer_end_km IS NULL OR odometer_end_km >= odometer_start_km",
            name="odometer_end_gte_start",
        ),
        CheckConstraint("fuel_start_l >= 0", name="fuel_start_non_negative"),
        CheckConstraint(
            "fuel_end_actual_l IS NULL OR fuel_end_actual_l >= 0",
            name="fuel_end_actual_non_negative",
        ),
        CheckConstraint("norm_l_per_100km > 0", name="norm_positive"),
    )

    car_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("cars.id", ondelete="CASCADE"))
    year: Mapped[int] = mapped_column(SmallInteger)
    month: Mapped[int] = mapped_column(SmallInteger)
    odometer_start_km: Mapped[int]
    odometer_end_km: Mapped[int | None]
    fuel_start_l: Mapped[Decimal] = mapped_column(Numeric(8, 2))
    fuel_end_actual_l: Mapped[Decimal | None] = mapped_column(Numeric(8, 2))
    # Сезон листа (☀️/❄️) и копия нормы авто для этого сезона — на момент создания листа или
    # переключения сезона: смена нормы у авто не меняет прошлые месяцы.
    season: Mapped[Season] = mapped_column(
        Enum(
            Season,
            name="season",
            native_enum=False,
            create_constraint=True,
            length=6,
            values_callable=lambda e: [m.value for m in e],
        )
    )
    norm_l_per_100km: Mapped[Decimal] = mapped_column(Numeric(6, 3))
    status: Mapped[SheetStatus] = mapped_column(
        Enum(
            SheetStatus,
            name="sheet_status",
            native_enum=False,
            create_constraint=True,
            length=10,
            values_callable=lambda e: [m.value for m in e],
        ),
        default=SheetStatus.OPEN,
        server_default=SheetStatus.OPEN.value,
    )
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    # Заправки листа, по дате по возрастанию. Грузятся сразу вместе с листом (selectin):
    # в async-режиме «ленивая» подгрузка при обращении к полю невозможна.
    refuelings: Mapped[list[Refueling]] = relationship(
        order_by=(Refueling.refueled_at, Refueling.created_at, Refueling.id),
        lazy="selectin",
        passive_deletes=True,
    )
