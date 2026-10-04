"""seasonal norms

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-04 05:11:35.263856

Сезонные нормы (docs/06_BUSINESS_RULES.md, «Сезон и норма листа»): зимняя норма у авто,
нормы с 3 знаками после точки, сезон у листа.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# CHECK для enum-поля задан явно (ck_*), как в 0001, поэтому в sa.Enum create_constraint=False.
SEASON = sa.Enum(
    "SUMMER", "WINTER", name="season", native_enum=False, create_constraint=False, length=6
)


def upgrade() -> None:
    op.add_column(
        "cars",
        sa.Column("norm_winter_l_per_100km", sa.Numeric(precision=6, scale=3), nullable=True),
    )
    op.create_check_constraint(
        op.f("ck_cars_norm_winter_positive"),
        "cars",
        "norm_winter_l_per_100km IS NULL OR norm_winter_l_per_100km > 0",
    )
    op.alter_column(
        "cars",
        "norm_l_per_100km",
        existing_type=sa.NUMERIC(precision=5, scale=2),
        type_=sa.Numeric(precision=6, scale=3),
        existing_nullable=False,
    )

    # Уже существующим листам (если есть) — лето; дальше значение всегда задаёт приложение.
    op.add_column(
        "fuel_sheets", sa.Column("season", SEASON, server_default="SUMMER", nullable=False)
    )
    op.alter_column("fuel_sheets", "season", server_default=None)
    op.create_check_constraint(
        op.f("ck_fuel_sheets_season"), "fuel_sheets", "season IN ('SUMMER', 'WINTER')"
    )
    op.alter_column(
        "fuel_sheets",
        "norm_l_per_100km",
        existing_type=sa.NUMERIC(precision=5, scale=2),
        type_=sa.Numeric(precision=6, scale=3),
        existing_nullable=False,
    )


def downgrade() -> None:
    # Обратно к 2 знакам: нормы округляются (10.068 → 10.07).
    op.alter_column(
        "fuel_sheets",
        "norm_l_per_100km",
        existing_type=sa.Numeric(precision=6, scale=3),
        type_=sa.NUMERIC(precision=5, scale=2),
        existing_nullable=False,
    )
    op.drop_constraint(op.f("ck_fuel_sheets_season"), "fuel_sheets", type_="check")
    op.drop_column("fuel_sheets", "season")
    op.alter_column(
        "cars",
        "norm_l_per_100km",
        existing_type=sa.Numeric(precision=6, scale=3),
        type_=sa.NUMERIC(precision=5, scale=2),
        existing_nullable=False,
    )
    op.drop_constraint(op.f("ck_cars_norm_winter_positive"), "cars", type_="check")
    op.drop_column("cars", "norm_winter_l_per_100km")
