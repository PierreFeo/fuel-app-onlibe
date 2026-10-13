from app.core.config import get_settings
from app.services.sms.base import SmsSender
from app.services.sms.console import ConsoleSmsSender
from app.services.sms.smsgate import SmsGateSender


def get_sms_sender() -> SmsSender:
    """Зависимость FastAPI: отдаёт отправщика по SMS_PROVIDER. В тестах подменяется на фейк."""
    settings = get_settings()
    if settings.sms_provider == "smsgate":
        return SmsGateSender(
            base_url=settings.smsgate_url,
            username=settings.smsgate_username,
            password=settings.smsgate_password,
            timeout_sec=settings.smsgate_timeout_sec,
        )
    return ConsoleSmsSender()
