# 02. Архитектура

## Принцип: офлайн прежде всего
```
┌──────────── Android-app ────────────┐                 ┌──────── сервер ────────┐
│ экраны → ViewModel → Repository     │                 │ FastAPI → PostgreSQL   │
│                        │            │  по кнопке      │ (только хранит данные, │
│              Room (основная копия)  │ ◀── /sync ────▶ │  ничего не считает)    │
│              расчёты ЛУТ (domain)   │  при входе,     │                        │
└─────────────────────────────────────┘  смене пароля   └────────────────────────┘
```
- **Источник правды — база Room на телефоне.** Все экраны читают и пишут только её; приложение
  полностью работает без интернета.
- **Расчёты ЛУТ и все бизнес-проверки — на телефоне** (`06_BUSINESS_RULES.md`).
- **Сервер — хранилище копии.** Принимает изменения и отдаёт их при `POST /sync`; проверяет
  только формат, владельца и целостность ссылок (`04_API_CONTRACT.md`).
- **Синхронизация — по кнопке.** Отправляются только изменённые записи; затем приходят
  изменения с сервера (нужно при смене телефона). Подробно — `04_API_CONTRACT.md`, «Синхронизация».
- **Гостевой режим** — то же приложение без входа: Room есть, синхронизации нет.

## Схема развёртывания
```
┌─────────────────┐   HTTPS/JSON    ┌──────────────────────── VDS ─────────────────────────┐
│ Android-app     │ ──────────────▶ │  Caddy (HTTPS, домен) ─▶ FastAPI (api) ─▶ PostgreSQL │
│ (Kotlin/Compose)│                 │                              │                       │
└─────────────────┘                 └──────────────────────────────┼───────────────────────┘
                                                                   │ HTTPS: «отправь SMS»
                                                                   ▼
                                     api.sms-gate.app (Cloud) или свой Private server
                                                                   │ push (FCM)
                                                                   ▼
                                     ┌──────────────────────────────────────────────┐
                                     │ Домашний Android-телефон с SIM-картой         │
                                     │ приложение «SMS Gateway for Android» (SMSGate)│
                                     └──────────────────────────────────────────────┘
                                                                   │ обычная SMS
                                                                   ▼
                                                     Телефон пользователя
```
Почему так: домашний телефон обычно за NAT роутера, и сервер не может достучаться до него
напрямую. В режиме Cloud/Private телефон сам держит связь с сервером шлюза и получает задания
через push — пробрасывать порты дома не нужно.

## Backend
| Что | Выбор |
|---|---|
| Язык | Python 3.12 |
| Фреймворк | FastAPI |
| БД | PostgreSQL 16 |
| ORM / миграции | SQLAlchemy 2.0 (async, asyncpg) + Alembic |
| Валидация | Pydantic v2, pydantic-settings |
| Токены | JWT (PyJWT), access + refresh |
| SMS | `httpx` к API SMSGate, за интерфейсом `SmsSender` |
| Тесты | pytest, pytest-asyncio, httpx AsyncClient |
| Линтер/формат | ruff |
| Запуск | Docker Compose (api, db; в prod ещё caddy) |

Слои backend:
```
backend/
├── app/
│   ├── main.py          создание FastAPI, подключение роутеров, /health
│   ├── core/            config.py (настройки из .env), security.py (JWT, хэши), deps.py
│   ├── db/              engine, session, Base
│   ├── models/          SQLAlchemy-модели (таблицы)
│   ├── schemas/         Pydantic-схемы запросов/ответов (= контракт API)
│   ├── services/        auth_service.py, password_service.py, sync_service.py, sms/
│   ├── api/v1/          роутеры: auth, me, sync
│   └── cli.py           консольные команды для сервера (выдача паролей): python -m app.cli
├── alembic/             миграции
├── tests/
├── Dockerfile
├── docker-compose.yml        (dev)
├── docker-compose.prod.yml   (prod: + caddy)
├── Caddyfile
├── pyproject.toml
└── .env.example
```
Правило: роутеры тонкие, логика — в `services/`. Расчётов ЛУТ на сервере нет — их делает
приложение; сервер хранит только введённые пользователем данные.

## Android
| Что | Выбор |
|---|---|
| Язык | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Архитектура | MVVM: Screen (Compose) → ViewModel (StateFlow) → Repository → Room; синхронизация: SyncRepository → API |
| DI | Hilt |
| Локальная БД | Room (основная копия данных) |
| Расчёты ЛУТ | чистые функции Kotlin в `domain/calc/` (BigDecimal) |
| Сеть (вход, синхронизация, пароль) | Retrofit + OkHttp + kotlinx.serialization |
| Хранение токенов/настроек | DataStore (Preferences): токены, режим (гость/аккаунт), курсор и время синхронизации, `selected_car_id` |
| Навигация | Navigation Compose |
| Асинхронность | Coroutines + Flow |
| Тесты | JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI tests |

## SMS-шлюз
Open-source проект **SMS Gateway for Android** (SMSGate): https://sms-gate.app,
документация https://docs.sms-gate.app. Режимы:
- **Cloud** (на старт): телефон подключается к `api.sms-gate.app`, backend шлёт запрос туда.
- **Private** (позже, по желанию): свой сервер шлюза в Docker на том же VDS — тексты SMS
  не проходят через чужой сервер.
- **Local**: API на самом телефоне — НЕ подходит, т.к. телефон дома за NAT.

## Окружения
| Окружение | Где | SMS |
|---|---|---|
| dev | компьютер разработчика, Docker | `SMS_PROVIDER=console` — код пишется в лог |
| prod | VDS | `SMS_PROVIDER=smsgate` |

Из эмулятора Android локальный backend доступен по адресу `http://10.0.2.2:8000`.
С реального телефона в той же Wi-Fi — по IP компьютера, напр. `http://192.168.1.50:8000`.
