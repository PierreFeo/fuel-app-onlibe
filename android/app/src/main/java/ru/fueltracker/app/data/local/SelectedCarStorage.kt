package ru.fueltracker.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Какое авто выбрано: по нему открывается лента ЛУТ. */
interface SelectedCarStorage {

    /** null — авто ещё не выбрано. */
    val selectedCarId: Flow<String?>

    suspend fun get(): String? = selectedCarId.first()

    suspend fun select(carId: String)

    suspend fun clear()

    /** Сбросить выбор, только если выбрано именно [carId] (авто ушло в архив). */
    suspend fun clearIf(carId: String)
}

/** `selected_car_id` в том же DataStore, что и токены. */
@Singleton
class DataStoreSelectedCarStorage @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SelectedCarStorage {

    override val selectedCarId: Flow<String?> = dataStore.data
        .map { it[SELECTED_CAR_ID] }
        .distinctUntilChanged()

    override suspend fun select(carId: String) {
        dataStore.edit { it[SELECTED_CAR_ID] = carId }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(SELECTED_CAR_ID) }
    }

    override suspend fun clearIf(carId: String) {
        dataStore.edit { if (it[SELECTED_CAR_ID] == carId) it.remove(SELECTED_CAR_ID) }
    }

    private companion object {
        val SELECTED_CAR_ID = stringPreferencesKey("selected_car_id")
    }
}
