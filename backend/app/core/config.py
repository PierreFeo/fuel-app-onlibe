from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки приложения. Читаются из переменных окружения и файла .env."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: str = "dev"
    api_v1_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel"
    test_database_url: str = "postgresql+asyncpg://fuel:fuel@db:5432/fuel_test"


@lru_cache
def get_settings() -> Settings:
    return Settings()
