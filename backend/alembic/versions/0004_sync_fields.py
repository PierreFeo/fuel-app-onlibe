"""sync fields

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-06 12:00:00

Поля синхронизации (docs/03_DATA_MODEL.md): общая последовательность версий, `version` и
`deleted_at` у авто, листов и заправок, версия имени пользователя; уникальность листа на месяц —
только среди неудалённых.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

SYNC_TABLES = ("cars", "fuel_sheets", "refuelings")
NEXT_VERSION = sa.text("nextval('sync_version_seq')")


def upgrade() -> None:
    op.execute(sa.schema.CreateSequence(sa.Sequence("sync_version_seq")))

    for table in SYNC_TABLES:
        # Уже существующие строки получают версии из последовательности (порядок не важен).
        op.add_column(
            table,
            sa.Column("version", sa.BigInteger(), server_default=NEXT_VERSION, nullable=False),
        )
        op.add_column(table, sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True))
        op.create_index(op.f(f"ix_{table}_version"), table, ["version"])

    op.add_column("users", sa.Column("name_version", sa.BigInteger(), nullable=True))

    op.drop_constraint(op.f("uq_fuel_sheets_car_id_year_month"), "fuel_sheets", type_="unique")
    op.create_index(
        "uq_fuel_sheets_car_id_year_month",
        "fuel_sheets",
        ["car_id", "year", "month"],
        unique=True,
        postgresql_where=sa.text("deleted_at IS NULL"),
    )


def downgrade() -> None:
    # Мягко удалённые строки при откате стираются: без deleted_at их не отличить от живых.
    op.execute("DELETE FROM refuelings WHERE deleted_at IS NOT NULL")
    op.execute("DELETE FROM fuel_sheets WHERE deleted_at IS NOT NULL")
    op.execute("DELETE FROM cars WHERE deleted_at IS NOT NULL")

    op.drop_index("uq_fuel_sheets_car_id_year_month", table_name="fuel_sheets")
    op.create_unique_constraint(
        op.f("uq_fuel_sheets_car_id_year_month"), "fuel_sheets", ["car_id", "year", "month"]
    )

    op.drop_column("users", "name_version")

    for table in reversed(SYNC_TABLES):
        op.drop_index(op.f(f"ix_{table}_version"), table_name=table)
        op.drop_column(table, "deleted_at")
        op.drop_column(table, "version")

    op.execute(sa.schema.DropSequence(sa.Sequence("sync_version_seq")))
