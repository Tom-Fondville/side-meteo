# side-meteo Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 4×2 home-screen widget showing the selected city's current conditions and next four hours, refreshing itself hourly, with no new dependency.

**Architecture:** The fetch → parse → slice → cache sequence moves out of `WeatherViewModel` into a `ForecastLoader.kt` that the ViewModel and the widget both call. The widget is a classic `AppWidgetProvider` + `RemoteViews`: it renders the cache instantly on every update, then attempts a fetch inside an 8-second budget (a `BroadcastReceiver` has ~10 s) and re-renders on success.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, JDK 17, `RemoteViews` + XML layouts (no Compose in widgets, no Glance), `AppWidgetProvider.updatePeriodMillis`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-17-side-meteo-widget-design.md`

## Global Constraints

- **No new dependency, not one.** Forbidden: Retrofit, OkHttp, Ktor, Hilt, Koin, Room, DataStore, navigation-compose, WorkManager, `androidx.glance`, and any test dependency (Robolectric, kotlinx-coroutines-test, compose-ui-test).
- Package `fr.sidemeteo`; `minSdk 26`, `targetSdk 35`, `compileSdk 35`; JDK 17 / `jvmTarget = "17"`.
- Every user-facing string French; every unit metric (°C, km/h, mm, %, UV).
- No failure may reach the UI or the widget as an exception. No `!!` in main source.
- `updatePeriodMillis = 3600000` (one hour). Android clamps below 30 minutes; do not try to beat it.
- The widget's fetch must be bounded by 8 seconds so the `BroadcastReceiver` always finishes inside its window.
- The widget must **never** trigger its own update after its own fetch — that loop would poll Open-Meteo forever.
- `WeatherApi.parseForecast` keeps its throwing signature; every call site wraps it.
- No `ponytail:` prefix in any comment.
- The 23 existing unit tests must keep passing. `./gradlew :app:test --tests '<class>'` does NOT work under AGP; use `./gradlew :app:testDebugUnitTest --tests 'fr.sidemeteo.<Class>'`, or `./gradlew test` for the suite.

## Two deviations from the spec, decided while planning

1. **The spec says the ViewModel pokes the widget with an `ACTION_APPWIDGET_UPDATE` broadcast.** That broadcast would make the widget run its own fetch — immediately after the app had just fetched and cached. Task 2 instead exposes `WeatherWidget.renderFromCache(context)`, and Task 3 calls it. Same visible result, one network request instead of two, and no risk of the loop the spec warns about.
2. **The spec says `withTimeout(8_000)`.** Task 2 uses `withTimeoutOrNull(8_000)` — identical budget, but it returns `null` instead of throwing `TimeoutCancellationException` into a `BroadcastReceiver`, which suits the no-exception constraint better.

---

### Task 1: Extract the shared forecast loader

Pulls the fetch/parse/cache sequence out of `WeatherViewModel` so the widget can reuse it, and puts the pure half under test. Behaviour of the app must not change.

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/ForecastLoader.kt`
- Modify: `app/src/main/java/fr/sidemeteo/WeatherViewModel.kt` (remove `nowIn` and its four `java.time` imports; rewrite `showCache()` and the body of `refresh()`'s coroutine; add one constructor parameter)
- Test: `app/src/test/java/fr/sidemeteo/ForecastLoaderTest.kt`

**Interfaces:**
- Consumes: `WeatherApi.fetch(url): Result<String>`, `WeatherApi.forecastUrl(lat, lon): String`, `WeatherApi.parseForecast(body): ForecastResponse` (**throws**), `ForecastResponse.toForecast(now: String = current.time): Forecast`, `Store.cachedBody(): String?`, `Store.cachedAt(): Long?`, `Store.saveCache(body: String)`, `City(name, latitude, longitude, country, admin1)`.
- Produces, all top-level in package `fr.sidemeteo`:
  - `internal fun nowIn(timezone: String): String`
  - `fun parseForecastAtNow(body: String): Forecast?`
  - `fun cachedForecast(store: Store): Forecast?`
  - `suspend fun loadForecast(store: Store, city: City): Result<Forecast>`
  - `WeatherViewModel(store: Store, onForecastSaved: () -> Unit = {})` — the second parameter defaults, so this task compiles and runs without Task 3.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/fr/sidemeteo/ForecastLoaderTest.kt`:

```kotlin
package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastLoaderTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a real body into a forecast`() {
        val forecast = parseForecastAtNow(fixture("forecast_paris.json"))

        assertNotNull(forecast)
        // Deliberately no assertion on forecast.hours: that list is sliced at the real
        // wall-clock now, and the fixture's window ends 2026-08-23, so asserting it is
        // non-empty would make this test start failing on its own one day.
        assertEquals(7, forecast!!.days.size)
        assertEquals(24, forecast.days.first().hours.size)
        assertEquals("Europe/Paris", forecast.timezone)
    }

    @Test
    fun `a malformed body degrades to null instead of throwing`() {
        assertNull(parseForecastAtNow("{ not json"))
        // Type-valid JSON that is missing everything the DTOs require.
        assertNull(parseForecastAtNow("""{"timezone":"Europe/Paris"}"""))
        assertNull(parseForecastAtNow(""))
    }

    @Test
    fun `nowIn truncates to the hour and degrades on an unusable zone`() {
        val now = nowIn("Europe/Paris")

        assertTrue(now, now.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:00""")))
        assertEquals("", nowIn("Nowhere/Nothing"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:testDebugUnitTest --tests 'fr.sidemeteo.ForecastLoaderTest'
```

Expected: compilation failure — `Unresolved reference 'parseForecastAtNow'` and `Unresolved reference 'nowIn'`.

- [ ] **Step 3: Write the loader**

`app/src/main/java/fr/sidemeteo/ForecastLoader.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:testDebugUnitTest --tests 'fr.sidemeteo.ForecastLoaderTest'
```

Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Step 5: Point the ViewModel at the loader**

Four edits to `app/src/main/java/fr/sidemeteo/WeatherViewModel.kt`, and nothing else in that file changes — no state field, no method signature, no message text.

First, the constructor gains a defaulted callback:

```kotlin
class WeatherViewModel(
    private val store: Store,
    /**
     * Called after a refresh has saved a fresh forecast, so another surface — the home-screen
     * widget — can re-render. A lambda rather than a `Context` keeps this class framework-free.
     */
    private val onForecastSaved: () -> Unit = {},
) : ViewModel() {
```

Second, delete the private `nowIn` function and its KDoc from this file (it now lives in `ForecastLoader.kt`), and delete these four now-unused imports:

```kotlin
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
```

Third, `showCache()` becomes:

```kotlin
    /** Renders whatever was last fetched, so a slow or dead network never shows a blank screen. */
    private fun showCache() {
        val forecast = cachedForecast(store) ?: return
        _state.update { it.copy(forecast = forecast, fetchedAt = store.cachedAt(), offline = true) }
    }
```

Fourth, inside `refresh()`, replace only the coroutine body — keep the `val city`/`refreshJob?.cancel()`/`loading = true` lines above it exactly as they are:

```kotlin
        refreshJob = viewModelScope.launch {
            loadForecast(store, city)
                .onSuccess { forecast ->
                    _state.update {
                        it.copy(
                            forecast = forecast,
                            fetchedAt = store.cachedAt(),
                            offline = false,
                            loading = false,
                            error = null,
                        )
                    }
                    onForecastSaved()
                }
                .onFailure { failure ->
                    // Keep any cached forecast on screen; the banner explains why it is stale.
                    _state.update {
                        it.copy(
                            loading = false,
                            offline = it.forecast != null,
                            error = frenchError("Échec de la mise à jour", failure),
                        )
                    }
                }
        }
```

- [ ] **Step 6: Run the whole suite and build**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew test assembleDebug
```

Expected: `BUILD SUCCESSFUL`, **26** tests passing (23 existing + 3 new), no compiler warnings. If any pre-existing test fails, stop and report — this task is an extraction and must not change behaviour.

- [ ] **Step 7: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/ForecastLoader.kt \
        app/src/main/java/fr/sidemeteo/WeatherViewModel.kt \
        app/src/test/java/fr/sidemeteo/ForecastLoaderTest.kt
git commit -m "refactor: share the fetch-parse-cache sequence via a forecast loader

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 2: The widget

Everything the home screen needs: the layout, the view builder, the provider, and the manifest entry.

**Files:**
- Create: `app/src/main/res/layout/widget_weather.xml`
- Create: `app/src/main/res/drawable/widget_background.xml`
- Create: `app/src/main/res/values/widget_colors.xml`
- Create: `app/src/main/res/xml/weather_widget_info.xml`
- Create: `app/src/main/java/fr/sidemeteo/WidgetViews.kt`
- Create: `app/src/main/java/fr/sidemeteo/WeatherWidget.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add one `<receiver>` inside `<application>`)

**Interfaces:**
- Consumes: `cachedForecast(store)`, `loadForecast(store, city)` (Task 1); `Store(context)`, `Store.city`, `Store.cachedAt()`; `Forecast(timezone, current, hours, days)`; `HourEntry(time, temperature, precipitationProbability, precipitation, weatherCode)`; `DayEntry(date, weatherCode, tempMax, tempMin, …)`; `Current(time, temperature, humidity, apparentTemperature, precipitation, weatherCode, windSpeed)`; `weatherLook(code): WeatherLook(label, emoji)`; `String.hourLabel()`.
- Produces:
  - `fun buildWidgetViews(context: Context, city: City?, forecast: Forecast?, fetchedAt: Long?): RemoteViews`
  - `class WeatherWidget : AppWidgetProvider()` with `companion object { fun renderFromCache(context: Context) }` — Task 3 calls that function.

No unit tests: `RemoteViews` and `AppWidgetProvider` cannot be constructed without the Android framework, and Robolectric is a barred dependency. The verification is on the device, in Step 7. Do not add a test dependency to make this testable.

- [ ] **Step 1: Write the colours and the background**

`app/src/main/res/values/widget_colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      A widget does not inherit the app's MaterialTheme, so its colours are explicit here.
      One dark scheme in both system themes: a home-screen tile that flips to white would
      fight most wallpapers.
    -->
    <color name="widget_background">#E61B2430</color>
    <color name="widget_text">#FFFFFFFF</color>
    <color name="widget_text_dim">#B3FFFFFF</color>
</resources>
```

`app/src/main/res/drawable/widget_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/widget_background" />
    <corners android:radius="16dp" />
</shape>
```

- [ ] **Step 2: Write the layout**

`app/src/main/res/layout/widget_weather.xml`. Every view the builder touches needs an id. The four hour cells are fixed and identical in structure — a variable count would need a `RemoteViewsService` collection adapter, which this widget does not justify.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:orientation="vertical"
    android:padding="12dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/widget_city"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:singleLine="true"
            android:textColor="@color/widget_text"
            android:textSize="15sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/widget_time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/widget_text_dim"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/widget_refresh"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="10dp"
            android:paddingEnd="2dp"
            android:text="↻"
            android:textColor="@color/widget_text"
            android:textSize="16sp" />
    </LinearLayout>

    <!-- Shown instead of the data when there is no city or no forecast at all. -->
    <TextView
        android:id="@+id/widget_message"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:gravity="center"
        android:textColor="@color/widget_text"
        android:textSize="15sp"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/widget_content"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingTop="6dp">

            <TextView
                android:id="@+id/widget_emoji"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="26sp" />

            <TextView
                android:id="@+id/widget_temp"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:paddingStart="8dp"
                android:paddingEnd="8dp"
                android:textColor="@color/widget_text"
                android:textSize="30sp" />

            <TextView
                android:id="@+id/widget_minmax"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom"
                android:layout_weight="1"
                android:gravity="end"
                android:paddingBottom="4dp"
                android:textColor="@color/widget_text"
                android:textSize="15sp" />
        </LinearLayout>

        <TextView
            android:id="@+id/widget_condition"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:singleLine="true"
            android:textColor="@color/widget_text_dim"
            android:textSize="13sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingTop="8dp">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/widget_h1_time"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/widget_h1_emoji"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/widget_h1_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text"
                    android:textSize="13sp" />

                <TextView
                    android:id="@+id/widget_h1_rain"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/widget_h2_time"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/widget_h2_emoji"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/widget_h2_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text"
                    android:textSize="13sp" />

                <TextView
                    android:id="@+id/widget_h2_rain"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/widget_h3_time"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/widget_h3_emoji"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/widget_h3_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text"
                    android:textSize="13sp" />

                <TextView
                    android:id="@+id/widget_h3_rain"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/widget_h4_time"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/widget_h4_emoji"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/widget_h4_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text"
                    android:textSize="13sp" />

                <TextView
                    android:id="@+id/widget_h4_rain"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/widget_text_dim"
                    android:textSize="11sp" />
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
```

Only `android:` attributes appear above, on purpose: the layout declares no `tools` namespace, so a `tools:` attribute would fail resource linking.

- [ ] **Step 3: Write the widget metadata**

`app/src/main/res/xml/weather_widget_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/widget_weather"
    android:minHeight="110dp"
    android:minWidth="250dp"
    android:previewLayout="@layout/widget_weather"
    android:resizeMode="horizontal|vertical"
    android:targetCellHeight="2"
    android:targetCellWidth="4"
    android:updatePeriodMillis="3600000"
    android:widgetCategory="home_screen" />
```

`android:description` needs a string resource, so create `app/src/main/res/values/strings.xml` (the project has none yet):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="widget_description">Météo actuelle et prochaines heures</string>
</resources>
```

`targetCellWidth`/`targetCellHeight` are API 31+ and are ignored below that, where `minWidth`/`minHeight` decide the size. Both are declared so the widget sizes correctly on any supported version.

- [ ] **Step 4: Write the view builder**

`app/src/main/java/fr/sidemeteo/WidgetViews.kt`:

```kotlin
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
```

`FLAG_IMMUTABLE` is mandatory from API 31 and legal from 23, so it is unconditional here.

- [ ] **Step 5: Write the provider**

`app/src/main/java/fr/sidemeteo/WeatherWidget.kt`:

```kotlin
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
```

- [ ] **Step 6: Declare the receiver**

In `app/src/main/AndroidManifest.xml`, inside `<application>` and after the existing `<activity>` block:

```xml
        <!--
          A widget provider receives system broadcasts, so it must be exported. The only action it
          honours triggers a weather fetch and nothing else: no data leaves the device and nothing
          is written that the app would not write itself.
        -->
        <receiver
            android:name=".WeatherWidget"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/weather_widget_info" />
        </receiver>
```

- [ ] **Step 7: Build, install, and verify on the device**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew test assembleDebug
ADB=/Users/t.fondville/.local/share/mise/installs/android-sdk/22.0/platform-tools/adb
$ADB devices
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, 26 tests passing, `Success` from `adb`.

If no device is attached, report that the on-device verification is pending and stop — do not claim the widget works from a green build alone. A `RemoteViews` layout error only appears at runtime, as a tile reading "Problem loading widget".

With a device attached, the widget must be placed by hand (long-press the home screen → Widgets → Météo). Then capture it and check the log for the two failure modes that do not crash anything:

```bash
$ADB exec-out screencap -p > /tmp/widget.png
$ADB logcat -d -v brief | grep -iE 'sidemeteo|AppWidget|RemoteViews|did not call finish' | tail -20
```

Confirm: city name, current temperature, condition, min/max and four hour cells all populated; the ↻ repaints; tapping the body opens the app. Then turn on airplane mode, tap ↻, and confirm the tile keeps its data and its timestamp instead of emptying.

- [ ] **Step 8: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/WidgetViews.kt \
        app/src/main/java/fr/sidemeteo/WeatherWidget.kt \
        app/src/main/res/layout/widget_weather.xml \
        app/src/main/res/drawable/widget_background.xml \
        app/src/main/res/values/widget_colors.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/res/xml/weather_widget_info.xml \
        app/src/main/AndroidManifest.xml
git commit -m "feat: 4x2 home-screen widget with hourly self-refresh

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 3: Keep the widget in step with the app, and update the docs

**Files:**
- Modify: `app/src/main/java/fr/sidemeteo/MainActivity.kt` (pass the callback into the ViewModel factory)
- Modify: `README.md` (Known gaps)

**Interfaces:**
- Consumes: `WeatherViewModel(store: Store, onForecastSaved: () -> Unit = {})` (Task 1), `WeatherWidget.renderFromCache(context: Context)` (Task 2).
- Produces: nothing further.

- [ ] **Step 1: Pass the callback through**

In `app/src/main/java/fr/sidemeteo/MainActivity.kt`, `App` currently takes only the store and builds the ViewModel with it. Change the signature and the factory so a refresh repaints the widget:

```kotlin
                Surface {
                    App(Store(applicationContext), onForecastSaved = {
                        WeatherWidget.renderFromCache(applicationContext)
                    })
                }
```

and:

```kotlin
@Composable
private fun App(store: Store, onForecastSaved: () -> Unit) {
    // A two-line factory instead of a DI framework for one dependency.
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WeatherViewModel(store, onForecastSaved) as T
        }
    }
```

Everything else in the file stays as it is.

- [ ] **Step 2: Verify**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew test assembleDebug
```

Expected: `BUILD SUCCESSFUL`, 26 tests passing, no warnings.

With a device attached, install and check the sync path specifically: place the widget, open the app, tap "Actualiser", go back to the home screen, and confirm the widget's timestamp has moved to the app's refresh time. Report it as pending if no device is attached.

- [ ] **Step 3: Update the README's known gaps**

In `README.md`, the "Known gaps" list currently opens with two entries that this work resolves:

```
- No launcher icon, so the phone shows the generic system icon.
- Days in the 7-day list are not tappable; there is no per-day detail view.
```

Both are already false — the icon and the tappable days shipped earlier. Delete those two lines. Then add one line recording what the widget does not do:

```
- The widget shows one city (the app's selected city) and refreshes hourly; Android
  will not schedule widget updates more often than every 30 minutes without a
  scheduling dependency.
```

- [ ] **Step 4: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/MainActivity.kt README.md
git commit -m "feat: repaint the widget after an in-app refresh

Co-authored-by: Claude <claude@anthropic.com>"
```

---

## Verification Summary

| Spec criterion | Verified by |
|---|---|
| 4×2 widget placeable, shows city / temp / condition / min-max / 4 hours | Task 2 Steps 2, 4, 7 |
| Refreshes hourly without the app being opened | `updatePeriodMillis = 3600000` (Task 2 Step 3); `onReceive` fetch (Step 5) |
| Offline keeps the last data with its age | `renderFromCache` before the fetch, no state change on failure (Task 2 Step 5); device check with airplane mode (Step 7) |
| No new dependency | No Gradle file appears in any task's file list |
| Loader shared, app behaviour unchanged | Task 1 Steps 5-6; the 23 pre-existing tests must stay green |
| Receiver finishes inside its window | `withTimeoutOrNull(8_000)` + `finally { pending.finish() }` (Task 2 Step 5); log check for "did not call finish" (Step 7) |
| No self-triggering update loop | `fetchAndRender` sends no broadcast (Task 2 Step 5); `renderFromCache` used for the app hook instead (Task 3 Step 1) |
| Pure logic under test | `ForecastLoaderTest`, 3 tests (Task 1) |
