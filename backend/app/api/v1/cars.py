import uuid

from fastapi import APIRouter, Response, status

from app.core.deps import CurrentUser, Db
from app.schemas.car import CarCreate, CarOut, CarUpdate
from app.services import car_service

router = APIRouter(prefix="/cars", tags=["cars"])


@router.get("", response_model=list[CarOut])
async def list_cars(user: CurrentUser, db: Db, include_archived: bool = False) -> list[CarOut]:
    """Мои автомобили (архивные — только с include_archived=true)."""
    cars = await car_service.list_cars(db, user, include_archived=include_archived)
    return [CarOut.model_validate(car) for car in cars]


@router.post("", response_model=CarOut, status_code=status.HTTP_201_CREATED)
async def create_car(body: CarCreate, user: CurrentUser, db: Db) -> CarOut:
    """Добавить автомобиль."""
    return CarOut.model_validate(await car_service.create_car(db, user, body))


@router.get("/{car_id}", response_model=CarOut)
async def get_car(car_id: uuid.UUID, user: CurrentUser, db: Db) -> CarOut:
    """Один автомобиль."""
    return CarOut.model_validate(await car_service.get_own_car(db, user, car_id))


@router.patch("/{car_id}", response_model=CarOut)
async def update_car(car_id: uuid.UUID, body: CarUpdate, user: CurrentUser, db: Db) -> CarOut:
    """Изменить переданные поля (is_archived=false — вернуть из архива)."""
    car = await car_service.get_own_car(db, user, car_id)
    return CarOut.model_validate(await car_service.update_car(db, car, body))


@router.delete("/{car_id}", status_code=status.HTTP_204_NO_CONTENT)
async def archive_car(car_id: uuid.UUID, user: CurrentUser, db: Db) -> Response:
    """Убрать в архив (мягкое удаление)."""
    car = await car_service.get_own_car(db, user, car_id)
    await car_service.archive_car(db, car)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
