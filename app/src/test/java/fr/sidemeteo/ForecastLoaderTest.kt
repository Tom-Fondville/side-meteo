package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastLoaderTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a real body into a forecast`() {
        val forecast = parseForecastAtNow(fixture("forecast_paris.json"))

        assertNotNull(forecast)
        // Deliberately no assertion on forecast.hours: that list is sliced at the real
        // wall-clock now, and the fixture's window ends 2026-08-23, so asserting it is
        // non-empty would make this test start failing on its own one day.
        assertEquals(7, forecast!!.days.size)
        assertEquals(24, forecast.days.first().hours.size)
        assertEquals("Europe/Paris", forecast.timezone)
    }

    @Test
    fun `a malformed body degrades to null instead of throwing`() {
        assertNull(parseForecastAtNow("{ not json"))
        // Type-valid JSON that is missing everything the DTOs require.
        assertNull(parseForecastAtNow("""{"timezone":"Europe/Paris"}"""))
        assertNull(parseForecastAtNow(""))
    }

    @Test
    fun `nowIn truncates to the hour and degrades on an unusable zone`() {
        val now = nowIn("Europe/Paris")

        assertTrue(now, now.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:00""")))
        assertEquals("", nowIn("Nowhere/Nothing"))
    }
}
