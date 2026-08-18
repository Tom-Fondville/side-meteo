package fr.sidemeteo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * Builds the widget's populated views. Split from the provider so that "what the tile looks like"
 * and "when the tile updates" stay separately readable.
 *
 * There is one builder per shape rather than one builder with branches, because a `RemoteViews`
 * action aimed at an id the chosen layout does not contain throws when the launcher applies it —
 * the tiny and compact layouts have no clock, no refresh glyph and no hour cells, so nothing may set them.
 */

/**
 * The views for one placed widget, sized to that widget's own footprint.
 *
 * On Android 12+ every shape is handed to the launcher at once, keyed by the smallest footprint it
 * suits, and the launcher swaps between them as the user resizes with no further broadcast. Below
 * that the API does not exist, so the size is read from the widget's options bundle and one shape
 * is returned; [WeatherWidget.onAppWidgetOptionsChanged] is what repaints after a resize there.
 */
fun buildWidgetViewsFor(
    context: Context,
    appWidgetId: Int,
    city: City?,
    forecast: Forecast?,
    fetchedAt: Long?,
): RemoteViews {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return RemoteViews(
            mapOf(
                SizeF(57f, 40f) to buildWidgetViews(context, WidgetSize.TINY, city, forecast, fetchedAt),
                SizeF(100f, 40f) to buildWidgetViews(context, WidgetSize.COMPACT, city, forecast, fetchedAt),
                SizeF(200f, 40f) to buildWidgetViews(context, WidgetSize.ROW, city, forecast, fetchedAt),
                SizeF(250f, 100f) to buildWidgetViews(context, WidgetSize.FULL, city, forecast, fetchedAt),
            ),
        )
    }

    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val size = widgetSizeFor(
        widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0,
        heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0,
    )
    return buildWidgetViews(context, size, city, forecast, fetchedAt)
}

/** One shape's views. Public so a caller can build a specific shape without a placed widget. */
fun buildWidgetViews(
    context: Context,
    size: WidgetSize,
    city: City?,
    forecast: Forecast?,
    fetchedAt: Long?,
): RemoteViews = when (size) {
    WidgetSize.FULL -> buildFull(context, city, forecast, fetchedAt)
    WidgetSize.ROW -> buildRow(context, city, forecast, fetchedAt)
    WidgetSize.COMPACT -> buildCompact(context, city, forecast)
    WidgetSize.TINY -> buildTiny(context, city, forecast)
}

private fun buildFull(
    context: Context,
    city: City?,
    forecast: Forecast?,
    fetchedAt: Long?,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_weather)
    header(views, context, city, fetchedAt)
    if (emptyState(views, city, forecast)) return views

    current(views, forecast!!)
    views.setTextViewText(R.id.widget_condition, weatherLook(forecast.current.weatherCode).label)
    views.setTextViewText(R.id.widget_minmax, todayRange(forecast))

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
        views.setTextViewText(cell.temp, hourTempText(hour?.temperature))
        views.setTextViewText(cell.rain, hourRainText(hour?.precipitationProbability))
    }
    return views
}

private fun buildRow(
    context: Context,
    city: City?,
    forecast: Forecast?,
    fetchedAt: Long?,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_weather_row)
    header(views, context, city, fetchedAt)
    if (emptyState(views, city, forecast)) return views

    current(views, forecast!!)
    views.setTextViewText(R.id.widget_condition, weatherLook(forecast.current.weatherCode).label)
    views.setTextViewText(R.id.widget_minmax, todayRange(forecast))
    return views
}

private fun buildCompact(context: Context, city: City?, forecast: Forecast?): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_weather_compact)
    views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
    // No clock and no refresh glyph at this size: today's range earns the room instead.
    views.setTextViewText(R.id.widget_city, city?.name ?: "Météo")
    if (emptyState(views, city, forecast)) return views

    current(views, forecast!!)
    views.setTextViewText(R.id.widget_minmax, todayRange(forecast))
    return views
}

private fun buildTiny(context: Context, city: City?, forecast: Forecast?): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_weather_tiny)
    views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
    // No clock and no refresh glyph at this size: the city name is worth the room instead.
    views.setTextViewText(R.id.widget_city, city?.name ?: "Météo")
    if (emptyState(views, city, forecast)) return views

    current(views, forecast!!)
    return views
}

/** City, timestamp and the two tap targets — the ROW and FULL shapes only. */
private fun header(views: RemoteViews, context: Context, city: City?, fetchedAt: Long?) {
    views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
    views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
    views.setTextViewText(R.id.widget_city, city?.name ?: "Météo")
    views.setTextViewText(
        R.id.widget_time,
        fetchedAt?.let { widgetClock(it, System.currentTimeMillis()) } ?: "",
    )
}

/**
 * Swaps the message in for the data when there is nothing to show. Returns true when the caller
 * should stop — there is no forecast to render.
 */
private fun emptyState(views: RemoteViews, city: City?, forecast: Forecast?): Boolean {
    val empty = city == null || forecast == null
    views.setViewVisibility(R.id.widget_content, if (empty) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.widget_message, if (empty) View.VISIBLE else View.GONE)
    if (empty) {
        views.setTextViewText(R.id.widget_message, emptyStateMessage(hasCity = city != null))
    }
    return empty
}

/** The condition emoji and the current temperature, which every shape shows. */
private fun current(views: RemoteViews, forecast: Forecast) {
    views.setTextViewText(R.id.widget_emoji, weatherLook(forecast.current.weatherCode).emoji)
    views.setTextViewText(R.id.widget_temp, "${Math.round(forecast.current.temperature)}°")
}

private fun todayRange(forecast: Forecast): String {
    val today = forecast.days.firstOrNull()
    return minMaxText(today?.tempMin, today?.tempMax)
}

private class HourCell(val time: Int, val emoji: Int, val temp: Int, val rain: Int)

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
