import phonenumbers

from app.core.errors import AppError, ErrorCode

# Тестовый номер для разработки (принимает код 000000 только при ENV=dev).
DEV_TEST_PHONE = "+70000000000"


def normalize_phone(raw: str, region: str, *, allow_dev_test_phone: bool = False) -> str:
    """Привести номер к E.164: `8 999 123-45-67` → `+79991234567`. Неверный номер → 400."""
    try:
        number = phonenumbers.parse(raw, region)
    except phonenumbers.NumberParseException:
        number = None

    if number is not None:
        e164 = phonenumbers.format_number(number, phonenumbers.PhoneNumberFormat.E164)
        # Тестовый номер phonenumbers считает несуществующим — пропускаем его отдельно.
        if phonenumbers.is_valid_number(number) or (
            allow_dev_test_phone and e164 == DEV_TEST_PHONE
        ):
            return e164

    raise AppError(
        ErrorCode.VALIDATION_ERROR,
        "Неверные данные запроса",
        400,
        {"phone": "Неверный номер телефона"},
    )
