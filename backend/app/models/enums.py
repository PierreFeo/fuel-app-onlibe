from enum import StrEnum


class FuelType(StrEnum):
    AI92 = "AI92"
    AI95 = "AI95"
    AI98 = "AI98"
    DIESEL = "DIESEL"
    GAS = "GAS"


class SheetStatus(StrEnum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"


class Season(StrEnum):
    """Сезон листа: выбирает летнюю или зимнюю норму авто (docs/06_BUSINESS_RULES.md)."""

    SUMMER = "SUMMER"
    WINTER = "WINTER"


class PaymentType(StrEnum):
    PERSONAL = "PERSONAL"
    FUEL_CARD = "FUEL_CARD"
    COMPANY = "COMPANY"
