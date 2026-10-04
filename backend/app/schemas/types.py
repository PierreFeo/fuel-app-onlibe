"""Общие типы полей API."""

from decimal import Decimal
from typing import Annotated

from pydantic import Field, PlainSerializer

# Дробные числа (docs/04_API_CONTRACT.md): на вход — строка или число ("50", "8.5", 8.5),
# на выход — всегда строка с фиксированным числом знаков. Ограничения конкретного поля
# добавляются сверху:
#     Annotated[Decimal2, Field(gt=0, max_digits=6)]   ← как NUMERIC(6,2) в БД

# Литры и деньги: не больше 2 знаков после точки, в ответе "50.00".
Decimal2 = Annotated[
    Decimal,
    Field(decimal_places=2),
    PlainSerializer(lambda d: f"{d:.2f}", return_type=str, when_used="json"),
]

# Нормы и расход на 100 км: не больше 3 знаков, в ответе "10.068".
Decimal3 = Annotated[
    Decimal,
    Field(decimal_places=3),
    PlainSerializer(lambda d: f"{d:.3f}", return_type=str, when_used="json"),
]

# Пробег — целые км. Верхняя граница — с запасом, но влезает в INTEGER PostgreSQL.
Odometer = Annotated[int, Field(ge=0, le=9_999_999, examples=[52340])]
