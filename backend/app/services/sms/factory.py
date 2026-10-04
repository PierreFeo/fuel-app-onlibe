from app.core.config import get_settings
from app.services.sms.base import SmsSender
from app.services.sms.console import ConsoleSmsSender


def get_sms_sender() -> SmsSender:
    """Зависимость FastAPI: отдаёт отправщика по SMS_PROVIDER. В тестах подменяется на фейк."""
    provider = get_settings().sms_provider
    if provider == "console":
        return ConsoleSmsSender()
    # SmsGateSender появится в задаче 6.2 (docs/08_ROADMAP.md).
    raise NotImplementedError(f"SMS_PROVIDER={provider} ещё не реализован")
