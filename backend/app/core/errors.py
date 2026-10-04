"""Единый формат ошибок API (см. docs/04_API_CONTRACT.md, раздел «Формат ошибок»).

Любая ошибка отдаётся клиенту как:
    { "error": { "code": "...", "message": "...", "details": {} } }
"""

import logging
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger(__name__)


class ErrorCode(StrEnum):
    VALIDATION_ERROR = "VALIDATION_ERROR"
    UNAUTHORIZED = "UNAUTHORIZED"
    OTP_INVALID = "OTP_INVALID"
    OTP_EXPIRED = "OTP_EXPIRED"
    REFRESH_INVALID = "REFRESH_INVALID"
    PHONE_NOT_ALLOWED = "PHONE_NOT_ALLOWED"
    NOT_FOUND = "NOT_FOUND"
    SHEET_EXISTS = "SHEET_EXISTS"
    SHEET_CLOSED = "SHEET_CLOSED"
    BUSINESS_RULE = "BUSINESS_RULE"
    RATE_LIMITED = "RATE_LIMITED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    SMS_SEND_FAILED = "SMS_SEND_FAILED"


class AppError(Exception):
    """Ошибка, которую сервисы бросают, чтобы вернуть клиенту ответ из контракта."""

    def __init__(
        self,
        code: ErrorCode,
        message: str,
        status: int,
        details: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.details = details or {}
        self.headers = headers


def error_response(
    status: int,
    code: ErrorCode,
    message: str,
    details: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message, "details": details or {}}},
        headers=headers,
    )


def _field_name(loc: tuple[Any, ...]) -> str:
    # loc выглядит как ("body", "phone") или ("query", "page"); источник отбрасываем.
    parts = [str(p) for p in loc[1:]] if len(loc) > 1 else [str(p) for p in loc]
    return ".".join(parts)


async def _app_error_handler(_: Request, exc: AppError) -> JSONResponse:
    return error_response(exc.status, exc.code, exc.message, exc.details, exc.headers)


async def _validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    details: dict[str, str] = {}
    for err in exc.errors():
        # Для одного поля оставляем первую причину — её и покажет клиент.
        details.setdefault(_field_name(tuple(err["loc"])), err["msg"])
    return error_response(400, ErrorCode.VALIDATION_ERROR, "Неверные данные запроса", details)


async def _http_error_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    # Ошибки самого фреймворка: неизвестный URL, неподдерживаемый метод и т. п.
    if exc.status_code in (404, 405):
        return error_response(404, ErrorCode.NOT_FOUND, "Ресурс не найден")
    if exc.status_code == 401:
        return error_response(
            401, ErrorCode.UNAUTHORIZED, "Требуется авторизация", headers=exc.headers
        )
    if exc.status_code >= 500:
        return error_response(exc.status_code, ErrorCode.INTERNAL_ERROR, "Ошибка сервера")
    return error_response(exc.status_code, ErrorCode.VALIDATION_ERROR, str(exc.detail))


async def _unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled error on %s %s", request.method, request.url.path)
    return error_response(500, ErrorCode.INTERNAL_ERROR, "Внутренняя ошибка сервера")


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, _app_error_handler)
    app.add_exception_handler(RequestValidationError, _validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, _http_error_handler)
    app.add_exception_handler(Exception, _unhandled_error_handler)
