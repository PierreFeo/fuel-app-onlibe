# CLAUDE.md — backend

Читай вместе с корневым `../CLAUDE.md`. Контракт API: `../docs/04_API_CONTRACT.md`,
модель данных: `../docs/03_DATA_MODEL.md`, формулы: `../docs/06_BUSINESS_RULES.md`,
авторизация: `../docs/05_AUTH_SMS.md`.

## Стек
Python 3.12 · FastAPI · SQLAlchemy 2.0 async (asyncpg) · Alembic · Pydantic v2 · PyJWT ·
phonenumbers · httpx · pytest + pytest-asyncio · ruff. Зависимости — в `pyproject.toml`.

## Команды
```bash
cp .env.example .env                         # один раз
docker compose up -d --build                 # запустить api + db
docker compose logs -f api                   # логи (здесь видны DEV SMS-коды)
docker compose exec api alembic upgrade head # применить миграции
docker compose exec api alembic revision --autogenerate -m "описание"  # новая миграция
docker compose exec api pytest -q            # тесты
docker compose exec api ruff check . && docker compose exec api ruff format .
docker compose down                          # остановить (данные БД сохраняются в volume)
```
Swagger: http://localhost:8000/docs

## Правила кода
- Роутеры в `app/api/v1/` — тонкие: валидация через схемы, вызов сервиса, возврат схемы.
- Бизнес-логика — `app/services/`. Расчёты ЛУТ — чистые функции `app/services/sheet_calc.py`.
- Pydantic-схемы в `app/schemas/` точно повторяют JSON из контракта (имена, типы, nullable).
  Decimal сериализуется в строку с 2 знаками.
- Доступ к данным всегда фильтруется по `current_user.id` (через `car.user_id`). Чужое → 404.
- Все настройки — через `app/core/config.py` (pydantic-settings, чтение `.env`). Никаких
  захардкоженных URL, секретов, сроков.
- Ошибки — через собственное исключение `AppError(code, message, status, details)` и общий обработчик.
- Изменил модель → создай миграцию Alembic и проверь её глазами перед коммитом.
- Логи — модуль `logging`, без вывода токенов, кодов OTP (кроме ConsoleSmsSender в dev) и паролей.
- Новый эндпоинт = тесты: успех, 401, 404 (чужой), 400/422.

## Готовность задачи
1. `ruff check .` без ошибок. 2. `pytest` зелёный. 3. Swagger соответствует контракту.
4. Пользователю дано объяснение, как проверить руками.
