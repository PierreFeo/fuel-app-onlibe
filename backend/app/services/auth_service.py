"""Вход по коду из SMS (docs/05_AUTH_SMS.md)."""

import logging
import math
import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AppError, ErrorCode
from app.core.security import (
    create_access_token,
    create_refresh_token,
    generate_otp,
    hash_otp,
    otp_matches,
)
from app.models import OtpCode, RefreshToken, User
from app.schemas.auth import RequestCodeOut, TokensOut, UserOut, VerifyCodeOut
from app.services.phone import DEV_TEST_PHONE, normalize_phone
from app.services.rate_limit import SlidingWindowLimiter
from app.services.sms import SmsSender

logger = logging.getLogger(__name__)

OTP_MAX_ATTEMPTS = 5  # совпадает с CHECK-ограничением в таблице otp_codes
DEV_TEST_CODE = "000000"
SMS_TEXT = "Код входа в Учёт топлива: {code}. Никому не сообщайте."


def _rate_limited(retry_after_sec: int) -> AppError:
    return AppError(
        ErrorCode.RATE_LIMITED,
        "Слишком много запросов, повторите позже",
        429,
        {"retry_after_sec": retry_after_sec},
    )


def _seconds_until(moment: datetime, now: datetime) -> int:
    return max(1, math.ceil((moment - now).total_seconds()))


def _is_dev_test_phone(phone: str, settings: Settings) -> bool:
    return settings.is_dev and phone == DEV_TEST_PHONE


def _normalize(raw_phone: str, settings: Settings) -> str:
    return normalize_phone(
        raw_phone, settings.default_phone_region, allow_dev_test_phone=settings.is_dev
    )


def _allowed_phones(settings: Settings) -> set[str]:
    """ALLOWED_PHONES в формате E.164. Опечатка в .env не должна ломать вход остальным."""
    phones: set[str] = set()
    for raw in settings.allowed_phones_list:
        try:
            phones.add(normalize_phone(raw, settings.default_phone_region))
        except AppError:
            logger.warning("ALLOWED_PHONES: invalid phone %r skipped", raw)
    return phones


async def _get_user(session: AsyncSession, phone: str) -> User | None:
    return await session.scalar(select(User).where(User.phone == phone))


async def _ensure_phone_allowed(session: AsyncSession, phone: str, settings: Settings) -> None:
    """Белый список: номер из ALLOWED_PHONES или уже есть в users. Отключённых не пускаем."""
    user = await _get_user(session, phone)
    if user is not None:
        allowed = user.is_active
    elif settings.registration_mode == "open":
        allowed = True
    else:
        allowed = phone in _allowed_phones(settings)
    if not allowed:
        raise AppError(
            ErrorCode.PHONE_NOT_ALLOWED, "Этот номер не зарегистрирован в организации", 403
        )


async def _ensure_phone_rate_limit(
    session: AsyncSession, phone: str, now: datetime, settings: Settings
) -> None:
    """Не чаще 1 кода в OTP_RESEND_SEC и не более OTP_MAX_PER_PHONE_HOUR кодов в час."""
    count, oldest, latest = (
        await session.execute(
            select(func.count(), func.min(OtpCode.created_at), func.max(OtpCode.created_at)).where(
                OtpCode.phone == phone, OtpCode.created_at > now - timedelta(hours=1)
            )
        )
    ).one()
    resend = timedelta(seconds=settings.otp_resend_sec)
    if latest is not None and latest + resend > now:
        raise _rate_limited(_seconds_until(latest + resend, now))
    if count >= settings.otp_max_per_phone_hour:
        raise _rate_limited(_seconds_until(oldest + timedelta(hours=1), now))


async def request_code(
    session: AsyncSession,
    *,
    raw_phone: str,
    client_ip: str,
    now: datetime,
    settings: Settings,
    sms_sender: SmsSender,
    ip_limiter: SlidingWindowLimiter,
) -> RequestCodeOut:
    phone = _normalize(raw_phone, settings)
    response = RequestCodeOut(
        expires_in_sec=settings.otp_ttl_sec, resend_after_sec=settings.otp_resend_sec
    )
    if _is_dev_test_phone(phone, settings):
        return response  # тестовый номер: SMS не шлём, код всегда 000000

    retry_after = ip_limiter.hit(client_ip, now)
    if retry_after is not None:
        raise _rate_limited(retry_after)
    await _ensure_phone_allowed(session, phone, settings)
    await _ensure_phone_rate_limit(session, phone, now, settings)

    # Новый код отменяет все прежние коды этого номера.
    await session.execute(
        update(OtpCode).where(OtpCode.phone == phone, OtpCode.used_at.is_(None)).values(used_at=now)
    )
    code = generate_otp()
    otp = OtpCode(
        phone=phone,
        code_hash=hash_otp(code, settings.otp_pepper),
        expires_at=now + timedelta(seconds=settings.otp_ttl_sec),
        created_at=now,
    )
    session.add(otp)
    await session.commit()

    try:
        await sms_sender.send(phone, SMS_TEXT.format(code=code))
    except Exception:
        logger.exception("SMS send failed for %s", phone)
        otp.used_at = now
        await session.commit()
        raise AppError(
            ErrorCode.SMS_SEND_FAILED, "Не удалось отправить SMS, попробуйте позже", 502
        ) from None
    return response


async def _check_code(
    session: AsyncSession, phone: str, code: str, now: datetime, settings: Settings
) -> None:
    if _is_dev_test_phone(phone, settings):
        if code != DEV_TEST_CODE:
            raise AppError(ErrorCode.OTP_INVALID, "Неверный код", 401)
        return

    otp = await session.scalar(
        select(OtpCode)
        .where(OtpCode.phone == phone, OtpCode.used_at.is_(None))
        .order_by(OtpCode.created_at.desc())
        .limit(1)
    )
    if otp is None or otp.expires_at <= now or otp.attempts >= OTP_MAX_ATTEMPTS:
        raise AppError(ErrorCode.OTP_EXPIRED, "Код истёк — запросите новый", 401)
    if not otp_matches(code, otp.code_hash, settings.otp_pepper):
        otp.attempts += 1
        await session.commit()
        raise AppError(ErrorCode.OTP_INVALID, "Неверный код", 401)
    otp.used_at = now


def _issue_tokens(
    session: AsyncSession, user: User, now: datetime, settings: Settings
) -> TokensOut:
    """Выдать пару токенов; refresh-токен записывается в БД (его id = jti)."""
    refresh = RefreshToken(
        id=uuid.uuid4(),
        user_id=user.id,
        expires_at=now + timedelta(days=settings.refresh_token_ttl_days),
    )
    session.add(refresh)
    return TokensOut(
        access_token=create_access_token(user.id, now, settings),
        refresh_token=create_refresh_token(user.id, refresh.id, refresh.expires_at, settings),
        expires_in_sec=settings.access_token_ttl_min * 60,
        user=UserOut(id=user.id, phone=user.phone, name=user.name),
    )


async def verify_code(
    session: AsyncSession,
    *,
    raw_phone: str,
    code: str,
    now: datetime,
    settings: Settings,
) -> VerifyCodeOut:
    phone = _normalize(raw_phone, settings)
    await _check_code(session, phone, code, now, settings)

    user = await _get_user(session, phone)
    is_new_user = user is None
    if user is None:
        user = User(id=uuid.uuid4(), phone=phone)
        session.add(user)
        await session.flush()  # пользователь должен попасть в БД раньше ссылающегося токена
    tokens = _issue_tokens(session, user, now, settings)
    await session.commit()
    return VerifyCodeOut(**tokens.model_dump(), is_new_user=is_new_user)
