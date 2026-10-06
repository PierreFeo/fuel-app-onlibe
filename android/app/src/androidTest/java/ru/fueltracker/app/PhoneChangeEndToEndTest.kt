package ru.fueltracker.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.fueltracker.app.data.local.DataStoreAppStateStorage
import ru.fueltracker.app.data.local.DataStoreSelectedCarStorage
import ru.fueltracker.app.data.local.DataStoreTokenStorage
import ru.fueltracker.app.data.local.db.AppDatabase
import ru.fueltracker.app.data.local.db.RoomLocalData
import ru.fueltracker.app.data.local.db.RoomSyncStore
import ru.fueltracker.app.data.remote.ApiJson
import ru.fueltracker.app.data.remote.ApiResult
import ru.fueltracker.app.data.remote.AuthInterceptor
import ru.fueltracker.app.data.remote.TokenAuthenticator
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.SyncApi
import ru.fueltracker.app.data.remote.api.TokenRefreshApi
import ru.fueltracker.app.data.repository.DefaultAuthRepository
import ru.fueltracker.app.data.repository.DefaultCarRepository
import ru.fueltracker.app.data.repository.DefaultRefuelingRepository
import ru.fueltracker.app.data.repository.DefaultSheetRepository
import ru.fueltracker.app.data.repository.DefaultSyncRepository
import ru.fueltracker.app.data.repository.LocalResult
import ru.fueltracker.app.data.repository.SyncResult
import ru.fueltracker.app.di.NetworkModule
import ru.fueltracker.app.domain.model.CarInput
import ru.fueltracker.app.domain.model.FuelType
import ru.fueltracker.app.domain.model.NewSheetInput
import ru.fueltracker.app.domain.model.PaymentType
import ru.fueltracker.app.domain.model.RefuelingInput
import ru.fueltracker.app.domain.model.Season
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Сквозная проверка 6.12 (docs/08_ROADMAP.md): гость завёл данные → вошёл → синхронизировался;
 * «новый телефон» вошёл тем же номером и получил всё; удаление тоже доходит.
 * Два «телефона» — две отдельные базы Room и два DataStore; сервер — настоящий локальный backend
 * (`docker compose up -d`, ENV=dev, тестовый номер +70000000000 с кодом 000000).
 * Если backend не запущен — тест пропускается, а не падает.
 */
@RunWith(AndroidJUnit4::class)
class PhoneChangeEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phones = mutableListOf<Phone>()

    @Before
    fun backendIsUp() {
        val up = runCatching {
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(BuildConfig.BASE_URL + "health").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assumeTrue("Локальный backend не запущен — пропускаем сквозной тест", up)
    }

    @After
    fun tearDown() = phones.forEach { it.close() }

    @Test
    fun guestDataReachesNewPhoneAndDeletionFollows(): Unit = runBlocking {
        val today = LocalDate.now()
        val carName = "E2E " + UUID.randomUUID().toString().take(8)

        // Телефон A: гость ведёт ЛУТ без сервера
        val a = phone()
        a.appState.startGuest("Гость E2E")
        val carId = a.cars.createCar(CarInput(carName, null, FuelType.AI95, BigDecimal("50"), BigDecimal("8.5"), null)).id
        val created = a.sheets.createSheet(carId, NewSheetInput(today.year, today.monthValue, 52_340, BigDecimal("12"), Season.SUMMER))
        assertEquals(LocalResult.Ok(Unit), created)
        val sheetId = a.sheets.observeSheets(carId).first().single().id
        val refueling = RefuelingInput(today, BigDecimal("40"), BigDecimal("55"), null, null, null, PaymentType.PERSONAL, null)
        a.refuelings.create(sheetId, refueling)
        a.refuelings.create(sheetId, refueling)
        a.sheets.closeSheet(sheetId, 53_340, BigDecimal("10"))

        // Гость входит — его данные уходят на сервер
        assertTrue(a.auth.verifyCode(DEV_PHONE, DEV_CODE) is ApiResult.Success)
        val sent = a.sync.sync()
        assertTrue("$sent", sent is SyncResult.Success && sent.rejected.isEmpty())
        assertEquals(0, a.sync.observeStatus().first().pendingChanges)

        // Телефон B: чистый, вход тем же номером — пришло всё, итоги совпадают (пример A)
        val b = phone()
        assertTrue(b.auth.verifyCode(DEV_PHONE, DEV_CODE) is ApiResult.Success)
        assertTrue(b.sync.sync() is SyncResult.Success)
        assertEquals(carName, b.cars.getCar(carId)?.name)
        val sheetOnB = b.sheets.observeSheets(carId).first().single()
        assertEquals(sheetId, sheetOnB.id)
        assertEquals(2, sheetOnB.refuelings.size)
        assertEquals(BigDecimal("8.200"), sheetOnB.calc.actualLPer100km)
        assertEquals(BigDecimal("4400.00"), sheetOnB.calc.refueledCost)

        // На A переоткрыли месяц и удалили заправку → B после синхронизации её не видит
        a.sheets.reopenSheet(sheetId)
        a.refuelings.delete(a.sheets.observeSheets(carId).first().single().refuelings.first().id)
        assertTrue(a.sync.sync() is SyncResult.Success)
        assertTrue(b.sync.sync() is SyncResult.Success)
        assertEquals(1, b.sheets.observeSheets(carId).first().single().refuelings.size)

        // Архив, чтобы тестовые авто не копились в списке dev-аккаунта
        a.cars.archiveCar(carId)
        a.sync.sync()
    }

    private fun phone(): Phone = Phone(context, "phone-${phones.size}-${UUID.randomUUID()}").also { phones += it }

    private companion object {
        const val DEV_PHONE = "+70000000000"
        const val DEV_CODE = "000000"
    }
}

/** Один «телефон»: своя Room, свой DataStore, те же классы, что в приложении. */
private class Phone(context: Context, name: String) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        context.filesDir.resolve("e2e/$name.preferences_pb")
    }
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val clock = Clock.systemDefaultZone()

    val tokens = DataStoreTokenStorage(dataStore)
    val appState = DataStoreAppStateStorage(dataStore)
    private val selectedCar = DataStoreSelectedCarStorage(dataStore)

    private lateinit var refreshApi: TokenRefreshApi
    private val retrofit = NetworkModule.createRetrofit(
        BuildConfig.BASE_URL,
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens) { refreshApi })
            .build(),
        ApiJson,
    ).also { refreshApi = it.create(TokenRefreshApi::class.java) }

    val auth = DefaultAuthRepository(retrofit.create(AuthApi::class.java), tokens, selectedCar, appState, RoomLocalData(db))
    val sync = DefaultSyncRepository(retrofit.create(SyncApi::class.java), RoomSyncStore(db), appState, tokens, clock)
    val cars = DefaultCarRepository(db, clock)
    val sheets = DefaultSheetRepository(db, clock)
    val refuelings = DefaultRefuelingRepository(db)

    fun close() {
        db.close()
        scope.cancel()
    }
}
