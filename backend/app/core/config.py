from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки приложения. Читаются из переменных окружения и файла .env."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: Literal["dev", "prod"] = "dev"
    api_v1_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel"
    test_database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel_test"

    # Отправка SMS (docs/05_AUTH_SMS.md). smsgate — настоящие SMS через домашний телефон;
    # off — SMS не отправляются, код всегда 000000 и только для номеров без пароля.
    sms_provider: Literal["console", "smsgate", "off"] = "console"
    smsgate_url: str = "https://api.sms-gate.app/3rdparty/v1"
    smsgate_username: str = ""
    smsgate_password: str = ""
    smsgate_timeout_sec: float = 10.0

    # Токены (docs/05_AUTH_SMS.md, «Токены»)
    jwt_secret: str = Field(min_length=32)
    access_token_ttl_min: int = 15
    refresh_token_ttl_days: int = 90

    # Коды из SMS и ограничения частоты (docs/05_AUTH_SMS.md)
    otp_pepper: str = Field(min_length=8)
    otp_ttl_sec: int = 300
    otp_resend_sec: int = 60
    otp_max_per_phone_hour: int = 5
    otp_max_per_ip_hour: int = 20

    # Запасной вход по паролю (docs/05_AUTH_SMS.md)
    password_max_attempts: int = 5  # неверных паролей подряд до блокировки
    password_lock_min: int = 15  # на сколько минут блокируется вход по паролю
    password_max_per_ip_hour: int = 20

    # Кто может войти
    registration_mode: Literal["whitelist", "open"] = "whitelist"
    allowed_phones: str = ""  # через запятую: +79991234567,+79997654321
    default_phone_region: str = "RU"

    @model_validator(mode="after")
    def _smsgate_needs_credentials(self) -> "Settings":
        # Без логина/пароля сервер запустился бы, а SMS падали бы при каждом входе — лучше сразу.
        if self.sms_provider == "smsgate" and not (self.smsgate_username and self.smsgate_password):
            raise ValueError("SMS_PROVIDER=smsgate требует SMSGATE_USERNAME и SMSGATE_PASSWORD")
        return self

    @property
    def is_dev(self) -> bool:
        return self.env == "dev"

    @property
    def allowed_phones_list(self) -> list[str]:
        return [p.strip() for p in self.allowed_phones.split(",") if p.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
