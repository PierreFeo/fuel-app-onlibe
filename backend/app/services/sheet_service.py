"""ЛУТ: список, подсказка нового листа, создание, изменение, закрытие, удаление.

Контракт — docs/04_API_CONTRACT.md, правила — docs/06_BUSINESS_RULES.md. Формулы здесь не
считаются: всё вычисляемое отдаёт чистая функция sheet_calc.calculate.
"""

import uuid
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from sqlalchemy import select, tuple_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import AppError, ErrorCode
from app.models import Car, FuelSheet, User
from app.models.enums import Season, SheetStatus
from app.schemas.sheet import SheetClose, SheetCreate, SheetUpdate
from app.services.sheet_calc import RefuelingData, SheetCalc, SheetData, calculate

_ZERO_L = Decimal("0.00")


@dataclass(frozen=True)
class SheetView:
    """Лист вместе с вычисляемыми полями — то, что уходит в ответ API."""

    sheet: FuelSheet
    calc: SheetCalc


@dataclass(frozen=True)
class SheetPageResult:
    items: list[SheetView]
    next_before: str | None


@dataclass(frozen=True)
class Prefill:
    year: int
    month: int
    odometer_start_km: int
    fuel_start_l: Decimal
    season: Season


# --- ошибки ---


def business_rule(reason: str, message: str) -> AppError:
    """422 BUSINESS_RULE: правило зависит от данных в БД, причина — в details.reason."""
    return AppError(ErrorCode.BUSINESS_RULE, message, 422, {"reason": reason})


def _sheet_closed() -> AppError:
    return AppError(ErrorCode.SHEET_CLOSED, "Лист закрыт — сначала переоткройте его", 409)


def _sheet_exists() -> AppError:
    return AppError(ErrorCode.SHEET_EXISTS, "Лист за этот месяц уже есть", 409)


def _not_found() -> AppError:
    return AppError(ErrorCode.NOT_FOUND, "Лист не найден", 404)


# --- вспомогательное ---


def _month_index(year: int, month: int) -> int:
    """Номер месяца «подряд» — чтобы сравнивать и сдвигать месяцы простой арифметикой."""
    return year * 12 + month - 1


def _add_month(year: int, month: int) -> tuple[int, int]:
    index = _month_index(year, month) + 1
    return index // 12, index % 12 + 1


def _norm_for(car: Car, season: Season) -> Decimal:
    """Норма авто для сезона. Зимней нет — 422 WINTER_NORM_NOT_SET (правило 10)."""
    if season is Season.SUMMER:
        return car.norm_l_per_100km
    if car.norm_winter_l_per_100km is None:
        raise business_rule("WINTER_NORM_NOT_SET", "Зимняя норма не указана")
    return car.norm_winter_l_per_100km


def _calculate(sheet: FuelSheet, car: Car, prev_odometer_end_km: int | None) -> SheetCalc:
    data = SheetData(
        odometer_start_km=sheet.odometer_start_km,
        odometer_end_km=sheet.odometer_end_km,
        fuel_start_l=sheet.fuel_start_l,
        fuel_end_actual_l=sheet.fuel_end_actual_l,
        norm_l_per_100km=sheet.norm_l_per_100km,
        refuelings=[
            RefuelingData(liters=r.liters, total_cost=r.total_cost, odometer_km=r.odometer_km)
            for r in sheet.refuelings
        ],
    )
    return calculate(
        data, tank_capacity_l=car.tank_capacity_l, prev_odometer_end_km=prev_odometer_end_km
    )


async def _prev_odometer_end(session: AsyncSession, sheet: FuelSheet) -> int | None:
    """Пробег на конец ближайшего более раннего листа этого авто (для ODOMETER_GAP)."""
    return await session.scalar(
        select(FuelSheet.odometer_end_km)
        .where(
            FuelSheet.car_id == sheet.car_id,
            tuple_(FuelSheet.year, FuelSheet.month) < tuple_(sheet.year, sheet.month),
        )
        .order_by(FuelSheet.year.desc(), FuelSheet.month.desc())
        .limit(1)
    )


async def _view(session: AsyncSession, sheet: FuelSheet, car: Car) -> SheetView:
    return SheetView(sheet, _calculate(sheet, car, await _prev_odometer_end(session, sheet)))


async def save_sheet(session: AsyncSession, sheet: FuelSheet, car: Car) -> SheetView:
    """Сохранить лист (вместе с заправками) и вернуть его с пересчитанным calc."""
    await session.commit()
    # created_at/updated_at ставит БД — перечитываем лист, чтобы отдать их в ответе.
    # Заправки (selectin) перечитываются вместе с ним — снова по дате.
    await session.refresh(sheet)
    return await _view(session, sheet, car)


def validate_sheet(sheet: FuelSheet, car: Car) -> None:
    """Блокирующие правила, которые зависят от уже сохранённых данных (06, «Валидации»)."""
    if sheet.odometer_end_km is not None and sheet.odometer_end_km < sheet.odometer_start_km:
        raise business_rule("ODOMETER_END_BEFORE_START", "Пробег на конец меньше пробега на начало")
    if sheet.fuel_end_actual_l is not None:
        available = _calculate(sheet, car, None).fuel_available_l
        if sheet.fuel_end_actual_l > available:
            raise business_rule(
                "FUEL_END_OVER_AVAILABLE",
                "Остаток на конец больше, чем было топлива (на начало + заправки)",
            )


def ensure_open(sheet: FuelSheet) -> None:
    if sheet.status is SheetStatus.CLOSED:
        raise _sheet_closed()


async def _latest_sheet(session: AsyncSession, car: Car) -> FuelSheet | None:
    return await session.scalar(
        select(FuelSheet)
        .where(FuelSheet.car_id == car.id)
        .order_by(FuelSheet.year.desc(), FuelSheet.month.desc())
        .limit(1)
    )


def _default_season(
    car: Car, latest: FuelSheet | None, month: int, winter_months: frozenset[int]
) -> Season:
    """Сезон нового листа, если его не выбрали явно (06, «Сезон и норма листа», пп. 1–3)."""
    if latest is not None:
        season = latest.season  # наследуем от самого позднего листа
    else:
        season = Season.WINTER if month in winter_months else Season.SUMMER
    # Зимней нормы нет — подсказываем лето, а не ошибку.
    if season is Season.WINTER and car.norm_winter_l_per_100km is None:
        return Season.SUMMER
    return season


# --- операции ---


async def get_own_sheet(
    session: AsyncSession, user: User, sheet_id: uuid.UUID
) -> tuple[FuelSheet, Car]:
    """Лист текущего пользователя и его авто. Чужой — 404, как и несуществующий."""
    row = (
        await session.execute(
            select(FuelSheet, Car)
            .join(Car, FuelSheet.car_id == Car.id)
            .where(FuelSheet.id == sheet_id, Car.user_id == user.id)
        )
    ).first()
    if row is None:
        raise _not_found()
    return row[0], row[1]


async def get_sheet(session: AsyncSession, sheet: FuelSheet, car: Car) -> SheetView:
    return await _view(session, sheet, car)


async def list_sheets(
    session: AsyncSession, car: Car, *, limit: int, before: tuple[int, int] | None
) -> SheetPageResult:
    """Новые сверху. before=(год, месяц) — только листы раньше этого месяца."""
    query = select(FuelSheet).where(FuelSheet.car_id == car.id)
    if before is not None:
        query = query.where(tuple_(FuelSheet.year, FuelSheet.month) < tuple_(*before))
    # Берём на один лист больше: он показывает, что дальше ещё есть листы, и даёт
    # пробег «предыдущего месяца» для последнего листа страницы (ODOMETER_GAP).
    rows = list(
        await session.scalars(
            query.order_by(FuelSheet.year.desc(), FuelSheet.month.desc()).limit(limit + 1)
        )
    )
    page = rows[:limit]
    items = [
        SheetView(
            sheet,
            _calculate(sheet, car, rows[i + 1].odometer_end_km if i + 1 < len(rows) else None),
        )
        for i, sheet in enumerate(page)
    ]
    has_more = len(rows) > limit
    next_before = f"{page[-1].year:04d}-{page[-1].month:02d}" if has_more else None
    return SheetPageResult(items, next_before)


async def next_prefill(
    session: AsyncSession, car: Car, *, now: datetime, winter_months: frozenset[int]
) -> Prefill:
    """Подсказка для нового листа (06, «Перенос между месяцами» и «Сезон и норма листа»)."""
    latest = await _latest_sheet(session, car)
    if latest is None:
        year, month = now.year, now.month
        odometer, fuel = 0, _ZERO_L
    else:
        year, month = _add_month(latest.year, latest.month)
        odometer = (
            latest.odometer_end_km
            if latest.odometer_end_km is not None
            else latest.odometer_start_km
        )
        fuel_end = _calculate(latest, car, None).fuel_end_l
        fuel = fuel_end if fuel_end is not None else _ZERO_L
    season = _default_season(car, latest, month, winter_months)
    return Prefill(year, month, odometer, fuel, season)


async def create_sheet(
    session: AsyncSession,
    car: Car,
    data: SheetCreate,
    *,
    now: datetime,
    winter_months: frozenset[int],
) -> SheetView:
    # Правило 6: не дальше следующего месяца.
    if _month_index(data.year, data.month) > _month_index(now.year, now.month) + 1:
        raise business_rule("MONTH_TOO_FAR", "Нельзя создать лист позже следующего месяца")

    season = data.season
    if season is None:
        # Первый лист — сезон по месяцу самого листа (не по текущему).
        latest = await _latest_sheet(session, car)
        season = _default_season(car, latest, data.month, winter_months)
    norm = _norm_for(car, season)

    exists = await session.scalar(
        select(FuelSheet.id).where(
            FuelSheet.car_id == car.id,
            FuelSheet.year == data.year,
            FuelSheet.month == data.month,
        )
    )
    if exists is not None:
        raise _sheet_exists()

    sheet = FuelSheet(
        car_id=car.id,
        year=data.year,
        month=data.month,
        odometer_start_km=data.odometer_start_km,
        fuel_start_l=data.fuel_start_l,
        season=season,
        norm_l_per_100km=norm,
        status=SheetStatus.OPEN,
        refuelings=[],
    )
    session.add(sheet)
    try:
        await session.flush()
    except IntegrityError:
        # Тот же месяц успели создать параллельным запросом.
        await session.rollback()
        raise _sheet_exists() from None
    return await save_sheet(session, sheet, car)


async def update_sheet(
    session: AsyncSession, sheet: FuelSheet, car: Car, data: SheetUpdate
) -> SheetView:
    ensure_open(sheet)
    changes = data.model_dump(exclude_unset=True)
    season = changes.pop("season", None)
    # Тот же сезон — ничего не переключаем: норма остаётся прежней (правило 6).
    if season is not None and season is not sheet.season:
        sheet.norm_l_per_100km = _norm_for(car, season)
        sheet.season = season
    for field, value in changes.items():
        setattr(sheet, field, value)
    validate_sheet(sheet, car)
    return await save_sheet(session, sheet, car)


async def close_sheet(
    session: AsyncSession, sheet: FuelSheet, car: Car, data: SheetClose, *, now: datetime
) -> SheetView:
    ensure_open(sheet)
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(sheet, field, value)
    validate_sheet(sheet, car)
    sheet.status = SheetStatus.CLOSED
    sheet.closed_at = now
    return await save_sheet(session, sheet, car)


async def reopen_sheet(session: AsyncSession, sheet: FuelSheet, car: Car) -> SheetView:
    """Открытый лист остаётся открытым — повторный reopen ничего не ломает."""
    sheet.status = SheetStatus.OPEN
    sheet.closed_at = None
    return await save_sheet(session, sheet, car)


async def delete_sheet(session: AsyncSession, sheet: FuelSheet) -> None:
    ensure_open(sheet)
    if sheet.refuelings:
        raise business_rule("SHEET_HAS_REFUELINGS", "В листе есть заправки — сначала удалите их")
    await session.delete(sheet)
    await session.commit()
