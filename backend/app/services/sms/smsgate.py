import logging

import httpx

logger = logging.getLogger(__name__)


class SmsGateSender:
    """Настоящая отправка SMS через SMSGate (docs/05_AUTH_SMS.md, «SMSGate: как отправляется SMS»).

    Облако SMSGate передаёт сообщение на домашний телефон, а тот отправляет SMS со своей SIM.
    Любой ответ не 2xx, сетевая ошибка или таймаут — исключение; auth_service превращает его
    в 502 SMS_SEND_FAILED.
    """

    def __init__(
        self,
        *,
        base_url: str,
        username: str,
        password: str,
        timeout_sec: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,  # в тестах — поддельный HTTP
    ) -> None:
        self._url = base_url.rstrip("/") + "/messages"
        self._auth = httpx.BasicAuth(username, password)
        self._timeout = timeout_sec
        self._transport = transport

    async def send(self, phone: str, text: str) -> None:
        payload = {"textMessage": {"text": text}, "phoneNumbers": [phone]}
        async with httpx.AsyncClient(
            auth=self._auth, timeout=self._timeout, transport=self._transport
        ) as client:
            response = await client.post(self._url, json=payload)
        response.raise_for_status()
        # Текст SMS (в нём код) не логируем — только id сообщения в SMSGate.
        logger.info("SMS queued in SMSGate: id=%s", response.json().get("id"))
