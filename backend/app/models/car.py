import uuid
from decimal import Decimal

from sqlalchemy import CheckConstraint, Enum, ForeignKey, Numeric, String, false
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, SyncMixin, TimestampMixin, UUIDPkMixin
from app.models.enums import FuelType


class Car(UUIDPkMixin, TimestampMixin, SyncMixin, Base):
    __tablename__ = "cars"
    __table_args__ = (
        CheckConstraint("tank_capacity_l > 0", name="tank_capacity_positive"),
        CheckConstraint("norm_l_per_100km > 0", name="norm_positive"),
        CheckConstraint(
            "norm_winter_l_per_100km IS NULL OR norm_winter_l_per_100km > 0",
            name="norm_winter_positive",
        ),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(60))
    plate_number: Mapped[str | None] = mapped_column(String(15))
    fuel_type: Mapped[FuelType] = mapped_column(
        Enum(
            FuelType,
            name="fuel_type",
            native_enum=False,
            create_constraint=True,
            length=10,
            values_callable=lambda e: [m.value for m in e],
        )
    )
    tank_capacity_l: Mapped[Decimal] = mapped_column(Numeric(6, 2))
    # Нормы расхода, л/100 км, 3 знака (10.068). Летняя — основная, зимняя — по желанию.
    norm_l_per_100km: Mapped[Decimal] = mapped_column(Numeric(6, 3))
    norm_winter_l_per_100km: Mapped[Decimal | None] = mapped_column(Numeric(6, 3))
    is_archived: Mapped[bool] = mapped_column(default=False, server_default=false())
