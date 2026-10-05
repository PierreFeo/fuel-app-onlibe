package ru.fueltracker.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Токены в памяти — для тестов сети, где DataStore не важен. */
class FakeTokenStorage(initial: AuthTokens? = null) : TokenStorage {

    override val tokens: StateFlow<AuthTokens?> get() = state
    private val state = MutableStateFlow(initial)

    /** Текущее значение без корутин — удобно в assert. */
    val current: AuthTokens? get() = state.value

    override suspend fun save(tokens: AuthTokens) {
        state.value = tokens
    }

    override suspend fun clear() {
        state.value = null
    }

    override suspend fun replaceIfCurrent(expectedRefreshToken: String, tokens: AuthTokens): Boolean {
        val old = state.value
        return old?.refreshToken == expectedRefreshToken && state.compareAndSet(old, tokens)
    }

    override suspend fun clearIfCurrent(expectedRefreshToken: String) {
        val old = state.value
        if (old?.refreshToken == expectedRefreshToken) state.compareAndSet(old, null)
    }
}
