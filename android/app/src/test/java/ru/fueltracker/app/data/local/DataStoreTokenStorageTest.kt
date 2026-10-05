package ru.fueltracker.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Настоящий DataStore во временной папке. */
class DataStoreTokenStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val storage by lazy {
        DataStoreTokenStorage(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tmp.root.resolve("test.preferences_pb") },
            ),
        )
    }

    private val first = AuthTokens("access-1", "refresh-1")
    private val second = AuthTokens("access-2", "refresh-2")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun empty_returnsNull() = runTest {
        assertNull(storage.get())
    }

    @Test
    fun save_thenGet() = runTest {
        storage.save(first)

        assertEquals(first, storage.get())
        assertEquals(first, storage.tokens.first())
    }

    @Test
    fun clear_removesTokens() = runTest {
        storage.save(first)

        storage.clear()

        assertNull(storage.get())
    }

    @Test
    fun replaceIfCurrent_sameRefresh_replaces() = runTest {
        storage.save(first)

        assertTrue(storage.replaceIfCurrent("refresh-1", second))
        assertEquals(second, storage.get())
    }

    @Test
    fun replaceIfCurrent_afterLogout_doesNotRestoreTokens() = runTest {
        storage.save(first)
        storage.clear()

        assertFalse(storage.replaceIfCurrent("refresh-1", second))
        assertNull(storage.get())
    }

    @Test
    fun replaceIfCurrent_otherRefresh_keepsCurrent() = runTest {
        storage.save(second)

        assertFalse(storage.replaceIfCurrent("refresh-1", AuthTokens("access-3", "refresh-3")))
        assertEquals(second, storage.get())
    }

    @Test
    fun clearIfCurrent_onlyMatchingRefresh() = runTest {
        storage.save(second)

        storage.clearIfCurrent("refresh-1")
        assertEquals(second, storage.get())

        storage.clearIfCurrent("refresh-2")
        assertNull(storage.get())
    }

    @Test
    fun toString_hidesTokens() {
        assertFalse(first.toString().contains("access-1"))
        assertFalse(first.toString().contains("refresh-1"))
    }
}
