package ru.fueltracker.app.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fueltracker.app.data.remote.api.AuthApi
import ru.fueltracker.app.data.remote.api.ProfileApi
import ru.fueltracker.app.data.remote.api.SyncApi
import ru.fueltracker.app.data.remote.dto.SyncRequest
import ru.fueltracker.app.data.remote.dto.RefreshRequest
import ru.fueltracker.app.data.remote.dto.VerifyCodeRequest

/** Ответы из 04_API_CONTRACT.md разбираются в DTO без потерь. */
class ApiResponseParsingTest {

    private val server = ApiTestServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun tokens_withIsNewUser() = runTest {
        server.enqueueJson(Fixtures.tokens)

        val tokens = server.api<AuthApi>().verifyCode(VerifyCodeRequest("+79991234567", "123456"))

        assertEquals("access.jwt", tokens.accessToken)
        assertEquals("refresh.jwt", tokens.refreshToken)
        assertEquals("bearer", tokens.tokenType)
        assertEquals(900, tokens.expiresInSec)
        assertEquals("+79991234567", tokens.user.phone)
        assertNull(tokens.user.name)
        assertEquals(true, tokens.isNewUser)
    }

    @Test
    fun tokens_refreshWithoutIsNewUser() = runTest {
        server.enqueueJson(
            """
            { "access_token": "a2", "refresh_token": "r2", "token_type": "bearer",
              "expires_in_sec": 900, "user": ${Fixtures.user} }
            """,
        )

        val tokens = server.api<AuthApi>().refresh(RefreshRequest("refresh.jwt"))

        assertEquals("r2", tokens.refreshToken)
        assertNull(tokens.isNewUser)
    }

    @Test
    fun unknownFields_areIgnored() = runTest {
        server.enqueueJson("""{ "id": "u1", "phone": "+79991234567", "name": "Иван", "avatar_url": "x" }""")

        val me = server.api<ProfileApi>().getMe()

        assertEquals("Иван", me.name)
    }

    @Test
    fun syncResponse_recordsDeletedAndRejected() = runTest {
        server.enqueueJson(
            """
            { "cursor": 1544, "profile": { "name": "Иван Петров" },
              "cars": [{ "id": "c1", "name": "Lada", "plate_number": null, "fuel_type": "AI95",
                         "tank_capacity_l": "50.00", "norm_l_per_100km": "10.068", "norm_winter_l_per_100km": "11.684",
                         "is_archived": false, "created_at": "2026-10-04T04:01:48.291122Z", "deleted": false }],
              "sheets": [{ "id": "s1", "car_id": "c1", "year": 2026, "month": 10, "status": "CLOSED",
                           "odometer_start_km": 52340, "odometer_end_km": 53340, "fuel_start_l": "12.00",
                           "fuel_end_actual_l": "10.00", "season": "WINTER", "norm_l_per_100km": "11.684",
                           "closed_at": "2026-10-31T18:00:00Z", "created_at": "2026-10-01T07:00:00Z", "deleted": true }],
              "refuelings": [],
              "rejected": [{ "entity": "refueling", "id": "r9", "code": "PARENT_NOT_FOUND",
                             "message": "Нет листа этой заправки" }] }
            """,
        )

        val response = server.api<SyncApi>().sync(SyncRequest(null, null, emptyList(), emptyList(), emptyList()))

        assertEquals(1544L, response.cursor)
        assertEquals("Иван Петров", response.profile?.name)
        assertEquals("11.684", response.cars.single().normWinterLPer100km)
        assertEquals(true, response.sheets.single().deleted)
        assertEquals(53_340L, response.sheets.single().odometerEndKm)
        assertEquals("PARENT_NOT_FOUND", response.rejected.single().code)
    }

    @Test
    fun noContent_204() = runTest {
        server.enqueueEmpty(204)

        server.api<AuthApi>().logout(RefreshRequest("refresh.jwt")) // не бросает исключение

        assertEquals("POST", server.takeRequest().method)
    }
}
