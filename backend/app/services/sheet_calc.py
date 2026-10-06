"""Расчёты ЛУТ — чистые функции без БД и сети (docs/06_BUSINESS_RULES.md).

Все числа — Decimal. Каждое поле округляется (ROUND_HALF_UP) в конце своего вычисления:
литры и деньги — до 2 знаков, расход на 100 км — до 3 (как нормы: 10.068). Поля, которые
зависят от других, берут их уже ОКРУГЛЁННЫЕ значения — так цифры в карточке сходятся
«на глаз»: доступно 40.00 − по норме 25.87 = остаток 14.13.
Если для поля не хватает данных — None (в API — null).
"""

from collections.abc import Sequence
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from enum import StrEnum

_CENT = Decimal("0.01")
_MILLI = Decimal("0.001")
_ZERO = Decimal("0")


@dataclass(frozen=True)
class RefuelingData:
    liters: Decimal
    total_cost: Decimal
    odometer_km: int | None = None


@dataclass(frozen=True)
class SheetData:
    odometer_start_km: int
    odometer_end_km: int | None
    fuel_start_l: Decimal
    fuel_end_actual_l: Decimal | None
    norm_l_per_100km: Decimal  # копия нормы, сохранённая в листе
    refuelings: Sequence[RefuelingData] = ()


class ConsumptionStatus(StrEnum):
    """Цвет расхода в приложении: NORMAL — зелёный (не больше нормы), OVER — красный."""

    NORMAL = "NORMAL"
    OVER = "OVER"


@dataclass(frozen=True)
class CalcWarning:
    code: str
    message: str


@dataclass(frozen=True)
class SheetCalc:
    refueled_l: Decimal
    refueled_cost: Decimal
    fuel_available_l: Decimal
    mileage_km: int | None
    norm_consumption_l: Decimal | None
    fuel_end_calc_l: Decimal | None
    fuel_end_l: Decimal | None
    actual_consumption_l: Decimal | None
    actual_l_per_100km: Decimal | None
    consumption_status: ConsumptionStatus | None
    deviation_l: Decimal | None
    cost_per_km: Decimal | None
    warnings: list[CalcWarning] = field(default_factory=list)


# Предупреждения — не блокируют сохранение, только подсказывают проверить данные.
FUEL_END_NEGATIVE = CalcWarning(
    "FUEL_END_NEGATIVE", "Расчётный остаток отрицательный — проверьте пробег и заправки"
)
FUEL_END_OVER_TANK = CalcWarning("FUEL_END_OVER_TANK", "Остаток на конец месяца больше объёма бака")
ODOMETER_GAP = CalcWarning(
    "ODOMETER_GAP", "Пробег на начало не совпадает с пробегом на конец прошлого месяца"
)
REFUELING_ODOMETER_OUT_OF_RANGE = CalcWarning(
    "REFUELING_ODOMETER_OUT_OF_RANGE",
    "Пробег при заправке вне пробега за месяц — проверьте показания одометра",
)


def _round(value: Decimal, step: Decimal) -> Decimal:
    result = value.quantize(step, rounding=ROUND_HALF_UP)
    return result.copy_abs() if result.is_zero() else result  # без «−0.00»


def round2(value: Decimal) -> Decimal:
    """До 2 знаков по правилам школьной математики (0.005 → 0.01) — литры и деньги."""
    return _round(value, _CENT)


def round3(value: Decimal) -> Decimal:
    """До 3 знаков (0.0005 → 0.001) — расход на 100 км, как у норм."""
    return _round(value, _MILLI)


def calculate(
    sheet: SheetData,
    *,
    tank_capacity_l: Decimal,
    prev_odometer_end_km: int | None = None,
) -> SheetCalc:
    """Все вычисляемые поля листа.

    prev_odometer_end_km — пробег на конец предыдущего листа этого авто (None — листа нет
    или пробег на конец там не введён); нужен только для предупреждения ODOMETER_GAP.
    """
    refueled_l = round2(sum((r.liters for r in sheet.refuelings), _ZERO))
    refueled_cost = round2(sum((r.total_cost for r in sheet.refuelings), _ZERO))
    fuel_available_l = round2(sheet.fuel_start_l + refueled_l)

    mileage_km = norm_consumption_l = fuel_end_calc_l = None
    if sheet.odometer_end_km is not None:
        mileage_km = sheet.odometer_end_km - sheet.odometer_start_km
        norm_consumption_l = round2(mileage_km * sheet.norm_l_per_100km / 100)
        fuel_end_calc_l = round2(fuel_available_l - norm_consumption_l)

    fuel_end_l = (
        round2(sheet.fuel_end_actual_l) if sheet.fuel_end_actual_l is not None else fuel_end_calc_l
    )

    actual_consumption_l = actual_l_per_100km = consumption_status = deviation_l = None
    if (
        mileage_km is not None
        and norm_consumption_l is not None
        and sheet.fuel_end_actual_l is not None
    ):
        actual_consumption_l = round2(fuel_available_l - sheet.fuel_end_actual_l)
        deviation_l = round2(actual_consumption_l - norm_consumption_l)
        if mileage_km > 0:
            actual_l_per_100km = round3(actual_consumption_l * 100 / mileage_km)
            # Сравниваем то же число с 3 знаками, что видит пользователь: ровно по норме — зелёный.
            consumption_status = (
                ConsumptionStatus.NORMAL
                if actual_l_per_100km <= sheet.norm_l_per_100km
                else ConsumptionStatus.OVER
            )

    cost_per_km = round2(refueled_cost / mileage_km) if mileage_km else None

    return SheetCalc(
        refueled_l=refueled_l,
        refueled_cost=refueled_cost,
        fuel_available_l=fuel_available_l,
        mileage_km=mileage_km,
        norm_consumption_l=norm_consumption_l,
        fuel_end_calc_l=fuel_end_calc_l,
        fuel_end_l=fuel_end_l,
        actual_consumption_l=actual_consumption_l,
        actual_l_per_100km=actual_l_per_100km,
        consumption_status=consumption_status,
        deviation_l=deviation_l,
        cost_per_km=cost_per_km,
        warnings=_warnings(
            sheet, tank_capacity_l, prev_odometer_end_km, fuel_end_calc_l, fuel_end_l
        ),
    )


def _warnings(
    sheet: SheetData,
    tank_capacity_l: Decimal,
    prev_odometer_end_km: int | None,
    fuel_end_calc_l: Decimal | None,
    fuel_end_l: Decimal | None,
) -> list[CalcWarning]:
    """Каждый вид предупреждения — не больше одного раза, в порядке таблицы из 06."""
    start, end = sheet.odometer_start_km, sheet.odometer_end_km
    warnings = []
    if fuel_end_calc_l is not None and fuel_end_calc_l < 0:
        warnings.append(FUEL_END_NEGATIVE)
    if fuel_end_l is not None and fuel_end_l > tank_capacity_l:
        warnings.append(FUEL_END_OVER_TANK)
    if prev_odometer_end_km is not None and start != prev_odometer_end_km:
        warnings.append(ODOMETER_GAP)
    # Пока пробег на конец не введён — проверяем только нижнюю границу.
    if any(
        r.odometer_km is not None
        and (r.odometer_km < start or (end is not None and r.odometer_km > end))
        for r in sheet.refuelings
    ):
        warnings.append(REFUELING_ODOMETER_OUT_OF_RANGE)
    return warnings
