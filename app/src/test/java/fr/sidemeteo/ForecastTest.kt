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
    fun `a cached body slices from the given now, not from the stale fetch time`() {
        // The fixture was fetched at 10:00; replay it as if it were now 22:00 the same day.
        val forecast = response.toForecast(now = "2026-08-17T22:00")

        assertEquals(24, forecast.hours.size)
        assertEquals("2026-08-17T22:00", forecast.hours.first().time)
        assertEquals("2026-08-18T21:00", forecast.hours.last().time)
    }

    @Test
    fun `a now past the whole window yields no hours`() {
        // Hourly data ends at 2026-08-23T23:00, so a week-old cache has nothing left to show.
        assertTrue(response.toForecast(now = "2026-08-24T00:00").hours.isEmpty())
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
    fun `every day carries its own 24 hours`() {
        val forecast = response.toForecast()

        assertEquals(7, forecast.days.size)
        forecast.days.forEach { day ->
            assertEquals("hours for ${day.date}", 24, day.hours.size)
            assertTrue(
                "hours for ${day.date} belong to another day",
                day.hours.all { it.time.startsWith(day.date) },
            )
        }
        // Per-day hours are the whole response, not the next-24 slice: 7 x 24 = 168.
        assertEquals(168, forecast.days.sumOf { it.hours.size })
        assertEquals("2026-08-18T00:00", forecast.days[1].hours.first().time)
        assertEquals("2026-08-18T23:00", forecast.days[1].hours.last().time)
    }

    @Test
    fun `a truncated hourly array leaves later days empty rather than throwing`() {
        val trimmed = response.copy(
            hourly = response.hourly.copy(
                time = response.hourly.time.take(12),
                temperature = response.hourly.temperature.take(12),
                precipitationProbability = response.hourly.precipitationProbability.take(12),
                precipitation = response.hourly.precipitation.take(12),
                weatherCode = response.hourly.weatherCode.take(12),
            ),
        )

        val days = trimmed.toForecast().days

        assertEquals(7, days.size)
        assertEquals(12, days[0].hours.size)
        assertTrue(days.drop(1).all { it.hours.isEmpty() })
    }

    @Test
    fun `per-day hours are unaffected by the reference instant`() {
        // `now` slices the 24-hour strip only; a day's own hours always start at its midnight.
        val forecast = response.toForecast(now = "2026-08-20T15:00")

        assertEquals("2026-08-17T00:00", forecast.days.first().hours.first().time)
        assertEquals(24, forecast.days.first().hours.size)
    }

    @Test
    fun `formats hour and day labels`() {
        assertEquals("14h", "2026-08-17T14:00".hourLabel())
        assertEquals("00h", "2026-08-17T00:00".hourLabel())
        assertEquals("lun. 17/08", "2026-08-17".dayLabel())
    }
}
