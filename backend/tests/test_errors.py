from collections.abc import AsyncIterator

import pytest
from fastapi import FastAPI, HTTPException
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

from app.core.errors import AppError, ErrorCode, register_error_handlers


class _Payload(BaseModel):
    phone: str = Field(min_length=3)
    liters: int


def _make_test_app() -> FastAPI:
    """Маленькое приложение со служебными маршрутами — в боевой API они не попадают."""
    app = FastAPI()
    register_error_handlers(app)

    @app.get("/app-error")
    async def app_error() -> None:
        raise AppError(ErrorCode.RATE_LIMITED, "Слишком часто", 429, {"retry_after_sec": 45})

    @app.post("/validate")
    async def validate(payload: _Payload) -> dict[str, str]:
        return {"ok": "yes"}

    @app.get("/http-401")
    async def http_401() -> None:
        raise HTTPException(status_code=401, headers={"WWW-Authenticate": "Bearer"})

    @app.get("/boom")
    async def boom() -> None:
        raise RuntimeError("secret internal detail")

    return app


@pytest.fixture
async def test_client() -> AsyncIterator[AsyncClient]:
    # raise_app_exceptions=False: иначе httpx пробросит RuntimeError в тест,
    # а нам нужно увидеть ответ 500, который получит клиент.
    transport = ASGITransport(app=_make_test_app(), raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def test_app_error_uses_contract_format(test_client: AsyncClient) -> None:
    response = await test_client.get("/app-error")

    assert response.status_code == 429
    assert response.json() == {
        "error": {
            "code": "RATE_LIMITED",
            "message": "Слишком часто",
            "details": {"retry_after_sec": 45},
        }
    }


async def test_validation_error_is_400_with_field_details(test_client: AsyncClient) -> None:
    response = await test_client.post("/validate", json={"phone": "1"})

    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == "VALIDATION_ERROR"
    assert set(error["details"]) == {"phone", "liters"}
    assert all(isinstance(reason, str) for reason in error["details"].values())


async def test_invalid_json_body_is_400(test_client: AsyncClient) -> None:
    response = await test_client.post(
        "/validate", content="{not json", headers={"Content-Type": "application/json"}
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_unknown_url_is_not_found(test_client: AsyncClient) -> None:
    response = await test_client.get("/no-such-path")

    assert response.status_code == 404
    assert response.json() == {
        "error": {"code": "NOT_FOUND", "message": "Ресурс не найден", "details": {}}
    }


async def test_wrong_method_is_not_found(test_client: AsyncClient) -> None:
    response = await test_client.delete("/app-error")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "NOT_FOUND"


async def test_http_401_is_unauthorized_and_keeps_headers(test_client: AsyncClient) -> None:
    response = await test_client.get("/http-401")

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
    assert response.headers["WWW-Authenticate"] == "Bearer"


async def test_unhandled_exception_is_500_without_leaking_details(
    test_client: AsyncClient,
) -> None:
    response = await test_client.get("/boom")

    assert response.status_code == 500
    body = response.json()
    assert body["error"]["code"] == "INTERNAL_ERROR"
    assert "secret" not in response.text


async def test_real_app_returns_contract_404(client: AsyncClient) -> None:
    response = await client.get("/api/v1/does-not-exist")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "NOT_FOUND"
