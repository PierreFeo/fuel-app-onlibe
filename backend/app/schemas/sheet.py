"""ЛУТ и заправки в ответах API (docs/04_API_CONTRACT.md, «Листы учёта топлива»)."""

import uuid
from datetime import date, datetime
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.models.enums import PaymentType, Season, SheetStatus
from app.schemas.types import Decimal2, Decimal3
from app.services.sheet_calc import ConsumptionStatus

# Пробег — целые км. Верхняя граница — с запасом, но влезает в INTEGER PostgreSQL.
Odometer = Annotated[int, Field(ge=0, le=9_999_999, examples=[52340])]
# Литры в листе — как NUMERIC(8,2) в БД.
Liters = Annotated[Decimal2, Field(ge=0, max_digits=8, examples=["12.00"])]
Year = Annotated[int, Field(ge=2020, le=2100, examples=[2026])]
Month = Annotated[int, Field(ge=1, le=12, examples=[10])]


class SheetCreate(BaseModel):
    year: Year
    month: Month
    odometer_start_km: Odometer
    fuel_start_l: Liters
    season: Season | None = None  # не передан — как в next-prefill


class SheetUpdate(BaseModel):
    """Частичное изменение открытого листа: меняются только переданные поля."""

    odometer_start_km: Odometer | None = None
    odometer_end_km: Odometer | None = None  # null — стереть пробег на конец
    fuel_start_l: Liters | None = None
    fuel_end_actual_l: Liters | None = None  # null — стереть фактический остаток
    season: Season | None = None  # переключение ☀️/❄️ — норма заново копируется из авто

    # Валидатор вызывается только для переданных полей — пропущенные остаются как есть.
    @field_validator("odometer_start_km", "fuel_start_l", "season", mode="after")
    @classmethod
    def _not_null(cls, value: object) -> object:
        if value is None:
            raise ValueError("Поле не может быть null")
        return value


class SheetClose(BaseModel):
    odometer_end_km: Odometer
    fuel_end_actual_l: Liters | None = None  # не передан — остаётся как был


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


class WarningOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    code: str
    message: str


class SheetCalcOut(BaseModel):
    """Вычисляемые поля — формулы в docs/06_BUSINESS_RULES.md."""

    model_config = ConfigDict(from_attributes=True)

    refueled_l: Decimal2
    refueled_cost: Decimal2
    fuel_available_l: Decimal2
    mileage_km: int | None
    norm_consumption_l: Decimal2 | None
    fuel_end_calc_l: Decimal2 | None
    fuel_end_l: Decimal2 | None
    actual_consumption_l: Decimal2 | None
    actual_l_per_100km: Decimal3 | None
    consumption_status: ConsumptionStatus | None
    deviation_l: Decimal2 | None
    cost_per_km: Decimal2 | None
    warnings: list[WarningOut]


class SheetOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    car_id: uuid.UUID
    year: int
    month: int
    status: SheetStatus
    odometer_start_km: int
    odometer_end_km: int | None
    fuel_start_l: Decimal2
    fuel_end_actual_l: Decimal2 | None
    season: Season
    norm_l_per_100km: Decimal3
    refuelings: list[RefuelingOut]
    calc: SheetCalcOut
    closed_at: datetime | None
    created_at: datetime
    updated_at: datetime

    @classmethod
    def build(cls, sheet: Any, calc: Any) -> "SheetOut":
        """Лист из БД + его вычисляемые поля (sheet_calc.SheetCalc)."""
        fields = {name: getattr(sheet, name) for name in cls.model_fields if name != "calc"}
        return cls.model_validate({**fields, "calc": calc}, from_attributes=True)


class SheetPage(BaseModel):
    items: list[SheetOut]
    next_before: str | None = Field(examples=["2025-10"])  # null — листов дальше нет


class SheetPrefill(BaseModel):
    """Подсказка для нового листа: следующий месяц и перенос остатков."""

    year: int
    month: int
    odometer_start_km: int
    fuel_start_l: Decimal2
    season: Season
