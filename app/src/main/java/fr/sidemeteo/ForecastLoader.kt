package fr.sidemeteo

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Getting a forecast, from the network or from the cache. The app's ViewModel and the home-screen
 * widget are the two callers, so the sequence — fetch, parse, slice at real now, cache the raw
 * body — lives here once rather than in both.
 */

/**
 * Real now in the given timezone, truncated to the hour and shaped like the API's timestamps, so a
 * replayed cache slices its 24-hour strip from now instead of from the fetch time. Truncation
 * matters: `current.time` is snapped to a 15-minute mark, so an untruncated now would skip the
 * in-progress hour. An unusable timezone degrades to `""`, which makes `toForecast` fall back to
 * `current.time` — this runs on the offline path and must never throw.
 */
internal fun nowIn(timezone: String): String =
    runCatching {
        ZonedDateTime.now(ZoneId.of(timezone))
            .truncatedTo(ChronoUnit.HOURS)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }.getOrElse { "" }

/**
 * Parses a response body and slices its 24-hour strip at real now. Returns null on any failure:
 * `parseForecast` throws on malformed JSON, and both callers render this on paths where a throw
 * would take down a screen or a widget.
 */
fun parseForecastAtNow(body: String): Forecast? =
    runCatching {
        val response = WeatherApi.parseForecast(body)
        response.toForecast(nowIn(response.timezone))
    }.getOrNull()

/** The offline path: whatever was last fetched, or null when there is no usable cache. */
fun cachedForecast(store: Store): Forecast? = store.cachedBody()?.let(::parseForecastAtNow)

/**
 * Fetches, parses and caches, then returns the forecast. Never throws — every failure arrives as
 * `Result.failure`. The cache holds the raw body, so a cached read goes back through the same parse
 * path, and a body that fails to parse is never cached.
 */
suspend fun loadForecast(store: Store, city: City): Result<Forecast> =
    WeatherApi.fetch(WeatherApi.forecastUrl(city.latitude, city.longitude))
        .mapCatching { body ->
            val response = WeatherApi.parseForecast(body)
            val forecast = response.toForecast(nowIn(response.timezone))
            store.saveCache(body)
            forecast
        }
