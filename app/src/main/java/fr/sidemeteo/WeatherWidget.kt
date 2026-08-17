package fr.sidemeteo

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

        val pending = goAsync()

        // A tap while a fetch is already in flight is skipped rather than queued or cancelled:
        // five taps would otherwise mean five sockets and five cache writes, with last-writer-wins
        // letting an older response clobber a newer one. finish() still runs on this path.
        if (inFlight?.isActive == true) {
            Log.d(TAG, "update skipped: a fetch is already in flight")
            pending.finish()
            return
        }

        inFlight = scope.launch {
            try {
                // Containment, not error handling: fetchAndRender already turns network and parse
                // failures into a logged Result, so this only catches what escapes that — a prefs
                // failure, an updateAppWidget IllegalArgumentException — so it can never reach the
                // process as an uncaught exception on this bare SupervisorJob scope.
                runCatching { fetchAndRender(context.applicationContext) }
                    .onFailure { Log.e(TAG, "widget update crashed", it) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetchAndRender(context: Context) {
        val store = Store(context)
        val city = store.city ?: return
        // The transport timeouts (3 s connect / 4 s read) are what actually bound the socket: the
        // fetch is blocking, uninterruptible I/O, so cancelling the coroutine at 8 s cannot stop it
        // mid-read. withTimeoutOrNull only stops *waiting* at 8 s; paired with those tighter
        // transport timeouts, the real worst case lands well inside the ~60 s window a
        // BroadcastReceiver's goAsync() grants — not because 8 s guarantees it by itself.
        val result = withTimeoutOrNull(FETCH_BUDGET_MILLIS) {
            loadForecast(store, city, connectMs = 3_000, readMs = 4_000)
        }
        if (result == null) {
            Log.w(TAG, "widget fetch timed out after ${FETCH_BUDGET_MILLIS}ms")
            return
        }
        result
            .onSuccess { forecast -> render(context, city, forecast, store.cachedAt()) }
            .onFailure { failure -> Log.w(TAG, "widget fetch failed", failure) }
        // On failure or timeout the cached render from onUpdate stays on screen, with its own
        // timestamp. Deliberately no update broadcast here: the fetch has already written the
        // cache, and re-broadcasting would make this widget trigger itself forever.
    }

    companion object {
        private const val TAG = "WeatherWidget"
        private const val FETCH_BUDGET_MILLIS = 8_000L

        // The provider instance is recreated per broadcast, so the scope and the in-flight
        // tracking cannot live on it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var inFlight: Job? = null

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
