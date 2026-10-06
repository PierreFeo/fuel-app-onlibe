"""Синхронизация (docs/04_API_CONTRACT.md, «Синхронизация»).

Записи в запросе приходят как произвольные объекты и проверяются ПО ОДНОЙ в сервисе: плохая
запись не валит весь запрос, а попадает в `rejected`. Поэтому в `SyncIn` они — `dict`.
"""

import uuid
from datetime import date, datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.models.enums import FuelType, PaymentType, Season, SheetStatus
from app.schemas.fields import (
    CarName,
    Norm,
    Note,
    PlateNumber,
    Price,
    RefuelLiters,
    SheetLiters,
    Station,
    TankCapacity,
    TotalCost,
)
from app.schemas.types import Odometer

MAX_RECORDS = 5000  # больше записей в одном запросе — 400, ничего не сохраняется


class ProfileSync(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str = Field(min_length=1, max_length=100, examples=["Иван Петров"])


class SyncIn(BaseModel):
    cursor: int | None = Field(default=None, ge=0, examples=[1520])
    profile: ProfileSync | None = None
    cars: list[dict[str, Any]] = []
    sheets: list[dict[str, Any]] = []
    refuelings: list[dict[str, Any]] = []

    @model_validator(mode="after")
    def _not_too_many(self) -> "SyncIn":
        if len(self.cars) + len(self.sheets) + len(self.refuelings) > MAX_RECORDS:
            raise ValueError(f"Не больше {MAX_RECORDS} записей в одном запросе")
        return self


# --- Записи: одинаковы на вход и на выход ---


class DeletedRecord(BaseModel):
    """Удаление: сервер смотрит только на id (остальные поля могут быть любыми)."""

    id: uuid.UUID


class CarRecord(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, from_attributes=True)

    id: uuid.UUID
    name: CarName
    plate_number: PlateNumber = None
    fuel_type: FuelType
    tank_capacity_l: TankCapacity
    norm_l_per_100km: Norm
    norm_winter_l_per_100km: Norm | None = None
    is_archived: bool = False
    created_at: datetime
    deleted: bool = False


class SheetRecord(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    car_id: uuid.UUID
    year: int = Field(ge=2020, le=2100)
    month: int = Field(ge=1, le=12)
    status: SheetStatus
    odometer_start_km: Odometer
    odometer_end_km: Odometer | None = None
    fuel_start_l: SheetLiters
    fuel_end_actual_l: SheetLiters | None = None
    season: Season
    norm_l_per_100km: Norm
    closed_at: datetime | None = None
    created_at: datetime
    deleted: bool = False

    @model_validator(mode="after")
    def _end_after_start(self) -> "SheetRecord":
        # Повторяет CHECK в БД: иначе запись упала бы на INSERT, а не попала в rejected.
        if self.odometer_end_km is not None and self.odometer_end_km < self.odometer_start_km:
            raise ValueError("odometer_end_km: меньше пробега на начало")
        return self


class RefuelingRecord(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    sheet_id: uuid.UUID
    refueled_at: date
    liters: RefuelLiters
    price_per_liter: Price
    total_cost: TotalCost
    odometer_km: Odometer | None = None
    station: Station = None
    payment_type: PaymentType = PaymentType.PERSONAL
    note: Note = None
    deleted: bool = False


class RejectedOut(BaseModel):
    entity: str = Field(examples=["sheet"])  # car | sheet | refueling
    id: str  # как пришёл: при неверном UUID сервер всё равно называет запись
    code: str = Field(examples=["SHEET_EXISTS"])
    message: str


class SyncOut(BaseModel):
    cursor: int
    profile: ProfileSync | None
    cars: list[CarRecord]
    sheets: list[SheetRecord]
    refuelings: list[RefuelingRecord]
    rejected: list[RejectedOut]
