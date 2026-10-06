# CLAUDE.md — android

Читай вместе с корневым `../CLAUDE.md`. Экраны: `../docs/07_UI_SCREENS.md`,
API: `../docs/04_API_CONTRACT.md`.

## Стек
Kotlin · Jetpack Compose + Material 3 · MVVM · Hilt · Room · Retrofit + OkHttp + kotlinx.serialization ·
Navigation Compose · DataStore · Coroutines/Flow. minSdk 26, targetSdk — последний стабильный.
Офлайн прежде всего: экраны работают только с Room, сеть — для входа, синхронизации и пароля
(`../docs/02_ARCHITECTURE.md`).
Версии библиотек — только через `gradle/libs.versions.toml` (version catalog).

## Структура пакетов (`app/src/main/java/<package>/`)
```
data/
  remote/      Retrofit API-интерфейсы (auth, me, sync), DTO (@Serializable), AuthInterceptor, TokenAuthenticator
  local/       DataStore: токены, режим, курсор синхронизации, selected_car_id
  local/db/    Room: AppDatabase, сущности (Entity), DAO
  repository/  AuthRepository, CarRepository, SheetRepository, RefuelingRepository (Room),
               SyncRepository (Room ↔ /sync)
domain/model/  модели для UI (Car, FuelSheet, Refueling, SheetCalc)
domain/calc/   SheetCalculator, проверки, next-prefill — чистые функции, BigDecimal
ui/
  theme/
  navigation/  NavGraph, маршруты
  auth/        PhoneScreen, CodeScreen, NameScreen + ViewModel'и
  cars/        CarsScreen, CarEditScreen + ViewModel'и
  sheets/      SheetsFeedScreen, SheetCard, диалоги + ViewModel
  refueling/   RefuelingEditSheet + ViewModel
  profile/
  common/      общие компоненты: LoadingView, ErrorView, EmptyView, форматтеры чисел и дат
di/            Hilt-модули
```

## Правила кода
- Экран = `@Composable XxxScreen(viewModel)` + stateless `XxxContent(state, onEvent)` — второе
  удобно для Preview и UI-тестов.
- Состояние экрана — один `data class XxxUiState` в `StateFlow`; события — sealed interface.
- Никакой сети/DataStore в Composable-функциях — только через ViewModel.
- Дробные значения (в Room и API — строки) → `BigDecimal`. Не использовать `Double` для литров и денег.
- Формулы ЛУТ и бизнес-проверки — ТОЛЬКО в `domain/calc/` (чистые функции без Android и Room),
  каждая формула покрыта эталонными примерами из `06_BUSINESS_RULES.md`. ViewModel и Composable
  формулы не дублируют — берут готовый `SheetCalc`.
- Любая запись в Room через репозиторий ставит `is_dirty` / `is_deleted` и увеличивает
  `change_seq` (`03_DATA_MODEL.md`) — иначе изменение не уйдёт на сервер. id новых записей —
  `UUID.randomUUID()` на телефоне.
- Схема Room меняется только с миграцией (`exportSchema = true`, схемы — в `app/schemas/`).
- `BASE_URL` — через `buildConfigField` в `app/build.gradle.kts`:
  debug = `http://10.0.2.2:8000/api/v1/`, release = `https://<домен>/api/v1/`.
  Для debug разрешить cleartext только для `10.0.2.2` и локальной сети (network_security_config).
- Тексты — в `strings.xml`, на русском.
- Для каждого экрана — `@Preview` в светлой и тёмной теме.
- Токены не логировать. Logging interceptor OkHttp — только в debug.

## Команды
```bash
./gradlew assembleDebug            # собрать debug APK
./gradlew test                     # unit-тесты
./gradlew connectedAndroidTest     # UI-тесты (нужен запущенный эмулятор)
./gradlew lint
```
Запуск приложения — кнопкой ▶ Run в Android Studio (эмулятор или телефон по USB).

## Готовность задачи
1. Проект собирается (`assembleDebug`). 2. `./gradlew test` зелёный.
3. Есть Preview для новых экранов. 4. Пользователю сказано, что нажать на эмуляторе для проверки.
