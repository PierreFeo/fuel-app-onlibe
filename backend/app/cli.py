"""Консольные команды для сервера (docs/05_AUTH_SMS.md, «Кто и как выдаёт пароль»).

docker compose exec api python -m app.cli set-password --phone "+79991234567"
docker compose exec api python -m app.cli disable-password --phone "+79991234567"
"""

import argparse
import asyncio
import sys

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.services import password_service
from app.services.phone import normalize_phone


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m app.cli", description="Учёт топлива: команды")
    commands = parser.add_subparsers(dest="command", required=True)
    set_pw = commands.add_parser("set-password", help="выдать или сбросить пароль сотруднику")
    set_pw.add_argument("--phone", required=True, help="номер телефона, напр. +79991234567")
    disable = commands.add_parser("disable-password", help="выключить вход по паролю")
    disable.add_argument("--phone", required=True, help="номер телефона, напр. +79991234567")
    return parser


async def run(
    argv: list[str], session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> int:
    """Выполнить команду. Возвращает код завершения: 0 — успех, 1 — ошибка."""
    args = _parser().parse_args(argv)
    try:
        phone = normalize_phone(
            args.phone, settings.default_phone_region, allow_dev_test_phone=settings.is_dev
        )
    except AppError:
        print(f"Ошибка: неверный номер телефона {args.phone!r}", file=sys.stderr)
        return 1

    async with session_factory() as session:
        if args.command == "set-password":
            password, created = await password_service.set_password(session, phone)
            if created:
                print(f"Создан новый пользователь {phone}.")
            print(f"Пароль для {phone}: {password}")
            print("Пароль показывается один раз — передайте его сотруднику.")
            return 0

        if not await password_service.disable_password(session, phone):
            print(f"Ошибка: пользователь {phone} не найден", file=sys.stderr)
            return 1
        print(f"Вход по паролю для {phone} выключен.")
        return 0


def main() -> None:
    from app.db.session import SessionLocal, engine

    async def _main() -> int:
        try:
            return await run(sys.argv[1:], SessionLocal, get_settings())
        finally:
            await engine.dispose()

    sys.exit(asyncio.run(_main()))


if __name__ == "__main__":
    main()
