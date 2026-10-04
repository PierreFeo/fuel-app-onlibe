from fastapi import APIRouter

from app.api.v1 import auth, cars, health, me, sheets

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(auth.router)
api_router.include_router(me.router)
api_router.include_router(cars.router)
api_router.include_router(sheets.router)
