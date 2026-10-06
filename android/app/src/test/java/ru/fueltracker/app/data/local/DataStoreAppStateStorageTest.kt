package ru.fueltracker.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Режим, владелец данных и имя — в настоящем DataStore (docs/03_DATA_MODEL.md, docs/05_AUTH_SMS.md). */
class DataStoreAppStateStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.root.resolve("test.preferences_pb") })
    }
    private val storage by lazy { DataStoreAppStateStorage(dataStore) }
    private val tokens by lazy { DataStoreTokenStorage(dataStore) }

    private val ivan = SignedInUser(id = "u1", phone = "+79991234567", name = "Иван", hasPassword = true)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `сначала режима нет`() = runTest {
        assertEquals(LocalProfile(), storage.get())
    }

    @Test
    fun `гость — имя только на телефоне`() = runTest {
        storage.startGuest("Иван")
        storage.setName("Пётр")

        assertEquals(LocalProfile(mode = AppMode.GUEST, name = "Пётр"), storage.get())
    }

    @Test
    fun `вход — аккаунт, владелец, телефон, пароль, имя с сервера`() = runTest {
        storage.signedIn(ivan)

        assertEquals(
            LocalProfile(AppMode.ACCOUNT, ownerUserId = "u1", phone = "+79991234567", name = "Иван", hasPassword = true),
            storage.get(),
        )
    }

    @Test
    fun `гость входит в аккаунт без имени — его имя уйдёт на сервер`() = runTest {
        storage.startGuest("Гость")
        storage.signedIn(ivan.copy(name = null))

        val profile = storage.get()
        assertEquals("Гость", profile.name)
        assertTrue(profile.nameDirty)
    }

    @Test
    fun `аккаунт меняет имя — помечено, после синхронизации — нет`() = runTest {
        storage.signedIn(ivan)
        storage.setName("Иван Петров")
        assertTrue(storage.get().nameDirty)

        storage.setSyncedName("Иван Петров")
        assertFalse(storage.get().nameDirty)
        assertEquals("Иван Петров", storage.get().name)
    }

    @Test
    fun `выход стирает профиль, но не чужие ключи DataStore`() = runTest {
        storage.signedIn(ivan)
        storage.setHasPassword(false)
        tokens.save(AuthTokens("a", "r"))

        storage.clear()

        assertEquals(LocalProfile(), storage.get())
        assertEquals(AuthTokens("a", "r"), tokens.get()) // токены стирает AuthRepository отдельно
    }
}
