"""Проверка первой миграции на чистой тестовой БД (fuel_test).

Тесты синхронные: env.py Alembic сам запускает asyncio.run(), а внутри уже работающего
event loop этого сделать нельзя.
"""

import asyncio
import uuid
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import Connection, inspect, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import create_async_engine

from app import models  # noqa: F401
from app.core.config import get_settings
from app.db.base import Base

BACKEND_DIR = Path(__file__).resolve().parent.parent
TEST_DB_URL = get_settings().test_database_url
EXPECTED_TABLES = {"users", "otp_codes", "refresh_tokens", "cars", "fuel_sheets", "refuelings"}


def run_on_db[T](fn: Callable[[Connection], T]) -> T:
    """Выполнить синхронную функцию на соединении с тестовой БД (с коммитом)."""

    async def _run() -> T:
        engine = create_async_engine(TEST_DB_URL)
        try:
            async with engine.begin() as conn:
                return await conn.run_sync(fn)
        finally:
            await engine.dispose()

    return asyncio.run(_run())


def alembic_config() -> Config:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("sqlalchemy.url", TEST_DB_URL)
    cfg.attributes["configure_logger"] = False
    return cfg


def table_names(conn: Connection) -> set[str]:
    return set(inspect(conn).get_table_names())


@pytest.fixture
def empty_db() -> Iterator[Config]:
    """Пустая БД: схема public пересоздаётся перед тестом."""

    def reset(conn: Connection) -> None:
        conn.execute(text("DROP SCHEMA public CASCADE"))
        conn.execute(text("CREATE SCHEMA public"))

    run_on_db(reset)
    yield alembic_config()


@pytest.fixture
def migrated_db(empty_db: Config) -> Config:
    command.upgrade(empty_db, "head")
    return empty_db


def test_upgrade_creates_all_tables(migrated_db: Config) -> None:
    assert run_on_db(table_names) == EXPECTED_TABLES | {"alembic_version"}


def test_models_match_migration(migrated_db: Config) -> None:
    """Модели и миграция не расходятся: autogenerate не видит изменений."""

    def diff(conn: Connection) -> list[Any]:
        return compare_metadata(MigrationContext.configure(conn), Base.metadata)

    assert run_on_db(diff) == []


def test_schema_details_follow_data_model(migrated_db: Config) -> None:
    def details(conn: Connection) -> dict[str, Any]:
        insp = inspect(conn)
        sheet_cols = {c["name"]: c for c in insp.get_columns("fuel_sheets")}
        return {
            "users_unique": insp.get_unique_constraints("users"),
            "sheets_unique": insp.get_unique_constraints("fuel_sheets"),
            "sheets_unique_index": [
                i for i in insp.get_indexes("fuel_sheets") if i["name"].startswith("uq_")
            ],
            "sheets_fk": insp.get_foreign_keys("fuel_sheets"),
            "fuel_start_type": sheet_cols["fuel_start_l"]["type"],
            "sheet_norm_type": sheet_cols["norm_l_per_100km"]["type"],
            "car_norm_types": [
                c["type"] for c in insp.get_columns("cars") if c["name"].startswith("norm_")
            ],
            "odometer_end_nullable": sheet_cols["odometer_end_km"]["nullable"],
            "base_cols": {c["name"] for c in insp.get_columns("refuelings")},
        }

    d = run_on_db(details)

    assert [u["column_names"] for u in d["users_unique"]] == [["phone"]]
    # Один лист на месяц — частичный уникальный индекс среди неудалённых (не constraint).
    assert d["sheets_unique"] == []
    [month_index] = d["sheets_unique_index"]
    assert month_index["column_names"] == ["car_id", "year", "month"]
    assert month_index["unique"]
    assert month_index["dialect_options"]["postgresql_where"] == "(deleted_at IS NULL)"
    assert d["sheets_fk"][0]["referred_table"] == "cars"
    assert d["sheets_fk"][0]["options"]["ondelete"] == "CASCADE"
    assert (d["fuel_start_type"].precision, d["fuel_start_type"].scale) == (8, 2)
    # нормы — 3 знака после точки (10.068), docs/03_DATA_MODEL.md
    assert (d["sheet_norm_type"].precision, d["sheet_norm_type"].scale) == (6, 3)
    assert [(t.precision, t.scale) for t in d["car_norm_types"]] == [(6, 3), (6, 3)]
    assert d["odometer_end_nullable"] is True
    assert {"id", "created_at", "updated_at", "version", "deleted_at"} <= d["base_cols"]


def _insert_user_and_car(
    conn: Connection, fuel_type: str = "AI95", winter_norm: str | None = None
) -> uuid.UUID:
    user_id, car_id = uuid.uuid4(), uuid.uuid4()
    conn.execute(
        text("INSERT INTO users (id, phone) VALUES (:id, :phone)"),
        {"id": user_id, "phone": f"+7999{str(user_id.int)[:7]}"},
    )
    conn.execute(
        text(
            "INSERT INTO cars (id, user_id, name, fuel_type, tank_capacity_l, norm_l_per_100km, "
            "norm_winter_l_per_100km) VALUES (:id, :uid, 'Lada Vesta', :ft, 50, 10.068, :winter)"
        ),
        {"id": car_id, "uid": user_id, "ft": fuel_type, "winter": winter_norm},
    )
    return car_id


def _insert_sheet(
    conn: Connection, car_id: uuid.UUID, month: int = 10, season: str = "SUMMER"
) -> uuid.UUID:
    sheet_id = uuid.uuid4()
    conn.execute(
        text(
            "INSERT INTO fuel_sheets (id, car_id, year, month, odometer_start_km, fuel_start_l, "
            "season, norm_l_per_100km) VALUES (:id, :car, 2026, :m, 52340, 12, :season, 10.068)"
        ),
        {"id": sheet_id, "car": car_id, "m": month, "season": season},
    )
    return sheet_id


def test_valid_rows_are_accepted_with_defaults(migrated_db: Config) -> None:
    def scenario(conn: Connection) -> tuple[str, bool]:
        car_id = _insert_user_and_car(conn)
        _insert_sheet(conn, car_id)
        status = conn.execute(text("SELECT status FROM fuel_sheets")).scalar_one()
        archived = conn.execute(text("SELECT is_archived FROM cars")).scalar_one()
        return status, archived

    assert run_on_db(scenario) == ("OPEN", False)


@pytest.mark.parametrize(
    "bad_action",
    [
        pytest.param(lambda c: _insert_user_and_car(c, fuel_type="AI100"), id="bad-fuel-type"),
        pytest.param(lambda c: _insert_sheet(c, _insert_user_and_car(c), month=13), id="month-13"),
        pytest.param(
            lambda c: _insert_sheet(c, _insert_user_and_car(c), season="SPRING"), id="bad-season"
        ),
        pytest.param(lambda c: _insert_user_and_car(c, winter_norm="0"), id="winter-norm-zero"),
        pytest.param(
            lambda c: [_insert_sheet(c, car := _insert_user_and_car(c)), _insert_sheet(c, car)],
            id="duplicate-sheet-month",
        ),
    ],
)
def test_constraints_reject_invalid_rows(
    migrated_db: Config, bad_action: Callable[[Connection], Any]
) -> None:
    with pytest.raises(IntegrityError):
        run_on_db(bad_action)


def test_rows_get_increasing_versions_from_one_sequence(migrated_db: Config) -> None:
    """version берётся из общей последовательности: у разных таблиц значения не повторяются."""

    def scenario(conn: Connection) -> list[int]:
        car_id = _insert_user_and_car(conn)
        _insert_sheet(conn, car_id, month=9)
        _insert_sheet(conn, car_id, month=10)
        car = conn.execute(text("SELECT version FROM cars")).scalar_one()
        sheets = conn.execute(text("SELECT version FROM fuel_sheets ORDER BY month")).scalars()
        return [car, *sheets]

    versions = run_on_db(scenario)

    assert versions == sorted(versions)
    assert len(set(versions)) == 3


def test_deleted_sheet_does_not_block_same_month(migrated_db: Config) -> None:
    def scenario(conn: Connection) -> int:
        car_id = _insert_user_and_car(conn)
        old = _insert_sheet(conn, car_id)
        conn.execute(text("UPDATE fuel_sheets SET deleted_at = now() WHERE id = :id"), {"id": old})
        _insert_sheet(conn, car_id)  # тот же месяц — можно, старый лист удалён
        return conn.execute(text("SELECT count(*) FROM fuel_sheets")).scalar_one()

    assert run_on_db(scenario) == 2


def test_downgrade_from_sync_fields_keeps_live_rows(migrated_db: Config) -> None:
    """Откат 0004: удалённые строки стираются, живые остаются, UNIQUE месяца — снова constraint."""

    def prepare(conn: Connection) -> None:
        car_id = _insert_user_and_car(conn)
        deleted = _insert_sheet(conn, car_id, month=9)
        conn.execute(
            text("UPDATE fuel_sheets SET deleted_at = now() WHERE id = :id"), {"id": deleted}
        )
        _insert_sheet(conn, car_id, month=10)

    run_on_db(prepare)
    command.downgrade(migrated_db, "0003")

    def check(conn: Connection) -> tuple[list[int], list[list[str]]]:
        months = list(conn.execute(text("SELECT month FROM fuel_sheets")).scalars())
        unique = [u["column_names"] for u in inspect(conn).get_unique_constraints("fuel_sheets")]
        return months, unique

    result = run_on_db(check)
    # Обратно на head: остальные тесты создают таблицы через create_all, а он не добавляет
    # колонки в уже существующие таблицы.
    command.upgrade(migrated_db, "head")

    assert result == ([10], [["car_id", "year", "month"]])


def test_downgrade_removes_all_tables(migrated_db: Config) -> None:
    command.downgrade(migrated_db, "base")

    assert run_on_db(table_names) == {"alembic_version"}


def test_norms_keep_three_decimals(migrated_db: Config) -> None:
    def scenario(conn: Connection) -> tuple[str, str]:
        _insert_sheet(conn, _insert_user_and_car(conn, winter_norm="11.684"), season="WINTER")
        winter = conn.execute(text("SELECT norm_winter_l_per_100km FROM cars")).scalar_one()
        sheet_norm = conn.execute(text("SELECT norm_l_per_100km FROM fuel_sheets")).scalar_one()
        return str(winter), str(sheet_norm)

    assert run_on_db(scenario) == ("11.684", "10.068")
