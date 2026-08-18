package fr.sidemeteo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WidgetTextTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        // widgetClock/isToday format against the JVM default zone; pin it so "same calendar
        // day" is deterministic no matter which zone happens to run this suite.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millisAt(iso: String): Long =
        checkNotNull(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.FRENCH).parse(iso)).time

    @Test
    fun `widgetClock renders a bare hour for a timestamp earlier the same day`() {
        val now = millisAt("2026-08-17T08:00:00")
        val fetchedAt = millisAt("2026-08-17T06:30:00")

        assertEquals("06:30", widgetClock(fetchedAt, now))
    }

    @Test
    fun `widgetClock renders the date for a timestamp on the previous calendar day`() {
        val now = millisAt("2026-08-17T08:00:00")
        val fetchedAt = millisAt("2026-08-16T18:03:00")

        assertEquals("16/08 18:03", widgetClock(fetchedAt, now))
    }

    @Test
    fun `widgetClock renders the date for the same clock time a different month`() {
        val now = millisAt("2026-08-17T08:00:00")
        val fetchedAt = millisAt("2026-07-17T08:00:00")

        assertEquals("17/07 08:00", widgetClock(fetchedAt, now))
    }

    @Test
    fun `minMaxText renders both values rounded`() {
        assertEquals("18 / 27°", minMaxText(18.4, 26.6))
    }

    @Test
    fun `minMaxText degrades a null max to a dash`() {
        assertEquals("18 / —°", minMaxText(18.4, null))
    }

    @Test
    fun `minMaxText degrades a null min to a dash`() {
        assertEquals("— / 27°", minMaxText(null, 26.6))
    }

    @Test
    fun `minMaxText degrades both nulls to dashes`() {
        assertEquals("— / —°", minMaxText(null, null))
    }

    @Test
    fun `emptyStateMessage asks to pick a city when none is stored`() {
        assertEquals("Choisir une ville", emptyStateMessage(hasCity = false))
    }

    @Test
    fun `emptyStateMessage reports unavailable weather when a city is stored`() {
        assertEquals("Météo indisponible", emptyStateMessage(hasCity = true))
    }

    @Test
    fun `hourTempText rounds a temperature and dashes a null`() {
        assertEquals("18°", hourTempText(18.4))
        assertEquals("—", hourTempText(null))
    }

    @Test
    fun `hourRainText renders a percentage and blanks a null`() {
        assertEquals("40 %", hourRainText(40))
        assertEquals("", hourRainText(null))
    }

    @Test
    fun `a daily row's rain reads in French with a unit, or a dash when absent`() {
        assertEquals("4,2 mm", dayRainText(4.2))
        assertEquals("0,0 mm", dayRainText(0.0))
        assertEquals("12,9 mm", dayRainText(12.85))
        assertEquals("—", dayRainText(null))
    }
}
