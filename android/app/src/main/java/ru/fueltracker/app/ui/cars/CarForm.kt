package ru.fueltracker.app.ui.cars

import ru.fueltracker.app.R
import ru.fueltracker.app.domain.model.Car
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.ui.common.Formatters
import ru.fueltracker.app.ui.common.UiText
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

/** Ввод в числовое поле: только цифры и разделитель. */
internal fun filterDecimalInput(text: String): String = text.filter { it.isDigit() || it == ',' || it == '.' }

/** Авто из API → поля формы: «50.00» → «50», «10.068» → «10,068». */
fun Car.toForm() = CarForm(
    name = name,
    plateNumber = plateNumber.orEmpty(),
    fuelType = fuelType,
    tankCapacity = tankCapacityL.toInput(),
    normSummer = normSummer.toInput(),
    normWinter = normWinter?.toInput().orEmpty(),
)

private fun BigDecimal.toInput(): String = stripTrailingZeros().toPlainString().replace('.', ',')

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

private data class Parsed(val value: BigDecimal?, val error: UiText?)

private fun parsePositive(text: String, scale: Int, required: Boolean): Parsed {
    if (text.isBlank()) {
        return Parsed(null, if (required) UiText.Resource(R.string.error_required) else null)
    }
    // «50,00» → 50; без setScale(0) stripTrailingZeros дал бы 5E+1
    val value = Formatters.parseDecimalInput(text)?.stripTrailingZeros()?.let { if (it.scale() < 0) it.setScale(0) else it }
    return when {
        value == null || value.signum() <= 0 -> Parsed(null, UiText.Resource(R.string.error_positive_number))
        value.scale() > scale -> Parsed(null, UiText.Resource(R.string.error_max_decimals, listOf(scale)))
        // numeric(6, scale): целая часть — не больше 6 - scale цифр
        value >= BigDecimal.TEN.pow(MAX_DIGITS - scale) -> Parsed(null, UiText.Resource(R.string.error_too_big))
        else -> Parsed(value, null)
    }
}
