# 03. Модель данных (PostgreSQL)

Общее: первичные ключи — `UUID`. Время — `timestamptz` в UTC. Литры — `NUMERIC(8,2)`,
деньги — `NUMERIC(10,2)`, пробег — `INTEGER` (км). У всех таблиц есть `created_at`, `updated_at`.
Схема меняется ТОЛЬКО через миграции Alembic.

## users
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| phone | varchar(16) | UNIQUE, NOT NULL, формат E.164 (`+79991234567`) |
| name | varchar(100) | NULL до первого заполнения |
| is_active | bool | default true |

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
| norm_l_per_100km | numeric(5,2) | > 0 |
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
| norm_l_per_100km | numeric(5,2) | КОПИЯ нормы авто на момент создания листа |
| status | varchar(10) | enum: `OPEN`, `CLOSED`, default `OPEN` |
| closed_at | timestamptz | NULL |

UNIQUE (`car_id`, `year`, `month`).

Почему норма копируется в лист: если пользователь позже поменяет норму у авто, расчёты
старых месяцев не должны измениться.

## refuelings (заправки)
| Поле | Тип | Ограничения |
|---|---|---|
| id | uuid | PK |
| sheet_id | uuid | FK fuel_sheets, ON DELETE CASCADE, индекс |
| refueled_at | date | NOT NULL, должна попадать в месяц листа |
| liters | numeric(8,2) | > 0 |
| price_per_liter | numeric(8,2) | >= 0 |
| total_cost | numeric(10,2) | >= 0 (если не передан — liters × price, округл. до копеек) |
| odometer_km | int | NULL |
| station | varchar(100) | NULL |
| payment_type | varchar(12) | enum: `PERSONAL`, `FUEL_CARD`, `COMPANY`; default `PERSONAL` |
| note | varchar(255) | NULL |

## Вычисляемые поля
В БД НЕ хранятся. Считаются в `services/sheet_calc.py` и отдаются в API
(см. `06_BUSINESS_RULES.md`).
