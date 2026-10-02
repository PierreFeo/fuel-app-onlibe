import uuid
from datetime import date
from decimal import Decimal

from sqlalchemy import CheckConstraint, Enum, ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPkMixin
from app.models.enums import PaymentType


class Refueling(UUIDPkMixin, TimestampMixin, Base):
    __tablename__ = "refuelings"
    __table_args__ = (
        CheckConstraint("liters > 0", name="liters_positive"),
        CheckConstraint("price_per_liter >= 0", name="price_non_negative"),
        CheckConstraint("total_cost >= 0", name="total_cost_non_negative"),
        CheckConstraint("odometer_km IS NULL OR odometer_km >= 0", name="odometer_non_negative"),
    )

    sheet_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("fuel_sheets.id", ondelete="CASCADE"), index=True
    )
    # Попадание даты в месяц листа проверяет сервис (CHECK между таблицами невозможен).
    refueled_at: Mapped[date]
    liters: Mapped[Decimal] = mapped_column(Numeric(8, 2))
    price_per_liter: Mapped[Decimal] = mapped_column(Numeric(8, 2))
    total_cost: Mapped[Decimal] = mapped_column(Numeric(10, 2))
    odometer_km: Mapped[int | None]
    station: Mapped[str | None] = mapped_column(String(100))
    payment_type: Mapped[PaymentType] = mapped_column(
        Enum(
            PaymentType,
            name="payment_type",
            native_enum=False,
            create_constraint=True,
            length=12,
            values_callable=lambda e: [m.value for m in e],
        ),
        default=PaymentType.PERSONAL,
        server_default=PaymentType.PERSONAL.value,
    )
    note: Mapped[str | None] = mapped_column(String(255))
