"""Общие типы полей API."""

from decimal import Decimal
from typing import Annotated

from pydantic import Field, PlainSerializer

# Литры, деньги, нормы (docs/04_API_CONTRACT.md): на вход — "50", "50.5", "50.50" (или число),
# не больше 2 знаков после точки; на выход — всегда строка с 2 знаками: "50.00".
# Ограничения конкретного поля добавляются сверху:
#     Annotated[Decimal2, Field(gt=0, max_digits=6)]   ← как NUMERIC(6,2) в БД
Decimal2 = Annotated[
    Decimal,
    Field(decimal_places=2),
    PlainSerializer(lambda d: f"{d:.2f}", return_type=str, when_used="json"),
]
