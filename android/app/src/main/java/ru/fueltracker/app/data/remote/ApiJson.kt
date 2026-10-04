package ru.fueltracker.app.data.remote

import kotlinx.serialization.json.Json

/**
 * Настройки JSON для API — одни и те же в приложении и тестах.
 * - `ignoreUnknownKeys`: новое поле на сервере не ломает старую версию приложения;
 * - `encodeDefaults = false` (по умолчанию): поля со значением по умолчанию не отправляются —
 *   на этом держатся PATCH-запросы и [PatchField].
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
