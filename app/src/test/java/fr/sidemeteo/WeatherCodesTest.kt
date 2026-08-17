package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `maps representative codes to distinct labels`() {
        val labels = listOf(0, 3, 45, 61, 95).map { weatherLook(it).label }

        assertEquals("Ciel dégagé", weatherLook(0).label)
        assertEquals("Couvert", weatherLook(3).label)
        assertEquals("Brouillard", weatherLook(45).label)
        assertEquals("Pluie", weatherLook(61).label)
        assertEquals("Orage", weatherLook(95).label)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `groups intensity variants under the same label`() {
        assertEquals(weatherLook(61).label, weatherLook(65).label)
        assertEquals(weatherLook(71).label, weatherLook(75).label)
        assertNotEquals(weatherLook(61).label, weatherLook(71).label)
    }

    @Test
    fun `unknown and null codes fall back instead of throwing`() {
        assertEquals("Inconnu", weatherLook(999).label)
        assertEquals("Inconnu", weatherLook(null).label)
    }

    @Test
    fun `every mapped code has a non-empty emoji`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65,
                           66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)

        assertTrue(codes.all { weatherLook(it).emoji.isNotBlank() })
        assertTrue(codes.all { weatherLook(it).label != "Inconnu" })
    }
}
