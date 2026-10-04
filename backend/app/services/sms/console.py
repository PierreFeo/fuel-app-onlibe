import logging

logger = logging.getLogger(__name__)


class ConsoleSmsSender:
    """Для разработки: ничего не отправляет, только пишет SMS в лог (docker compose logs api)."""

    async def send(self, phone: str, text: str) -> None:
        logger.info("[DEV SMS] %s : %s", phone, text)
