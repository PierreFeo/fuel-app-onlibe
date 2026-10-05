package ru.fueltracker.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Токены в DataStore Preferences. Файл лежит в закрытой папке приложения
 * и исключён из резервных копий (res/xml/backup_rules.xml, data_extraction_rules.xml).
 */
@Singleton
class DataStoreTokenStorage @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TokenStorage {

    override val tokens: Flow<AuthTokens?> = dataStore.data
        .map { prefs ->
            val access = prefs[ACCESS_TOKEN]
            val refresh = prefs[REFRESH_TOKEN]
            if (access != null && refresh != null) AuthTokens(access, refresh) else null
        }
        .distinctUntilChanged()

    override suspend fun save(tokens: AuthTokens) {
        dataStore.edit { it.put(tokens) }
    }

    override suspend fun clear() {
        dataStore.edit { it.removeTokens() }
    }

    override suspend fun replaceIfCurrent(expectedRefreshToken: String, tokens: AuthTokens): Boolean {
        var replaced = false
        // edit {} выполняется атомарно: проверка и запись не разрываются другим изменением
        dataStore.edit { prefs ->
            if (prefs[REFRESH_TOKEN] == expectedRefreshToken) {
                prefs.put(tokens)
                replaced = true
            }
        }
        return replaced
    }

    override suspend fun clearIfCurrent(expectedRefreshToken: String) {
        dataStore.edit { prefs ->
            if (prefs[REFRESH_TOKEN] == expectedRefreshToken) prefs.removeTokens()
        }
    }

    private fun MutablePreferences.put(tokens: AuthTokens) {
        this[ACCESS_TOKEN] = tokens.accessToken
        this[REFRESH_TOKEN] = tokens.refreshToken
    }

    private fun MutablePreferences.removeTokens() {
        remove(ACCESS_TOKEN)
        remove(REFRESH_TOKEN)
    }

    private companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}
