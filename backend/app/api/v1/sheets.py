import uuid
from typing import Annotated

from fastapi import APIRouter, Query, Response, status

from app.core.deps import AppSettings, CurrentUser, Db, Now
from app.schemas.sheet import (
    SheetClose,
    SheetCreate,
    SheetOut,
    SheetPage,
    SheetPrefill,
    SheetUpdate,
)
from app.services import car_service, sheet_service

router = APIRouter(tags=["sheets"])

# Месяц в виде «2026-10».
YearMonth = Annotated[str | None, Query(pattern=r"^\d{4}-(0[1-9]|1[0-2])$", examples=["2026-10"])]


@router.get("/cars/{car_id}/sheets", response_model=SheetPage)
async def list_sheets(
    car_id: uuid.UUID,
    user: CurrentUser,
    db: Db,
    limit: Annotated[int, Query(ge=1, le=50)] = 12,
    before: YearMonth = None,
) -> SheetPage:
    """Листы авто, новые сверху. Следующая страница — `before=<next_before>`."""
    car = await car_service.get_own_car(db, user, car_id)
    before_month = (int(before[:4]), int(before[5:])) if before else None
    page = await sheet_service.list_sheets(db, car, limit=limit, before=before_month)
    return SheetPage(
        items=[SheetOut.build(v.sheet, v.calc) for v in page.items],
        next_before=page.next_before,
    )


@router.get("/cars/{car_id}/sheets/next-prefill", response_model=SheetPrefill)
async def next_prefill(
    car_id: uuid.UUID, user: CurrentUser, db: Db, now: Now, settings: AppSettings
) -> SheetPrefill:
    """Подсказка для нового листа: месяц, пробег и остаток на начало, сезон."""
    car = await car_service.get_own_car(db, user, car_id)
    prefill = await sheet_service.next_prefill(
        db, car, now=now, winter_months=settings.winter_months_set
    )
    return SheetPrefill.model_validate(prefill, from_attributes=True)


@router.post("/cars/{car_id}/sheets", response_model=SheetOut, status_code=status.HTTP_201_CREATED)
async def create_sheet(
    car_id: uuid.UUID,
    body: SheetCreate,
    user: CurrentUser,
    db: Db,
    now: Now,
    settings: AppSettings,
) -> SheetOut:
    """Новый лист. Без `season` — сезон как в next-prefill."""
    car = await car_service.get_own_car(db, user, car_id)
    view = await sheet_service.create_sheet(
        db, car, body, now=now, winter_months=settings.winter_months_set
    )
    return SheetOut.build(view.sheet, view.calc)


@router.get("/sheets/{sheet_id}", response_model=SheetOut)
async def get_sheet(sheet_id: uuid.UUID, user: CurrentUser, db: Db) -> SheetOut:
    """Один лист с вычисляемыми полями."""
    sheet, car = await sheet_service.get_own_sheet(db, user, sheet_id)
    view = await sheet_service.get_sheet(db, sheet, car)
    return SheetOut.build(view.sheet, view.calc)


@router.patch("/sheets/{sheet_id}", response_model=SheetOut)
async def update_sheet(
    sheet_id: uuid.UUID, body: SheetUpdate, user: CurrentUser, db: Db
) -> SheetOut:
    """Изменить открытый лист; `season` — переключить ☀️/❄️ (норма копируется из авто)."""
    sheet, car = await sheet_service.get_own_sheet(db, user, sheet_id)
    view = await sheet_service.update_sheet(db, sheet, car, body)
    return SheetOut.build(view.sheet, view.calc)


@router.post("/sheets/{sheet_id}/close", response_model=SheetOut)
async def close_sheet(
    sheet_id: uuid.UUID, body: SheetClose, user: CurrentUser, db: Db, now: Now
) -> SheetOut:
    """Закрыть месяц: пробег на конец обязателен, фактический остаток — по желанию."""
    sheet, car = await sheet_service.get_own_sheet(db, user, sheet_id)
    view = await sheet_service.close_sheet(db, sheet, car, body, now=now)
    return SheetOut.build(view.sheet, view.calc)


@router.post("/sheets/{sheet_id}/reopen", response_model=SheetOut)
async def reopen_sheet(sheet_id: uuid.UUID, user: CurrentUser, db: Db) -> SheetOut:
    """Переоткрыть закрытый лист, чтобы снова его редактировать."""
    sheet, car = await sheet_service.get_own_sheet(db, user, sheet_id)
    view = await sheet_service.reopen_sheet(db, sheet, car)
    return SheetOut.build(view.sheet, view.calc)


@router.delete("/sheets/{sheet_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_sheet(sheet_id: uuid.UUID, user: CurrentUser, db: Db) -> Response:
    """Удалить открытый лист без заправок."""
    sheet, _ = await sheet_service.get_own_sheet(db, user, sheet_id)
    await sheet_service.delete_sheet(db, sheet)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
