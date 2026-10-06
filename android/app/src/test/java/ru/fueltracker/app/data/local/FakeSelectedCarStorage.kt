package ru.fueltracker.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSelectedCarStorage(initial: String? = null) : SelectedCarStorage {

    private val state = MutableStateFlow(initial)
    override val selectedCarId: StateFlow<String?> get() = state

    val current: String? get() = state.value

    override suspend fun select(carId: String) {
        state.value = carId
    }

    override suspend fun clear() {
        state.value = null
    }

    override suspend fun clearIf(carId: String) {
        if (state.value == carId) state.value = null
    }
}
