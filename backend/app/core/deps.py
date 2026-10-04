from datetime import datetime
from typing import Annotated

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.clock import get_now
from app.core.config import Settings, get_settings
from app.core.errors import AppError, ErrorCode
from app.core.security import InvalidTokenError, decode_token
from app.db.session import get_db
from app.models import User

# auto_error=False: без заголовка отвечаем сами — в едином формате ошибок, а не 403 FastAPI.
_bearer = HTTPBearer(auto_error=False)

Db = Annotated[AsyncSession, Depends(get_db)]
Now = Annotated[datetime, Depends(get_now)]
AppSettings = Annotated[Settings, Depends(get_settings)]


def _unauthorized() -> AppError:
    return AppError(
        ErrorCode.UNAUTHORIZED,
        "Требуется авторизация",
        401,
        headers={"WWW-Authenticate": "Bearer"},
    )


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    db: Db,
    now: Now,
    settings: AppSettings,
) -> User:
    """Пользователь из access-токена в заголовке `Authorization: Bearer ...`."""
    if credentials is None:
        raise _unauthorized()
    try:
        claims = decode_token(credentials.credentials, "access", now, settings)
    except InvalidTokenError:
        raise _unauthorized() from None
    user = await db.get(User, claims["sub"])
    if user is None or not user.is_active:
        raise _unauthorized()
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]
