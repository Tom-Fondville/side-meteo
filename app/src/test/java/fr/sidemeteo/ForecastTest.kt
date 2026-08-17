package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private val response = lenientJson.decodeFromString<ForecastResponse>(fixture("forecast_paris.json"))

    @Test
    fun `keeps 24 hours starting at the current hour`() {
        val forecast = response.toForecast()

        assertEquals(24, forecast.hours.size)
        // Fixture: current.time is 10:00 and hourly starts at midnight, so the slice starts at index 10.
        assertEquals("2026-08-17T10:00", forecast.hours.first().time)
        assertEquals("2026-08-18T09:00", forecast.hours.last().time)
        assertTrue(forecast.hours.all { it.time >= response.current.time })
    }

    @Test
    fun `zips hourly arrays into records at matching indices`() {
        val forecast = response.toForecast()
        val i = response.hourly.time.indexOf("2026-08-17T10:00")

        // Whole-record equality: one assertion, and no ambiguity over which
        // JUnit assertEquals overload nullable Doubles resolve to.
        val expected = HourEntry(
            time = response.hourly.time[i],
            temperature = response.hourly.temperature[i],
            precipitationProbability = response.hourly.precipitationProbability[i],
            precipitation = response.hourly.precipitation[i],
            weatherCode = response.hourly.weatherCode[i],
        )

        assertEquals(expected, forecast.hours.first())
    }

    @Test
    fun `zips all seven days`() {
        val forecast = response.toForecast()

        assertEquals(7, forecast.days.size)
        assertEquals("2026-08-17", forecast.days.first().date)
        assertEquals("Europe/Paris", forecast.timezone)
        assertEquals(response.daily.tempMax[2]!!, forecast.days[2].tempMax!!, 0.001)
        assertEquals(response.daily.sunrise[0], forecast.days[0].sunrise)
        assertEquals(response.daily.sunset[6], forecast.days[6].sunset)
    }

    @Test
    fun `a current time between two hours rounds forward`() {
        val shifted = response.copy(current = response.current.copy(time = "2026-08-17T10:15"))

        assertEquals("2026-08-17T11:00", shifted.toForecast().hours.first().time)
    }

    @Test
    fun `takes what is available when fewer than 24 hours remain`() {
        val trimmed = response.copy(
            hourly = response.hourly.copy(
                time = response.hourly.time.take(12),
                temperature = response.hourly.temperature.take(12),
                precipitationProbability = response.hourly.precipitationProbability.take(12),
                precipitation = response.hourly.precipitation.take(12),
                weatherCode = response.hourly.weatherCode.take(12),
            ),
        )

        assertEquals(2, trimmed.toForecast().hours.size)
    }

    @Test
    fun `formats hour and day labels`() {
        assertEquals("14h", "2026-08-17T14:00".hourLabel())
        assertEquals("00h", "2026-08-17T00:00".hourLabel())
        assertEquals("lun. 17/08", "2026-08-17".dayLabel())
    }
}
