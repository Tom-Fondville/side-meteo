package fr.sidemeteo

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class HourEntry(
    val time: String,
    val temperature: Double?,
    val precipitationProbability: Int?,
    val precipitation: Double?,
    val weatherCode: Int?,
)

data class DayEntry(
    val date: String,
    val weatherCode: Int?,
    val tempMax: Double?,
    val tempMin: Double?,
    val precipitationSum: Double?,
    val precipitationProbabilityMax: Int?,
    val uvIndexMax: Double?,
    val sunrise: String,
    val sunset: String,
)

data class Forecast(
    val timezone: String,
    val current: Current,
    val hours: List<HourEntry>,
    val days: List<DayEntry>,
)

/**
 * Flattens Open-Meteo's parallel arrays into records, keeping the 24 hours from now on.
 *
 * ISO-8601 timestamps in a fixed format and a single timezone compare correctly as
 * strings, so no date parsing is needed to find the starting index.
 *
 * [now] is the reference instant, in the response's own timezone and in the API's
 * `"yyyy-MM-dd'T'HH:mm"` shape. It defaults to the response's own `current.time`, which is
 * the fetch time — a cached body replayed hours later must pass real now instead, or the
 * strip headed "next 24 h" is mostly history. Callers passing real now must truncate it to
 * the hour: `current.time` is snapped to a 15-minute mark, so an untruncated now would skip
 * the in-progress hour. A [now] past the whole window yields no hours at all, rather than
 * silently falling back to the oldest 24.
 */
fun ForecastResponse.toForecast(now: String = current.time): Forecast {
    val from = maxOf(now, current.time)
    val start = hourly.time.indexOfFirst { it >= from }

    val hours = if (start < 0) emptyList() else {
        val end = (start + 24).coerceAtMost(hourly.time.size)
        (start until end).map { i ->
            HourEntry(
                time = hourly.time[i],
                temperature = hourly.temperature.getOrNull(i),
                precipitationProbability = hourly.precipitationProbability.getOrNull(i),
                precipitation = hourly.precipitation.getOrNull(i),
                weatherCode = hourly.weatherCode.getOrNull(i),
            )
        }
    }

    val days = daily.time.indices.map { i ->
        DayEntry(
            date = daily.time[i],
            weatherCode = daily.weatherCode.getOrNull(i),
            tempMax = daily.tempMax.getOrNull(i),
            tempMin = daily.tempMin.getOrNull(i),
            precipitationSum = daily.precipitationSum.getOrNull(i),
            precipitationProbabilityMax = daily.precipitationProbabilityMax.getOrNull(i),
            uvIndexMax = daily.uvIndexMax.getOrNull(i),
            sunrise = daily.sunrise.getOrElse(i) { "" },
            sunset = daily.sunset.getOrElse(i) { "" },
        )
    }

    return Forecast(timezone = timezone, current = current, hours = hours, days = days)
}

/**
 * `"2026-08-17T14:00"` -> `"14h"`.
 *
 * Both label helpers run inside composables, outside every `runCatching`, so they degrade to
 * the raw input rather than throwing on a malformed-but-type-valid timestamp.
 */
fun String.hourLabel(): String = if (length >= 13) substring(11, 13) + "h" else this

/** `"2026-08-17"` -> `"lun. 17/08"`. */
fun String.dayLabel(): String {
    val date = runCatching { LocalDate.parse(this) }.getOrNull() ?: return this
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
    return "%s %02d/%02d".format(weekday, date.dayOfMonth, date.monthValue)
}
