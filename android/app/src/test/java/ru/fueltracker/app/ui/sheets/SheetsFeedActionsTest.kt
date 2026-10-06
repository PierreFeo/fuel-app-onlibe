package ru.fueltracker.app.ui.sheets

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.fueltracker.app.R
import ru.fueltracker.app.data.local.FakeSelectedCarStorage
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.domain.model.Season
import ru.fueltracker.app.domain.model.SheetPage
import ru.fueltracker.app.domain.model.SheetStatus
import ru.fueltracker.app.testutil.FakeCarRepository
import ru.fueltracker.app.testutil.FakeSheetRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.testutil.networkError
import ru.fueltracker.app.testutil.testCar
import ru.fueltracker.app.ui.common.UiText
import java.math.BigDecimal

/** Действия в ленте (5.4): новый лист, закрытие, переоткрытие, удаление, сезон. */
class SheetsFeedActionsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val cars = FakeCarRepository().apply { cars = mutableListOf(testCar(id = "car-1")) }
    private val sheets = FakeSheetRepository()
    private val selected = FakeSelectedCarStorage(initial = "car-1")

    private val october = previewOpenSheet().copy(id = "s-10", month = 10, refuelings = emptyList())
    private val september = previewClosedSheet().copy(id = "s-09", month = 9)

    private val winterNormError = httpError(
        422,
        ErrorCodes.BUSINESS_RULE,
        message = "Зимняя норма не указана",
        details = mapOf("reason" to ErrorCodes.REASON_WINTER_NORM_NOT_SET),
    )

    private fun TestScope.createViewModel(): SheetsFeedViewModel {
        sheets.pages[null] = ApiResult.Success(SheetPage(listOf(october, september), nextBefore = null))
        val viewModel = SheetsFeedViewModel(cars, sheets, selected)
        advanceUntilIdle()
        return viewModel
    }

    // --- Новый лист ---

    @Test
    fun `Новый лист — форма из next-prefill`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        assertTrue(viewModel.state.value.isPreparingNewSheet)
        advanceUntilIdle()

        val form = viewModel.state.value.newSheet!!
        assertFalse(viewModel.state.value.isPreparingNewSheet)
        assertEquals(2026 to 11, form.year to form.month)
        assertEquals("53340", form.odometerStart)
        assertEquals("10", form.fuelStart)
    }

    @Test
    fun `next-prefill не загрузился — Snackbar, диалога нет`() = runTest {
        sheets.prefillResult = networkError
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        assertNull(viewModel.state.value.newSheet)
        assertEquals(UiText.Resource(R.string.error_no_connection), viewModel.state.value.snackbar)
    }

    @Test
    fun `создание — лист встаёт в ленту по месяцу, диалог закрыт`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetSeason(Season.WINTER))
        viewModel.onEvent(SheetsFeedEvent.NewSheetFuel("12,5"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        val (carId, input) = sheets.created.single()
        assertEquals("car-1", carId)
        assertEquals(Season.WINTER, input.season)
        assertEquals(BigDecimal("12.5"), input.fuelStartL)
        val state = viewModel.state.value
        assertNull(state.newSheet)
        assertEquals(listOf("new-2026-11", "s-10", "s-09"), state.sheets.map { it.id })
    }

    @Test
    fun `создание — ошибки полей не отправляют запрос`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetOdometer(""))
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        assertTrue(sheets.created.isEmpty())
        assertEquals(UiText.Resource(R.string.error_required), viewModel.state.value.newSheet?.odometerError)
    }

    @Test
    fun `создание — лист уже есть (409), текст сервера в диалоге`() = runTest {
        sheets.createResult = httpError(409, ErrorCodes.SHEET_EXISTS, message = "Лист за этот месяц уже есть")
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.ConfirmNewSheet)
        advanceUntilIdle()

        val form = viewModel.state.value.newSheet!!
        assertEquals(UiText.Raw("Лист за этот месяц уже есть"), form.error)
        assertFalse(form.isSaving)
    }

    @Test
    fun `в поля пробега попадают только цифры`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.NewSheet)
        advanceUntilIdle()
        viewModel.onEvent(SheetsFeedEvent.NewSheetOdometer("53 340 км"))
        assertEquals("53340", viewModel.state.value.newSheet?.odometerStart)
    }

    // --- Закрытие ---

    @Test
    fun `закрыть месяц — лист обновлён, диалог закрыт`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.CloseOdometer("54340"))
        viewModel.onEvent(SheetsFeedEvent.CloseFuel("7,5"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmClose)
        advanceUntilIdle()

        assertEquals(listOf(Triple("s-10", 54_340L, BigDecimal("7.5"))), sheets.closeCalls)
        val state = viewModel.state.value
        assertNull(state.closeSheet)
        assertEquals(SheetStatus.CLOSED, state.sheets.first { it.id == "s-10" }.status)
    }

    @Test
    fun `закрыть месяц — ошибка сервера в диалоге`() = runTest {
        sheets.actionResult = httpError(
            422,
            ErrorCodes.BUSINESS_RULE,
            message = "Остаток на конец больше, чем было топлива (на начало + заправки)",
            details = mapOf("reason" to "FUEL_END_OVER_AVAILABLE"),
        )
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.CloseOdometer("54340"))
        viewModel.onEvent(SheetsFeedEvent.CloseFuel("500"))
        viewModel.onEvent(SheetsFeedEvent.ConfirmClose)
        advanceUntilIdle()

        assertEquals(
            UiText.Raw("Остаток на конец больше, чем было топлива (на начало + заправки)"),
            viewModel.state.value.closeSheet?.error,
        )
        assertEquals(SheetStatus.OPEN, viewModel.state.value.sheets.first { it.id == "s-10" }.status)
    }

    @Test
    fun `отмена закрытия`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Close(october))
        viewModel.onEvent(SheetsFeedEvent.DismissClose)
        assertNull(viewModel.state.value.closeSheet)
    }

    // --- Переоткрытие и удаление ---

    @Test
    fun `переоткрыть закрытый месяц`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.Reopen(september))
        assertTrue("s-09" in viewModel.state.value.busySheetIds)
        advanceUntilIdle()

        assertEquals(listOf("s-09"), sheets.reopenCalls)
        val state = viewModel.state.value
        assertEquals(SheetStatus.OPEN, state.sheets.first { it.id == "s-09" }.status)
        assertTrue(state.busySheetIds.isEmpty())
        assertEquals(UiText.Resource(R.string.sheet_reopened), state.snackbar)
    }

    @Test
    fun `удалить лист — с подтверждением`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.RequestDelete(october))
        assertEquals(october, viewModel.state.value.deleteCandidate)
        viewModel.onEvent(SheetsFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("s-10"), sheets.deleteCalls)
        assertEquals(listOf("s-09"), viewModel.state.value.sheets.map { it.id })
        assertEquals(UiText.Resource(R.string.sheet_deleted), viewModel.state.value.snackbar)
    }

    @Test
    fun `удаление не удалось — лист остаётся, текст сервера`() = runTest {
        sheets.deleteResult = httpError(422, ErrorCodes.BUSINESS_RULE, message = "В листе есть заправки — сначала удалите их")
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.RequestDelete(october))
        viewModel.onEvent(SheetsFeedEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.sheets.size)
        assertEquals(UiText.Raw("В листе есть заправки — сначала удалите их"), viewModel.state.value.snackbar)
    }

    // --- Сезон ---

    @Test
    fun `переключение сезона — лист обновлён, Snackbar с новой нормой`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(october))
        advanceUntilIdle()

        assertEquals(listOf("s-10" to Season.WINTER), sheets.seasonCalls)
        val state = viewModel.state.value
        assertEquals(Season.WINTER, state.sheets.first { it.id == "s-10" }.season)
        assertEquals(UiText.Resource(R.string.sheet_season_switched_winter, listOf("11,684")), state.snackbar)
        assertNull(state.snackbarAction)
    }

    @Test
    fun `нет зимней нормы — Snackbar с кнопкой Указать, сезон не меняется`() = runTest {
        sheets.actionResult = winterNormError
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(october))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(Season.SUMMER, state.sheets.first { it.id == "s-10" }.season)
        assertEquals(UiText.Resource(R.string.winter_norm_not_set), state.snackbar)
        assertEquals(FeedSnackbarAction.SET_WINTER_NORM, state.snackbarAction)

        viewModel.onEvent(SheetsFeedEvent.SnackbarShown)
        assertNull(viewModel.state.value.snackbarAction)
    }

    @Test
    fun `сезон закрытого листа — подсказка, без запроса`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(SheetsFeedEvent.ToggleSeason(september))
        advanceUntilIdle()
        assertTrue(sheets.seasonCalls.isEmpty())
        assertEquals(UiText.Resource(R.string.sheet_closed_cannot_edit), viewModel.state.value.snackbar)
    }

    @Test
    fun `возврат на экран обновляет шапку авто`() = runTest {
        val viewModel = createViewModel()
        cars.cars = mutableListOf(testCar(id = "car-1", normWinter = null))
        viewModel.onEvent(SheetsFeedEvent.Resume)
        advanceUntilIdle()
        assertNull(viewModel.state.value.car?.normWinter)
    }
}
