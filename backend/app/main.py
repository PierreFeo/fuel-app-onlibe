import logging

from fastapi import FastAPI

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.errors import register_error_handlers

# Без этого логи уровня INFO (в т. ч. «[DEV SMS]») не видны в `docker compose logs api`.
logging.basicConfig(level=logging.INFO, format="%(levelname)s:     %(name)s - %(message)s")


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="Fuel Tracker API", version="0.1.0")
    register_error_handlers(app)
    app.include_router(api_router, prefix=settings.api_v1_prefix)
    return app


app = create_app()
