"""Заправки (docs/04_API_CONTRACT.md, «Заправки»)."""

import uuid
from datetime import date
from typing import Annotated

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator

from app.models.enums import PaymentType
from app.schemas.types import Decimal2, Odometer

# Ограничения — как в БД: liters NUMERIC(8,2) > 0, price NUMERIC(8,2), total NUMERIC(10,2).
RefuelLiters = Annotated[Decimal2, Field(gt=0, max_digits=8, examples=["40.00"])]
Price = Annotated[Decimal2, Field(ge=0, max_digits=8, examples=["55.00"])]
TotalCost = Annotated[Decimal2, Field(ge=0, max_digits=10, examples=["2200.00"])]


def _blank_to_none(value: object) -> object:
    """« Лукойл » → «Лукойл»; пустая строка — значения нет."""
    if isinstance(value, str):
        return value.strip() or None
    return value


Station = Annotated[
    Annotated[str, Field(max_length=100, examples=["Лукойл, Ленина 1"])] | None,
    BeforeValidator(_blank_to_none),
]
Note = Annotated[
    Annotated[str, Field(max_length=255)] | None,
    BeforeValidator(_blank_to_none),
]


class RefuelingCreate(BaseModel):
    refueled_at: date = Field(examples=["2026-10-05"])
    liters: RefuelLiters
    price_per_liter: Price
    total_cost: TotalCost | None = None  # не передан — liters × price_per_liter
    odometer_km: Odometer | None = None
    station: Station = None
    payment_type: PaymentType = PaymentType.PERSONAL
    note: Note = None


class RefuelingUpdate(BaseModel):
    """Частичное изменение: меняются только переданные поля.

    `total_cost`: null или не передан при смене литров/цены — сумма пересчитывается.
    `odometer_km`, `station`, `note`: null — стереть.
    """

    refueled_at: date | None = Field(default=None, examples=["2026-10-05"])
    liters: RefuelLiters | None = None
    price_per_liter: Price | None = None
    total_cost: TotalCost | None = None
    odometer_km: Odometer | None = None
    station: Station = None
    payment_type: PaymentType | None = None
    note: Note = None

    # Валидатор вызывается только для переданных полей — пропущенные остаются как есть.
    @field_validator("refueled_at", "liters", "price_per_liter", "payment_type", mode="after")
    @classmethod
    def _not_null(cls, value: object) -> object:
        if value is None:
            raise ValueError("Поле не может быть null")
        return value


class RefuelingOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    sheet_id: uuid.UUID
    refueled_at: date
    liters: Decimal2
    price_per_liter: Decimal2
    total_cost: Decimal2
    odometer_km: int | None
    station: str | None
    payment_type: PaymentType
    note: str | None
