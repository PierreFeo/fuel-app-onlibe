package ru.fueltracker.app.ui.common

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFormatTest {

    @Test
    fun `ввод по одной цифре`() {
        assertEquals("9", PhoneFormat.applyInput("", "9"))
        assertEquals("999", PhoneFormat.applyInput("99", "999"))
    }

    @Test
    fun `11-я цифра не добавляется`() {
        assertEquals("9991234567", PhoneFormat.applyInput("9991234567", "99912345678"))
        // Даже если номер начинается с 7 или 8 — это не вставка с кодом страны
        assertEquals("8001234567", PhoneFormat.applyInput("8001234567", "80012345675"))
    }

    @Test
    fun `вставка номера с 8 или +7 убирает код страны`() {
        assertEquals("9991234567", PhoneFormat.applyInput("", "8 999 123-45-67"))
        assertEquals("9991234567", PhoneFormat.applyInput("", "+7 (999) 123-45-67"))
        assertEquals("9991234567", PhoneFormat.applyInput("", "+79991234567"))
    }

    @Test
    fun `буквы и знаки отбрасываются`() {
        assertEquals("999", PhoneFormat.applyInput("", "9a9-9"))
    }

    @Test
    fun `номер полный только из 10 цифр`() {
        assertFalse(PhoneFormat.isComplete("999123456"))
        assertTrue(PhoneFormat.isComplete("9991234567"))
    }

    @Test
    fun `номер для API`() {
        assertEquals("+79991234567", PhoneFormat.toApi("9991234567"))
    }

    @Test
    fun `маска по мере ввода`() {
        assertEquals("", PhoneFormat.mask(""))
        assertEquals("+7 (9", PhoneFormat.mask("9"))
        assertEquals("+7 (999", PhoneFormat.mask("999"))
        assertEquals("+7 (999) 1", PhoneFormat.mask("9991"))
        assertEquals("+7 (999) 123-4", PhoneFormat.mask("9991234"))
        assertEquals("+7 (999) 123-45-67", PhoneFormat.mask("9991234567"))
    }

    @Test
    fun `номер из API для показа`() {
        assertEquals("+7 (999) 123-45-67", PhoneFormat.display("+79991234567"))
    }

    @Test
    fun `курсор после маски стоит за последней цифрой`() {
        val transformed = PhoneVisualTransformation.filter(AnnotatedString("9991"))
        assertEquals("+7 (999) 1", transformed.text.text)
        val mapping = transformed.offsetMapping
        assertEquals(4, mapping.originalToTransformed(0)) // перед первой «9»
        assertEquals(10, mapping.originalToTransformed(4)) // в конце
        assertEquals(4, mapping.transformedToOriginal(10))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(3, mapping.transformedToOriginal(8)) // после «) »
    }

    @Test
    fun `таймер`() {
        assertEquals("0:59", formatCountdown(59))
        assertEquals("2:05", formatCountdown(125))
        assertEquals("0:00", formatCountdown(-3))
    }
}
