# Импорт всех моделей — чтобы они попали в Base.metadata (нужно Alembic).
from app.models.car import Car
from app.models.fuel_sheet import FuelSheet
from app.models.otp_code import OtpCode
from app.models.refresh_token import RefreshToken
from app.models.refueling import Refueling
from app.models.user import User

__all__ = ["Car", "FuelSheet", "OtpCode", "RefreshToken", "Refueling", "User"]
