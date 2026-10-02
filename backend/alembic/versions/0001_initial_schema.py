"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-10-02 13:26:46.337936

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # CHECK для enum-полей заданы явно ниже (ck_*), поэтому в sa.Enum create_constraint=False.
    op.create_table(
        "otp_codes",
        sa.Column("phone", sa.String(length=16), nullable=False),
        sa.Column("code_hash", sa.String(length=128), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("attempts", sa.Integer(), server_default="0", nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("attempts BETWEEN 0 AND 5", name=op.f("ck_otp_codes_attempts_range")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_otp_codes")),
    )
    op.create_index(op.f("ix_otp_codes_phone"), "otp_codes", ["phone"], unique=False)
    op.create_table(
        "users",
        sa.Column("phone", sa.String(length=16), nullable=False),
        sa.Column("name", sa.String(length=100), nullable=True),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
        sa.UniqueConstraint("phone", name=op.f("uq_users_phone")),
    )
    op.create_table(
        "cars",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=60), nullable=False),
        sa.Column("plate_number", sa.String(length=15), nullable=True),
        sa.Column(
            "fuel_type",
            sa.Enum(
                "AI92",
                "AI95",
                "AI98",
                "DIESEL",
                "GAS",
                name="fuel_type",
                native_enum=False,
                create_constraint=False,
                length=10,
            ),
            nullable=False,
        ),
        sa.Column("tank_capacity_l", sa.Numeric(precision=6, scale=2), nullable=False),
        sa.Column("norm_l_per_100km", sa.Numeric(precision=5, scale=2), nullable=False),
        sa.Column("is_archived", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "fuel_type IN ('AI92', 'AI95', 'AI98', 'DIESEL', 'GAS')", name=op.f("ck_cars_fuel_type")
        ),
        sa.CheckConstraint("norm_l_per_100km > 0", name=op.f("ck_cars_norm_positive")),
        sa.CheckConstraint("tank_capacity_l > 0", name=op.f("ck_cars_tank_capacity_positive")),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name=op.f("fk_cars_user_id_users")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_cars")),
    )
    op.create_index(op.f("ix_cars_user_id"), "cars", ["user_id"], unique=False)
    op.create_table(
        "refresh_tokens",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_refresh_tokens_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_refresh_tokens")),
    )
    op.create_index(op.f("ix_refresh_tokens_user_id"), "refresh_tokens", ["user_id"], unique=False)
    op.create_table(
        "fuel_sheets",
        sa.Column("car_id", sa.Uuid(), nullable=False),
        sa.Column("year", sa.SmallInteger(), nullable=False),
        sa.Column("month", sa.SmallInteger(), nullable=False),
        sa.Column("odometer_start_km", sa.Integer(), nullable=False),
        sa.Column("odometer_end_km", sa.Integer(), nullable=True),
        sa.Column("fuel_start_l", sa.Numeric(precision=8, scale=2), nullable=False),
        sa.Column("fuel_end_actual_l", sa.Numeric(precision=8, scale=2), nullable=True),
        sa.Column("norm_l_per_100km", sa.Numeric(precision=5, scale=2), nullable=False),
        sa.Column(
            "status",
            sa.Enum(
                "OPEN",
                "CLOSED",
                name="sheet_status",
                native_enum=False,
                create_constraint=False,
                length=10,
            ),
            server_default="OPEN",
            nullable=False,
        ),
        sa.Column("closed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('OPEN', 'CLOSED')", name=op.f("ck_fuel_sheets_sheet_status")
        ),
        sa.CheckConstraint(
            "fuel_end_actual_l IS NULL OR fuel_end_actual_l >= 0",
            name=op.f("ck_fuel_sheets_fuel_end_actual_non_negative"),
        ),
        sa.CheckConstraint(
            "fuel_start_l >= 0", name=op.f("ck_fuel_sheets_fuel_start_non_negative")
        ),
        sa.CheckConstraint("month BETWEEN 1 AND 12", name=op.f("ck_fuel_sheets_month_range")),
        sa.CheckConstraint("norm_l_per_100km > 0", name=op.f("ck_fuel_sheets_norm_positive")),
        sa.CheckConstraint(
            "odometer_end_km IS NULL OR odometer_end_km >= odometer_start_km",
            name=op.f("ck_fuel_sheets_odometer_end_gte_start"),
        ),
        sa.CheckConstraint(
            "odometer_start_km >= 0", name=op.f("ck_fuel_sheets_odometer_start_non_negative")
        ),
        sa.CheckConstraint("year BETWEEN 2020 AND 2100", name=op.f("ck_fuel_sheets_year_range")),
        sa.ForeignKeyConstraint(
            ["car_id"], ["cars.id"], name=op.f("fk_fuel_sheets_car_id_cars"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_fuel_sheets")),
        sa.UniqueConstraint(
            "car_id", "year", "month", name=op.f("uq_fuel_sheets_car_id_year_month")
        ),
    )
    op.create_table(
        "refuelings",
        sa.Column("sheet_id", sa.Uuid(), nullable=False),
        sa.Column("refueled_at", sa.Date(), nullable=False),
        sa.Column("liters", sa.Numeric(precision=8, scale=2), nullable=False),
        sa.Column("price_per_liter", sa.Numeric(precision=8, scale=2), nullable=False),
        sa.Column("total_cost", sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column("odometer_km", sa.Integer(), nullable=True),
        sa.Column("station", sa.String(length=100), nullable=True),
        sa.Column(
            "payment_type",
            sa.Enum(
                "PERSONAL",
                "FUEL_CARD",
                "COMPANY",
                name="payment_type",
                native_enum=False,
                create_constraint=False,
                length=12,
            ),
            server_default="PERSONAL",
            nullable=False,
        ),
        sa.Column("note", sa.String(length=255), nullable=True),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "payment_type IN ('PERSONAL', 'FUEL_CARD', 'COMPANY')",
            name=op.f("ck_refuelings_payment_type"),
        ),
        sa.CheckConstraint("liters > 0", name=op.f("ck_refuelings_liters_positive")),
        sa.CheckConstraint(
            "odometer_km IS NULL OR odometer_km >= 0",
            name=op.f("ck_refuelings_odometer_non_negative"),
        ),
        sa.CheckConstraint("price_per_liter >= 0", name=op.f("ck_refuelings_price_non_negative")),
        sa.CheckConstraint("total_cost >= 0", name=op.f("ck_refuelings_total_cost_non_negative")),
        sa.ForeignKeyConstraint(
            ["sheet_id"],
            ["fuel_sheets.id"],
            name=op.f("fk_refuelings_sheet_id_fuel_sheets"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_refuelings")),
    )
    op.create_index(op.f("ix_refuelings_sheet_id"), "refuelings", ["sheet_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_refuelings_sheet_id"), table_name="refuelings")
    op.drop_table("refuelings")
    op.drop_table("fuel_sheets")
    op.drop_index(op.f("ix_refresh_tokens_user_id"), table_name="refresh_tokens")
    op.drop_table("refresh_tokens")
    op.drop_index(op.f("ix_cars_user_id"), table_name="cars")
    op.drop_table("cars")
    op.drop_table("users")
    op.drop_index(op.f("ix_otp_codes_phone"), table_name="otp_codes")
    op.drop_table("otp_codes")
