"""Заправки: /sheets/{id}/refuelings и /refuelings/{id} — docs/04_API_CONTRACT.md, «Заправки»."""

import uuid
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Refueling
from tests.conftest import OTHER_PHONE, bearer, login
from tests.fakes import FakeSmsSender

CARS = "/api/v1/cars"
SHEETS = "/api/v1/sheets"
REFUELINGS = "/api/v1/refuelings"
VESTA = {
    "name": "Lada Vesta",
    "fuel_type": "AI95",
    "tank_capacity_l": "50.00",
    "norm_l_per_100km": "8.500",
}
# Часы тестов (tests/fakes.py) стоят на 2 октября 2026 — текущий месяц 2026-10.
OCTOBER = {"year": 2026, "month": 10, "odometer_start_km": 52340, "fuel_start_l": "12.00"}
FILL = {"refueled_at": "2026-10-05", "liters": "40.00", "price_per_liter": "55.00"}


@pytest.fixture
async def headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    return bearer(await login(api, sms))


@pytest.fixture
async def other_headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    """Второй сотрудник."""
    return bearer(await login(api, sms, phone=OTHER_PHONE))


@pytest.fixture
async def sheet(api: AsyncClient, headers: dict[str, str]) -> dict[str, Any]:
    """Открытый лист за октябрь по новому авто."""
    response = await api.post(CARS, json=VESTA, headers=headers)
    assert response.status_code == 201, response.text
    car = response.json()
    response = await api.post(f"{CARS}/{car['id']}/sheets", json=OCTOBER, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()


async def _add(
    api: AsyncClient, headers: dict[str, str], sheet: dict[str, Any], **changes: Any
) -> dict[str, Any]:
    """Добавить заправку и вернуть весь лист из ответа."""
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/refuelings", json={**FILL, **changes}, headers=headers
    )
    assert response.status_code == 201, response.text
    return response.json()


async def _get_sheet(api: AsyncClient, headers: dict[str, str], sheet: dict) -> dict[str, Any]:
    response = await api.get(f"{SHEETS}/{sheet['id']}", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


async def _close(api: AsyncClient, headers: dict[str, str], sheet: dict) -> None:
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "0"},
        headers=headers,
    )
    assert response.status_code == 200, response.text


def _error(response: Any) -> dict[str, Any]:
    return response.json()["error"]


# --- POST /sheets/{sheet_id}/refuelings ---


async def test_add_refueling_returns_whole_sheet(
    api: AsyncClient, headers: dict[str, str], sheet: dict, db: AsyncSession
) -> None:
    response = await api.post(f"{SHEETS}/{sheet['id']}/refuelings", json=FILL, headers=headers)

    assert response.status_code == 201
    body = response.json()
    assert body["id"] == sheet["id"]
    [refueling] = body["refuelings"]
    assert list(refueling) == [
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
    ]
    assert refueling["sheet_id"] == sheet["id"]
    assert refueling["refueled_at"] == "2026-10-05"
    assert refueling["liters"] == "40.00"
    assert refueling["price_per_liter"] == "55.00"
    # Сумму не передали — литры × цена (правило 9).
    assert refueling["total_cost"] == "2200.00"
    assert refueling["odometer_km"] is None
    assert refueling["station"] is None
    assert refueling["payment_type"] == "PERSONAL"
    assert refueling["note"] is None
    # calc пересчитан сразу — второй запрос приложению не нужен.
    assert body["calc"]["refueled_l"] == "40.00"
    assert body["calc"]["refueled_cost"] == "2200.00"
    assert body["calc"]["fuel_available_l"] == "52.00"

    saved = await db.get(Refueling, uuid.UUID(refueling["id"]))
    assert saved is not None
    assert str(saved.total_cost) == "2200.00"


async def test_add_refueling_with_all_fields(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    body = await _add(
        api,
        headers,
        sheet,
        total_cost="2150.50",  # со скидкой — берём как есть
        odometer_km=52610,
        station="  Лукойл, Ленина 1 ",
        payment_type="FUEL_CARD",
        note="чек потерян",
    )

    [refueling] = body["refuelings"]
    assert refueling["total_cost"] == "2150.50"
    assert refueling["odometer_km"] == 52610
    assert refueling["station"] == "Лукойл, Ленина 1"
    assert refueling["payment_type"] == "FUEL_CARD"
    assert refueling["note"] == "чек потерян"
    assert body["calc"]["refueled_cost"] == "2150.50"


async def test_blank_station_and_note_are_null(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    body = await _add(api, headers, sheet, station="   ", note="")

    assert body["refuelings"][0]["station"] is None
    assert body["refuelings"][0]["note"] is None


@pytest.mark.parametrize(
    ("liters", "price", "total"),
    [
        ("33.33", "55.55", "1851.48"),  # 1851.4815
        ("0.50", "0.01", "0.01"),  # 0.005 — половина копейки вверх (ROUND_HALF_UP)
        ("10", "0", "0.00"),  # бесплатно, например по талону
    ],
)
async def test_total_cost_is_rounded_to_kopecks(
    api: AsyncClient, headers: dict[str, str], sheet: dict, liters: str, price: str, total: str
) -> None:
    body = await _add(api, headers, sheet, liters=liters, price_per_liter=price)

    assert body["refuelings"][0]["total_cost"] == total


async def test_refuelings_sorted_by_date(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    await _add(api, headers, sheet, refueled_at="2026-10-20")
    await _add(api, headers, sheet, refueled_at="2026-10-31")
    body = await _add(api, headers, sheet, refueled_at="2026-10-01")

    assert [r["refueled_at"] for r in body["refuelings"]] == [
        "2026-10-01",
        "2026-10-20",
        "2026-10-31",
    ]
    assert body["calc"]["refueled_l"] == "120.00"


async def test_refueling_over_tank_is_allowed_without_warning(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    """Одна запись за весь месяц может быть больше бака — ни ошибки, ни предупреждения."""
    body = await _add(api, headers, sheet, liters="60.00")  # бак 50 л

    assert body["calc"]["warnings"] == []


async def test_refueling_odometer_below_start_is_warning(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    body = await _add(api, headers, sheet, odometer_km=52000)  # начало месяца 52340

    assert [w["code"] for w in body["calc"]["warnings"]] == ["REFUELING_ODOMETER_OUT_OF_RANGE"]


@pytest.mark.parametrize("refueled_at", ["2026-09-30", "2026-11-01", "2025-10-05"])
async def test_add_date_outside_month_is_422(
    api: AsyncClient, headers: dict[str, str], sheet: dict, refueled_at: str
) -> None:
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/refuelings",
        json={**FILL, "refueled_at": refueled_at},
        headers=headers,
    )

    assert response.status_code == 422
    assert _error(response)["code"] == "BUSINESS_RULE"
    assert _error(response)["details"] == {"reason": "REFUELING_DATE_OUTSIDE_MONTH"}
    assert (await _get_sheet(api, headers, sheet))["refuelings"] == []


@pytest.mark.parametrize(
    ("changes", "field"),
    [
        ({"liters": "0"}, "liters"),
        ({"liters": "-5"}, "liters"),
        ({"liters": "1.234"}, "liters"),
        ({"liters": "1000000"}, "liters"),
        ({"price_per_liter": "-1"}, "price_per_liter"),
        ({"total_cost": "-1"}, "total_cost"),
        ({"odometer_km": -1}, "odometer_km"),
        ({"payment_type": "CASH"}, "payment_type"),
        ({"station": "А" * 101}, "station"),
        ({"note": "А" * 256}, "note"),
        ({"refueled_at": "05.10.2026"}, "refueled_at"),
        ({"refueled_at": None}, "refueled_at"),
    ],
)
async def test_add_invalid_fields_is_400(
    api: AsyncClient, headers: dict[str, str], sheet: dict, changes: dict, field: str
) -> None:
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/refuelings", json={**FILL, **changes}, headers=headers
    )

    assert response.status_code == 400
    assert _error(response)["code"] == "VALIDATION_ERROR"
    assert field in _error(response)["details"]


async def test_add_missing_field_is_400(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    body = {k: v for k, v in FILL.items() if k != "liters"}

    response = await api.post(f"{SHEETS}/{sheet['id']}/refuelings", json=body, headers=headers)

    assert response.status_code == 400
    assert "liters" in _error(response)["details"]


async def test_add_too_big_calculated_total_is_400(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    """999 999.99 л × 999 999.99 ₽ не влезает в сумму NUMERIC(10,2)."""
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/refuelings",
        json={**FILL, "liters": "999999.99", "price_per_liter": "999999.99"},
        headers=headers,
    )

    assert response.status_code == 400
    assert "total_cost" in _error(response)["details"]


async def test_add_to_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    await _close(api, headers, sheet)

    response = await api.post(f"{SHEETS}/{sheet['id']}/refuelings", json=FILL, headers=headers)

    assert response.status_code == 409
    assert _error(response)["code"] == "SHEET_CLOSED"


async def test_add_without_token_is_401(api: AsyncClient, sheet: dict) -> None:
    response = await api.post(f"{SHEETS}/{sheet['id']}/refuelings", json=FILL)

    assert response.status_code == 401


async def test_add_to_others_sheet_is_404(
    api: AsyncClient, sheet: dict, other_headers: dict[str, str]
) -> None:
    response = await api.post(
        f"{SHEETS}/{sheet['id']}/refuelings", json=FILL, headers=other_headers
    )

    assert response.status_code == 404
    assert _error(response)["code"] == "NOT_FOUND"


async def test_add_to_unknown_sheet_is_404(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.post(f"{SHEETS}/{uuid.uuid4()}/refuelings", json=FILL, headers=headers)

    assert response.status_code == 404


# --- PATCH /refuelings/{refueling_id} ---


def _refueling_id(body: dict[str, Any], index: int = 0) -> str:
    return body["refuelings"][index]["id"]


async def test_patch_changes_only_passed_fields(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet, station="Лукойл", total_cost="2150.00")
    refueling_id = _refueling_id(added)

    response = await api.patch(
        f"{REFUELINGS}/{refueling_id}",
        json={"odometer_km": 52610, "payment_type": "COMPANY", "note": "командировка"},
        headers=headers,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["id"] == sheet["id"]
    [refueling] = body["refuelings"]
    assert refueling["id"] == refueling_id
    assert refueling["odometer_km"] == 52610
    assert refueling["payment_type"] == "COMPANY"
    assert refueling["note"] == "командировка"
    # Остальное как было — в том числе сумма со скидкой.
    assert refueling["station"] == "Лукойл"
    assert refueling["liters"] == "40.00"
    assert refueling["total_cost"] == "2150.00"


@pytest.mark.parametrize(
    ("changes", "total"),
    [
        ({"liters": "30.00"}, "1650.00"),  # 30 × 55
        ({"price_per_liter": "60.00"}, "2400.00"),  # 40 × 60
        ({"liters": "30.00", "total_cost": "1600.00"}, "1600.00"),  # сумму передали — она
        ({"total_cost": None}, "2200.00"),  # null — пересчитать
        ({"total_cost": "2100.00"}, "2100.00"),
    ],
)
async def test_patch_total_cost(
    api: AsyncClient, headers: dict[str, str], sheet: dict, changes: dict, total: str
) -> None:
    added = await _add(api, headers, sheet, total_cost="2150.00")

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json=changes, headers=headers
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["refuelings"][0]["total_cost"] == total
    assert body["calc"]["refueled_cost"] == total


async def test_patch_liters_recalculates_sheet(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json={"liters": "25.50"}, headers=headers
    )

    assert response.json()["calc"]["refueled_l"] == "25.50"
    assert response.json()["calc"]["fuel_available_l"] == "37.50"


async def test_patch_null_clears_optional_fields(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet, odometer_km=52610, station="Лукойл", note="x")

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}",
        json={"odometer_km": None, "station": None, "note": ""},
        headers=headers,
    )

    refueling = response.json()["refuelings"][0]
    assert (refueling["odometer_km"], refueling["station"], refueling["note"]) == (None, None, None)


async def test_patch_date_reorders_refuelings(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    await _add(api, headers, sheet, refueled_at="2026-10-10")
    added = await _add(api, headers, sheet, refueled_at="2026-10-20", liters="20.00")
    late_id = _refueling_id(added, 1)

    response = await api.patch(
        f"{REFUELINGS}/{late_id}", json={"refueled_at": "2026-10-02"}, headers=headers
    )

    assert [r["id"] for r in response.json()["refuelings"]][0] == late_id
    assert response.json()["refuelings"][0]["refueled_at"] == "2026-10-02"


@pytest.mark.parametrize(
    ("changes", "field"),
    [
        ({"refueled_at": None}, "refueled_at"),
        ({"liters": None}, "liters"),
        ({"price_per_liter": None}, "price_per_liter"),
        ({"payment_type": None}, "payment_type"),
        ({"liters": "0"}, "liters"),
        ({"total_cost": "-1"}, "total_cost"),
    ],
)
async def test_patch_invalid_is_400(
    api: AsyncClient, headers: dict[str, str], sheet: dict, changes: dict, field: str
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json=changes, headers=headers
    )

    assert response.status_code == 400
    assert field in _error(response)["details"]


async def test_patch_date_outside_month_is_422(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}",
        json={"refueled_at": "2026-11-01"},
        headers=headers,
    )

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "REFUELING_DATE_OUTSIDE_MONTH"}
    after = await _get_sheet(api, headers, sheet)
    assert after["refuelings"][0]["refueled_at"] == "2026-10-05"


async def test_patch_liters_below_actual_fuel_end_is_422(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    """Было 12 + 40 = 52 л, в конце осталось 30 л. Заправка 10 л — топлива стало бы 22 л < 30."""
    added = await _add(api, headers, sheet)
    await api.patch(f"{SHEETS}/{sheet['id']}", json={"fuel_end_actual_l": "30.00"}, headers=headers)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json={"liters": "10.00"}, headers=headers
    )

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "FUEL_END_OVER_AVAILABLE"}
    after = await _get_sheet(api, headers, sheet)
    assert after["refuelings"][0]["liters"] == "40.00"


async def test_patch_in_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)
    await _close(api, headers, sheet)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json={"note": "x"}, headers=headers
    )

    assert response.status_code == 409
    assert _error(response)["code"] == "SHEET_CLOSED"


async def test_patch_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.patch(f"{REFUELINGS}/{_refueling_id(added)}", json={})

    assert response.status_code == 401


async def test_patch_others_refueling_is_404(
    api: AsyncClient, headers: dict[str, str], sheet: dict, other_headers: dict[str, str]
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.patch(
        f"{REFUELINGS}/{_refueling_id(added)}", json={"note": "x"}, headers=other_headers
    )

    assert response.status_code == 404
    assert _error(response)["code"] == "NOT_FOUND"


async def test_patch_unknown_or_bad_id(api: AsyncClient, headers: dict[str, str]) -> None:
    unknown = await api.patch(f"{REFUELINGS}/{uuid.uuid4()}", json={}, headers=headers)
    bad = await api.patch(f"{REFUELINGS}/not-a-uuid", json={}, headers=headers)

    assert unknown.status_code == 404
    assert bad.status_code == 400


# --- DELETE /refuelings/{refueling_id} ---


async def test_delete_refueling_returns_sheet(
    api: AsyncClient, headers: dict[str, str], sheet: dict, db: AsyncSession
) -> None:
    await _add(api, headers, sheet, refueled_at="2026-10-03", liters="20.00")
    added = await _add(api, headers, sheet, refueled_at="2026-10-10")
    second_id = _refueling_id(added, 1)

    response = await api.delete(f"{REFUELINGS}/{second_id}", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert [r["liters"] for r in body["refuelings"]] == ["20.00"]
    assert body["calc"]["refueled_l"] == "20.00"
    assert body["calc"]["fuel_available_l"] == "32.00"
    assert await db.scalar(select(Refueling).where(Refueling.id == uuid.UUID(second_id))) is None


async def test_sheet_can_be_deleted_after_its_refuelings(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)
    assert (await api.delete(f"{SHEETS}/{sheet['id']}", headers=headers)).status_code == 422

    await api.delete(f"{REFUELINGS}/{_refueling_id(added)}", headers=headers)

    assert (await api.delete(f"{SHEETS}/{sheet['id']}", headers=headers)).status_code == 204


async def test_delete_below_actual_fuel_end_is_422(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)
    await api.patch(f"{SHEETS}/{sheet['id']}", json={"fuel_end_actual_l": "30.00"}, headers=headers)

    response = await api.delete(f"{REFUELINGS}/{_refueling_id(added)}", headers=headers)

    assert response.status_code == 422
    assert _error(response)["details"] == {"reason": "FUEL_END_OVER_AVAILABLE"}
    assert len((await _get_sheet(api, headers, sheet))["refuelings"]) == 1


async def test_delete_in_closed_sheet_is_409(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)
    await _close(api, headers, sheet)

    response = await api.delete(f"{REFUELINGS}/{_refueling_id(added)}", headers=headers)

    assert response.status_code == 409
    assert _error(response)["code"] == "SHEET_CLOSED"


async def test_delete_without_token_is_401(
    api: AsyncClient, headers: dict[str, str], sheet: dict
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.delete(f"{REFUELINGS}/{_refueling_id(added)}")

    assert response.status_code == 401


async def test_delete_others_refueling_is_404(
    api: AsyncClient, headers: dict[str, str], sheet: dict, other_headers: dict[str, str]
) -> None:
    added = await _add(api, headers, sheet)

    response = await api.delete(f"{REFUELINGS}/{_refueling_id(added)}", headers=other_headers)

    assert response.status_code == 404
    assert len((await _get_sheet(api, headers, sheet))["refuelings"]) == 1


# --- весь сценарий из docs/01_PRODUCT.md ---


async def test_full_month_scenario(api: AsyncClient, headers: dict[str, str]) -> None:
    """Авто → лист → две заправки → закрытие (пример A) → переоткрытие → следующий месяц."""
    car = (await api.post(CARS, json=VESTA, headers=headers)).json()
    prefill = (await api.get(f"{CARS}/{car['id']}/sheets/next-prefill", headers=headers)).json()
    sheet = (
        await api.post(
            f"{CARS}/{car['id']}/sheets",
            json={**prefill, "odometer_start_km": 52340, "fuel_start_l": "12.00"},
            headers=headers,
        )
    ).json()

    await _add(api, headers, sheet, refueled_at="2026-10-05", odometer_km=52610)
    body = await _add(api, headers, sheet, refueled_at="2026-10-19", odometer_km=52990)
    assert body["calc"]["fuel_available_l"] == "92.00"
    assert body["calc"]["mileage_km"] is None

    response = await api.post(
        f"{SHEETS}/{sheet['id']}/close",
        json={"odometer_end_km": 53340, "fuel_end_actual_l": "10.00"},
        headers=headers,
    )
    assert response.status_code == 200
    closed = response.json()
    assert closed["status"] == "CLOSED"
    assert closed["calc"] == {
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

    # Закрытый лист не меняется, после переоткрытия — снова можно.
    refueling_id = closed["refuelings"][0]["id"]
    blocked = await api.patch(f"{REFUELINGS}/{refueling_id}", json={"note": "x"}, headers=headers)
    assert blocked.status_code == 409
    await api.post(f"{SHEETS}/{sheet['id']}/reopen", headers=headers)
    edited = await api.patch(f"{REFUELINGS}/{refueling_id}", json={"note": "x"}, headers=headers)
    assert edited.status_code == 200

    prefill = (await api.get(f"{CARS}/{car['id']}/sheets/next-prefill", headers=headers)).json()
    assert prefill == {
        "year": 2026,
        "month": 11,
        "odometer_start_km": 53340,
        "fuel_start_l": "10.00",
        "season": "SUMMER",
    }
