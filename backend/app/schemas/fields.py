"""Поля авто, листов и заправок — ограничения как в БД (docs/03_DATA_MODEL.md).

Используются в записях `POST /sync` (app/schemas/sync.py).
"""

from typing import Annotated

from pydantic import BeforeValidator, Field

from app.schemas.types import Decimal2, Decimal3

# --- Авто ---

CarName = Annotated[str, Field(min_length=1, max_length=60, examples=["Lada Vesta"])]
TankCapacity = Annotated[Decimal2, Field(gt=0, max_digits=6, examples=["50.00"])]
# Нормы расхода, л/100 км — 3 знака после точки, как NUMERIC(6,3) в БД.
Norm = Annotated[Decimal3, Field(gt=0, max_digits=6, examples=["10.068"])]


def _normalize_plate(value: object) -> object:
    """« а123вс77 » → «А123ВС77»; пустая строка — номера нет."""
    if isinstance(value, str):
        value = value.strip().upper()
        return value or None
    return value


PlateNumber = Annotated[
    Annotated[str, Field(max_length=15, examples=["А123ВС77"])] | None,
    BeforeValidator(_normalize_plate),
]

# --- Лист ---

SheetLiters = Annotated[Decimal2, Field(ge=0, max_digits=8, examples=["12.00"])]

# --- Заправка: liters NUMERIC(8,2) > 0, price NUMERIC(8,2), total NUMERIC(10,2) ---

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
