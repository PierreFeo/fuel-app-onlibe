import pytest

from app.core.errors import AppError
from app.services.phone import DEV_TEST_PHONE, normalize_phone


@pytest.mark.parametrize(
    "raw",
    ["+79991234567", "89991234567", "8 999 123-45-67", "+7 (999) 123-45-67", "9991234567"],
)
def test_normalizes_russian_formats_to_e164(raw: str) -> None:
    assert normalize_phone(raw, "RU") == "+79991234567"


@pytest.mark.parametrize("raw", ["12345", "abc", "+7999", ""])
def test_invalid_phone_is_validation_error(raw: str) -> None:
    with pytest.raises(AppError) as exc:
        normalize_phone(raw, "RU")

    assert exc.value.status == 400
    assert exc.value.code == "VALIDATION_ERROR"
    assert "phone" in exc.value.details


def test_dev_test_phone_only_when_allowed() -> None:
    assert normalize_phone(DEV_TEST_PHONE, "RU", allow_dev_test_phone=True) == DEV_TEST_PHONE
    with pytest.raises(AppError):
        normalize_phone(DEV_TEST_PHONE, "RU")
