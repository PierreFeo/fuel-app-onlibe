from fastapi import APIRouter

from app.core.deps import CurrentUser, Db, Now
from app.schemas.sync import SyncIn, SyncOut
from app.services import sync_service

router = APIRouter(prefix="/sync", tags=["sync"])


@router.post("", response_model=SyncOut)
async def sync(body: SyncIn, user: CurrentUser, db: Db, now: Now) -> SyncOut:
    """Принять изменения с телефона и отдать изменения с сервера после `cursor`.

    Записи, не прошедшие проверку, не сохраняются и перечислены в `rejected`, остальные — приняты.
    """
    return await sync_service.sync(db, user, body, now=now)
