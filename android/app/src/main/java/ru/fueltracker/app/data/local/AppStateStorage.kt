package ru.fueltracker.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Режим приложения (docs/05_AUTH_SMS.md): гость — данные только на телефоне; аккаунт — с синхронизацией. */
enum class AppMode { GUEST, ACCOUNT }

/**
 * Кто пользуется приложением на этом телефоне.
 * [ownerUserId] — чей аккаунт у данных в Room (null у гостя); [name] — имя на телефоне;
 * [nameDirty] — имя изменено и ещё не отправлено на сервер.
 */
data class LocalProfile(
    val mode: AppMode? = null,
    val ownerUserId: String? = null,
    val phone: String? = null,
    val name: String? = null,
    val nameDirty: Boolean = false,
    /** Задан ли пароль для входа без SMS (по данным сервера при последнем входе / смене пароля). */
    val hasPassword: Boolean = false,
)

/** Данные пользователя из ответа входа. */
data class SignedInUser(
    val id: String,
    val phone: String,
    val name: String?,
    val hasPassword: Boolean,
)

interface AppStateStorage {

    val profile: Flow<LocalProfile>

    suspend fun get(): LocalProfile = profile.first()

    /** «Продолжить без входа»: имя хранится только на телефоне. */
    suspend fun startGuest(name: String)

    /**
     * Вход выполнен: данные на телефоне теперь принадлежат аккаунту [user].
     * Имя гостя сохраняется и уйдёт на сервер, только если у аккаунта имени ещё нет.
     */
    suspend fun signedIn(user: SignedInUser)

    /** Имя изменили на телефоне: у аккаунта оно уйдёт на сервер при синхронизации. */
    suspend fun setName(name: String)

    /** Имя совпадает с сервером (после `PATCH /me` или синхронизации). */
    suspend fun setSyncedName(name: String?)

    suspend fun setHasPassword(hasPassword: Boolean)

    /** Выход: всё о пользователе стирается. */
    suspend fun clear()
}

/** В том же DataStore, что токены и выбранное авто (исключён из резервной копии). */
@Singleton
class DataStoreAppStateStorage @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AppStateStorage {

    override val profile: Flow<LocalProfile> = dataStore.data
        .map { prefs ->
            LocalProfile(
                mode = prefs[MODE]?.let { stored -> AppMode.entries.firstOrNull { it.name == stored } },
                ownerUserId = prefs[OWNER_USER_ID],
                phone = prefs[PHONE],
                name = prefs[NAME],
                nameDirty = prefs[NAME_DIRTY] ?: false,
                hasPassword = prefs[HAS_PASSWORD] ?: false,
            )
        }
        .distinctUntilChanged()

    override suspend fun startGuest(name: String) {
        dataStore.edit {
            it[MODE] = AppMode.GUEST.name
            it[NAME] = name
            it[NAME_DIRTY] = false
        }
    }

    override suspend fun signedIn(user: SignedInUser) {
        dataStore.edit {
            val localName = it[NAME]
            val keepLocalName = user.name == null && localName != null
            it[MODE] = AppMode.ACCOUNT.name
            it[OWNER_USER_ID] = user.id
            it[PHONE] = user.phone
            it[HAS_PASSWORD] = user.hasPassword
            if (keepLocalName) {
                it[NAME_DIRTY] = true // имя гостя уйдёт на сервер при синхронизации
            } else {
                user.name?.let { name -> it[NAME] = name } ?: it.remove(NAME)
                it[NAME_DIRTY] = false
            }
        }
    }

    override suspend fun setName(name: String) {
        dataStore.edit {
            it[NAME] = name
            it[NAME_DIRTY] = it[MODE] == AppMode.ACCOUNT.name
        }
    }

    override suspend fun setSyncedName(name: String?) {
        dataStore.edit {
            if (name != null) it[NAME] = name
            it[NAME_DIRTY] = false
        }
    }

    override suspend fun setHasPassword(hasPassword: Boolean) {
        dataStore.edit { it[HAS_PASSWORD] = hasPassword }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> listOf(MODE, OWNER_USER_ID, PHONE, NAME, NAME_DIRTY, HAS_PASSWORD).forEach { prefs.remove(it) } }
    }

    private companion object {
        val MODE = stringPreferencesKey("app_mode")
        val OWNER_USER_ID = stringPreferencesKey("owner_user_id")
        val PHONE = stringPreferencesKey("phone")
        val NAME = stringPreferencesKey("name")
        val NAME_DIRTY = booleanPreferencesKey("name_dirty")
        val HAS_PASSWORD = booleanPreferencesKey("has_password")
    }
}
