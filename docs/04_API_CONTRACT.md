# 04. Контракт REST API (v1)

Базовый URL: `{BASE_URL}/api/v1` · формат: JSON, UTF-8 · имена полей: `snake_case`.
Числа с дробной частью (литры, деньги, расход) передаются **строками**: `"45.50"` —
чтобы не терять точность на float. Пробег — целые числа. Даты — `"2026-10-02"`,
время — ISO 8601 UTC `"2026-10-02T08:15:00Z"`.

Авторизация: заголовок `Authorization: Bearer <access_token>` для всех эндпоинтов, кроме `/auth/*`
и `/health`. Пользователь видит только свои данные; чужой ресурс → `404` (не `403`, чтобы не
раскрывать существование).

FastAPI автоматически публикует интерактивную документацию на `/docs` (Swagger) — она должна
совпадать с этим файлом.

## Формат ошибок (единый)
```json
{ "error": { "code": "OTP_INVALID", "message": "Неверный код", "details": {} } }
```
| HTTP | code | Когда |
|---|---|---|
| 400 | `VALIDATION_ERROR` | неверные поля (details: `{ "field": "причина" }`) |
| 401 | `UNAUTHORIZED` | нет/просрочен access-токен |
| 401 | `OTP_INVALID` | неверный код |
| 401 | `OTP_EXPIRED` | код истёк или исчерпаны попытки |
| 401 | `INVALID_CREDENTIALS` | неверный телефон или пароль (вход по паролю) |
| 401 | `REFRESH_INVALID` | refresh-токен недействителен |
| 403 | `PHONE_NOT_ALLOWED` | номер не в белом списке |
| 404 | `NOT_FOUND` | ресурс не найден или чужой; также неизвестный URL или неподдерживаемый HTTP-метод |
| 409 | `SHEET_EXISTS` | ЛУТ за этот месяц уже есть |
| 409 | `SHEET_CLOSED` | попытка изменить закрытый ЛУТ |
| 422 | `BUSINESS_RULE` | нарушено бизнес-правило (details поясняют) |
| 429 | `RATE_LIMITED` | слишком часто (details: `{ "retry_after_sec": 45 }`) |
| 500 | `INTERNAL_ERROR` | непредвиденная ошибка сервера (подробности только в логах) |
| 502 | `SMS_SEND_FAILED` | шлюз не принял SMS |

---

## Служебное
### GET /health → 200
`{ "status": "ok" }`

## Авторизация (подробно — `05_AUTH_SMS.md`)
### POST /auth/request-code
```json
// запрос
{ "phone": "+79991234567" }
// 200
{ "expires_in_sec": 300, "resend_after_sec": 60 }
```
Ошибки: 400, 403 `PHONE_NOT_ALLOWED`, 429, 502.

### POST /auth/verify-code
```json
// запрос
{ "phone": "+79991234567", "code": "123456" }
// 200
{
  "access_token": "eyJ...", "refresh_token": "eyJ...",
  "token_type": "bearer", "expires_in_sec": 900,
  "user": { "id": "uuid", "phone": "+79991234567", "name": null },
  "is_new_user": true
}
```
Ошибки: 401 `OTP_INVALID`, 401 `OTP_EXPIRED`.

### POST /auth/login
Запасной вход по паролю (если SMS не пришла). Логин — номер телефона, нормализуется так же,
как в `request-code`. Белый список здесь НЕ проверяется: пароль есть только у тех, кому его
выдали командой на сервере.
```json
// запрос
{ "phone": "+79991234567", "password": "k7Fm2xQp9a" }
// 200 — та же структура, что у verify-code
{
  "access_token": "eyJ...", "refresh_token": "eyJ...",
  "token_type": "bearer", "expires_in_sec": 900,
  "user": { "id": "uuid", "phone": "+79991234567", "name": null },
  "is_new_user": true
}
```
`is_new_user` здесь = `true`, если у пользователя ещё не заполнено имя (`name` = null), —
приложение покажет экран ввода имени.
Ошибки: 400 `VALIDATION_ERROR`; 401 `INVALID_CREDENTIALS` — одинаково для неизвестного номера,
номера без пароля, неактивного пользователя и неверного пароля; 429 `RATE_LIMITED` — вход
временно заблокирован после неудачных попыток (details: `{ "retry_after_sec": 840 }`).

### POST /auth/refresh
`{ "refresh_token": "..." }` → 200 — та же структура, что у verify-code (без `is_new_user`).
Работает одинаково независимо от способа входа (SMS или пароль).
Старый refresh-токен отзывается (ротация). Ошибка: 401 `REFRESH_INVALID`.

### POST /auth/logout
`{ "refresh_token": "..." }` → 204. Отвечает 204 и для уже недействительного токена — выйти можно всегда.

## Профиль
### GET /me → 200
`{ "id": "uuid", "phone": "+79991234567", "name": "Иван Петров" }`
### PATCH /me
`{ "name": "Иван Петров" }` → 200 (профиль). name: 1..100 символов.

## Автомобили
Объект **Car**:
```json
{
  "id": "uuid", "name": "Lada Vesta", "plate_number": "А123ВС77",
  "fuel_type": "AI95", "tank_capacity_l": "50.00",
  "norm_l_per_100km": "10.068", "norm_winter_l_per_100km": "11.684",
  "is_archived": false, "created_at": "2026-10-02T08:15:00Z"
}
```
`norm_l_per_100km` — летняя (основная) норма, обязательна. `norm_winter_l_per_100km` — зимняя,
необязательна (`null` — не задана). Как они попадают в листы — `06_BUSINESS_RULES.md`,
«Сезон и норма листа».
| Метод | Путь | Тело | Ответ |
|---|---|---|---|
| GET | `/cars?include_archived=false` | — | 200 `[Car]` |
| POST | `/cars` | Car без id/is_archived/created_at | 201 `Car` |
| GET | `/cars/{car_id}` | — | 200 `Car` |
| PATCH | `/cars/{car_id}` | любые поля Car (частично) | 200 `Car` |
| DELETE | `/cars/{car_id}` | — | 204 (мягкое удаление: `is_archived=true`) |

Уточнения:
- `GET /cars` — по дате добавления, старые сверху.
- `plate_number`: пробелы по краям обрезаются, буквы — в верхний регистр; пустая строка = `null`.
- `PATCH` принимает и `is_archived`: `false` возвращает авто из архива. `null` допустим только
  для `plate_number` (удалить номер) и `norm_winter_l_per_100km` (убрать зимнюю норму),
  для остальных полей — 400.
- Дробные поля на вход принимают строку или число (`"50"`, `"8.5"`, `8.5`). Литры и деньги —
  не больше 2 знаков после точки, в ответе строка с 2 знаками (`"50.00"`). Нормы и расход на
  100 км — не больше 3 знаков, в ответе строка с 3 знаками (`"10.068"`, `"8.500"`).
- Неверный формат id в пути (не UUID) — 400 `VALIDATION_ERROR`.

## Листы учёта топлива (ЛУТ)
Объект **FuelSheet** (в ответах всегда с вычисляемыми полями `calc`):
```json
{
  "id": "uuid", "car_id": "uuid", "year": 2026, "month": 10,
  "status": "OPEN",
  "odometer_start_km": 52340, "odometer_end_km": null,
  "fuel_start_l": "12.00", "fuel_end_actual_l": null,
  "season": "SUMMER",
  "norm_l_per_100km": "10.068",
  "refuelings": [ /* Refueling[], по дате по возрастанию */ ],
  "calc": {
    "refueled_l": "80.00",
    "refueled_cost": "4400.00",
    "fuel_available_l": "92.00",
    "mileage_km": null,
    "norm_consumption_l": null,
    "fuel_end_calc_l": null,
    "fuel_end_l": null,
    "actual_consumption_l": null,
    "actual_l_per_100km": null,
    "consumption_status": null,
    "deviation_l": null,
    "cost_per_km": null,
    "warnings": []
  },
  "closed_at": null, "created_at": "...", "updated_at": "..."
}
```
Смысл каждого поля `calc` и формулы — `06_BUSINESS_RULES.md`.
`season` — `SUMMER` (☀️) или `WINTER` (❄️); `norm_l_per_100km` — норма авто для этого сезона,
скопированная в лист. `calc.consumption_status` — `NORMAL` (зелёный), `OVER` (красный) или `null`.

| Метод | Путь | Тело | Ответ |
|---|---|---|---|
| GET | `/cars/{car_id}/sheets?limit=12&before=2026-10` | — | 200 `{ "items": [FuelSheet], "next_before": "2025-10" \| null }` — новые сверху |
| GET | `/cars/{car_id}/sheets/next-prefill` | — | 200 `{ "year", "month", "odometer_start_km", "fuel_start_l", "season" }` — подсказка для нового листа |
| POST | `/cars/{car_id}/sheets` | `{ year, month, odometer_start_km, fuel_start_l, season? }` (без `season` — как в `next-prefill`) | 201 `FuelSheet` · 409 `SHEET_EXISTS` · 422 `WINTER_NORM_NOT_SET` |
| GET | `/sheets/{sheet_id}` | — | 200 `FuelSheet` |
| PATCH | `/sheets/{sheet_id}` | `{ odometer_start_km?, odometer_end_km?, fuel_start_l?, fuel_end_actual_l?, season? }` | 200 · 409 `SHEET_CLOSED` · 422 `WINTER_NORM_NOT_SET` |
| POST | `/sheets/{sheet_id}/close` | `{ odometer_end_km, fuel_end_actual_l? }` | 200 `FuelSheet` (status `CLOSED`) |
| POST | `/sheets/{sheet_id}/reopen` | — | 200 `FuelSheet` (status `OPEN`) |
| DELETE | `/sheets/{sheet_id}` | — | 204 (только если нет заправок, иначе 422) |

Переключение сезона (иконка ☀️/❄️) — `PATCH /sheets/{id}` с `{ "season": "WINTER" }`: норма
листа заново копируется из авто, в ответе лист с пересчитанным `calc`.
`422 WINTER_NORM_NOT_SET` — это `BUSINESS_RULE` с `details: { "reason": "WINTER_NORM_NOT_SET" }`:
у авто не задана зимняя норма.

## Заправки
Объект **Refueling**:
```json
{
  "id": "uuid", "sheet_id": "uuid", "refueled_at": "2026-10-05",
  "liters": "40.00", "price_per_liter": "55.00", "total_cost": "2200.00",
  "odometer_km": 52610, "station": "Лукойл, Ленина 1",
  "payment_type": "PERSONAL", "note": null
}
```
| Метод | Путь | Тело | Ответ |
|---|---|---|---|
| POST | `/sheets/{sheet_id}/refuelings` | Refueling без id/sheet_id (`total_cost` необязателен) | 201 `FuelSheet` (весь лист с пересчитанным `calc`) |
| PATCH | `/refuelings/{refueling_id}` | частично | 200 `FuelSheet` |
| DELETE | `/refuelings/{refueling_id}` | — | 200 `FuelSheet` |

Изменение заправок закрытого листа → 409 `SHEET_CLOSED`.
Почему заправки возвращают весь лист: карточке в приложении нужно сразу обновить итоги,
без второго запроса.
