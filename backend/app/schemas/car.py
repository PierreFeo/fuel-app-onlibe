import uuid
from datetime import datetime
from typing import Annotated

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator

from app.models.enums import FuelType
from app.schemas.types import Decimal2, Decimal3

TankCapacity = Annotated[Decimal2, Field(gt=0, max_digits=6, examples=["50.00"])]
# Нормы расхода, л/100 км — 3 знака после точки, как NUMERIC(6,3) в БД.
Norm = Annotated[Decimal3, Field(gt=0, max_digits=6, examples=["10.068"])]
WinterNorm = Annotated[Decimal3, Field(gt=0, max_digits=6, examples=["11.684"])]
CarName = Annotated[str, Field(min_length=1, max_length=60, examples=["Lada Vesta"])]


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


class CarCreate(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: CarName
    plate_number: PlateNumber = None
    fuel_type: FuelType
    tank_capacity_l: TankCapacity
    norm_l_per_100km: Norm
    norm_winter_l_per_100km: WinterNorm | None = None  # зимняя норма — по желанию


class CarUpdate(BaseModel):
    """Частичное изменение: меняются только переданные поля."""

    model_config = ConfigDict(str_strip_whitespace=True)

    name: CarName | None = None
    plate_number: PlateNumber = None
    fuel_type: FuelType | None = None
    tank_capacity_l: TankCapacity | None = None
    norm_l_per_100km: Norm | None = None
    norm_winter_l_per_100km: WinterNorm | None = None  # null — убрать зимнюю норму
    is_archived: bool | None = None  # false — вернуть авто из архива

    # null допустим только для госномера и зимней нормы (= удалить); остальные поля обязательны.
    # Валидатор вызывается только для переданных полей — пропущенные остаются как есть.
    @field_validator(
        "name", "fuel_type", "tank_capacity_l", "norm_l_per_100km", "is_archived", mode="after"
    )
    @classmethod
    def _not_null(cls, value: object) -> object:
        if value is None:
            raise ValueError("Поле не может быть null")
        return value


class CarOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    plate_number: str | None
    fuel_type: FuelType
    tank_capacity_l: Decimal2
    norm_l_per_100km: Decimal3
    norm_winter_l_per_100km: Decimal3 | None
    is_archived: bool
    created_at: datetime
