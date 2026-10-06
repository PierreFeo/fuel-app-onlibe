package ru.fueltracker.app.data.repository

import ru.fueltracker.app.domain.calc.SheetRuleViolation

/**
 * Результат изменения данных на телефоне: получилось или нарушено бизнес-правило
 * (docs/06_BUSINESS_RULES.md, «Валидации»). Сетевых ошибок здесь не бывает — данные в Room.
 */
sealed interface LocalResult<out T> {
    data class Ok<T>(val value: T) : LocalResult<T>
    data class Rejected(val violation: SheetRuleViolation) : LocalResult<Nothing>
}

internal fun <T> T.ok(): LocalResult<T> = LocalResult.Ok(this)

internal fun SheetRuleViolation.rejected(): LocalResult<Nothing> = LocalResult.Rejected(this)
