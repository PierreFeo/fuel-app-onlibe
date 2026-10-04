"""Автомобили пользователя (docs/04_API_CONTRACT.md, «Автомобили»)."""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import AppError, ErrorCode
from app.models import Car, User
from app.schemas.car import CarCreate, CarUpdate


async def list_cars(session: AsyncSession, user: User, *, include_archived: bool) -> list[Car]:
    query = select(Car).where(Car.user_id == user.id)
    if not include_archived:
        query = query.where(Car.is_archived.is_(False))
    return list(await session.scalars(query.order_by(Car.created_at, Car.id)))


async def create_car(session: AsyncSession, user: User, data: CarCreate) -> Car:
    car = Car(user_id=user.id, **data.model_dump())
    session.add(car)
    await session.commit()
    return car


async def get_own_car(session: AsyncSession, user: User, car_id: uuid.UUID) -> Car:
    """Авто текущего пользователя. Чужое — 404, а не 403: не раскрываем, что оно существует."""
    car = await session.get(Car, car_id)
    if car is None or car.user_id != user.id:
        raise AppError(ErrorCode.NOT_FOUND, "Автомобиль не найден", 404)
    return car


async def update_car(session: AsyncSession, car: Car, data: CarUpdate) -> Car:
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(car, field, value)
    await session.commit()
    return car


async def archive_car(session: AsyncSession, car: Car) -> None:
    """«Удаление» — мягкое: авто прячется в архив, его ЛУТ сохраняются."""
    car.is_archived = True
    await session.commit()
