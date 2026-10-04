package ru.fueltracker.app.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.fueltracker.app.data.remote.dto.SheetPatchRequest

class PatchFieldTest {

    @Test
    fun absent_isNotSent() {
        assertEquals("{}", ApiJson.encodeToString(SheetPatchRequest()))
    }

    @Test
    fun presentValue_isSent() {
        assertJsonEquals(
            """{ "odometer_end_km": 53340 }""",
            ApiJson.encodeToString(SheetPatchRequest(odometerEndKm = PatchField.Present(53340))),
        )
    }

    @Test
    fun presentNull_isSentAsNull() {
        assertJsonEquals(
            """{ "odometer_end_km": null }""",
            ApiJson.encodeToString(SheetPatchRequest(odometerEndKm = PatchField.Present(null))),
        )
    }

    @Test
    fun deserialize_valueAndNull() {
        val withValue = ApiJson.decodeFromString<SheetPatchRequest>("""{ "fuel_end_actual_l": "9.50" }""")
        assertEquals(PatchField.Present("9.50"), withValue.fuelEndActualL)

        val withNull = ApiJson.decodeFromString<SheetPatchRequest>("""{ "fuel_end_actual_l": null }""")
        assertEquals(PatchField.Present<String>(null), withNull.fuelEndActualL)

        val missing = ApiJson.decodeFromString<SheetPatchRequest>("{}")
        assertEquals(PatchField.Absent, missing.fuelEndActualL)
    }

    @Test
    fun jsonWithEncodeDefaults_failsLoudly() {
        // Защита от случайной смены настроек Json: молча отправить null вместо «не менять» нельзя
        val wrongJson = Json { encodeDefaults = true }
        assertThrows(SerializationException::class.java) {
            wrongJson.encodeToString(SheetPatchRequest())
        }
    }
}
