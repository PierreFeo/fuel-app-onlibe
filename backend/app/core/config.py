from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки приложения. Читаются из переменных окружения и файла .env."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: Literal["dev", "prod"] = "dev"
    api_v1_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel"
    test_database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel_test"

    sms_provider: Literal["console", "smsgate"] = "console"

    # Токены (docs/05_AUTH_SMS.md, «Токены»)
    jwt_secret: str = Field(min_length=32)
    access_token_ttl_min: int = 15
    refresh_token_ttl_days: int = 30

    # Коды из SMS и ограничения частоты (docs/05_AUTH_SMS.md)
    otp_pepper: str = Field(min_length=8)
    otp_ttl_sec: int = 300
    otp_resend_sec: int = 60
    otp_max_per_phone_hour: int = 5
    otp_max_per_ip_hour: int = 20

    # Кто может войти
    registration_mode: Literal["whitelist", "open"] = "whitelist"
    allowed_phones: str = ""  # через запятую: +79991234567,+79997654321
    default_phone_region: str = "RU"

    @property
    def is_dev(self) -> bool:
        return self.env == "dev"

    @property
    def allowed_phones_list(self) -> list[str]:
        return [p.strip() for p in self.allowed_phones.split(",") if p.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
