# 08. Roadmap — план по шагам

Правило: одна задача = одна сессия Claude Code = один коммит.
После каждой задачи — отметить `[x]` в этом файле.
«Готово, когда» — критерий, который проверяет ЧЕЛОВЕК своими руками.
Обясняй что и как работает после каэдого шага максимально понятно.

## Фаза 0. Подготовка (делает человек)
- [x] 0.1 Установлены: Git, Android Studio, Docker Desktop, Claude Code; аккаунт GitHub
- [x] 0.2 Создан репозиторий `fuel-app`, в него положены эти контрактные файлы, первый коммит

## Фаза 1. Backend: каркас
- [x] 1.1 Каркас FastAPI + Docker Compose (api + PostgreSQL), `GET /api/v1/health`, ruff, pytest
  - Готово, когда: `docker compose up -d` → в браузере `http://localhost:8000/docs` открывается Swagger,
    `/api/v1/health` отвечает `{"status":"ok"}`, `docker compose exec api pytest` зелёный
- [x] 1.2 SQLAlchemy + Alembic, модели всех таблиц из `03_DATA_MODEL.md`, первая миграция
  - Готово, когда: миграция применяется на пустую БД, тест проверяет, что таблицы созданы
- [x] 1.3 Единый формат ошибок из `04_API_CONTRACT.md` (обработчики исключений)

## Фаза 2. Backend: авторизация
- [x] 2.1 `SmsSender` + `ConsoleSmsSender` + `FakeSmsSender` для тестов
- [x] 2.2 `/auth/request-code`, `/auth/verify-code`, OTP-правила, rate limit, whitelist
- [x] 2.3 JWT (проверка; выдача токенов уже сделана в 2.2), `/auth/refresh` (ротация), `/auth/logout`, зависимость `get_current_user`, `/me`
  - Готово, когда: через Swagger можно запросить код, увидеть его в логах
    (`docker compose logs api`), войти, нажать «Authorize» и вызвать `/me`
- [x] 2.4 Запасной вход по паролю: поля `password_hash`, `failed_login_attempts`, `locked_until`
  в `users` (миграция 0002), хэширование scrypt, `POST /auth/login`, блокировка после 5 ошибок,
  команды `python -m app.cli set-password` / `disable-password` (см. `05_AUTH_SMS.md`)
  - Готово, когда: `docker compose exec api python -m app.cli set-password --phone "+79991234567"`
    печатает пароль, через Swagger `/auth/login` с ним выдаёт токены, 5 неверных паролей → 429

## Фаза 3. Backend: предметная область
- [x] 3.1 CRUD автомобилей
- [x] 3.2 `sheet_calc.py` — все формулы и эталонные примеры A–F из `06_BUSINESS_RULES.md` как тесты
- [x] 3.2a Сезонные нормы: зимняя норма у авто, нормы с 3 знаками, `season` у листа (миграция),
  `actual_l_per_100km` с 3 знаками, `consumption_status`, примеры G–I как тесты
  - Готово, когда: в Swagger у авто можно задать `norm_winter_l_per_100km`, тесты зелёные
- [x] 3.3 ЛУТ: список с пагинацией, next-prefill (вкл. сезон), создание, PATCH (вкл. переключение
  сезона), close/reopen, delete
- [x] 3.4 Заправки: создание/изменение/удаление, ответ — весь лист
  - Готово, когда: через Swagger проходит весь сценарий из `01_PRODUCT.md`, все тесты зелёные

## Фаза 4. Android: каркас
- [x] 4.1 Человек создаёт проект в Android Studio в папке `android/` (см. START_HERE.md)
- [x] 4.2 Зависимости (Hilt, Retrofit, kotlinx.serialization, Navigation, DataStore), тема, структура пакетов
- [x] 4.3 Сетевой слой: Retrofit, DTO по `04_API_CONTRACT.md`, разбор ошибок, `BASE_URL` из `BuildConfig`
- [x] 4.4 Хранение токенов, interceptor с Bearer, автообновление токена по 401
  - Готово, когда: приложение запускается на эмуляторе, тесты `./gradlew test` зелёные

## Фаза 5. Android: экраны
- [x] 5.1 Splash + PhoneScreen + CodeScreen + PasswordLoginScreen + NameScreen
  (вход по SMS и по паролю работает с локальным backend)
- [x] 5.2 CarsScreen + CarEditScreen + выбор авто
- [ ] 5.3 SheetsFeedScreen + SheetCard (только отображение, пагинация, pull-to-refresh)
- [ ] 5.4 NewSheetDialog + CloseSheetDialog + reopen
- [ ] 5.5 RefuelingEditScreen: добавить/изменить/удалить заправку
- [ ] 5.6 ProfileScreen + выход
  - Готово, когда: весь сценарий из `01_PRODUCT.md` проходит на эмуляторе

## Фаза 6. Реальные SMS
- [ ] 6.1 Человек настраивает домашний телефон (см. `05_AUTH_SMS.md`)
- [ ] 6.2 `SmsGateSender` + тест с замоканным HTTP; проверка на реальном номере с локального backend

## Фаза 7. Продакшен
- [ ] 7.1 `docker-compose.prod.yml` + Caddy (HTTPS) + `.env.prod.example`
- [ ] 7.2 Человек: VDS, домен, установка Docker, деплой (Claude даёт пошаговые команды)
- [ ] 7.3 Бэкап БД (cron + `pg_dump`, хранить 14 дней)
- [ ] 7.4 Release-сборка APK с prod `BASE_URL`, подпись, раздача сотрудникам

## Идеи после MVP (не делать без запроса)
Выгрузка ЛУТ в Excel/PDF · роль администратора · офлайн-кэш (Room) · фото чеков ·
автоподстановка кода из SMS · Private-сервер SMSGate на VDS.
