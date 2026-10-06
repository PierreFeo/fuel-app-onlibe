# 03. Модель данных

Данные живут в двух местах с одинаковыми полями: **Room на телефоне** (основная копия) и
**PostgreSQL на сервере** (копия для синхронизации). Ниже — таблицы сервера; локальная база —
в конце файла.

Общее: первичные ключи — `UUID`. **id авто, листов и заправок создаёт телефон** (UUID v4) —
поэтому запись сразу имеет постоянный id и при синхронизации не дублируется. Время —
`timestamptz` в UTC. Литры — `NUMERIC(8,2)`, деньги — `NUMERIC(10,2)`, пробег — `INTEGER` (км).
У всех таблиц есть `created_at`, `updated_at`. Схема сервера меняется ТОЛЬКО через миграции Alembic.

### Поля синхронизации (в `cars`, `fuel_sheets`, `refuelings`)
| Поле | Тип | Ограничения |
|---|---|---|
| version | bigint | NOT NULL, индекс; берётся из общей последовательности `sync_version_seq` при КАЖДОЙ записи строки на сервере. Курсор синхронизации — это наибольший `version`, который телефон уже получил |
| deleted_at | timestamptz | NULL; запись удалена на телефоне («мягкое» удаление — строка остаётся, чтобы другой телефон узнал об удалении) |

Удалённые строки (`deleted_at` не NULL) не участвуют в ограничениях уникальности (частичный
UNIQUE-индекс `WHERE deleted_at IS NULL`).

В `users` для синхронизации имени: `name_version bigint NULL` — из той же последовательности,
при каждом изменении `name`.

## users
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| phone | varchar(16) | UNIQUE, NOT NULL, формат E.164 (`+79991234567`) |
| name | varchar(100) | NULL до первого заполнения |
| is_active | bool | default true |
| password_hash | varchar(255) | NULL = пароль не задан (вход по паролю выключен). Задаёт пользователь в профиле или администратор командой. Формат — `05_AUTH_SMS.md`. Пароль в открытом виде НЕ хранить |
| failed_login_attempts | int | NOT NULL, default 0; неудачные попытки входа по паролю подряд |
| locked_until | timestamptz | NULL; до этого момента вход по паролю заблокирован |

Логин для входа по паролю — это `phone`, отдельного поля логина нет.

## otp_codes
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| phone | varchar(16) | NOT NULL, индекс |
| code_hash | varchar(128) | хэш кода (код в открытом виде НЕ хранить) |
| expires_at | timestamptz | NOT NULL (создание + 5 минут) |
| attempts | int | default 0, максимум 5 |
| used_at | timestamptz | NULL, пока не использован |

## refresh_tokens
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK (он же `jti` в токене) |
| user_id | uuid | FK users, ON DELETE CASCADE |
| expires_at | timestamptz | NOT NULL |
| revoked_at | timestamptz | NULL |

## cars
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| user_id | uuid | FK users, NOT NULL, индекс |
| name | varchar(60) | NOT NULL, напр. «Lada Vesta» |
| plate_number | varchar(15) | NULL, напр. «А123ВС77» |
| fuel_type | varchar(10) | enum: `AI92`, `AI95`, `AI98`, `DIESEL`, `GAS` |
| tank_capacity_l | numeric(6,2) | > 0 |
| norm_l_per_100km | numeric(6,3) | > 0, NOT NULL; летняя (основная) норма, напр. `10.068` |
| norm_winter_l_per_100km | numeric(6,3) | NULL или > 0; зимняя норма, напр. `11.684`; NULL — не задана |
| is_archived | bool | default false |

## fuel_sheets (ЛУТ)
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| car_id | uuid | FK cars, ON DELETE CASCADE |
| year | smallint | 2020..2100 |
| month | smallint | 1..12 |
| odometer_start_km | int | >= 0, NOT NULL |
| odometer_end_km | int | NULL до ввода; >= odometer_start_km |
| fuel_start_l | numeric(8,2) | >= 0, NOT NULL |
| fuel_end_actual_l | numeric(8,2) | NULL; фактический остаток, если пользователь его ввёл |
| season | varchar(6) | enum: `SUMMER`, `WINTER`, NOT NULL — сезон листа (иконка ☀️/❄️) |
| norm_l_per_100km | numeric(6,3) | КОПИЯ нормы авто для сезона листа (летней или зимней) — на момент создания листа или переключения сезона |
| status | varchar(10) | enum: `OPEN`, `CLOSED`, default `OPEN` |
| closed_at | timestamptz | NULL |

UNIQUE (`car_id`, `year`, `month`) среди неудалённых листов.

Почему норма копируется в лист: если пользователь позже поменяет норму у авто, расчёты
старых месяцев не должны измениться. Правила выбора сезона и нормы — `06_BUSINESS_RULES.md`,
раздел «Сезон и норма листа».

## refuelings (заправки)
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| sheet_id | uuid | FK fuel_sheets, ON DELETE CASCADE, индекс |
| refueled_at | date | NOT NULL; то, что дата внутри месяца листа, проверяет телефон |
| liters | numeric(8,2) | > 0 |
| price_per_liter | numeric(8,2) | >= 0 |
| total_cost | numeric(10,2) | >= 0, NOT NULL (сумму считает телефон, см. `06_BUSINESS_RULES.md`) |
| odometer_km | int | NULL |
| station | varchar(100) | NULL |
| payment_type | varchar(12) | enum: `PERSONAL`, `FUEL_CARD`, `COMPANY`; default `PERSONAL` |
| note | varchar(255) | NULL |

## Вычисляемые поля
Нигде НЕ хранятся — ни на сервере, ни в Room. Телефон считает их при показе
(`domain/calc/`, см. `06_BUSINESS_RULES.md`).

## Локальная база на телефоне (Room)
Таблицы `cars`, `fuel_sheets`, `refuelings` — те же поля, что на сервере (кроме `user_id` и
`version`), типы: UUID — `String`, литры/деньги/нормы — `String` с точкой (`"45.50"`) и
`BigDecimal` в коде, даты — ISO-строки. Плюс служебные поля:

| Поле | Смысл |
|---|---|
| `is_dirty` | запись изменена на телефоне и ещё не отправлена на сервер |
| `is_deleted` | запись удалена на телефоне, но удаление ещё не отправлено; на экранах не видна. После успешной синхронизации строка стирается из Room |
| `change_seq` | счётчик изменений строки: если запись поменяли во время синхронизации, `is_dirty` после неё не сбрасывается |

Внешние ключи с каскадом — как на сервере. «Один лист на месяц среди неудалённых» проверяет
репозиторий в транзакции: частичных индексов Room не умеет, а полный UNIQUE не дал бы завести
месяц заново, пока удаление старого листа не отправлено. В гостевом режиме таблицы те же —
просто без синхронизации. Файл базы (`fuel_tracker.db`), как и DataStore, исключён из резервной
копии Android: данные переносятся на новый телефон только через вход и синхронизацию.

Настройки (DataStore, не Room): токены; режим `GUEST` / `ACCOUNT`; `owner_user_id` — чей аккаунт
у данных на телефоне; имя и флаг «имя изменено, не отправлено» (у гостя имя только здесь);
`sync_cursor`; `last_sync_at`; `selected_car_id`.
