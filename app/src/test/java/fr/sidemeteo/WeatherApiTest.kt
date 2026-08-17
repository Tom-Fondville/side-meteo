package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherApiTest {

    @Test
    fun `forecast url carries coordinates and every requested field`() {
        val url = WeatherApi.forecastUrl(48.8566, 2.3522)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=48.8566"))
        assertTrue(url.contains("longitude=2.3522"))
        assertTrue(url.contains("timezone=auto"))
        assertTrue(url.contains("forecast_days=7"))
        listOf(
            "temperature_2m", "relative_humidity_2m", "apparent_temperature",
            "precipitation", "weather_code", "wind_speed_10m",
            "precipitation_probability", "temperature_2m_max", "temperature_2m_min",
            "precipitation_sum", "precipitation_probability_max", "uv_index_max",
            "sunrise", "sunset",
        ).forEach { assertTrue("missing $it in $url", url.contains(it)) }
    }

    @Test
    fun `forecast url uses a dot decimal separator regardless of locale`() {
        val url = WeatherApi.forecastUrl(-3.5, 0.0)

        assertTrue(url, url.contains("latitude=-3.5"))
        assertTrue(url, url.contains("longitude=0.0"))
    }

    @Test
    fun `geocode url percent-encodes the query`() {
        val url = WeatherApi.geocodeUrl("Saint-Étienne du Rouvray")

        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url, url.contains("name=Saint-%C3%89tienne+du+Rouvray"))
        assertTrue(url.contains("count=10"))
        assertTrue(url.contains("language=fr"))
    }

    @Test
    fun `parseForecast reads a real body`() {
        val body = checkNotNull(javaClass.classLoader?.getResourceAsStream("forecast_paris.json"))
            .bufferedReader().readText()

        assertEquals("Europe/Paris", WeatherApi.parseForecast(body).timezone)
    }
}
