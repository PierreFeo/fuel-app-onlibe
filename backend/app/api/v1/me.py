from fastapi import APIRouter

from app.core.deps import CurrentUser, Db
from app.models import User
from app.schemas.me import MeOut, MeUpdateIn
from app.services import user_service

router = APIRouter(prefix="/me", tags=["profile"])


def _to_out(user: User) -> MeOut:
    return MeOut(id=user.id, phone=user.phone, name=user.name)


@router.get("", response_model=MeOut)
async def get_me(user: CurrentUser) -> MeOut:
    """Профиль текущего пользователя."""
    return _to_out(user)


@router.patch("", response_model=MeOut)
async def update_me(body: MeUpdateIn, user: CurrentUser, db: Db) -> MeOut:
    """Изменить имя."""
    return _to_out(await user_service.update_name(db, user, body.name))
