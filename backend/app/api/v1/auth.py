from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, status

from app.core.deps import AppSettings, Db, Now
from app.schemas.auth import (
    RefreshIn,
    RequestCodeIn,
    RequestCodeOut,
    TokensOut,
    VerifyCodeIn,
    VerifyCodeOut,
)
from app.services import auth_service
from app.services.rate_limit import SlidingWindowLimiter, get_otp_ip_limiter
from app.services.sms import SmsSender, get_sms_sender

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/request-code", response_model=RequestCodeOut)
async def request_code(
    body: RequestCodeIn,
    request: Request,
    db: Db,
    now: Now,
    settings: AppSettings,
    sms_sender: Annotated[SmsSender, Depends(get_sms_sender)],
    ip_limiter: Annotated[SlidingWindowLimiter, Depends(get_otp_ip_limiter)],
) -> RequestCodeOut:
    """Отправить 6-значный код входа по SMS."""
    client_ip = request.client.host if request.client else "unknown"
    return await auth_service.request_code(
        db,
        raw_phone=body.phone,
        client_ip=client_ip,
        now=now,
        settings=settings,
        sms_sender=sms_sender,
        ip_limiter=ip_limiter,
    )


@router.post("/verify-code", response_model=VerifyCodeOut)
async def verify_code(body: VerifyCodeIn, db: Db, now: Now, settings: AppSettings) -> VerifyCodeOut:
    """Проверить код из SMS и выдать токены."""
    return await auth_service.verify_code(
        db, raw_phone=body.phone, code=body.code, now=now, settings=settings
    )


@router.post("/refresh", response_model=TokensOut)
async def refresh(body: RefreshIn, db: Db, now: Now, settings: AppSettings) -> TokensOut:
    """Обменять refresh-токен на новую пару токенов (старый отзывается)."""
    return await auth_service.refresh_tokens(
        db, refresh_token=body.refresh_token, now=now, settings=settings
    )


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(body: RefreshIn, db: Db, now: Now, settings: AppSettings) -> Response:
    """Выйти: отозвать refresh-токен."""
    await auth_service.logout(db, refresh_token=body.refresh_token, now=now, settings=settings)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
