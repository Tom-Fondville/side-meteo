package fr.sidemeteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Open-Meteo client. Two unauthenticated GETs, so `java.net` is the whole
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
            "&timezone=auto&forecast_days=7" +
            // Pinned rather than inherited from the API defaults: the UI labels are hardcoded.
            "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm"

    fun geocodeUrl(query: String): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=10&language=fr&format=json"

    fun parseForecast(body: String): ForecastResponse = lenientJson.decodeFromString(body)

    suspend fun geocode(query: String): Result<List<City>> =
        fetch(geocodeUrl(query)).mapCatching {
            lenientJson.decodeFromString<GeocodingResponse>(it).results
        }

    /**
     * Raw body, or a failed Result. Callers never see an exception.
     *
     * The connection is opened explicitly rather than via `URL.readText()` so the two
     * timeouts can be set: their defaults are infinite, and a captive portal or a
     * half-open socket would otherwise hang the refresh forever with nothing thrown.
     *
     * [connectMs]/[readMs] default to the app's own values. They are sockets-level limits, not
     * coroutine cancellation: this I/O is blocking and uninterruptible, so a caller racing this
     * against `withTimeoutOrNull` only gets a bound on the wall clock by keeping these tighter
     * than its own timeout, never by relying on cancellation to land mid-read.
     */
    suspend fun fetch(url: String, connectMs: Int = 10_000, readMs: Int = 15_000): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = connectMs
                    readTimeout = readMs
                    try {
                        inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        disconnect()
                    }
                }
            }
        }
}
