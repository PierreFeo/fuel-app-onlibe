from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User


async def update_name(session: AsyncSession, user: User, name: str) -> User:
    user.name = name
    await session.commit()
    return user
