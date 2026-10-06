package ru.fueltracker.app.ui.profile

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
import ru.fueltracker.app.data.local.AppMode
import ru.fueltracker.app.data.local.AuthTokens
import ru.fueltracker.app.data.local.FakeTokenStorage
import ru.fueltracker.app.data.local.FakeAppStateStorage
import ru.fueltracker.app.data.local.LocalProfile
import ru.fueltracker.app.data.local.db.SyncEntity
import ru.fueltracker.app.data.remote.ApiError
import ru.fueltracker.app.data.repository.RejectedRecord
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.data.repository.SyncStatus
import ru.fueltracker.app.testutil.FakeAuthRepository
import ru.fueltracker.app.testutil.FakeProfileRepository
import ru.fueltracker.app.testutil.FakeSyncRepository
import ru.fueltracker.app.testutil.MainDispatcherRule
import ru.fueltracker.app.testutil.httpError
import ru.fueltracker.app.data.remote.dto.ErrorCodes
import ru.fueltracker.app.ui.common.UiText
import java.io.IOException
import java.time.Instant

/** Профиль читает всё с телефона — сеть не нужна. */
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val account = LocalProfile(mode = AppMode.ACCOUNT, ownerUserId = "id-1", phone = "+79991234567", name = "Иван")
    private val appState = FakeAppStateStorage(account)
    private val auth = FakeAuthRepository()
    private val sync = FakeSyncRepository()
    private val tokens = FakeTokenStorage(initial = AuthTokens("a", "r"))
    private val profileRepository = FakeProfileRepository()

    private fun TestScope.createViewModel(): ProfileViewModel {
        val viewModel = ProfileViewModel(appState, auth, sync, profileRepository, tokens)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `имя и телефон аккаунта`() = runTest {
        val state = createViewModel().state.value
        assertFalse(state.isLoading)
        assertFalse(state.isGuest)
        assertEquals("+79991234567", state.phone)
        assertEquals("Иван", state.name)
        assertFalse(state.canSaveName) // имя не меняли — сохранять нечего
    }

    @Test
    fun `аккаунт меняет имя офлайн — оно помечено для синхронизации`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("  Иван Петров "))
        assertTrue(viewModel.state.value.canSaveName)

        viewModel.onEvent(ProfileEvent.SaveName)
        advanceUntilIdle()

        assertEquals("Иван Петров", appState.current.name)
        assertTrue(appState.current.nameDirty)
        val state = viewModel.state.value
        assertEquals("Иван Петров", state.savedName)
        assertFalse(state.canSaveName)
        assertEquals(UiText.Resource(R.string.profile_name_saved), state.snackbar)
    }

    @Test
    fun `гость — имя только на телефоне, выйти нельзя`() = runTest {
        appState.clear()
        appState.startGuest("Иван")
        val viewModel = createViewModel()
        assertTrue(viewModel.state.value.isGuest)

        viewModel.onEvent(ProfileEvent.NameChanged("Пётр"))
        viewModel.onEvent(ProfileEvent.SaveName)
        viewModel.onEvent(ProfileEvent.ConfirmLogout)
        advanceUntilIdle()

        assertEquals("Пётр", appState.current.name)
        assertFalse(appState.current.nameDirty) // гостю отправлять некуда
        assertEquals(0, auth.logoutCalls)
    }

    // --- Синхронизация ---

    @Test
    fun `статус синхронизации — сколько не отправлено и когда была`() = runTest {
        val at = Instant.parse("2026-10-06T18:20:00Z")
        sync.status.value = SyncStatus(pendingChanges = 12, lastSyncAt = at)
        val state = createViewModel().state.value

        assertEquals(12, state.pendingChanges)
        assertEquals(at, state.lastSyncAt)
        assertTrue(state.canSync)
    }

    @Test
    fun `Синхронизировать — Snackbar отправлено и получено`() = runTest {
        sync.results = mutableListOf(SyncResult.Success(sent = 3, received = 5, rejected = emptyList()))
        val viewModel = createViewModel()

        viewModel.onEvent(ProfileEvent.Sync)
        assertTrue(viewModel.state.value.isSyncing)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isSyncing)
        assertEquals(UiText.Resource(R.string.sync_done, listOf(3, 5)), state.snackbar)
        assertTrue(state.rejected.isEmpty())
    }

    @Test
    fun `не принятые сервером записи — диалог «Не отправлено»`() = runTest {
        val rejected = listOf(RejectedRecord(SyncEntity.SHEET, "s1", "Лист за октябрь 2026 по этому авто уже есть"))
        sync.results = mutableListOf(SyncResult.Success(sent = 0, received = 0, rejected = rejected))
        val viewModel = createViewModel()

        viewModel.onEvent(ProfileEvent.Sync)
        advanceUntilIdle()
        assertEquals(rejected, viewModel.state.value.rejected)

        viewModel.onEvent(ProfileEvent.DismissRejected)
        assertTrue(viewModel.state.value.rejected.isEmpty())
    }

    @Test
    fun `нет связи — Проверьте интернет`() = runTest {
        sync.results = mutableListOf(SyncResult.Failure(ApiError.Network(IOException("no route"))))
        val viewModel = createViewModel()

        viewModel.onEvent(ProfileEvent.Sync)
        advanceUntilIdle()

        assertEquals(UiText.Resource(R.string.sync_failed), viewModel.state.value.snackbar)
    }

    @Test
    fun `сессия истекла — Войти снова вместо кнопки синхронизации`() = runTest {
        val viewModel = createViewModel()
        sync.results = mutableListOf(SyncResult.SessionExpired)
        viewModel.onEvent(ProfileEvent.Sync)
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.sync_session_expired), viewModel.state.value.snackbar)

        tokens.clear() // TokenAuthenticator стёр токены
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.isSessionExpired)
        assertFalse(state.canSync)
        viewModel.onEvent(ProfileEvent.Sync)
        advanceUntilIdle()
        assertEquals(1, sync.syncCalls) // кнопки нет — второй синхронизации нет
    }

    @Test
    fun `гость не синхронизирует`() = runTest {
        appState.clear()
        appState.startGuest("Иван")
        val viewModel = createViewModel()

        viewModel.onEvent(ProfileEvent.Sync)
        advanceUntilIdle()

        assertEquals(0, sync.syncCalls)
        assertFalse(viewModel.state.value.isSessionExpired)
    }

    // --- Пароль ---

    @Test
    fun `первый пароль — без текущего, после успеха пароль задан`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.OpenPassword)
        assertEquals(PasswordForm(needsCurrent = false), viewModel.state.value.passwordForm)

        viewModel.onEvent(ProfileEvent.PasswordNewChanged("мой-пароль"))
        viewModel.onEvent(ProfileEvent.PasswordRepeatChanged("мой-пароль"))
        viewModel.onEvent(ProfileEvent.SavePassword)
        assertTrue(viewModel.state.value.passwordForm!!.isSaving)
        advanceUntilIdle()

        assertEquals(listOf<Pair<String?, String>>(null to "мой-пароль"), profileRepository.changePasswordCalls)
        val state = viewModel.state.value
        assertNull(state.passwordForm)
        assertTrue(state.hasPassword)
        assertEquals(UiText.Resource(R.string.password_saved), state.snackbar)
    }

    @Test
    fun `смена — с текущим паролем`() = runTest {
        appState.setHasPassword(true)
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.OpenPassword)
        assertTrue(viewModel.state.value.passwordForm!!.needsCurrent)

        viewModel.onEvent(ProfileEvent.PasswordCurrentChanged("старый-пароль"))
        viewModel.onEvent(ProfileEvent.PasswordNewChanged("новый-пароль"))
        viewModel.onEvent(ProfileEvent.PasswordRepeatChanged("новый-пароль"))
        viewModel.onEvent(ProfileEvent.SavePassword)
        advanceUntilIdle()

        assertEquals(listOf<Pair<String?, String>>("старый-пароль" to "новый-пароль"), profileRepository.changePasswordCalls)
    }

    @Test
    fun `ошибки формы — запрос не уходит`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.OpenPassword)
        viewModel.onEvent(ProfileEvent.PasswordNewChanged("короткий"))
        viewModel.onEvent(ProfileEvent.PasswordRepeatChanged("другой-пароль"))
        viewModel.onEvent(ProfileEvent.SavePassword)
        advanceUntilIdle()

        assertTrue(profileRepository.changePasswordCalls.isEmpty())
        assertEquals(UiText.Resource(R.string.error_password_mismatch), viewModel.state.value.passwordForm?.repeatError)
    }

    @Test
    fun `неверный текущий — под полем, диалог открыт`() = runTest {
        appState.setHasPassword(true)
        profileRepository.changePasswordResult = httpError(
            400,
            ErrorCodes.VALIDATION_ERROR,
            details = mapOf("current_password" to "Неверный пароль"),
        )
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.OpenPassword)
        viewModel.onEvent(ProfileEvent.PasswordCurrentChanged("мимо-мимо"))
        viewModel.onEvent(ProfileEvent.PasswordNewChanged("новый-пароль"))
        viewModel.onEvent(ProfileEvent.PasswordRepeatChanged("новый-пароль"))
        viewModel.onEvent(ProfileEvent.SavePassword)
        advanceUntilIdle()

        val form = viewModel.state.value.passwordForm!!
        assertEquals(UiText.Resource(R.string.error_password_wrong), form.currentError)
        assertFalse(form.isSaving)
    }

    @Test
    fun `сессия истекла или гость — пароль не сменить`() = runTest {
        val viewModel = createViewModel()
        tokens.clear()
        advanceUntilIdle()
        viewModel.onEvent(ProfileEvent.OpenPassword)
        assertNull(viewModel.state.value.passwordForm)

        appState.clear()
        appState.startGuest("Иван")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.canChangePassword)
    }

    @Test
    fun `пустое имя не сохранить`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.NameChanged("   "))
        assertFalse(viewModel.state.value.canSaveName)
    }

    @Test
    fun `выход — с подтверждением`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.RequestLogout)
        assertTrue(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCalls)

        viewModel.onEvent(ProfileEvent.ConfirmLogout)
        advanceUntilIdle()

        assertEquals(1, auth.logoutCalls)
        assertTrue(viewModel.state.value.isLoggingOut)
    }

    @Test
    fun `отмена выхода`() = runTest {
        val viewModel = createViewModel()
        viewModel.onEvent(ProfileEvent.RequestLogout)
        viewModel.onEvent(ProfileEvent.DismissLogout)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.confirmLogout)
        assertEquals(0, auth.logoutCalls)
    }
}
