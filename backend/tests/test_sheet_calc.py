"""Расчёты ЛУТ: эталонные примеры A–F и предупреждения (docs/06_BUSINESS_RULES.md)."""

from dataclasses import replace
from decimal import Decimal as D

import pytest

from app.services.sheet_calc import (
    FUEL_END_NEGATIVE,
    FUEL_END_OVER_TANK,
    ODOMETER_GAP,
    REFUELING_ODOMETER_OUT_OF_RANGE,
    REFUELING_OVER_TANK,
    ConsumptionStatus,
    RefuelingData,
    SheetData,
    calculate,
    round2,
    round3,
)

TANK = D("50.00")

# Пример A: норма 8.50; пробег 52340 → 53340; остаток на начало 12.00;
# заправки 40.00 л / 2200.00 ₽ дважды; фактический остаток 10.00.
EXAMPLE_A = SheetData(
    odometer_start_km=52340,
    odometer_end_km=53340,
    fuel_start_l=D("12.00"),
    fuel_end_actual_l=D("10.00"),
    norm_l_per_100km=D("8.50"),
    refuelings=(
        RefuelingData(liters=D("40.00"), total_cost=D("2200.00")),
        RefuelingData(liters=D("40.00"), total_cost=D("2200.00")),
    ),
)


def _fields(calc: object) -> dict[str, object]:
    return {k: v for k, v in vars(calc).items() if k != "warnings"}


# --- эталонные примеры ---


def test_example_a_closed_month_with_actual_fuel() -> None:
    calc = calculate(EXAMPLE_A, tank_capacity_l=TANK)

    assert _fields(calc) == {
        "refueled_l": D("80.00"),
        "refueled_cost": D("4400.00"),
        "fuel_available_l": D("92.00"),
        "mileage_km": 1000,
        "norm_consumption_l": D("85.00"),
        "fuel_end_calc_l": D("7.00"),
        "fuel_end_l": D("10.00"),
        "actual_consumption_l": D("82.00"),
        "actual_l_per_100km": D("8.200"),
        "consumption_status": ConsumptionStatus.NORMAL,
        "deviation_l": D("-3.00"),
        "cost_per_km": D("4.40"),
    }
    assert calc.warnings == []


def test_example_b_open_month_only_start() -> None:
    sheet = SheetData(
        odometer_start_km=53340,
        odometer_end_km=None,
        fuel_start_l=D("10.00"),
        fuel_end_actual_l=None,
        norm_l_per_100km=D("8.50"),
        refuelings=(RefuelingData(liters=D("30.00"), total_cost=D("1650.00")),),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert _fields(calc) == {
        "refueled_l": D("30.00"),
        "refueled_cost": D("1650.00"),
        "fuel_available_l": D("40.00"),
        "mileage_km": None,
        "norm_consumption_l": None,
        "fuel_end_calc_l": None,
        "fuel_end_l": None,
        "actual_consumption_l": None,
        "actual_l_per_100km": None,
        "consumption_status": None,
        "deviation_l": None,
        "cost_per_km": None,
    }
    assert calc.warnings == []


def test_example_c_closed_without_actual_fuel() -> None:
    calc = calculate(replace(EXAMPLE_A, fuel_end_actual_l=None), tank_capacity_l=TANK)

    assert calc.fuel_end_calc_l == D("7.00")
    assert calc.fuel_end_l == D("7.00")
    assert calc.actual_consumption_l is None
    assert calc.actual_l_per_100km is None
    assert calc.deviation_l is None
    assert calc.consumption_status is None
    # то, что не зависит от фактического остатка, считается как в примере A
    assert calc.norm_consumption_l == D("85.00")
    assert calc.cost_per_km == D("4.40")
    assert calc.warnings == []


def test_example_d_negative_fuel_end() -> None:
    sheet = replace(
        EXAMPLE_A, odometer_start_km=10000, odometer_end_km=11500, fuel_end_actual_l=None
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert calc.mileage_km == 1500
    assert calc.norm_consumption_l == D("127.50")
    assert calc.fuel_end_calc_l == D("-35.50")
    assert calc.warnings == [FUEL_END_NEGATIVE]


def test_example_e_zero_mileage() -> None:
    sheet = SheetData(
        odometer_start_km=53340,
        odometer_end_km=53340,
        fuel_start_l=D("10.00"),
        fuel_end_actual_l=None,
        norm_l_per_100km=D("8.50"),
        refuelings=(RefuelingData(liters=D("20.00"), total_cost=D("1100.00")),),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert calc.mileage_km == 0
    assert calc.norm_consumption_l == D("0.00")
    assert calc.fuel_end_calc_l == D("30.00")
    assert calc.actual_l_per_100km is None
    assert calc.cost_per_km is None


def test_example_e_zero_mileage_with_actual_fuel() -> None:
    sheet = SheetData(
        odometer_start_km=53340,
        odometer_end_km=53340,
        fuel_start_l=D("10.00"),
        fuel_end_actual_l=D("30.00"),
        norm_l_per_100km=D("8.50"),
        refuelings=(RefuelingData(liters=D("20.00"), total_cost=D("1100.00")),),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert calc.actual_consumption_l == D("0.00")
    assert calc.deviation_l == D("0.00")
    assert calc.actual_l_per_100km is None  # на 0 км делить нельзя
    assert calc.consumption_status is None  # нет расхода на 100 км — нет и цвета


def test_example_f_rounding() -> None:
    sheet = SheetData(
        odometer_start_km=1000,
        odometer_end_km=1333,
        fuel_start_l=D("40.00"),
        fuel_end_actual_l=None,
        norm_l_per_100km=D("7.77"),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert calc.norm_consumption_l == D("25.87")  # 333 × 7.77 / 100 = 25.8741
    # остаток считается от уже округлённого расхода — цифры в карточке сходятся
    assert calc.fuel_end_calc_l == D("14.13")


# --- округление и мелочи ---


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (D("25.8741"), D("25.87")),
        (D("0.005"), D("0.01")),  # половина — вверх
        (D("2.675"), D("2.68")),
        (D("-0.004"), D("0.00")),  # без «−0.00»
        (D("-35.5"), D("-35.50")),
        (D("7"), D("7.00")),
    ],
)
def test_round2(value: D, expected: D) -> None:
    result = round2(value)

    assert result == expected
    assert str(result) == str(expected)


def test_no_refuelings_gives_zeros_not_null() -> None:
    sheet = SheetData(
        odometer_start_km=0,
        odometer_end_km=None,
        fuel_start_l=D("0"),
        fuel_end_actual_l=None,
        norm_l_per_100km=D("8.50"),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert str(calc.refueled_l) == "0.00"
    assert str(calc.refueled_cost) == "0.00"
    assert str(calc.fuel_available_l) == "0.00"


def test_actual_l_per_100km_is_rounded() -> None:
    # 92.00 − 10.00 = 82.00 л на 333 км → 24.6246... → 24.625 (3 знака, как у нормы)
    calc = calculate(replace(EXAMPLE_A, odometer_end_km=52673), tank_capacity_l=TANK)

    assert str(calc.actual_l_per_100km) == "24.625"
    assert calc.cost_per_km == D("13.21")  # 4400 / 333 = 13.2132...


def test_economy_and_overspending_sign() -> None:
    overspent = calculate(replace(EXAMPLE_A, fuel_end_actual_l=D("2.00")), tank_capacity_l=TANK)

    assert overspent.deviation_l == D("5.00")  # 90 факт − 85 по норме: перерасход > 0
    assert calculate(EXAMPLE_A, tank_capacity_l=TANK).deviation_l == D("-3.00")  # экономия < 0


# --- предупреждения ---


def test_fuel_end_over_tank_uses_final_fuel() -> None:
    sheet = replace(EXAMPLE_A, fuel_end_actual_l=D("50.01"))

    assert calculate(sheet, tank_capacity_l=TANK).warnings == [FUEL_END_OVER_TANK]
    assert (
        calculate(replace(sheet, fuel_end_actual_l=D("50.00")), tank_capacity_l=TANK).warnings == []
    )


def test_fuel_end_over_tank_by_calculated_fuel() -> None:
    # без фактического остатка итоговый остаток = расчётный: 92 − 0 = 92 > 50
    sheet = replace(EXAMPLE_A, odometer_end_km=52340, fuel_end_actual_l=None)

    assert calculate(sheet, tank_capacity_l=TANK).warnings == [FUEL_END_OVER_TANK]


def test_refueling_over_tank_reported_once() -> None:
    big = RefuelingData(liters=D("55.00"), total_cost=D("3000.00"))
    sheet = replace(EXAMPLE_A, refuelings=(big, big), fuel_end_actual_l=D("40.00"))

    warnings = calculate(sheet, tank_capacity_l=TANK).warnings

    assert warnings == [REFUELING_OVER_TANK]


def test_refueling_equal_to_tank_is_fine() -> None:
    full = RefuelingData(liters=D("50.00"), total_cost=D("2750.00"))
    # 500 км × 8.5 = 42.50 л по норме; доступно 12 + 50 = 62 — остаток положительный
    sheet = replace(
        EXAMPLE_A, odometer_end_km=52840, refuelings=(full,), fuel_end_actual_l=D("10.00")
    )

    assert calculate(sheet, tank_capacity_l=TANK).warnings == []


@pytest.mark.parametrize(
    ("prev_end", "has_gap"),
    [(52340, False), (52300, True), (None, False)],
)
def test_odometer_gap(prev_end: int | None, has_gap: bool) -> None:
    calc = calculate(EXAMPLE_A, tank_capacity_l=TANK, prev_odometer_end_km=prev_end)

    assert (ODOMETER_GAP in calc.warnings) is has_gap


@pytest.mark.parametrize(
    ("odometer_km", "end", "out_of_range"),
    [
        (52340, 53340, False),  # ровно на начале
        (53340, 53340, False),  # ровно на конце
        (52339, 53340, True),  # раньше начала
        (53341, 53340, True),  # позже конца
        (60000, None, False),  # конца ещё нет — верхнюю границу не проверяем
        (52000, None, True),  # но нижнюю — проверяем
        (None, 53340, False),  # одометр при заправке не указан
    ],
)
def test_refueling_odometer_out_of_range(
    odometer_km: int | None, end: int | None, out_of_range: bool
) -> None:
    refueling = RefuelingData(liters=D("40.00"), total_cost=D("2200.00"), odometer_km=odometer_km)
    sheet = replace(EXAMPLE_A, odometer_end_km=end, refuelings=(refueling,))

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert (REFUELING_ODOMETER_OUT_OF_RANGE in calc.warnings) is out_of_range


def test_several_warnings_in_table_order() -> None:
    sheet = SheetData(
        odometer_start_km=1000,
        odometer_end_km=3000,
        fuel_start_l=D("0.00"),
        fuel_end_actual_l=None,
        norm_l_per_100km=D("8.50"),
        refuelings=(RefuelingData(liters=D("60.00"), total_cost=D("3300.00"), odometer_km=500),),
    )

    calc = calculate(sheet, tank_capacity_l=TANK, prev_odometer_end_km=900)

    assert [w.code for w in calc.warnings] == [
        "FUEL_END_NEGATIVE",
        "REFUELING_OVER_TANK",
        "ODOMETER_GAP",
        "REFUELING_ODOMETER_OUT_OF_RANGE",
    ]
    assert all(w.message for w in calc.warnings)


# --- сезонные нормы и цвет расхода: примеры G–I (docs/06_BUSINESS_RULES.md) ---


def test_example_g_summer_overspending_is_red() -> None:
    sheet = SheetData(
        odometer_start_km=52340,
        odometer_end_km=53340,
        fuel_start_l=D("12.00"),
        fuel_end_actual_l=D("9.00"),
        norm_l_per_100km=D("10.068"),
        refuelings=(
            RefuelingData(liters=D("50.00"), total_cost=D("2750.00")),
            RefuelingData(liters=D("48.50"), total_cost=D("2667.50")),
        ),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert _fields(calc) == {
        "refueled_l": D("98.50"),
        "refueled_cost": D("5417.50"),
        "fuel_available_l": D("110.50"),
        "mileage_km": 1000,
        "norm_consumption_l": D("100.68"),
        "fuel_end_calc_l": D("9.82"),
        "fuel_end_l": D("9.00"),
        "actual_consumption_l": D("101.50"),
        "actual_l_per_100km": D("10.150"),
        "consumption_status": ConsumptionStatus.OVER,
        "deviation_l": D("0.82"),
        "cost_per_km": D("5.42"),
    }
    assert calc.warnings == []


def test_example_h_winter_within_norm_is_green() -> None:
    sheet = SheetData(
        odometer_start_km=53340,
        odometer_end_km=54240,
        fuel_start_l=D("20.00"),
        fuel_end_actual_l=D("19.50"),
        norm_l_per_100km=D("11.684"),
        refuelings=(
            RefuelingData(liters=D("45.00"), total_cost=D("2520.00")),
            RefuelingData(liters=D("50.00"), total_cost=D("2800.00")),
        ),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert _fields(calc) == {
        "refueled_l": D("95.00"),
        "refueled_cost": D("5320.00"),
        "fuel_available_l": D("115.00"),
        "mileage_km": 900,
        "norm_consumption_l": D("105.16"),  # 900 × 11.684 / 100 = 105.156
        "fuel_end_calc_l": D("9.84"),
        "fuel_end_l": D("19.50"),
        "actual_consumption_l": D("95.50"),
        "actual_l_per_100km": D("10.611"),  # 95.5 / 900 × 100 = 10.6111...
        "consumption_status": ConsumptionStatus.NORMAL,
        "deviation_l": D("-9.66"),
        "cost_per_km": D("5.91"),
    }
    assert calc.warnings == []


@pytest.mark.parametrize(
    ("fuel_end_actual_l", "per_100km", "status"),
    [
        (D("0.00"), "10.068", ConsumptionStatus.NORMAL),  # ушло 100.68 л — ровно норма: зелёный
        (D("0.01"), "10.067", ConsumptionStatus.NORMAL),
    ],
)
def test_example_i_exactly_norm_is_green(
    fuel_end_actual_l: D, per_100km: str, status: ConsumptionStatus
) -> None:
    sheet = SheetData(
        odometer_start_km=0,
        odometer_end_km=1000,
        fuel_start_l=D("0.68"),
        fuel_end_actual_l=fuel_end_actual_l,
        norm_l_per_100km=D("10.068"),
        refuelings=(
            RefuelingData(liters=D("50.00"), total_cost=D("2750.00")),
            RefuelingData(liters=D("50.00"), total_cost=D("2750.00")),
        ),
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert str(calc.actual_l_per_100km) == per_100km
    assert calc.consumption_status is status


def test_example_i_one_hundredth_over_norm_is_red() -> None:
    # ушло 100.69 л на 1000 км → 10.069 > 10.068
    sheet = SheetData(
        odometer_start_km=0,
        odometer_end_km=1000,
        fuel_start_l=D("0.69"),
        fuel_end_actual_l=D("0.00"),
        norm_l_per_100km=D("10.068"),
        refuelings=(RefuelingData(liters=D("50.00"), total_cost=D("2750.00")),) * 2,
    )

    calc = calculate(sheet, tank_capacity_l=TANK)

    assert str(calc.actual_l_per_100km) == "10.069"
    assert calc.consumption_status is ConsumptionStatus.OVER


def test_status_compares_rounded_value_user_sees() -> None:
    # Ушло 1006.84 л на 10 000 км → точно 10.0684, это чуть БОЛЬШЕ нормы 10.068.
    # Но на экране пользователь видит 10.068 = норма, поэтому цвет — зелёный.
    sheet = SheetData(
        odometer_start_km=0,
        odometer_end_km=10000,
        fuel_start_l=D("6.84"),
        fuel_end_actual_l=D("0.00"),
        norm_l_per_100km=D("10.068"),
        refuelings=(RefuelingData(liters=D("1000.00"), total_cost=D("0.00")),),
    )

    calc = calculate(sheet, tank_capacity_l=D("2000.00"))

    assert str(calc.actual_l_per_100km) == "10.068"
    assert calc.consumption_status is ConsumptionStatus.NORMAL


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (D("10.0684"), "10.068"),
        (D("10.0685"), "10.069"),  # половина — вверх
        (D("8.2"), "8.200"),
        (D("-0.0004"), "0.000"),  # без «−0.000»
    ],
)
def test_round3(value: D, expected: str) -> None:
    assert str(round3(value)) == expected
