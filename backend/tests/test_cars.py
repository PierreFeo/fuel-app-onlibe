"""Автомобили: /cars — docs/04_API_CONTRACT.md, «Автомобили»."""

import uuid
from typing import Any

import pytest
from httpx import AsyncClient

from tests.conftest import OTHER_PHONE, bearer, login
from tests.fakes import FakeClock, FakeSmsSender

CARS = "/api/v1/cars"
VESTA = {
    "name": "Lada Vesta",
    "plate_number": "А123ВС77",
    "fuel_type": "AI95",
    "tank_capacity_l": "50.00",
    "norm_l_per_100km": "8.50",
}


@pytest.fixture
async def headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    return bearer(await login(api, sms))


@pytest.fixture
async def other_headers(api: AsyncClient, sms: FakeSmsSender) -> dict[str, str]:
    """Второй сотрудник."""
    return bearer(await login(api, sms, phone=OTHER_PHONE))


async def _create(api: AsyncClient, headers: dict[str, str], **changes: Any) -> dict[str, Any]:
    response = await api.post(CARS, json={**VESTA, **changes}, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()


# --- POST /cars ---


async def test_create_car(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.post(CARS, json=VESTA, headers=headers)

    assert response.status_code == 201
    car = response.json()
    assert set(car) == {
        "id",
        "name",
        "plate_number",
        "fuel_type",
        "tank_capacity_l",
        "norm_l_per_100km",
        "is_archived",
        "created_at",
    }
    assert {k: car[k] for k in VESTA} == VESTA
    assert car["is_archived"] is False
    uuid.UUID(car["id"])


async def test_decimals_are_returned_as_strings_with_2_places(
    api: AsyncClient, headers: dict[str, str]
) -> None:
    car = await _create(api, headers, tank_capacity_l="50", norm_l_per_100km=8.5)

    assert car["tank_capacity_l"] == "50.00"
    assert car["norm_l_per_100km"] == "8.50"


async def test_plate_is_normalized_and_optional(api: AsyncClient, headers: dict[str, str]) -> None:
    assert (await _create(api, headers, plate_number=" а123вс77 "))["plate_number"] == "А123ВС77"
    assert (await _create(api, headers, plate_number=""))["plate_number"] is None
    car = {k: v for k, v in VESTA.items() if k != "plate_number"}
    response = await api.post(CARS, json=car, headers=headers)
    assert response.json()["plate_number"] is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("name", ""),
        ("name", "   "),
        ("name", "x" * 61),
        ("name", None),
        ("plate_number", "А" * 16),
        ("fuel_type", "AI100"),
        ("fuel_type", None),
        ("tank_capacity_l", "0"),
        ("tank_capacity_l", "-5"),
        ("tank_capacity_l", "50.123"),
        ("tank_capacity_l", "10000"),
        ("tank_capacity_l", "50,5"),
        ("tank_capacity_l", "abc"),
        ("norm_l_per_100km", "0.00"),
        ("norm_l_per_100km", "1000"),
        ("norm_l_per_100km", None),
    ],
)
async def test_create_invalid_field_is_400(
    api: AsyncClient, headers: dict[str, str], field: str, value: object
) -> None:
    response = await api.post(CARS, json={**VESTA, field: value}, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert field in response.json()["error"]["details"]


@pytest.mark.parametrize("field", ["name", "fuel_type", "tank_capacity_l", "norm_l_per_100km"])
async def test_create_without_required_field_is_400(
    api: AsyncClient, headers: dict[str, str], field: str
) -> None:
    car = {k: v for k, v in VESTA.items() if k != field}

    response = await api.post(CARS, json=car, headers=headers)

    assert response.status_code == 400
    assert field in response.json()["error"]["details"]


# --- GET /cars ---


async def test_list_returns_own_cars_oldest_first(
    api: AsyncClient,
    headers: dict[str, str],
    other_headers: dict[str, str],
    clock: FakeClock,
) -> None:
    first = await _create(api, headers, name="Первая")
    second = await _create(api, headers, name="Вторая")
    await _create(api, other_headers, name="Чужая")

    response = await api.get(CARS, headers=headers)

    assert response.status_code == 200
    assert [c["id"] for c in response.json()] == [first["id"], second["id"]]


async def test_list_empty(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.get(CARS, headers=headers)

    assert response.status_code == 200
    assert response.json() == []


async def test_archived_hidden_unless_requested(api: AsyncClient, headers: dict[str, str]) -> None:
    active = await _create(api, headers, name="Активная")
    archived = await _create(api, headers, name="Старая")
    await api.delete(f"{CARS}/{archived['id']}", headers=headers)

    default = (await api.get(CARS, headers=headers)).json()
    everything = (await api.get(CARS, params={"include_archived": "true"}, headers=headers)).json()

    assert [c["id"] for c in default] == [active["id"]]
    assert [c["id"] for c in everything] == [active["id"], archived["id"]]
    assert everything[1]["is_archived"] is True


# --- GET /cars/{id} ---


async def test_get_car(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)

    response = await api.get(f"{CARS}/{car['id']}", headers=headers)

    assert response.status_code == 200
    assert response.json() == car


async def test_get_unknown_car_is_404(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.get(f"{CARS}/{uuid.uuid4()}", headers=headers)

    assert response.status_code == 404
    assert response.json()["error"] == {
        "code": "NOT_FOUND",
        "message": "Автомобиль не найден",
        "details": {},
    }


async def test_bad_car_id_is_400(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.get(f"{CARS}/not-a-uuid", headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


# --- PATCH /cars/{id} ---


async def test_patch_changes_only_given_fields(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)

    response = await api.patch(
        f"{CARS}/{car['id']}", json={"norm_l_per_100km": "9.1"}, headers=headers
    )

    assert response.status_code == 200
    assert response.json() == {**car, "norm_l_per_100km": "9.10"}


async def test_patch_can_remove_plate(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)

    response = await api.patch(f"{CARS}/{car['id']}", json={"plate_number": None}, headers=headers)

    assert response.json()["plate_number"] is None


async def test_patch_empty_body_changes_nothing(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)

    response = await api.patch(f"{CARS}/{car['id']}", json={}, headers=headers)

    assert response.status_code == 200
    assert response.json() == car


@pytest.mark.parametrize(
    "payload",
    [
        {"name": None},
        {"name": ""},
        {"fuel_type": None},
        {"tank_capacity_l": None},
        {"tank_capacity_l": "0"},
        {"norm_l_per_100km": "-1"},
        {"is_archived": None},
    ],
)
async def test_patch_invalid_is_400(
    api: AsyncClient, headers: dict[str, str], payload: dict[str, Any]
) -> None:
    car = await _create(api, headers)

    response = await api.patch(f"{CARS}/{car['id']}", json=payload, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert set(response.json()["error"]["details"]) == set(payload)
    # авто не изменилось
    assert (await api.get(f"{CARS}/{car['id']}", headers=headers)).json() == car


async def test_patch_restores_from_archive(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)
    await api.delete(f"{CARS}/{car['id']}", headers=headers)

    response = await api.patch(f"{CARS}/{car['id']}", json={"is_archived": False}, headers=headers)

    assert response.json()["is_archived"] is False
    assert [c["id"] for c in (await api.get(CARS, headers=headers)).json()] == [car["id"]]


# --- DELETE /cars/{id} ---


async def test_delete_archives_car(api: AsyncClient, headers: dict[str, str]) -> None:
    car = await _create(api, headers)

    response = await api.delete(f"{CARS}/{car['id']}", headers=headers)

    assert response.status_code == 204
    assert response.content == b""
    # авто не удалено — его можно открыть по id, оно в архиве
    archived = (await api.get(f"{CARS}/{car['id']}", headers=headers)).json()
    assert archived["is_archived"] is True


async def test_delete_unknown_car_is_404(api: AsyncClient, headers: dict[str, str]) -> None:
    response = await api.delete(f"{CARS}/{uuid.uuid4()}", headers=headers)

    assert response.status_code == 404


# --- чужое авто и без токена ---


@pytest.mark.parametrize(
    ("method", "body"),
    [("GET", None), ("PATCH", {"name": "Угнали"}), ("DELETE", None)],
)
async def test_other_users_car_is_404(
    api: AsyncClient,
    headers: dict[str, str],
    other_headers: dict[str, str],
    method: str,
    body: dict[str, str] | None,
) -> None:
    car = await _create(api, headers)

    response = await api.request(method, f"{CARS}/{car['id']}", json=body, headers=other_headers)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "NOT_FOUND"
    # хозяин видит своё авто нетронутым
    assert (await api.get(f"{CARS}/{car['id']}", headers=headers)).json() == car


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", CARS),
        ("POST", CARS),
        ("GET", f"{CARS}/{uuid.uuid4()}"),
        ("PATCH", f"{CARS}/{uuid.uuid4()}"),
        ("DELETE", f"{CARS}/{uuid.uuid4()}"),
    ],
)
async def test_without_token_is_401(api: AsyncClient, method: str, path: str) -> None:
    response = await api.request(method, path, json=VESTA)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
