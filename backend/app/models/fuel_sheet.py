import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    Numeric,
    SmallInteger,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPkMixin
from app.models.enums import SheetStatus


class FuelSheet(UUIDPkMixin, TimestampMixin, Base):
    """ЛУТ — лист учёта топлива за один месяц по одному авто."""

    __tablename__ = "fuel_sheets"
    __table_args__ = (
        UniqueConstraint("car_id", "year", "month"),
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
    # Копия нормы авто на момент создания листа: смена нормы не меняет прошлые месяцы.
    norm_l_per_100km: Mapped[Decimal] = mapped_column(Numeric(5, 2))
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
