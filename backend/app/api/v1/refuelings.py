import uuid

from fastapi import APIRouter, status

from app.core.deps import CurrentUser, Db
from app.schemas.refueling import RefuelingCreate, RefuelingUpdate
from app.schemas.sheet import SheetOut
from app.services import refueling_service, sheet_service

router = APIRouter(tags=["refuelings"])


@router.post(
    "/sheets/{sheet_id}/refuelings",
    response_model=SheetOut,
    status_code=status.HTTP_201_CREATED,
)
async def create_refueling(
    sheet_id: uuid.UUID, body: RefuelingCreate, user: CurrentUser, db: Db
) -> SheetOut:
    """Добавить заправку. Ответ — весь лист с пересчитанным calc.

    Без `total_cost` сумма = литры × цена.
    """
    sheet, car = await sheet_service.get_own_sheet(db, user, sheet_id)
    view = await refueling_service.add_refueling(db, sheet, car, body)
    return SheetOut.build(view.sheet, view.calc)


@router.patch("/refuelings/{refueling_id}", response_model=SheetOut)
async def update_refueling(
    refueling_id: uuid.UUID, body: RefuelingUpdate, user: CurrentUser, db: Db
) -> SheetOut:
    """Изменить переданные поля заправки. Ответ — весь лист."""
    refueling, sheet, car = await refueling_service.get_own_refueling(db, user, refueling_id)
    view = await refueling_service.update_refueling(db, refueling, sheet, car, body)
    return SheetOut.build(view.sheet, view.calc)


@router.delete("/refuelings/{refueling_id}", response_model=SheetOut)
async def delete_refueling(refueling_id: uuid.UUID, user: CurrentUser, db: Db) -> SheetOut:
    """Удалить заправку. Ответ — весь лист без неё."""
    refueling, sheet, car = await refueling_service.get_own_refueling(db, user, refueling_id)
    view = await refueling_service.delete_refueling(db, refueling, sheet, car)
    return SheetOut.build(view.sheet, view.calc)
