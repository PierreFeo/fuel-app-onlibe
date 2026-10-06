from fastapi import APIRouter, Response, status

from app.core.deps import AppSettings, CurrentUser, Db, Now
from app.schemas.me import MeOut, MeUpdateIn, PasswordChangeIn
from app.services import auth_service, user_service

router = APIRouter(prefix="/me", tags=["profile"])


@router.get("", response_model=MeOut)
async def get_me(user: CurrentUser) -> MeOut:
    """Профиль текущего пользователя."""
    return MeOut.of(user)


@router.patch("", response_model=MeOut)
async def update_me(body: MeUpdateIn, user: CurrentUser, db: Db) -> MeOut:
    """Изменить имя."""
    return MeOut.of(await user_service.update_name(db, user, body.name))


@router.put("/password", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(
    body: PasswordChangeIn, user: CurrentUser, db: Db, now: Now, settings: AppSettings
) -> Response:
    """Задать пароль для входа без SMS (первый раз) или сменить его (нужен текущий пароль)."""
    await auth_service.change_password(
        db,
        user,
        current_password=body.current_password,
        new_password=body.new_password,
        now=now,
        settings=settings,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
