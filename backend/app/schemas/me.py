from pydantic import BaseModel, ConfigDict, Field

from app.schemas.auth import UserOut

MeOut = UserOut  # профиль: { id, phone, name } — та же структура, что user в ответе входа


class MeUpdateIn(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str = Field(min_length=1, max_length=100, examples=["Иван Петров"])
