from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import SYNC_VERSION_SEQ
from app.models import User


async def update_name(session: AsyncSession, user: User, name: str) -> User:
    user.name = name
    # Новая версия имени — телефоны получат его при синхронизации (docs/03_DATA_MODEL.md).
    user.name_version = SYNC_VERSION_SEQ.next_value()
    await session.commit()
    # name_version посчитала БД — без refresh в async-режиме его не прочитать.
    await session.refresh(user)
    return user
