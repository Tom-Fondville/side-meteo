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
 */
fun ForecastResponse.toForecast(): Forecast {
    val start = hourly.time.indexOfFirst { it >= current.time }.coerceAtLeast(0)
    val end = (start + 24).coerceAtMost(hourly.time.size)

    val hours = (start until end).map { i ->
        HourEntry(
            time = hourly.time[i],
            temperature = hourly.temperature.getOrNull(i),
            precipitationProbability = hourly.precipitationProbability.getOrNull(i),
            precipitation = hourly.precipitation.getOrNull(i),
            weatherCode = hourly.weatherCode.getOrNull(i),
        )
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

/** `"2026-08-17T14:00"` -> `"14h"`. */
fun String.hourLabel(): String = substring(11, 13) + "h"

/** `"2026-08-17"` -> `"lun. 17/08"`. */
fun String.dayLabel(): String {
    val date = LocalDate.parse(this)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
    return "%s %02d/%02d".format(weekday, date.dayOfMonth, date.monthValue)
}
