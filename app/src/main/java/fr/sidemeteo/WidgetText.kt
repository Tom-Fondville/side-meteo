package fr.sidemeteo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure text formatting for the home-screen widget, split out of [buildWidgetViews] so the date
 * decisions behind it are testable without a `RemoteViews`. Every function here takes its inputs
 * as parameters, "now" included, so a test can pin the clock instead of racing it.
 */

/**
 * True when [epochMillis] falls on the same calendar day as [now]. Shared with
 * `fr.sidemeteo.ui.ForecastScreen`, which switches its own timestamp wording on the same test: a
 * day-old cache must not read like an hour-old one.
 */
fun isToday(epochMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
    return day.format(Date(epochMillis)) == day.format(Date(now))
}

/**
 * The widget header's clock: `"HH:mm"` when [epochMillis] is from today relative to [now],
 * `"dd/MM HH:mm"` otherwise — a stale overnight cache must not be misread as fresh.
 */
fun widgetClock(epochMillis: Long, now: Long): String =
    SimpleDateFormat(if (isToday(epochMillis, now)) "HH:mm" else "dd/MM HH:mm", Locale.FRENCH)
        .format(Date(epochMillis))

/** `"18 / 27°"`; `"—"` stands in for either side that is null. */
fun minMaxText(tempMin: Double?, tempMax: Double?): String =
    "${tempMin?.let { Math.round(it) } ?: "—"} / ${tempMax?.let { Math.round(it) } ?: "—"}°"

/** The widget's empty-state message: no city stored yet, versus a city with no usable forecast. */
fun emptyStateMessage(hasCity: Boolean): String =
    if (!hasCity) "Choisir une ville" else "Météo indisponible"

/** An hour cell's temperature, or `"—"` when that hour has none. */
fun hourTempText(temperature: Double?): String =
    temperature?.let { "${Math.round(it)}°" } ?: "—"

/** An hour cell's rain probability, or `""` when that hour has none. */
fun hourRainText(probability: Int?): String =
    probability?.let { "$it %" } ?: ""
