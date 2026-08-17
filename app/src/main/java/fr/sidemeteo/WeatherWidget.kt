package fr.sidemeteo

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen widget. Refreshes on the system's hourly tick and on the ↻ tap.
 *
 * Every update renders the cache first and only then attempts the network: a tile that waits for
 * a response shows nothing for seconds, and on a dead network it would show nothing at all.
 */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        renderFromCache(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Dispatches onUpdate, which paints the cached forecast immediately.
        super.onReceive(context, intent)
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) return

        // A BroadcastReceiver gets roughly ten seconds, while the app's HTTP timeouts are 10 s
        // connect + 15 s read. The 8-second budget below guarantees finish() is reached.
        val pending = goAsync()
        scope.launch {
            try {
                fetchAndRender(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetchAndRender(context: Context) {
        val store = Store(context)
        val city = store.city ?: return
        withTimeoutOrNull(FETCH_BUDGET_MILLIS) { loadForecast(store, city) }
            ?.onSuccess { forecast ->
                render(context, city, forecast, store.cachedAt())
            }
        // On failure or timeout the cached render from onUpdate stays on screen, with its own
        // timestamp. Deliberately no update broadcast here: the fetch has already written the
        // cache, and re-broadcasting would make this widget trigger itself forever.
    }

    companion object {
        private const val FETCH_BUDGET_MILLIS = 8_000L

        // The provider instance is recreated per broadcast, so the scope cannot live on it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Repaints every placed widget from the cache. Also the app's hook: after a successful
         * in-app refresh the cache is fresh, so this is all the widget needs — no second fetch.
         */
        fun renderFromCache(context: Context) {
            val store = Store(context.applicationContext)
            render(context, store.city, cachedForecast(store), store.cachedAt())
        }

        private fun render(context: Context, city: City?, forecast: Forecast?, fetchedAt: Long?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildWidgetViews(context, city, forecast, fetchedAt)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
