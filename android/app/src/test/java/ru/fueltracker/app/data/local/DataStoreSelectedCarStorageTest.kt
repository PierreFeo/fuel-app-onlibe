package ru.fueltracker.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящий DataStore во временной папке; токены и выбранное авто живут в одном файле. */
class DataStoreSelectedCarStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmp.root.resolve("test.preferences_pb") },
        )
    }
    private val storage by lazy { DataStoreSelectedCarStorage(dataStore) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `сначала ничего не выбрано`() = runTest {
        assertNull(storage.get())
    }

    @Test
    fun `выбор сохраняется и заменяется`() = runTest {
        storage.select("car-1")
        assertEquals("car-1", storage.get())
        storage.select("car-2")
        assertEquals("car-2", storage.get())
    }

    @Test
    fun `clearIf сбрасывает только указанное авто`() = runTest {
        storage.select("car-1")
        storage.clearIf("car-2")
        assertEquals("car-1", storage.get())
        storage.clearIf("car-1")
        assertNull(storage.get())
    }

    @Test
    fun `выбор не трогает токены`() = runTest {
        val tokens = DataStoreTokenStorage(dataStore)
        tokens.save(AuthTokens("a", "r"))
        storage.select("car-1")
        storage.clear()
        assertEquals(AuthTokens("a", "r"), tokens.get())
    }
}
