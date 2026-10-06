package ru.fueltracker.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.fueltracker.app.data.local.db.LocalData

/** Повторяет правила DataStoreAppStateStorage на обычной переменной. */
class FakeAppStateStorage(initial: LocalProfile = LocalProfile()) : AppStateStorage {

    private val state = MutableStateFlow(initial)
    override val profile: StateFlow<LocalProfile> get() = state

    val current: LocalProfile get() = state.value

    override suspend fun startGuest(name: String) {
        state.value = state.value.copy(mode = AppMode.GUEST, name = name, nameDirty = false)
    }

    override suspend fun signedIn(user: SignedInUser) {
        val old = state.value
        val keepLocalName = user.name == null && old.name != null
        state.value = old.copy(
            mode = AppMode.ACCOUNT,
            ownerUserId = user.id,
            phone = user.phone,
            hasPassword = user.hasPassword,
            name = if (keepLocalName) old.name else user.name,
            nameDirty = keepLocalName,
        )
    }

    override suspend fun setName(name: String) {
        state.value = state.value.copy(name = name, nameDirty = state.value.mode == AppMode.ACCOUNT)
    }

    override suspend fun setSyncedName(name: String?) {
        state.value = state.value.copy(name = name ?: state.value.name, nameDirty = false)
    }

    private val sync = MutableStateFlow(SyncInfo())
    override val syncInfo: StateFlow<SyncInfo> get() = sync

    override suspend fun saveSync(cursor: Long, at: String) {
        sync.value = SyncInfo(cursor, at)
    }

    override suspend fun setHasPassword(hasPassword: Boolean) {
        state.value = state.value.copy(hasPassword = hasPassword)
    }

    override suspend fun clear() {
        state.value = LocalProfile()
        sync.value = SyncInfo()
    }
}

/** Данные Room «в памяти»: пусто или нет и сколько раз стирали. */
class FakeLocalData(var empty: Boolean = true) : LocalData {

    var clearCalls = 0

    override suspend fun isEmpty(): Boolean = empty

    override suspend fun clearAll() {
        clearCalls++
        empty = true
    }
}
