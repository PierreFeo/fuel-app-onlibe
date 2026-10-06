package ru.fueltracker.app.ui.cars

import androidx.annotation.StringRes
import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.FuelType

@get:StringRes
val FuelType.labelRes: Int
    get() = when (this) {
        FuelType.AI92 -> R.string.fuel_ai92
        FuelType.AI95 -> R.string.fuel_ai95
        FuelType.AI98 -> R.string.fuel_ai98
        FuelType.DIESEL -> R.string.fuel_diesel
        FuelType.GAS -> R.string.fuel_gas
    }
