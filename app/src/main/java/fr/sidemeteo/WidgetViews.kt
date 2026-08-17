package fr.sidemeteo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the widget's populated views. Split from the provider so that "what the tile looks like"
 * and "when the tile updates" stay separately readable.
 */
fun buildWidgetViews(
    context: Context,
    city: City?,
    forecast: Forecast?,
    fetchedAt: Long?,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_weather)

    views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
    views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))

    views.setTextViewText(R.id.widget_city, city?.name ?: "Météo")
    views.setTextViewText(R.id.widget_time, fetchedAt?.let { clock(it) } ?: "")

    if (city == null || forecast == null) {
        views.setViewVisibility(R.id.widget_content, View.GONE)
        views.setViewVisibility(R.id.widget_message, View.VISIBLE)
        views.setTextViewText(
            R.id.widget_message,
            if (city == null) "Choisir une ville" else "Météo indisponible",
        )
        return views
    }

    views.setViewVisibility(R.id.widget_message, View.GONE)
    views.setViewVisibility(R.id.widget_content, View.VISIBLE)

    val look = weatherLook(forecast.current.weatherCode)
    views.setTextViewText(R.id.widget_emoji, look.emoji)
    views.setTextViewText(R.id.widget_temp, "${Math.round(forecast.current.temperature)}°")
    views.setTextViewText(R.id.widget_condition, look.label)

    val today = forecast.days.firstOrNull()
    views.setTextViewText(
        R.id.widget_minmax,
        "${today?.tempMin?.let { Math.round(it) } ?: "—"} / ${today?.tempMax?.let { Math.round(it) } ?: "—"}°",
    )

    // Four fixed cells; forecast.hours already starts at the current hour.
    val cells = listOf(
        HourCell(R.id.widget_h1_time, R.id.widget_h1_emoji, R.id.widget_h1_temp, R.id.widget_h1_rain),
        HourCell(R.id.widget_h2_time, R.id.widget_h2_emoji, R.id.widget_h2_temp, R.id.widget_h2_rain),
        HourCell(R.id.widget_h3_time, R.id.widget_h3_emoji, R.id.widget_h3_temp, R.id.widget_h3_rain),
        HourCell(R.id.widget_h4_time, R.id.widget_h4_emoji, R.id.widget_h4_temp, R.id.widget_h4_rain),
    )
    cells.forEachIndexed { i, cell ->
        val hour = forecast.hours.getOrNull(i)
        views.setTextViewText(cell.time, hour?.time?.hourLabel() ?: "—")
        views.setTextViewText(cell.emoji, hour?.let { weatherLook(it.weatherCode).emoji } ?: "")
        views.setTextViewText(cell.temp, hour?.temperature?.let { "${Math.round(it)}°" } ?: "—")
        views.setTextViewText(cell.rain, hour?.precipitationProbability?.let { "$it %" } ?: "")
    }

    return views
}

private class HourCell(val time: Int, val emoji: Int, val temp: Int, val rain: Int)

private fun clock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date(epochMillis))

private fun openAppIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun refreshIntent(context: Context): PendingIntent {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))
    val intent = Intent(context, WeatherWidget::class.java).apply {
        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
    }
    return PendingIntent.getBroadcast(
        context,
        1,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
