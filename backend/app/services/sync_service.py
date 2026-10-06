"""POST /sync: принять изменения телефона, отдать изменения сервера.

Правила — docs/04_API_CONTRACT.md, «Синхронизация». Сервер только хранит данные: проверяет формат,
владельца, родителя и уникальность листа на месяц; бизнес-правил ЛУТ здесь нет.
"""

import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ValidationError
from sqlalchemy import Select, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import SYNC_VERSION_SEQ
from app.models import Car, FuelSheet, Refueling, User
from app.schemas.sync import (
    CarRecord,
    DeletedRecord,
    ProfileSync,
    RefuelingRecord,
    RejectedOut,
    SheetRecord,
    SyncIn,
    SyncOut,
)

NOT_FOUND = "NOT_FOUND"
PARENT_NOT_FOUND = "PARENT_NOT_FOUND"
SHEET_EXISTS = "SHEET_EXISTS"
VALIDATION_ERROR = "VALIDATION_ERROR"

MONTHS = (
    "январь", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
)  # fmt: skip


class _Rejected(Exception):
    """Запись не принята — попадёт в `rejected`, остальные записи запроса сохраняются."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


async def sync(session: AsyncSession, user: User, data: SyncIn, *, now: datetime) -> SyncOut:
    saver = _Saver(session, user, now)

    if data.profile is not None and data.profile.name != user.name:
        user.name = data.profile.name
        user.name_version = SYNC_VERSION_SEQ.next_value()

    # Сначала родители, потом дети: лист видит авто из этого же запроса, заправка — лист.
    for raw in data.cars:
        await saver.apply("car", raw, CarRecord, saver.save_car)
    for raw in data.sheets:
        await saver.apply("sheet", raw, SheetRecord, saver.save_sheet)
    for raw in data.refuelings:
        await saver.apply("refueling", raw, RefuelingRecord, saver.save_refueling)
    await session.flush()

    out = await _changes_since(session, user, data.cursor)
    out.rejected = saver.rejected
    await session.commit()
    return out


class _Saver:
    def __init__(self, session: AsyncSession, user: User, now: datetime) -> None:
        self.session = session
        self.user = user
        self.now = now
        self.rejected: list[RejectedOut] = []

    async def apply[R: BaseModel](
        self,
        entity: str,
        raw: dict[str, Any],
        model: type[R],
        save: Callable[[R | DeletedRecord], Awaitable[None]],
    ) -> None:
        """Проверить и сохранить одну запись; ошибка — в `rejected`, без отката остальных."""
        try:
            record: R | DeletedRecord = (
                DeletedRecord.model_validate(raw)
                if raw.get("deleted") is True
                else model.model_validate(raw)
            )
        except ValidationError as e:
            self._reject(entity, raw, VALIDATION_ERROR, _first_error(e))
            return
        try:
            # Точка сохранения: отклонённая запись не оставляет в БД ничего недописанного.
            async with self.session.begin_nested():
                await save(record)
        except _Rejected as e:
            self._reject(entity, raw, e.code, e.message)
        except IntegrityError:
            # Проверки схем повторяют ограничения БД, сюда попадать не должны — но и не 500.
            self._reject(entity, raw, VALIDATION_ERROR, "Запись не прошла проверку базы данных")

    def _reject(self, entity: str, raw: dict[str, Any], code: str, message: str) -> None:
        self.rejected.append(
            RejectedOut(entity=entity, id=str(raw.get("id", "")), code=code, message=message)
        )

    # --- Авто ---

    async def save_car(self, record: CarRecord | DeletedRecord) -> None:
        car = await self.session.get(Car, record.id)
        if car is not None and car.user_id != self.user.id:
            raise _Rejected(NOT_FOUND, "Запись с таким id принадлежит другому пользователю")
        if isinstance(record, DeletedRecord):
            if car is not None and car.deleted_at is None:
                await self._delete_car(car)
            return
        if car is None:
            car = Car(id=record.id, user_id=self.user.id)
            self.session.add(car)
        _copy(record, car, exclude={"id", "deleted"})
        car.deleted_at = None
        await self.session.flush()

    async def _delete_car(self, car: Car) -> None:
        car.deleted_at = self.now
        sheets = await self.session.scalars(
            select(FuelSheet).where(FuelSheet.car_id == car.id, FuelSheet.deleted_at.is_(None))
        )
        for sheet in sheets:
            self._delete_sheet(sheet)
        await self.session.flush()

    # --- Листы ---

    async def save_sheet(self, record: SheetRecord | DeletedRecord) -> None:
        sheet = await self.session.get(FuelSheet, record.id)
        if sheet is not None and not await self._owns_car(sheet.car_id):
            raise _Rejected(NOT_FOUND, "Запись с таким id принадлежит другому пользователю")
        if isinstance(record, DeletedRecord):
            if sheet is not None and sheet.deleted_at is None:
                self._delete_sheet(sheet)
                await self.session.flush()
            return
        if not await self._owns_car(record.car_id, alive=True):
            raise _Rejected(PARENT_NOT_FOUND, "Нет авто этого листа")
        duplicate = await self.session.scalar(
            select(FuelSheet.id).where(
                FuelSheet.car_id == record.car_id,
                FuelSheet.year == record.year,
                FuelSheet.month == record.month,
                FuelSheet.deleted_at.is_(None),
                FuelSheet.id != record.id,
            )
        )
        if duplicate is not None:
            month = f"{MONTHS[record.month - 1]} {record.year}"
            raise _Rejected(SHEET_EXISTS, f"Лист за {month} по этому авто уже есть")
        if sheet is None:
            sheet = FuelSheet(id=record.id)
            self.session.add(sheet)
        _copy(record, sheet, exclude={"id", "deleted"})
        sheet.deleted_at = None
        await self.session.flush()

    def _delete_sheet(self, sheet: FuelSheet) -> None:
        """Удаление листа помечает удалёнными и его заправки (каждая получит новый version)."""
        sheet.deleted_at = self.now
        for refueling in sheet.refuelings:
            if refueling.deleted_at is None:
                refueling.deleted_at = self.now

    # --- Заправки ---

    async def save_refueling(self, record: RefuelingRecord | DeletedRecord) -> None:
        refueling = await self.session.get(Refueling, record.id)
        if refueling is not None and not await self._owns_sheet(refueling.sheet_id):
            raise _Rejected(NOT_FOUND, "Запись с таким id принадлежит другому пользователю")
        if isinstance(record, DeletedRecord):
            if refueling is not None and refueling.deleted_at is None:
                refueling.deleted_at = self.now
                await self.session.flush()
            return
        if not await self._owns_sheet(record.sheet_id, alive=True):
            raise _Rejected(PARENT_NOT_FOUND, "Нет листа этой заправки")
        if refueling is None:
            refueling = Refueling(id=record.id)
            self.session.add(refueling)
        _copy(record, refueling, exclude={"id", "deleted"})
        refueling.deleted_at = None
        await self.session.flush()

    # --- Владелец ---

    async def _owns_car(self, car_id: uuid.UUID, *, alive: bool = False) -> bool:
        car = await self.session.get(Car, car_id)
        if car is None or car.user_id != self.user.id:
            return False
        return not (alive and car.deleted_at is not None)

    async def _owns_sheet(self, sheet_id: uuid.UUID, *, alive: bool = False) -> bool:
        sheet = await self.session.get(FuelSheet, sheet_id)
        if sheet is None or (alive and sheet.deleted_at is not None):
            return False
        return await self._owns_car(sheet.car_id, alive=alive)


def _copy(record: BaseModel, target: object, *, exclude: set[str]) -> None:
    for field, value in record.model_dump(exclude=exclude).items():
        setattr(target, field, value)


def _first_error(error: ValidationError) -> str:
    """«liters: Input should be greater than 0» — поле и причина, как в details ошибки 400."""
    first = error.errors()[0]
    field = ".".join(str(part) for part in first["loc"])
    return f"{field}: {first['msg']}" if field else first["msg"]


# --- Выдача изменений ---


async def _changes_since(session: AsyncSession, user: User, cursor: int | None) -> SyncOut:
    """Записи пользователя с version > cursor (cursor = null — все неудалённые) и новый курсор.

    Курсор — наибольший version среди записей пользователя: телефон работает один, поэтому
    гонку «меньший version закоммитили позже» здесь не учитываем.
    """
    cars_q = select(Car).where(Car.user_id == user.id)
    sheets_q = select(FuelSheet).join(Car, FuelSheet.car_id == Car.id).where(Car.user_id == user.id)
    refuelings_q = (
        select(Refueling)
        .join(FuelSheet, Refueling.sheet_id == FuelSheet.id)
        .join(Car, FuelSheet.car_id == Car.id)
        .where(Car.user_id == user.id)
    )

    def changed[M: (Car, FuelSheet, Refueling)](query: Select[tuple[M]], model: type[M]) -> Any:
        if cursor is None:
            query = query.where(model.deleted_at.is_(None))
        else:
            query = query.where(model.version > cursor)
        # populate_existing: version и updated_at после flush устарели в памяти — берём из БД.
        return query.order_by(model.version).execution_options(populate_existing=True)

    cars = (await session.scalars(changed(cars_q, Car))).all()
    sheets = (await session.scalars(changed(sheets_q, FuelSheet))).all()
    refuelings = (await session.scalars(changed(refuelings_q, Refueling))).all()

    name, name_version = (
        await session.execute(select(User.name, User.name_version).where(User.id == user.id))
    ).one()
    versions = [
        await session.scalar(cars_q.with_only_columns(func.max(Car.version))),
        await session.scalar(sheets_q.with_only_columns(func.max(FuelSheet.version))),
        await session.scalar(refuelings_q.with_only_columns(func.max(Refueling.version))),
        name_version,
    ]
    known = [v for v in versions if v is not None]
    new_cursor = max(known) if known else (cursor or 0)

    name_changed = cursor is None or (name_version is not None and name_version > cursor)
    profile = ProfileSync(name=name) if name_changed and name else None

    return SyncOut(
        cursor=new_cursor,
        profile=profile,
        cars=[_record(CarRecord, c) for c in cars],
        sheets=[_record(SheetRecord, s) for s in sheets],
        refuelings=[_record(RefuelingRecord, r) for r in refuelings],
        rejected=[],
    )


def _record[R: BaseModel](model: type[R], row: Car | FuelSheet | Refueling) -> R:
    return model.model_validate(row).model_copy(update={"deleted": row.deleted_at is not None})
