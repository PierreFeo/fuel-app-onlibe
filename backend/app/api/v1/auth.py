from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.clock import get_now
from app.core.config import Settings, get_settings
from app.db.session import get_db
from app.schemas.auth import RequestCodeIn, RequestCodeOut, VerifyCodeIn, VerifyCodeOut
from app.services import auth_service
from app.services.rate_limit import SlidingWindowLimiter, get_otp_ip_limiter
from app.services.sms import SmsSender, get_sms_sender

router = APIRouter(prefix="/auth", tags=["auth"])

Db = Annotated[AsyncSession, Depends(get_db)]
Now = Annotated[datetime, Depends(get_now)]
AppSettings = Annotated[Settings, Depends(get_settings)]


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
