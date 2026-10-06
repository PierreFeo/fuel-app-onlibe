"""ЛУТ: /cars/{id}/sheets и /sheets/{id} — docs/04_API_CONTRACT.md, «Листы учёта топлива»."""

import uuid
from datetime import UTC, date, datetime
from decimal import Decimal as D
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Refueling
from tests.conftest import OTHER_PHONE, bearer, login
from tests.fakes import FakeClock, FakeSmsSender

CARS = "/api/v1/cars"
SHEETS = "/api/v1/sheets"
VESTA = {
    "name": "Lada Vesta",
    "fuel_type": "AI95",
    "tank_capacity_l": "50.00",
    "norm_l_per_100km": "8.500",
    "norm_winter_l_per_100km": "11.684",
}
# Часы тестов (tests/fakes.py) стоят на 2 октября 2026 — текущий месяц 2026-10.
OCTOBER = {"year": 2026, "month": 10, "odometer_start_km": 52340, "fuel_start_l": "12.00"}


@pytest.fixture
async def headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    return bearer(await login(api, sms))


@pytest.fixture
async def other_headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    """Второй сотрудник."""
    return bearer(await login(api, sms, phone=OTHER_PHONE))


@pytest.fixture
async def car(api: AsyncClient, headers: dict[str, str]) -> dict[str, Any]:
    return await _create_car(api, headers)


async def _create_car(api: AsyncClient, headers: dict[str, str], **changes: Any) -> dict[str, Any]:
    response = await api.post(CARS, json={**VESTA, **changes}, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()


async def _create_sheet(
    api: AsyncClient, headers: dict[str, str], car: dict[str, Any], **changes: Any
) -> dict[str, Any]:
    response = await api.post(
        f"{CARS}/{car['id']}/sheets", json={**OCTOBER, **changes}, headers=headers
    )
    assert response.status_code == 201, response.text
    return response.json()


async def _add_refueling(
    db: AsyncSession, sheet: dict[str, Any], liters: str, cost: str, **changes: Any
) -> None:
    """Заправка прямо в БД, минуя API (сам API заправок — в test_refuelings.py)."""
    fields: dict[str, Any] = {
        "sheet_id": uuid.UUID(sheet["id"]),
        "refueled_at": date(sheet["year"], sheet["month"], 5),
        "liters": D(liters),
        "price_per_liter": D("55.00"),
        "total_cost": D(cost),
        **changes,
    }
    db.add(Refueling(**fields))
    await db.commit()


def _error(response: Any) -> dict[str, Any]:
    return response.json()["error"]


# --- POST /cars/{car_id}/sheets ---


async def test_create_sheet(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    response = await api.post(f"{CARS}/{car['id']}/sheets", json=OCTOBER, headers=headers)

    assert response.status_code == 201
    sheet = response.json()
    assert list(sheet) == [
        "id",
        "car_id",
        "year",
        "month",
        "status",
        "odometer_start_km",
        "odometer_end_km",
        "fuel_start_l",
        "fuel_end_actual_l",
        "season",
        "norm_l_per_100km",
        "refuelings",
        "calc",
        "closed_at",
        "created_at",
        "updated_at",
    ]
    assert sheet["car_id"] == car["id"]
    assert (sheet["year"], sheet["month"], sheet["status"]) == (2026, 10, "OPEN")
    assert sheet["odometer_start_km"] == 52340
    assert sheet["odometer_end_km"] is None
    assert sheet["fuel_start_l"] == "12.00"
    assert sheet["fuel_end_actual_l"] is None
    # Самый первый лист: октябрь не в WINTER_MONTHS → лето, летняя норма авто.
    assert sheet["season"] == "SUMMER"
    assert sheet["norm_l_per_100km"] == "8.500"
    assert sheet["refuelings"] == []
    assert sheet["closed_at"] is None
    assert sheet["calc"] == {
        "refueled_l": "0.00",
        "refueled_cost": "0.00",
        "fuel_available_l": "12.00",
        "mileage_km": None,
        "norm_consumption_l": None,
        "fuel_end_calc_l": None,
        "fuel_end_l": None,
        "actual_consumption_l": None,
        "actual_l_per_100km": None,
        "consumption_status": None,
        "deviation_l": None,
        "cost_per_km": None,
        "warnings": [],
    }


async def test_first_sheet_in_winter_month_is_winter(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car, month=11)

    assert sheet["season"] == "WINTER"
    assert sheet["norm_l_per_100km"] == "11.684"


async def test_first_winter_sheet_without_winter_norm_is_summer(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    car = await _create_car(api, headers, norm_winter_l_per_100km=None)

    sheet = await _create_sheet(api, headers, car, month=11)

    assert sheet["season"] == "SUMMER"
    assert sheet["norm_l_per_100km"] == "8.500"


async def test_new_sheet_inherits_season_of_latest(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car, month=9, season="WINTER")

    # Октябрь — «летний» месяц, но сезон наследуется от последнего листа.
    sheet = await _create_sheet(api, headers, car)

    assert sheet["season"] == "WINTER"
    assert sheet["norm_l_per_100km"] == "11.684"


async def test_create_with_explicit_season(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car, month=11, season="SUMMER")

    assert sheet["season"] == "SUMMER"
    assert sheet["norm_l_per_100km"] == "8.500"


async def test_create_winter_without_winter_norm_is_422(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    car = await _create_car(api, headers, norm_winter_l_per_100km=None)

    response = await api.post(
        f"{CARS}/{car['id']}/sheets", json={**OCTOBER, "season": "WINTER"}, headers=headers
    )

    assert response.status_code == 422
    assert _error(response)["code"] == "BUSINESS_RULE"
    assert _error(response)["details"] == {"reason": "WINTER_NORM_NOT_SET"}


async def test_create_same_month_twice_is_409(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car)

    response = await api.post(f"{CARS}/{car['id']}/sheets", json=OCTOBER, headers=headers)

    assert response.status_code == 409
    assert _error(response)["code"] == "SHEET_EXISTS"


async def test_same_month_for_another_car_is_allowed(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car)
    other_car = await _create_car(api, headers, name="Kia Rio")

    await _create_sheet(api, headers, other_car)


async def test_create_next_month_ok_but_not_later(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car, month=11)

    response = await api.post(
        f"{CARS}/{car['id']}/sheets", json={**OCTOBER, "month": 12}, headers=headers
    )

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "MONTH_TOO_FAR"}


async def test_month_limit_follows_clock(
    api: AsyncClient, car: dict, clock: FakeClock, sms: FakeSmsSender
) -> None:
    clock.now = datetime(2026, 12, 15, tzinfo=UTC)
    headers = bearer(await login(api, sms))  # старый access-токен за 2 месяца истёк

    sheet = await _create_sheet(api, headers, car, year=2027, month=1)

    assert (sheet["year"], sheet["month"]) == (2027, 1)


@pytest.mark.parametrize(
    ("changes", "field"),
    [
        ({"month": 13}, "month"),
        ({"month": 0}, "month"),
        ({"year": 2019}, "year"),
        ({"odometer_start_km": -1}, "odometer_start_km"),
        ({"fuel_start_l": "-0.01"}, "fuel_start_l"),
        ({"fuel_start_l": "1.234"}, "fuel_start_l"),
        ({"season": "AUTUMN"}, "season"),
    ],
)
async def test_create_invalid_fields_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict, changes: dict, field: str
) -> None:
    response = await api.post(
        f"{CARS}/{car['id']}/sheets", json={**OCTOBER, **changes}, headers=headers
    )

    assert response.status_code == 400
    assert _error(response)["code"] == "VALIDATION_ERROR"
    assert field in _error(response)["details"]


async def test_create_missing_field_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    body = {k: v for k, v in OCTOBER.items() if k != "odometer_start_km"}

    response = await api.post(f"{CARS}/{car['id']}/sheets", json=body, headers=headers)

    assert response.status_code == 400
    assert "odometer_start_km" in _error(response)["details"]


async def test_create_without_token_is_401(api: AsyncClient, car: dict) -> None:
    response = await api.post(f"{CARS}/{car['id']}/sheets", json=OCTOBER)

    assert response.status_code == 401


async def test_create_for_others_car_is_404(
    api: AsyncClient, car: dict, other_headers: dict[str, str]
) -> None:
    response = await api.post(f"{CARS}/{car['id']}/sheets", json=OCTOBER, headers=other_headers)

    assert response.status_code == 404
    assert _error(response)["code"] == "NOT_FOUND"


async def test_create_for_archived_car_is_allowed(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await api.delete(f"{CARS}/{car['id']}", headers=headers)

    await _create_sheet(api, headers, car)


# --- GET /cars/{car_id}/sheets ---


async def test_list_empty(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    response = await api.get(f"{CARS}/{car['id']}/sheets", headers=headers)

    assert response.status_code == 200
    assert response.json() == {"items": [], "next_before": None}


async def test_list_newest_first_with_pagination(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    for year, month in [(2026, 8), (2025, 12), (2026, 10), (2026, 1)]:
        await _create_sheet(api, headers, car, year=year, month=month)
    url = f"{CARS}/{car['id']}/sheets"

    first = (await api.get(url, params={"limit": 2}, headers=headers)).json()
    second = (
        await api.get(url, params={"limit": 2, "before": first["next_before"]}, headers=headers)
    ).json()

    assert [(s["year"], s["month"]) for s in first["items"]] == [(2026, 10), (2026, 8)]
    assert first["next_before"] == "2026-08"
    assert [(s["year"], s["month"]) for s in second["items"]] == [(2026, 1), (2025, 12)]
    assert second["next_before"] is None


async def test_list_default_limit_is_12(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    for month in range(1, 11):
        await _create_sheet(api, headers, car, year=2025, month=month)
    for month in range(1, 4):
        await _create_sheet(api, headers, car, year=2026, month=month)

    page = (await api.get(f"{CARS}/{car['id']}/sheets", headers=headers)).json()

    assert len(page["items"]) == 12
    assert page["next_before"] == "2025-02"


async def test_list_items_have_calc_and_odometer_gap(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    september = await _create_sheet(api, headers, car, month=9, odometer_start_km=51000)
    await api.post(
        f"{SHEETS}/{september['id']}/close",
        json={"odometer_end_km": 52000, "fuel_end_actual_l": "0"},
        headers=headers,
    )
    await _create_sheet(api, headers, car)  # начинается с 52340, а сентябрь закончился 52000

    # limit=1: «предыдущий месяц» для октября — за пределами страницы, но GAP всё равно виден.
    page = (await api.get(f"{CARS}/{car['id']}/sheets?limit=1", headers=headers)).json()

    assert [w["code"] for w in page["items"][0]["calc"]["warnings"]] == ["ODOMETER_GAP"]


@pytest.mark.parametrize(
    "params", [{"limit": 0}, {"limit": 51}, {"before": "2026-13"}, {"before": "октябрь"}]
)
async def test_list_invalid_params_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict, params: dict
) -> None:
    response = await api.get(f"{CARS}/{car['id']}/sheets", params=params, headers=headers)

    assert response.status_code == 400
    assert _error(response)["code"] == "VALIDATION_ERROR"


async def test_list_without_token_is_401(api: AsyncClient, car: dict) -> None:
    assert (await api.get(f"{CARS}/{car['id']}/sheets")).status_code == 401


async def test_list_others_car_is_404(
    api: AsyncClient, car: dict, other_headers: dict[str, str]
) -> None:
    response = await api.get(f"{CARS}/{car['id']}/sheets", headers=other_headers)

    assert response.status_code == 404


# --- GET /cars/{car_id}/sheets/next-prefill ---


async def _prefill(api: AsyncClient, headers: dict[str, str], car: dict) -> dict[str, Any]:
    response = await api.get(f"{CARS}/{car['id']}/sheets/next-prefill", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


async def test_prefill_without_sheets(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    assert await _prefill(api, headers, car) == {
        "year": 2026,
        "month": 10,
        "odometer_start_km": 0,
        "fuel_start_l": "0.00",
        "season": "SUMMER",
    }


async def test_prefill_without_sheets_in_winter(
    api: AsyncClient, car: dict, clock: FakeClock, sms: FakeSmsSender
) -> None:
    clock.now = datetime(2026, 11, 3, tzinfo=UTC)
    headers = bearer(await login(api, sms))  # старый access-токен за месяц истёк
    no_winter = await _create_car(api, headers, norm_winter_l_per_100km=None)

    assert (await _prefill(api, headers, car))["season"] == "WINTER"
    # Зимней нормы нет — подсказка даёт лето.
    assert (await _prefill(api, headers, no_winter))["season"] == "SUMMER"


async def test_prefill_after_closed_sheet(
    api: AsyncClient, headers: dict[str, str], car: dict, db: AsyncSession
) -> None:
    sheet = await _create_sheet(api, headers, car, season="WINTER")
    await _add_refueling(db, sheet, "40.00", "2200.00")
    await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "10.00"},
        headers=headers,
    )

    assert await _prefill(api, headers, car) == {
        "year": 2026,
        "month": 11,
        "odometer_start_km": 53340,
        "fuel_start_l": "10.00",
        "season": "WINTER",
    }


async def test_prefill_takes_calculated_fuel_end(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car, fuel_start_l="40.00")
    # Фактический остаток не введён — переносится расчётный (fuel_end_calc_l).
    await api.patch(f"{SHEETS}/{sheet['id']}", json={"odometer_end_km": 52540}, headers=headers)

    prefill = await _prefill(api, headers, car)

    # 200 км × 8.5 / 100 = 17.00 → 40.00 − 17.00 = 23.00
    assert prefill["odometer_start_km"] == 52540
    assert prefill["fuel_start_l"] == "23.00"


async def test_prefill_from_sheet_without_end(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car)

    prefill = await _prefill(api, headers, car)

    # Пробега на конец нет — берём пробег на начало; остатка на конец нет — 0.00.
    assert prefill["odometer_start_km"] == 52340
    assert prefill["fuel_start_l"] == "0.00"


async def test_prefill_december_rolls_to_january(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car, year=2025, month=12)

    prefill = await _prefill(api, headers, car)

    assert (prefill["year"], prefill["month"]) == (2026, 1)
    assert prefill["season"] == "WINTER"  # декабрь первым листом — зима, январь наследует


async def test_prefill_winter_without_winter_norm_gives_summer(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    await _create_sheet(api, headers, car, season="WINTER")
    await api.patch(f"{CARS}/{car['id']}", json={"norm_winter_l_per_100km": None}, headers=headers)

    assert (await _prefill(api, headers, car))["season"] == "SUMMER"


async def test_prefill_without_token_is_401(api: AsyncClient, car: dict) -> None:
    assert (await api.get(f"{CARS}/{car['id']}/sheets/next-prefill")).status_code == 401


async def test_prefill_others_car_is_404(
    api: AsyncClient, car: dict, other_headers: dict[str, str]
) -> None:
    response = await api.get(f"{CARS}/{car['id']}/sheets/next-prefill", headers=other_headers)

    assert response.status_code == 404


# --- GET /sheets/{sheet_id} ---


async def test_get_sheet(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)

    assert response.status_code == 200
    assert response.json() == sheet


async def test_get_sheet_with_refuelings_sorted_by_date(
    api: AsyncClient, headers: dict[str, str], car: dict, db: AsyncSession
) -> None:
    sheet = await _create_sheet(api, headers, car)
    await _add_refueling(db, sheet, "30.00", "1650.00", refueled_at=date(2026, 10, 20))
    await _add_refueling(db, sheet, "20.00", "1100.00", refueled_at=date(2026, 10, 3))

    body = (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).json()

    assert [r["refueled_at"] for r in body["refuelings"]] == ["2026-10-03", "2026-10-20"]
    assert set(body["refuelings"][0]) == {
        "id",
        "sheet_id",
        "refueled_at",
        "liters",
        "price_per_liter",
        "total_cost",
        "odometer_km",
        "station",
        "payment_type",
        "note",
    }
    assert body["refuelings"][0]["liters"] == "20.00"
    assert body["refuelings"][0]["payment_type"] == "PERSONAL"
    assert body["calc"]["refueled_l"] == "50.00"
    assert body["calc"]["refueled_cost"] == "2750.00"
    assert body["calc"]["fuel_available_l"] == "62.00"


async def test_get_sheet_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    assert (await api.get(f"{SHEETS}/{sheet['id']}")).status_code == 401


async def test_get_others_sheet_is_404(
    api: AsyncClient, headers: dict[str, str], car: dict, other_headers: dict[str, str]
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.get(f"{SHEETS}/{sheet['id']}", headers=other_headers)

    assert response.status_code == 404
    assert _error(response)["code"] == "NOT_FOUND"


async def test_get_unknown_sheet_is_404(api: AsyncClient, headers: dict[str, str]) -> None:
    assert (await api.get(f"{SHEETS}/{uuid.uuid4()}", headers=headers)).status_code == 404


async def test_get_sheet_bad_id_is_400(api: AsyncClient, headers: dict[str, str]) -> None:
    assert (await api.get(f"{SHEETS}/not-a-uuid", headers=headers)).status_code == 400


# --- PATCH /sheets/{sheet_id} ---


async def test_patch_fields(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.patch(
        f"{SHEETS}/{sheet['id']}",
        json={"odometer_end_km": 53340, "fuel_start_l": "15", "fuel_end_actual_l": "5.5"},
        headers=headers,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["odometer_start_km"] == 52340  # не передан — не изменился
    assert body["odometer_end_km"] == 53340
    assert body["fuel_start_l"] == "15.00"
    assert body["fuel_end_actual_l"] == "5.50"
    assert body["calc"]["mileage_km"] == 1000
    assert body["calc"]["norm_consumption_l"] == "85.00"
    assert body["calc"]["actual_consumption_l"] == "9.50"


async def test_patch_null_clears_end_values(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"
    await api.patch(url, json={"odometer_end_km": 53340, "fuel_end_actual_l": "5"}, headers=headers)

    response = await api.patch(
        url, json={"odometer_end_km": None, "fuel_end_actual_l": None}, headers=headers
    )

    assert response.status_code == 200
    assert response.json()["odometer_end_km"] is None
    assert response.json()["fuel_end_actual_l"] is None


async def test_patch_switch_season(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"
    await api.patch(url, json={"odometer_end_km": 53340}, headers=headers)

    winter = (await api.patch(url, json={"season": "WINTER"}, headers=headers)).json()
    summer = (await api.patch(url, json={"season": "SUMMER"}, headers=headers)).json()

    assert (winter["season"], winter["norm_l_per_100km"]) == ("WINTER", "11.684")
    assert winter["calc"]["norm_consumption_l"] == "116.84"  # calc пересчитан
    assert (summer["season"], summer["norm_l_per_100km"]) == ("SUMMER", "8.500")
    assert summer["calc"]["norm_consumption_l"] == "85.00"


async def test_switch_season_copies_current_car_norm(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"
    await api.patch(f"{CARS}/{car['id']}", json={"norm_l_per_100km": "9"}, headers=headers)

    # Норма авто изменилась, но лист её не видит, пока сезон не переключат (правило 6).
    assert (await api.get(url, headers=headers)).json()["norm_l_per_100km"] == "8.500"
    assert (await api.patch(url, json={"season": "SUMMER"}, headers=headers)).json()[
        "norm_l_per_100km"
    ] == "8.500"
    await api.patch(url, json={"season": "WINTER"}, headers=headers)
    summer = (await api.patch(url, json={"season": "SUMMER"}, headers=headers)).json()
    assert summer["norm_l_per_100km"] == "9.000"


async def test_patch_winter_without_winter_norm_is_422(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    car = await _create_car(api, headers, norm_winter_l_per_100km=None)
    sheet = await _create_sheet(api, headers, car)

    response = await api.patch(
        f"{SHEETS}/{sheet['id']}", json={"season": "WINTER"}, headers=headers
    )

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "WINTER_NORM_NOT_SET"}
    body = (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).json()
    assert body["season"] == "SUMMER"


async def test_patch_end_before_start_is_422(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.patch(
        f"{SHEETS}/{sheet['id']}", json={"odometer_end_km": 52339}, headers=headers
    )

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "ODOMETER_END_BEFORE_START"}


async def test_patch_start_after_end_is_422(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"
    await api.patch(url, json={"odometer_end_km": 53000}, headers=headers)

    response = await api.patch(url, json={"odometer_start_km": 53001}, headers=headers)

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "ODOMETER_END_BEFORE_START"}


async def test_patch_fuel_end_over_available_is_422(
    api: AsyncClient, headers: dict[str, str], car: dict, db: AsyncSession
) -> None:
    sheet = await _create_sheet(api, headers, car)
    await _add_refueling(db, sheet, "40.00", "2200.00")
    url = f"{SHEETS}/{sheet['id']}"

    ok = await api.patch(url, json={"fuel_end_actual_l": "52.00"}, headers=headers)  # 12 + 40
    too_much = await api.patch(url, json={"fuel_end_actual_l": "52.01"}, headers=headers)

    assert ok.status_code == 200
    assert too_much.status_code == 422
    assert _error(too_much)["details"] == {"reason": "FUEL_END_OVER_AVAILABLE"}


@pytest.mark.parametrize(
    "body",
    [
        {"odometer_start_km": None},
        {"fuel_start_l": None},
        {"season": None},
        {"odometer_end_km": -5},
        {"fuel_end_actual_l": "-1"},
        {"season": "AUTUMN"},
    ],
)
async def test_patch_invalid_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict, body: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.patch(f"{SHEETS}/{sheet['id']}", json=body, headers=headers)

    assert response.status_code == 400
    assert _error(response)["code"] == "VALIDATION_ERROR"


async def test_patch_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"},
        headers=headers,
    )

    for body in ({"fuel_start_l": "1"}, {"season": "WINTER"}):
        response = await api.patch(f"{SHEETS}/{sheet['id']}", json=body, headers=headers)
        assert response.status_code == 409
        assert _error(response)["code"] == "SHEET_CLOSED"


async def test_patch_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    assert (await api.patch(f"{SHEETS}/{sheet['id']}", json={})).status_code == 401


async def test_patch_others_sheet_is_404(
    api: AsyncClient, headers: dict[str, str], car: dict, other_headers: dict[str, str]
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.patch(
        f"{SHEETS}/{sheet['id']}", json={"fuel_start_l": "1"}, headers=other_headers
    )

    assert response.status_code == 404


async def test_odometer_gap_with_previous_sheet(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    september = await _create_sheet(api, headers, car, month=9, odometer_start_km=51000)
    await api.patch(f"{SHEETS}/{september['id']}", json={"odometer_end_km": 52340}, headers=headers)
    october = await _create_sheet(api, headers, car)
    assert october["calc"]["warnings"] == []

    response = await api.patch(
        f"{SHEETS}/{october['id']}", json={"odometer_start_km": 52400}, headers=headers
    )

    assert response.json()["calc"]["warnings"] == [
        {
            "code": "ODOMETER_GAP",
            "message": "Пробег на начало не совпадает с пробегом на конец прошлого месяца",
        }
    ]


# --- POST /sheets/{sheet_id}/close и /reopen ---


async def test_close_example_a(
    api: AsyncClient, headers: dict[str, str], car: dict, db: AsyncSession, clock: FakeClock
) -> None:
    """Пример A из docs/06_BUSINESS_RULES.md целиком через API."""
    sheet = await _create_sheet(api, headers, car)
    await _add_refueling(db, sheet, "40.00", "2200.00")
    await _add_refueling(db, sheet, "40.00", "2200.00")

    response = await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "10.00"},
        headers=headers,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CLOSED"
    assert body["closed_at"] == clock.now.isoformat().replace("+00:00", "Z")
    assert body["calc"] == {
        "refueled_l": "80.00",
        "refueled_cost": "4400.00",
        "fuel_available_l": "92.00",
        "mileage_km": 1000,
        "norm_consumption_l": "85.00",
        "fuel_end_calc_l": "7.00",
        "fuel_end_l": "10.00",
        "actual_consumption_l": "82.00",
        "actual_l_per_100km": "8.200",
        "consumption_status": "NORMAL",
        "deviation_l": "-3.00",
        "cost_per_km": "4.40",
        "warnings": [],
    }


async def test_close_without_fuel_end_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    """Фактический остаток обязателен, даже если раньше его ввели через PATCH; null — тоже нет."""
    sheet = await _create_sheet(api, headers, car)
    await api.patch(f"{SHEETS}/{sheet['id']}", json={"fuel_end_actual_l": "3"}, headers=headers)
    url = f"{SHEETS}/{sheet['id']}/close"

    missing = await api.post(url, json={"odometer_end_km": 52400}, headers=headers)
    null = await api.post(
        url, json={"odometer_end_km": 52400, "fuel_end_actual_l": None}, headers=headers
    )

    for response in (missing, null):
        assert response.status_code == 400
        assert _error(response)["code"] == "VALIDATION_ERROR"
        assert "fuel_end_actual_l" in _error(response)["details"]
    body = (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).json()
    assert body["status"] == "OPEN"


async def test_close_with_empty_tank(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 52400, "fuel_end_actual_l": "0"},
        headers=headers,
    )

    assert response.status_code == 200
    body = response.json()
    assert (body["status"], body["fuel_end_actual_l"]) == ("CLOSED", "0.00")
    assert body["calc"]["actual_l_per_100km"] is not None


async def test_close_without_odometer_end_is_400(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.post(
        f"{SHEETS}/{sheet['id']}/close", json={"fuel_end_actual_l": "1"}, headers=headers
    )

    assert response.status_code == 400
    assert "odometer_end_km" in _error(response)["details"]


async def test_close_business_rules_are_422(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}/close"

    before_start = await api.post(
        url, json={"odometer_end_km": 1, "fuel_end_actual_l": "0"}, headers=headers
    )
    too_much_fuel = await api.post(
        url, json={"odometer_end_km": 53340, "fuel_end_actual_l": "12.01"}, headers=headers
    )

    assert _error(before_start)["details"] == {"reason": "ODOMETER_END_BEFORE_START"}
    assert _error(too_much_fuel)["details"] == {"reason": "FUEL_END_OVER_AVAILABLE"}
    # Лист после ошибок остался открытым и нетронутым.
    body = (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).json()
    assert (body["status"], body["odometer_end_km"]) == ("OPEN", None)


async def test_close_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}/close"
    await api.post(url, json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"}, headers=headers)

    response = await api.post(
        url, json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"}, headers=headers
    )

    assert response.status_code == 409
    assert _error(response)["code"] == "SHEET_CLOSED"


async def test_reopen(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"
    await api.post(
        f"{url}/close", json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"}, headers=headers
    )

    response = await api.post(f"{url}/reopen", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert (body["status"], body["closed_at"]) == ("OPEN", None)
    assert body["odometer_end_km"] == 53340  # данные остались
    # Снова можно редактировать.
    assert (await api.patch(url, json={"fuel_start_l": "1"}, headers=headers)).status_code == 200


async def test_reopen_open_sheet_is_ok(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.post(f"{SHEETS}/{sheet['id']}/reopen", headers=headers)

    assert response.status_code == 200
    assert response.json()["status"] == "OPEN"


async def test_close_reopen_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"

    assert (
        await api.post(f"{url}/close", json={"odometer_end_km": 1, "fuel_end_actual_l": "0"})
    ).status_code == 401
    assert (await api.post(f"{url}/reopen")).status_code == 401


async def test_close_reopen_others_sheet_is_404(
    api: AsyncClient, headers: dict[str, str], car: dict, other_headers: dict[str, str]
) -> None:
    sheet = await _create_sheet(api, headers, car)
    url = f"{SHEETS}/{sheet['id']}"

    close = await api.post(
        f"{url}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"},
        headers=other_headers,
    )
    reopen = await api.post(f"{url}/reopen", headers=other_headers)

    assert (close.status_code, reopen.status_code) == (404, 404)


# --- DELETE /sheets/{sheet_id} ---


async def test_delete_sheet(api: AsyncClient, headers: dict[str, str], car: dict) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.delete(f"{SHEETS}/{sheet['id']}", headers=headers)

    assert response.status_code == 204
    assert (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).status_code == 404
    # Месяц освободился — лист можно создать заново.
    await _create_sheet(api, headers, car)


async def test_delete_sheet_with_refuelings_is_422(
    api: AsyncClient, headers: dict[str, str], car: dict, db: AsyncSession
) -> None:
    sheet = await _create_sheet(api, headers, car)
    await _add_refueling(db, sheet, "10.00", "550.00")

    response = await api.delete(f"{SHEETS}/{sheet['id']}", headers=headers)

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "SHEET_HAS_REFUELINGS"}


async def test_delete_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)
    await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"},
        headers=headers,
    )

    response = await api.delete(f"{SHEETS}/{sheet['id']}", headers=headers)

    assert response.status_code == 409


async def test_delete_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], car: dict
) -> None:
    sheet = await _create_sheet(api, headers, car)

    assert (await api.delete(f"{SHEETS}/{sheet['id']}")).status_code == 401


async def test_delete_others_sheet_is_404(
    api: AsyncClient, headers: dict[str, str], car: dict, other_headers: dict[str, str]
) -> None:
    sheet = await _create_sheet(api, headers, car)

    response = await api.delete(f"{SHEETS}/{sheet['id']}", headers=other_headers)

    assert response.status_code == 404
    assert (await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)).status_code == 200
