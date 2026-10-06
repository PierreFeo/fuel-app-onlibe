"""Вход по коду из SMS, запасной вход по паролю и работа с токенами (docs/05_AUTH_SMS.md)."""

import asyncio
import logging
import math
import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AppError, ErrorCode
from app.core.security import (
    InvalidTokenError,
    create_access_token,
    create_refresh_token,
    decode_token,
    generate_otp,
    hash_otp,
    otp_matches,
)
from app.models import OtpCode, RefreshToken, User
from app.schemas.auth import RequestCodeOut, TokensOut, UserOut, VerifyCodeOut
from app.services import password_service
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
        user=UserOut.of(user),
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


async def _find_active_refresh(
    session: AsyncSession, token: str, now: datetime, settings: Settings
) -> RefreshToken | None:
    """Запись refresh-токена, если он подлинный, не отозван и не истёк; иначе None."""
    try:
        claims = decode_token(token, "refresh", now, settings)
        jti = uuid.UUID(str(claims.get("jti")))
    except (InvalidTokenError, ValueError):
        return None
    stored = await session.get(RefreshToken, jti)
    if (
        stored is None
        or stored.user_id != claims["sub"]
        or stored.revoked_at is not None
        or stored.expires_at <= now
    ):
        return None
    return stored


async def refresh_tokens(
    session: AsyncSession, *, refresh_token: str, now: datetime, settings: Settings
) -> TokensOut:
    """Ротация: старый refresh-токен отзывается, выдаётся новая пара."""
    stored = await _find_active_refresh(session, refresh_token, now, settings)
    user = await session.get(User, stored.user_id) if stored is not None else None
    if stored is None or user is None or not user.is_active:
        raise AppError(ErrorCode.REFRESH_INVALID, "Сессия истекла — войдите заново", 401)
    stored.revoked_at = now
    tokens = _issue_tokens(session, user, now, settings)
    await session.commit()
    return tokens


async def logout(
    session: AsyncSession, *, refresh_token: str, now: datetime, settings: Settings
) -> None:
    """Отозвать refresh-токен. Недействительный токен — не ошибка: выйти можно всегда."""
    stored = await _find_active_refresh(session, refresh_token, now, settings)
    if stored is not None:
        stored.revoked_at = now
        await session.commit()


def _invalid_credentials() -> AppError:
    return AppError(ErrorCode.INVALID_CREDENTIALS, "Неверный номер или пароль", 401)


async def login_with_password(
    session: AsyncSession,
    *,
    raw_phone: str,
    password: str,
    client_ip: str,
    now: datetime,
    settings: Settings,
    ip_limiter: SlidingWindowLimiter,
) -> VerifyCodeOut:
    """Запасной вход по паролю. Порядок проверок — docs/05_AUTH_SMS.md."""
    phone = normalize_phone(raw_phone, settings.default_phone_region)

    retry_after = ip_limiter.hit(client_ip, now)
    if retry_after is not None:
        raise _rate_limited(retry_after)

    user = await _get_user(session, phone)
    if user is None or not user.is_active or user.password_hash is None:
        await asyncio.to_thread(password_service.burn_time)
        logger.warning("Password login failed (no such login) phone=%s ip=%s", phone, client_ip)
        raise _invalid_credentials()

    if user.locked_until is not None and user.locked_until > now:
        raise _rate_limited(_seconds_until(user.locked_until, now))

    # scrypt нарочно медленный — считаем в отдельном потоке, чтобы не тормозить другие запросы.
    ok = await asyncio.to_thread(password_service.verify_password, password, user.password_hash)
    if not ok:
        await _register_failed_attempt(session, user, now, settings)
        logger.warning("Password login failed (wrong password) phone=%s ip=%s", phone, client_ip)
        raise _invalid_credentials()

    user.failed_login_attempts = 0
    user.locked_until = None
    tokens = _issue_tokens(session, user, now, settings)
    await session.commit()
    return VerifyCodeOut(**tokens.model_dump(), is_new_user=user.name is None)


async def _register_failed_attempt(
    session: AsyncSession, user: User, now: datetime, settings: Settings
) -> None:
    """Неверный пароль: +1 к счётчику; на пятой ошибке — блокировка входа по паролю."""
    user.failed_login_attempts += 1
    if user.failed_login_attempts >= settings.password_max_attempts:
        user.failed_login_attempts = 0
        user.locked_until = now + timedelta(minutes=settings.password_lock_min)
    await session.commit()


async def change_password(
    session: AsyncSession,
    user: User,
    *,
    current_password: str | None,
    new_password: str,
    now: datetime,
    settings: Settings,
) -> None:
    """PUT /me/password: первый пароль — без текущего, смена — с ним (docs/04_API_CONTRACT.md).

    Неверный текущий пароль — неудачная попытка входа (общий счётчик и блокировка):
    иначе с чужого разблокированного телефона можно было бы подбирать пароль без ограничений.
    """
    if user.password_hash is not None:
        if user.locked_until is not None and user.locked_until > now:
            raise _rate_limited(_seconds_until(user.locked_until, now))
        ok = current_password is not None and await asyncio.to_thread(
            password_service.verify_password, current_password, user.password_hash
        )
        if not ok:
            await _register_failed_attempt(session, user, now, settings)
            logger.warning("Password change failed (wrong current password) user=%s", user.id)
            raise AppError(
                ErrorCode.VALIDATION_ERROR,
                "Неверный текущий пароль",
                400,
                {"current_password": "Неверный пароль"},
            )
    user.password_hash = await asyncio.to_thread(password_service.hash_password, new_password)
    user.failed_login_attempts = 0
    user.locked_until = None
    await session.commit()
