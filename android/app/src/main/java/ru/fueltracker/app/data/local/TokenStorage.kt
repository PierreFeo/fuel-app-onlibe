package ru.fueltracker.app.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Пара токенов из ответа входа или `/auth/refresh`. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    // Токены не должны попасть в лог даже случайно, через println(tokens)
    override fun toString(): String = "AuthTokens(***)"
}

/** Где приложение хранит токены между запусками. */
interface TokenStorage {

    /** Текущие токены; null — пользователь не вошёл или сессия закончилась. */
    val tokens: Flow<AuthTokens?>

    suspend fun get(): AuthTokens? = tokens.first()

    suspend fun save(tokens: AuthTokens)

    suspend fun clear()

    /**
     * Заменить токены, только если сейчас сохранён [expectedRefreshToken].
     * Так обновление, закончившееся уже после «Выйти», не вернёт токены обратно.
     * @return false — токены за это время сменились или стёрты, ничего не записано.
     */
    suspend fun replaceIfCurrent(expectedRefreshToken: String, tokens: AuthTokens): Boolean

    /** Стереть токены, только если сейчас сохранён [expectedRefreshToken]. */
    suspend fun clearIfCurrent(expectedRefreshToken: String)
}
