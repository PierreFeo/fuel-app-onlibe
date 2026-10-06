package ru.fueltracker.app.ui.cars

import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.ParsedInput
import ru.fueltracker.app.ui.common.UiText
import ru.fueltracker.app.ui.common.parseNonNegativeDecimal
import ru.fueltracker.app.ui.common.toInputText
import java.math.BigDecimal

/** Поля формы как их ввёл пользователь (числа — строки с «,» или «.»). */
data class CarForm(
    val name: String = "",
    val plateNumber: String = "",
    val fuelType: FuelType? = null,
    val tankCapacity: String = "",
    val normSummer: String = "",
    val normWinter: String = "",
)

data class CarFormErrors(
    val name: UiText? = null,
    val plateNumber: UiText? = null,
    val fuelType: UiText? = null,
    val tankCapacity: UiText? = null,
    val normSummer: UiText? = null,
    val normWinter: UiText? = null,
) {
    val hasAny: Boolean
        get() = listOf(name, plateNumber, fuelType, tankCapacity, normSummer, normWinter).any { it != null }
}

// Ограничения из 03_DATA_MODEL.md: name varchar(60), plate varchar(15),
// бак numeric(6,2), нормы numeric(6,3)
internal const val CAR_NAME_MAX = 60
internal const val PLATE_MAX = 15
private const val TANK_SCALE = 2
private const val NORM_SCALE = 3
private const val MAX_DIGITS = 6

/** Авто из API → поля формы: «50.00» → «50», «10.068» → «10,068». */
fun Car.toForm() = CarForm(
    name = name,
    plateNumber = plateNumber.orEmpty(),
    fuelType = fuelType,
    tankCapacity = tankCapacityL.toInputText(),
    normSummer = normSummer.toInputText(),
    normWinter = normWinter?.toInputText().orEmpty(),
)

/** Проверка как на сервере (обязательность, > 0, знаки после запятой). Ошибки нет — [CarInput] для отправки. */
fun validateCarForm(form: CarForm): Pair<CarInput?, CarFormErrors> {
    val name = form.name.trim()
    val plate = form.plateNumber.trim()
    val tank = parsePositive(form.tankCapacity, TANK_SCALE, required = true)
    val summer = parsePositive(form.normSummer, NORM_SCALE, required = true)
    val winter = parsePositive(form.normWinter, NORM_SCALE, required = false)

    val errors = CarFormErrors(
        name = when {
            name.isEmpty() -> UiText.Resource(R.string.error_required)
            name.length > CAR_NAME_MAX -> UiText.Resource(R.string.error_too_long, listOf(CAR_NAME_MAX))
            else -> null
        },
        plateNumber = if (plate.length > PLATE_MAX) UiText.Resource(R.string.error_too_long, listOf(PLATE_MAX)) else null,
        fuelType = if (form.fuelType == null) UiText.Resource(R.string.error_required) else null,
        tankCapacity = tank.error,
        normSummer = summer.error,
        normWinter = winter.error,
    )
    if (errors.hasAny) return null to errors

    val input = CarInput(
        name = name,
        plateNumber = plate.ifEmpty { null },
        fuelType = checkNotNull(form.fuelType),
        tankCapacityL = checkNotNull(tank.value),
        normSummer = checkNotNull(summer.value),
        normWinter = winter.value,
    )
    return input to errors
}

/** Как [parseNonNegativeDecimal], но ноль тоже ошибка: бак и нормы должны быть > 0. */
private fun parsePositive(text: String, scale: Int, required: Boolean): ParsedInput<BigDecimal> {
    val parsed = parseNonNegativeDecimal(text, scale, MAX_DIGITS, required)
    val notPositive = parsed.error == UiText.Resource(R.string.error_non_negative_number) || parsed.value?.signum() == 0
    return if (notPositive) ParsedInput(null, UiText.Resource(R.string.error_positive_number)) else parsed
}
