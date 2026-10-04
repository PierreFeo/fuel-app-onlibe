import uuid
from datetime import UTC, datetime, timedelta

import jwt
import pytest

from app.core.config import Settings
from app.core.security import (
    create_access_token,
    create_refresh_token,
    generate_otp,
    hash_otp,
    otp_matches,
)
from app.services.rate_limit import SlidingWindowLimiter

NOW = datetime(2026, 10, 2, 8, 0, tzinfo=UTC)


def test_otp_is_six_digits() -> None:
    codes = {generate_otp() for _ in range(200)}

    assert all(len(c) == 6 and c.isdigit() for c in codes)
    assert len(codes) > 150  # коды действительно случайные


def test_otp_hash_does_not_contain_code_and_depends_on_pepper() -> None:
    code_hash = hash_otp("123456", "pepper-1")

    assert "123456" not in code_hash
    assert code_hash != hash_otp("123456", "pepper-2")
    assert otp_matches("123456", code_hash, "pepper-1")
    assert not otp_matches("654321", code_hash, "pepper-1")
    assert not otp_matches("123456", code_hash, "pepper-2")


def test_access_token_claims(settings: Settings) -> None:
    user_id = uuid.uuid4()

    token = create_access_token(user_id, NOW, settings)
    claims = jwt.decode(
        token, settings.jwt_secret, algorithms=["HS256"], options={"verify_exp": False}
    )

    assert claims["sub"] == str(user_id)
    assert claims["type"] == "access"
    assert claims["exp"] == int((NOW + timedelta(minutes=15)).timestamp())


def test_refresh_token_claims(settings: Settings) -> None:
    user_id, jti = uuid.uuid4(), uuid.uuid4()
    expires_at = NOW + timedelta(days=30)

    token = create_refresh_token(user_id, jti, expires_at, settings)
    claims = jwt.decode(
        token, settings.jwt_secret, algorithms=["HS256"], options={"verify_exp": False}
    )

    assert claims == {
        "sub": str(user_id),
        "type": "refresh",
        "jti": str(jti),
        "exp": int(expires_at.timestamp()),
    }


def test_token_signed_with_other_secret_is_rejected(settings: Settings) -> None:
    token = create_access_token(uuid.uuid4(), datetime.now(UTC), settings)

    with pytest.raises(jwt.InvalidSignatureError):
        jwt.decode(token, "another-secret-another-secret-12345", algorithms=["HS256"])


def test_sliding_window_limiter() -> None:
    limiter = SlidingWindowLimiter(limit=2, window=timedelta(hours=1))

    assert limiter.hit("1.1.1.1", NOW) is None
    assert limiter.hit("1.1.1.1", NOW + timedelta(minutes=10)) is None
    # третий раз за час — нельзя; повторить можно, когда «выпадет» первое событие
    assert limiter.hit("1.1.1.1", NOW + timedelta(minutes=20)) == 40 * 60
    assert limiter.hit("2.2.2.2", NOW + timedelta(minutes=20)) is None  # другой IP
    assert limiter.hit("1.1.1.1", NOW + timedelta(hours=1)) is None
