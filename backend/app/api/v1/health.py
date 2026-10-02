from fastapi import APIRouter

from app.schemas.health import HealthOut

router = APIRouter(tags=["service"])


@router.get("/health", response_model=HealthOut)
async def health() -> HealthOut:
    return HealthOut(status="ok")
