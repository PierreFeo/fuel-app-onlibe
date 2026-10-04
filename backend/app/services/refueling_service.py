"""Заправки: добавить, изменить, удалить. Ответ — весь лист с пересчитанным calc.

Контракт — docs/04_API_CONTRACT.md («Заправки»), правила — docs/06_BUSINESS_RULES.md.
"""

import uuid
from datetime import date
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import AppError, ErrorCode
from app.models import Car, FuelSheet, Refueling, User
from app.schemas.refueling import RefuelingCreate, RefuelingUpdate
from app.services.sheet_calc import round2
from app.services.sheet_service import (
    SheetView,
    business_rule,
    ensure_open,
    save_sheet,
    validate_sheet,
)

# Наибольшая сумма, которая влезает в NUMERIC(10,2).
_MAX_TOTAL_COST = Decimal("99999999.99")


def _total_cost(liters: Decimal, price_per_liter: Decimal) -> Decimal:
    """Сумма, если её не ввели: литры × цена, до копеек (правило 9)."""
    total = round2(liters * price_per_liter)
    if total > _MAX_TOTAL_COST:
        raise AppError(
            ErrorCode.VALIDATION_ERROR,
            "Неверные данные запроса",
            400,
            {"total_cost": "Слишком большая сумма"},
        )
    return total


def _check_date(sheet: FuelSheet, refueled_at: date) -> None:
    """Дата заправки — внутри месяца листа (правило 4)."""
    if (refueled_at.year, refueled_at.month) != (sheet.year, sheet.month):
        raise business_rule("REFUELING_DATE_OUTSIDE_MONTH", "Дата заправки не входит в месяц листа")


async def get_own_refueling(
    session: AsyncSession, user: User, refueling_id: uuid.UUID
) -> tuple[Refueling, FuelSheet, Car]:
    """Заправка текущего пользователя, её лист и авто. Чужая — 404, как и несуществующая."""
    row = (
        await session.execute(
            select(Refueling, FuelSheet, Car)
            .join(FuelSheet, Refueling.sheet_id == FuelSheet.id)
            .join(Car, FuelSheet.car_id == Car.id)
            .where(Refueling.id == refueling_id, Car.user_id == user.id)
        )
    ).first()
    if row is None:
        raise AppError(ErrorCode.NOT_FOUND, "Заправка не найдена", 404)
    return row[0], row[1], row[2]


async def add_refueling(
    session: AsyncSession, sheet: FuelSheet, car: Car, data: RefuelingCreate
) -> SheetView:
    ensure_open(sheet)
    _check_date(sheet, data.refueled_at)
    values = data.model_dump()
    if values["total_cost"] is None:
        values["total_cost"] = _total_cost(data.liters, data.price_per_liter)
    sheet.refuelings.append(Refueling(**values))
    return await save_sheet(session, sheet, car)


async def update_refueling(
    session: AsyncSession,
    refueling: Refueling,
    sheet: FuelSheet,
    car: Car,
    data: RefuelingUpdate,
) -> SheetView:
    ensure_open(sheet)
    changes = data.model_dump(exclude_unset=True)
    if "refueled_at" in changes:
        _check_date(sheet, changes["refueled_at"])
    # Сумму пересчитываем, если её стёрли (null) или поменяли литры/цену, не указав новую.
    if "total_cost" in changes:
        recalc = changes["total_cost"] is None
    else:
        recalc = "liters" in changes or "price_per_liter" in changes
    if recalc:
        changes.pop("total_cost", None)
    for field, value in changes.items():
        setattr(refueling, field, value)
    if recalc:
        refueling.total_cost = _total_cost(refueling.liters, refueling.price_per_liter)
    # Меньше литров — фактический остаток может стать больше, чем было топлива (правило 3).
    validate_sheet(sheet, car)
    return await save_sheet(session, sheet, car)


async def delete_refueling(
    session: AsyncSession, refueling: Refueling, sheet: FuelSheet, car: Car
) -> SheetView:
    ensure_open(sheet)
    sheet.refuelings.remove(refueling)
    validate_sheet(sheet, car)
    await session.delete(refueling)
    return await save_sheet(session, sheet, car)
