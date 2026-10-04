# 05. Авторизация по SMS (SMS Gateway for Android) + запасной вход по паролю

Основной способ входа — код из SMS. Запасной (если SMS не пришла) — номер телефона + пароль,
см. раздел «Запасной вход по паролю» ниже. Оба способа выдают одинаковые токены и ведут
в один и тот же аккаунт (пользователь определяется по `phone`).

## Поток входа
```
App                    Backend                         SMSGate            Домашний телефон
 │ request-code(phone)  │                                 │                      │
 │────────────────────▶ │ проверки: формат, whitelist,    │                      │
 │                      │ rate limit                      │                      │
 │                      │ генерирует код, сохраняет ХЭШ   │                      │
 │                      │ POST /3rdparty/v1/messages ───▶ │ ── push ───────────▶ │ ── SMS ──▶ пользователь
 │ ◀── 200 ─────────────│                                 │                      │
 │ verify-code(phone,code)                                │                      │
 │────────────────────▶ │ сверяет хэш, срок, попытки      │                      │
 │ ◀── tokens + user ───│                                 │                      │
```

## Правила кода (OTP)
- 6 цифр, генерируется `secrets.randbelow` (не `random`).
- Хранится только хэш: HMAC-SHA256(код, `OTP_PEPPER` из .env).
- Срок жизни — 5 минут (`OTP_TTL_SEC=300`).
- Максимум 5 попыток ввода на один код, потом код недействителен (`OTP_EXPIRED`).
- Новый запрос кода делает все предыдущие коды этого номера недействительными.
- Сравнение — `hmac.compare_digest`.

## Ограничения частоты (защита от спама и расхода SMS)
- Не чаще 1 запроса кода в 60 секунд на номер.
- Не более 5 запросов кода в час на номер.
- Не более 20 запросов кода в час с одного IP.
- Реализация MVP: подсчёт по таблице `otp_codes` + простой in-memory счётчик по IP.

## Номер телефона
- Нормализуем к E.164 библиотекой `phonenumbers`, регион по умолчанию `DEFAULT_PHONE_REGION` (напр. `RU`).
  `8 999 123-45-67` → `+79991234567`.
- Если `REGISTRATION_MODE=whitelist` и номера нет в `ALLOWED_PHONES` и нет в `users` → 403.
- Пользователь с `is_active=false` → тоже 403 `PHONE_NOT_ALLOWED` (SMS не отправляется) при любом режиме.

## Токены
- Access JWT: 15 минут, claims: `sub` (user_id), `type: "access"`, `exp`.
- Refresh JWT: 90 дней (отсчёт заново при каждом /auth/refresh — активного пользователя не разлогинивает), claims: `sub`, `type: "refresh"`, `jti` (id в `refresh_tokens`).
- Refresh-ротация: при `/auth/refresh` старый отзывается, выдаётся новый.
- Подпись HS256, ключ `JWT_SECRET` (минимум 32 случайных символа).

## Запасной вход по паролю
Нужен, если SMS не доходит (шлюз выключен, проблемы у оператора и т. п.).
Эндпоинт — `POST /auth/login` (`04_API_CONTRACT.md`).

### Кто и как выдаёт пароль
Пользователь в MVP пароль НЕ задаёт и НЕ меняет (иначе без SMS его не задать). Пароль выдаёт
ответственный человек консольной командой на сервере:
```
# выдать / сбросить пароль (пользователь создаётся, если его ещё нет)
docker compose exec api python -m app.cli set-password --phone "+79991234567"
→ Пароль для +79991234567: k7Fm2xQp9a   (показывается ОДИН раз, передать сотруднику)

# выключить вход по паролю для номера
docker compose exec api python -m app.cli disable-password --phone "+79991234567"
```
- Номер нормализуется так же, как при входе по SMS (E.164).
- Пароль генерирует сервер: 10 символов через `secrets.choice` из алфавита без похожих
  символов (без `0 O o 1 l I`). Ввести свой пароль вручную нельзя — так он не попадёт
  в историю команд и не будет слабым.
- `set-password` сбрасывает `failed_login_attempts` и `locked_until`.
- `disable-password` ставит `password_hash = NULL`. Выданные ранее токены продолжают работать
  до истечения (выход с устройства — через `/auth/logout`).
- Белый список (`ALLOWED_PHONES`) командой не проверяется: раз человек выдаёт пароль, номер разрешён.

### Хранение пароля
- Только хэш: `hashlib.scrypt` из стандартной библиотеки (новая зависимость не нужна).
  Параметры: `n=2**14, r=8, p=1`, `dklen=32`, соль 16 байт из `secrets.token_bytes`.
- Формат строки в `users.password_hash`: `scrypt$16384$8$1$<соль base64>$<хэш base64>` —
  параметры хранятся рядом с хэшем, чтобы их можно было усилить позже без поломки старых паролей.
- Сравнение — `hmac.compare_digest`.
- Если номер не найден или у него нет пароля — всё равно вычисляем scrypt от фиктивного хэша,
  чтобы по времени ответа нельзя было понять, есть ли такой пользователь.

### Проверки при входе (в этом порядке)
1. Формат номера → иначе 400 `VALIDATION_ERROR`.
2. Лимит по IP: не более 20 попыток входа по паролю в час с одного IP → иначе 429 `RATE_LIMITED`
   (in-memory счётчик, как для SMS).
3. Пользователь найден, `is_active = true`, `password_hash` не NULL → иначе 401 `INVALID_CREDENTIALS`.
4. `locked_until` в будущем → 429 `RATE_LIMITED` с `retry_after_sec` (даже если пароль верный).
5. Пароль неверный → `failed_login_attempts += 1`; при достижении 5 —
   `locked_until = now + 15 мин`, счётчик обнуляется → 401 `INVALID_CREDENTIALS`.
6. Пароль верный → `failed_login_attempts = 0`, `locked_until = NULL`, выдаются токены
   (как в verify-code). `is_new_user = true`, если `name` пустое.

Неудачные попытки входа по паролю пишутся в лог (номер + IP, без пароля).

## Интерфейс отправки SMS (backend)
```python
class SmsSender(Protocol):
    async def send(self, phone: str, text: str) -> None: ...
```
Реализации (выбор по `SMS_PROVIDER`):
- `ConsoleSmsSender` — пишет `"[DEV SMS] +7999... : Код входа: 123456"` в лог. Для разработки и тестов.
- `SmsGateSender` — реальная отправка через SMSGate.
- В тестах — `FakeSmsSender`, который запоминает отправленные сообщения.

Текст SMS: `Код входа в Учёт топлива: 123456. Никому не сообщайте.`

## SMSGate: как отправляется SMS
Документация: https://docs.sms-gate.app (перед реализацией СВЕРИТЬСЯ с актуальной версией —
формат API мог измениться; при расхождении — следовать документации и обновить этот файл).

Cloud-режим:
```
POST https://api.sms-gate.app/3rdparty/v1/messages
Authorization: Basic base64(SMSGATE_USERNAME:SMSGATE_PASSWORD)
Content-Type: application/json

{ "textMessage": { "text": "Код входа ..." }, "phoneNumbers": ["+79991234567"] }
```
Private-режим: тот же запрос на `https://<свой-домен>/api/3rdparty/v1/messages`.
Поэтому базовый URL — настройка `SMSGATE_URL`, а не константа в коде.

Обработка ответа: 2xx — успех (сохраняем id сообщения в лог). Иначе / таймаут 10 с →
ошибка `SMS_SEND_FAILED` (502), код в БД помечается использованным.
Альтернатива ручному httpx — официальная библиотека `android-sms-gateway` (PyPI); выбрать одно и
записать в `DECISIONS.md`.

## Настройка домашнего телефона (делает человек, не Claude)
1. Android-телефон с SIM-картой (тариф с пакетом SMS), постоянно на зарядке и в Wi-Fi.
2. Установить SMS Gateway for Android (APK с GitHub или Google Play — ссылки на sms-gate.app).
3. Выдать разрешение на отправку SMS; отключить оптимизацию батареи для приложения.
4. Включить «Cloud server», запустить сервис. На главном экране появятся username и password —
   записать их в `backend/.env` как `SMSGATE_USERNAME` / `SMSGATE_PASSWORD`.
5. Проверить с компьютера:
   `curl -X POST -u USER:PASS -H "Content-Type: application/json" -d '{"textMessage":{"text":"test"},"phoneNumbers":["+7ВАШ_НОМЕР"]}' https://api.sms-gate.app/3rdparty/v1/messages`

## Переменные окружения
```
SMS_PROVIDER=console          # console | smsgate
SMSGATE_URL=https://api.sms-gate.app/3rdparty/v1
SMSGATE_USERNAME=
SMSGATE_PASSWORD=
OTP_TTL_SEC=300
OTP_PEPPER=change-me
JWT_SECRET=change-me-at-least-32-chars
ACCESS_TOKEN_TTL_MIN=15
REFRESH_TOKEN_TTL_DAYS=90
REGISTRATION_MODE=whitelist   # whitelist | open
ALLOWED_PHONES=+79991234567,+79997654321
DEFAULT_PHONE_REGION=RU
PASSWORD_MAX_ATTEMPTS=5       # неверных паролей подряд до блокировки
PASSWORD_LOCK_MIN=15          # на сколько минут блокируется вход по паролю
```

## Тест-аккаунт для разработки
Если `ENV=dev`, номер `+70000000000` всегда принимает код `000000` без отправки SMS.
В `ENV=prod` эта логика ОТКЛЮЧЕНА (обязательно покрыть тестом).
Для входа по паролю тестового аккаунта НЕТ — в dev пароль выдаётся той же командой
`python -m app.cli set-password`.
