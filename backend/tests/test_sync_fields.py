"""Поля синхронизации (docs/03_DATA_MODEL.md): version растёт при каждой записи, name_version —
при смене имени; id записи может прийти от клиента."""

import uuid

from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Car
from app.models.enums import FuelType
from tests.conftest import bearer, login
from tests.fakes import FakeSmsSender

CAR = {
    "id": "",
    "name": "Lada Vesta",
    "plate_number": None,
    "fuel_type": "AI95",
    "tank_capacity_l": "50",
    "norm_l_per_100km": "8.5",
    "norm_winter_l_per_100km": None,
    "is_archived": False,
    "created_at": "2026-10-02T08:15:00Z",
    "deleted": False,
}


async def _version(db: AsyncSession, table: str, row_id: str) -> int:
    query = text(f"SELECT version FROM {table} WHERE id = :id")  # noqa: S608 — имя таблицы наше
    return (await db.execute(query, {"id": uuid.UUID(row_id)})).scalar_one()


async def test_car_version_grows_on_every_change(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    headers = bearer(await login(api, sms))
    car = {**CAR, "id": str(uuid.uuid4())}

    async def sync_car(name: str) -> int:
        body = {"cursor": None, "cars": [{**car, "name": name}]}
        await api.post("/api/v1/sync", json=body, headers=headers)
        return await _version(db, "cars", car["id"])

    created = await sync_car("Lada Vesta")
    renamed = await sync_car("Kia Rio")

    assert created < renamed < await sync_car("Kia Rio 2")


async def test_name_version_is_set_on_name_change(
    api: AsyncClient, sms: FakeSmsSender, db: AsyncSession
) -> None:
    tokens = await login(api, sms)
    query = text("SELECT name_version FROM users WHERE id = :id")
    user_id = uuid.UUID(tokens["user"]["id"])
    assert (await db.execute(query, {"id": user_id})).scalar_one() is None

    await api.patch("/api/v1/me", json={"name": "Иван"}, headers=bearer(tokens))
    first = (await db.execute(query, {"id": user_id})).scalar_one()
    await api.patch("/api/v1/me", json={"name": "Иван Петров"}, headers=bearer(tokens))

    assert first is not None
    assert (await db.execute(query, {"id": user_id})).scalar_one() > first


async def test_client_id_is_kept(api: AsyncClient, sms: FakeSmsSender, db: AsyncSession) -> None:
    """id, созданный на телефоне, сохраняется как есть (для POST /sync)."""
    tokens = await login(api, sms)
    client_id = uuid.uuid4()
    db.add(
        Car(
            id=client_id,
            user_id=uuid.UUID(tokens["user"]["id"]),
            name="Lada",
            fuel_type=FuelType.AI95,
            tank_capacity_l=50,
            norm_l_per_100km=8.5,
        )
    )
    await db.commit()

    assert await _version(db, "cars", str(client_id)) > 0
