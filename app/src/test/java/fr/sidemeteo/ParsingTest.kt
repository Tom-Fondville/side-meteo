package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a real forecast response`() {
        val response = lenientJson.decodeFromString<ForecastResponse>(fixture("forecast_paris.json"))

        assertEquals("Europe/Paris", response.timezone)
        assertEquals("2026-08-17T10:00", response.current.time)
        assertEquals(22.8, response.current.temperature, 0.001)
        assertEquals(55, response.current.humidity)
        assertEquals(3, response.current.weatherCode)
        assertEquals(168, response.hourly.time.size)
        assertEquals(168, response.hourly.temperature.size)
        assertEquals(7, response.daily.time.size)
        assertEquals(7, response.daily.sunrise.size)
        assertEquals("2026-08-17", response.daily.time.first())
    }

    @Test
    fun `ignores unknown fields and tolerates null array slots`() {
        val body = """
            {"timezone":"Europe/Paris","brand_new_field":42,
             "current":{"time":"2026-08-17T10:00","temperature_2m":20.0,
                        "relative_humidity_2m":50,"apparent_temperature":19.0,
                        "precipitation":0.0,"weather_code":0,"wind_speed_10m":5.0},
             "hourly":{"time":["2026-08-17T10:00"],"temperature_2m":[null],
                       "precipitation_probability":[null],"precipitation":[null],
                       "weather_code":[null]},
             "daily":{"time":["2026-08-17"],"weather_code":[null],
                      "temperature_2m_max":[null],"temperature_2m_min":[null],
                      "precipitation_sum":[null],"precipitation_probability_max":[null],
                      "uv_index_max":[null],"sunrise":["2026-08-17T06:46"],
                      "sunset":["2026-08-17T21:02"]}}
        """.trimIndent()

        val response = lenientJson.decodeFromString<ForecastResponse>(body)

        assertNull(response.hourly.temperature.first())
        assertNull(response.daily.uvIndexMax.first())
    }

    @Test
    fun `parses geocoding results`() {
        val body = """
            {"results":[{"id":2998324,"name":"Lille","latitude":50.63391,"longitude":3.05512,
                         "country":"France","admin1":"Hauts-de-France","population":238695}]}
        """.trimIndent()

        val cities = lenientJson.decodeFromString<GeocodingResponse>(body).results

        assertEquals(1, cities.size)
        assertEquals("Lille", cities[0].name)
        assertEquals(50.63391, cities[0].latitude, 0.00001)
        assertEquals("Hauts-de-France", cities[0].admin1)
    }

    @Test
    fun `a geocoding response without results is an empty list, not a failure`() {
        val cities = lenientJson.decodeFromString<GeocodingResponse>("""{"generationtime_ms":0.28}""").results

        assertTrue(cities.isEmpty())
    }
}
