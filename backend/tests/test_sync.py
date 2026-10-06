"""POST /sync — docs/04_API_CONTRACT.md, «Синхронизация»."""

import uuid
from typing import Any

import pytest
from httpx import AsyncClient

from tests.conftest import OTHER_PHONE, bearer, login
from tests.fakes import FakeSmsSender

SYNC = "/api/v1/sync"


def car(**changes: Any) -> dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "name": "Lada Vesta",
        "plate_number": "А123ВС77",
        "fuel_type": "AI95",
        "tank_capacity_l": "50.00",
        "norm_l_per_100km": "10.068",
        "norm_winter_l_per_100km": "11.684",
        "is_archived": False,
        "created_at": "2026-10-02T08:15:00Z",
        "deleted": False,
        **changes,
    }


def sheet(car_id: str, **changes: Any) -> dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "car_id": car_id,
        "year": 2026,
        "month": 10,
        "status": "OPEN",
        "odometer_start_km": 52340,
        "odometer_end_km": None,
        "fuel_start_l": "12.00",
        "fuel_end_actual_l": None,
        "season": "SUMMER",
        "norm_l_per_100km": "10.068",
        "closed_at": None,
        "created_at": "2026-10-01T07:00:00Z",
        "deleted": False,
        **changes,
    }


def refueling(sheet_id: str, **changes: Any) -> dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "sheet_id": sheet_id,
        "refueled_at": "2026-10-05",
        "liters": "40.00",
        "price_per_liter": "55.00",
        "total_cost": "2200.00",
        "odometer_km": 52610,
        "station": "Лукойл",
        "payment_type": "PERSONAL",
        "note": None,
        "deleted": False,
        **changes,
    }


@pytest.fixture
async def headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    return bearer(await login(api, sms))


async def _sync(api: AsyncClient, headers: dict[str, str], **body: Any) -> dict[str, Any]:
    response = await api.post(SYNC, json={"cursor": None, **body}, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def _ids(records: list[dict[str, Any]]) -> list[str]:
    return [r["id"] for r in records]


# --- Приём и выдача ---


async def test_first_sync_returns_everything_it_accepted(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    c = car()
    s = sheet(c["id"], status="CLOSED", odometer_end_km=53340, fuel_end_actual_l="10.00",
              closed_at="2026-10-31T18:00:00Z")  # fmt: skip
    r = refueling(s["id"])

    body = await _sync(api, headers, cars=[c], sheets=[s], refuelings=[r])

    assert body["rejected"] == []
    assert body["cars"] == [c]  # поля — ровно как прислали, формат чисел по контракту
    assert body["sheets"] == [s]
    assert body["refuelings"] == [r]
    assert body["cursor"] > 0


async def test_sync_with_cursor_returns_only_new(api: AsyncClient, headers: dict[str, str]) -> None:
    first = await _sync(api, headers, cars=[car()])

    again = await _sync(api, headers, cursor=first["cursor"])

    assert (again["cars"], again["sheets"], again["refuelings"]) == ([], [], [])
    assert again["cursor"] == first["cursor"]

    newer = await _sync(api, headers, cursor=first["cursor"], cars=[car(name="Kia Rio")])
    assert [c["name"] for c in newer["cars"]] == ["Kia Rio"]
    assert newer["cursor"] > first["cursor"]


async def test_new_phone_gets_all_data(api: AsyncClient, headers: dict[str, str]) -> None:
    """cursor = null на «новом телефоне» — все неудалённые записи пользователя."""
    c = car()
    s = sheet(c["id"])
    await _sync(api, headers, cars=[c], sheets=[s], refuelings=[refueling(s["id"])])

    body = await _sync(api, headers)

    assert (_ids(body["cars"]), _ids(body["sheets"]), len(body["refuelings"])) == (
        [c["id"]],
        [s["id"]],
        1,
    )


async def test_record_is_replaced_by_id(api: AsyncClient, headers: dict[str, str]) -> None:
    c = car()
    first = await _sync(api, headers, cars=[c])

    body = await _sync(
        api, headers, cursor=first["cursor"], cars=[{**c, "name": "Kia Rio", "is_archived": True}]
    )

    assert [(x["id"], x["name"], x["is_archived"]) for x in body["cars"]] == [
        (c["id"], "Kia Rio", True)
    ]
    assert len((await _sync(api, headers))["cars"]) == 1  # не дубликат


# --- Удаление ---


async def test_delete_sheet_marks_its_refuelings(api: AsyncClient, headers: dict[str, str]) -> None:
    c = car()
    s = sheet(c["id"])
    r = refueling(s["id"])
    first = await _sync(api, headers, cars=[c], sheets=[s], refuelings=[r])

    body = await _sync(
        api, headers, cursor=first["cursor"], sheets=[{"id": s["id"], "deleted": True}]
    )

    assert [(x["id"], x["deleted"]) for x in body["sheets"]] == [(s["id"], True)]
    assert [(x["id"], x["deleted"]) for x in body["refuelings"]] == [(r["id"], True)]
    # «Новый телефон» удалённого не получает.
    fresh = await _sync(api, headers)
    assert (fresh["sheets"], fresh["refuelings"]) == ([], [])


async def test_delete_car_marks_sheets_and_refuelings(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    c = car()
    s = sheet(c["id"])
    first = await _sync(api, headers, cars=[c], sheets=[s], refuelings=[refueling(s["id"])])

    body = await _sync(
        api, headers, cursor=first["cursor"], cars=[{"id": c["id"], "deleted": True}]
    )

    assert all(x["deleted"] for x in [*body["cars"], *body["sheets"], *body["refuelings"]])
    assert len(body["cars"] + body["sheets"] + body["refuelings"]) == 3


async def test_deleting_unknown_record_is_not_an_error(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    body = await _sync(api, headers, refuelings=[{"id": str(uuid.uuid4()), "deleted": True}])

    assert (body["rejected"], body["refuelings"]) == ([], [])


async def test_deleted_month_can_be_created_again(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    c = car()
    old = sheet(c["id"])
    await _sync(api, headers, cars=[c], sheets=[old])

    body = await _sync(api, headers, sheets=[{"id": old["id"], "deleted": True}, sheet(c["id"])])

    assert body["rejected"] == []
    assert len(body["sheets"]) == 1  # cursor = null — только живой лист


# --- Отклонённые записи ---


async def test_other_users_id_is_rejected_and_untouched(
    api: AsyncClient, headers: dict[str, str], sms: FakeSmsSender
) -> None:
    other = bearer(await login(api, sms, OTHER_PHONE))
    theirs = car(name="Чужое авто")
    await _sync(api, other, cars=[theirs])

    body = await _sync(api, headers, cars=[{**theirs, "name": "Моё"}])
    deleted = await _sync(api, headers, cars=[{"id": theirs["id"], "deleted": True}])

    assert [(x["code"], x["id"]) for x in body["rejected"]] == [("NOT_FOUND", theirs["id"])]
    assert [x["code"] for x in deleted["rejected"]] == ["NOT_FOUND"]
    assert body["cars"] == []  # чужое не видно
    their_view = await _sync(api, other)
    assert [(x["name"], x["deleted"]) for x in their_view["cars"]] == [("Чужое авто", False)]


async def test_child_of_other_users_parent_is_rejected(
    api: AsyncClient, headers: dict[str, str], sms: FakeSmsSender
) -> None:
    other = bearer(await login(api, sms, OTHER_PHONE))
    theirs = car()
    their_sheet = sheet(theirs["id"])
    await _sync(api, other, cars=[theirs], sheets=[their_sheet])

    body = await _sync(
        api, headers, sheets=[sheet(theirs["id"])], refuelings=[refueling(their_sheet["id"])]
    )

    assert [x["code"] for x in body["rejected"]] == ["PARENT_NOT_FOUND", "PARENT_NOT_FOUND"]


async def test_missing_or_rejected_parent(api: AsyncClient, headers: dict[str, str]) -> None:
    bad_car = car(tank_capacity_l="0")  # отклонится — и его лист тоже
    orphan = sheet(str(uuid.uuid4()))
    s = sheet(bad_car["id"])

    body = await _sync(
        api, headers, cars=[bad_car], sheets=[orphan, s], refuelings=[refueling(s["id"])]
    )

    assert [(x["entity"], x["code"]) for x in body["rejected"]] == [
        ("car", "VALIDATION_ERROR"),
        ("sheet", "PARENT_NOT_FOUND"),
        ("sheet", "PARENT_NOT_FOUND"),
        ("refueling", "PARENT_NOT_FOUND"),
    ]
    assert (body["cars"], body["sheets"], body["refuelings"]) == ([], [], [])


async def test_sheet_for_deleted_car_is_rejected(api: AsyncClient, headers: dict[str, str]) -> None:
    c = car()
    await _sync(api, headers, cars=[c])
    await _sync(api, headers, cars=[{"id": c["id"], "deleted": True}])

    body = await _sync(api, headers, sheets=[sheet(c["id"])])

    assert [x["code"] for x in body["rejected"]] == ["PARENT_NOT_FOUND"]


async def test_second_sheet_for_same_month_is_rejected(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    c = car()
    first = sheet(c["id"])
    await _sync(api, headers, cars=[c], sheets=[first])

    body = await _sync(api, headers, sheets=[sheet(c["id"]), {**first, "odometer_start_km": 1}])

    [rejected] = body["rejected"]
    assert rejected["code"] == "SHEET_EXISTS"
    assert rejected["message"] == "Лист за октябрь 2026 по этому авто уже есть"
    assert [x["odometer_start_km"] for x in body["sheets"]] == [1]  # свой лист обновляется


@pytest.mark.parametrize(
    ("entity", "bad", "field"),
    [
        ("cars", {"tank_capacity_l": "-1"}, "tank_capacity_l"),
        ("cars", {"fuel_type": "AI100"}, "fuel_type"),
        ("cars", {"name": ""}, "name"),
        ("cars", {"id": "not-a-uuid"}, "id"),
        ("sheets", {"month": 13}, "month"),
        ("sheets", {"odometer_end_km": 1}, "odometer_end_km"),
        ("sheets", {"norm_l_per_100km": "10.0681"}, "norm_l_per_100km"),
        ("refuelings", {"liters": "0"}, "liters"),
        ("refuelings", {"total_cost": None}, "total_cost"),
    ],
)
async def test_invalid_record_is_rejected_others_saved(
    api: AsyncClient, headers: dict[str, str], entity: str, bad: dict[str, Any], field: str
) -> None:
    c = car()
    s = sheet(c["id"])
    ok = {"cars": [c], "sheets": [s], "refuelings": [refueling(s["id"])]}
    broken = {"cars": car, "sheets": lambda **k: sheet(c["id"], **k),
              "refuelings": lambda **k: refueling(s["id"], **k)}[entity](**bad)  # fmt: skip
    ok[entity] = [*ok[entity], broken]

    body = await _sync(api, headers, **ok)

    [rejected] = body["rejected"]
    assert (rejected["code"], rejected["id"]) == ("VALIDATION_ERROR", broken["id"])
    assert field in rejected["message"]
    assert (len(body["cars"]), len(body["sheets"]), len(body["refuelings"])) == (1, 1, 1)


# --- Профиль ---


async def test_profile_name_both_ways(
    api: AsyncClient, headers: dict[str, str], sms: FakeSmsSender
) -> None:
    sent = await _sync(api, headers, profile={"name": "  Иван  "})
    assert sent["profile"] == {"name": "Иван"}

    nothing_new = await _sync(api, headers, cursor=sent["cursor"])
    assert nothing_new["profile"] is None

    await api.patch("/api/v1/me", json={"name": "Иван Петров"}, headers=headers)
    changed = await _sync(api, headers, cursor=sent["cursor"])
    assert changed["profile"] == {"name": "Иван Петров"}
    assert changed["cursor"] > sent["cursor"]


async def test_profile_is_null_without_name(api: AsyncClient, headers: dict[str, str]) -> None:
    body = await _sync(api, headers)

    assert (body["profile"], body["cursor"]) == (None, 0)


# --- Ошибки всего запроса ---


async def test_too_many_records_is_400_and_nothing_saved(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    cars = [car() for _ in range(5001)]

    response = await api.post(SYNC, json={"cursor": None, "cars": cars}, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert (await _sync(api, headers))["cars"] == []


async def test_bad_structure_is_400(api: AsyncClient, headers: dict[str, str]) -> None:
    for body in ({"cursor": -1}, {"cars": "много"}, {"cars": [1]}):
        response = await api.post(SYNC, json=body, headers=headers)
        assert response.status_code == 400, body


async def test_without_token_is_401(api: AsyncClient) -> None:
    response = await api.post(SYNC, json={"cursor": None})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
