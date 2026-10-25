package ru.fueltracker.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateTest {

    @Test
    fun `номер и регион разделяются`() {
        assertEquals("А123ВС" to "77", splitPlate("А123ВС77"))
        assertEquals("М456ОР" to "750", splitPlate("М456ОР750"))
        assertEquals("А123ВС" to "77", splitPlate(" А123 ВС 77 "))
    }

    @Test
    fun `номер не по образцу — целиком, без региона`() {
        assertEquals("ТАКСИ" to null, splitPlate("ТАКСИ"))
        assertEquals("1234АВ77" to null, splitPlate("1234АВ77"))
    }
}
