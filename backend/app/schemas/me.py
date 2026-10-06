from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.auth import UserOut

# профиль: { id, phone, name, has_password } — та же структура, что user в ответе входа
MeOut = UserOut


class MeUpdateIn(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str = Field(min_length=1, max_length=100, examples=["Иван Петров"])


class PasswordChangeIn(BaseModel):
    """Пробелы НЕ обрезаются: пароль сохраняется ровно таким, каким его ввели."""

    current_password: str | None = Field(default=None, max_length=128)  # если пароль уже есть
    new_password: str = Field(min_length=8, max_length=64, examples=["мой-пароль-2026"])

    @field_validator("new_password")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Пароль не может состоять только из пробелов")
        return value
