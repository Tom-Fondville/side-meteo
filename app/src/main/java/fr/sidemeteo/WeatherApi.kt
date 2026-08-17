package fr.sidemeteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Open-Meteo client. Two unauthenticated GETs, so `URL.readText()` is the whole
 * transport — no HTTP library earns its place here.
 */
object WeatherApi {

    private const val CURRENT_FIELDS =
        "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
    private const val HOURLY_FIELDS =
        "temperature_2m,precipitation_probability,precipitation,weather_code"
    private const val DAILY_FIELDS =
        "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,uv_index_max,sunrise,sunset"

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            // Locale.ROOT: a French default locale would render 48,8566 and break the query.
            "?latitude=%s&longitude=%s".format(Locale.ROOT, latitude, longitude) +
            "&current=$CURRENT_FIELDS" +
            "&hourly=$HOURLY_FIELDS" +
            "&daily=$DAILY_FIELDS" +
            "&timezone=auto&forecast_days=7"

    fun geocodeUrl(query: String): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=10&language=fr&format=json"

    fun parseForecast(body: String): ForecastResponse = lenientJson.decodeFromString(body)

    suspend fun geocode(query: String): Result<List<City>> =
        fetch(geocodeUrl(query)).mapCatching {
            lenientJson.decodeFromString<GeocodingResponse>(it).results
        }

    /** Raw body, or a failed Result. Callers never see an exception. */
    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { URL(url).readText() }
    }
}
