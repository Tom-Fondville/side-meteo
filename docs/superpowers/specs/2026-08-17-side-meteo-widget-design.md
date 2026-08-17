# side-meteo home-screen widget — Design

Date: 2026-08-17

Builds on `2026-08-17-side-meteo-design.md`, which the app itself implements.

## Purpose

Answer "do I need a coat or an umbrella" from the home screen, without opening
the app.

Success criteria:

1. A 4×2 widget can be placed on the home screen and shows the selected city's
   current temperature, condition, today's min/max, and the next four hours.
2. It refreshes itself hourly even if the app is never opened.
3. With no network it keeps showing the last fetched data, labelled with its age.
4. No new dependency is added to the project.

## Scope

In scope:

- One widget size, 4×2, resizable.
- Hourly self-refresh, plus an on-widget refresh button and a body tap that
  opens the app.
- Extracting the fetch → parse → cache sequence the app already performs into a
  loader both the app and the widget call.

Out of scope, and why:

- Multiple widget sizes or a configuration activity — one size answers the
  question; sizes multiply layouts with no new information.
- A 7-day or hourly-scrolling widget — a `RemoteViews` collection needs a
  `RemoteViewsService` adapter, which is a large amount of machinery for a
  glance-at-it surface. The app already shows both.
- Choosing a different city per widget instance — the app has one selected city;
  per-instance state would need a configuration activity and an instance-keyed
  store.
- Jetpack Glance. It is the natural way to write this, but it is a new
  dependency, and every other choice in this project went the other way.

## Constraints

- `RemoteViews` and XML layouts. Compose cannot render in a widget, and Glance
  is barred as a new dependency.
- `updatePeriodMillis` is the scheduler. Android clamps it to 30 minutes
  minimum; this design uses 3600000 (one hour). WorkManager would be a new
  dependency; `AlarmManager` would add a boot receiver and rescheduling for no
  gain over the system's own tick.
- A `BroadcastReceiver` has roughly ten seconds of runtime. The app's HTTP
  timeouts are 10 s connect + 15 s read, which exceeds that, so the widget's
  fetch is wrapped in `withTimeout(8_000)`. Without it a slow network means the
  process is killed before `PendingResult.finish()` runs.
- Every user-facing string French, every unit metric.
- `minSdk 26`, `targetSdk 35`, JDK 17.

## Architecture

Three units, each usable without reading the others:

| Unit | Responsibility |
|---|---|
| `ForecastLoader.kt` | Getting a `Forecast`: from the network (and caching it) or from the cache |
| `WidgetViews.kt` | Turning `(City?, Forecast?, fetchedAt)` into a populated `RemoteViews` |
| `WeatherWidget.kt` | The `AppWidgetProvider`: when to update, and the async plumbing |

`WeatherViewModel` and `WeatherWidget` become the loader's two callers. Neither
knows about the other.

### ForecastLoader.kt

```kotlin
suspend fun loadForecast(store: Store, city: City): Result<Forecast>
fun cachedForecast(store: Store): Forecast?
fun parseForecastAtNow(body: String): Forecast?
internal fun nowIn(timezone: String): String
```

`loadForecast` fetches `WeatherApi.forecastUrl(city.latitude, city.longitude)`,
parses it, slices the 24-hour strip at real now in the response's own timezone,
saves the raw body to the cache, and returns the `Forecast`. It never throws:
every failure arrives as `Result.failure`, so the ViewModel's `frenchError`
handling and the widget's fall-back-to-cache behaviour both keep working.

`parseForecastAtNow` is the pure half — a body string in, a `Forecast` or `null`
out — and is where the unit tests go.

`nowIn` moves out of `WeatherViewModel` unchanged, including its degrade-to-`""`
behaviour on an unparseable timezone.

### Changes to existing code

`WeatherViewModel.refresh()` keeps its `loading` flag, its job cancellation, its
`frenchError` message shape and its cache-write ordering; the fetch → parse →
slice → save steps become one `loadForecast` call. `showCache()` becomes a
`cachedForecast(store)` call. Behaviour is unchanged — this is extraction, not
redesign.

On a successful `refresh()` the ViewModel calls an `onForecastSaved: () -> Unit`
constructor lambda, which `MainActivity` supplies as "send the widget an
`ACTION_APPWIDGET_UPDATE` broadcast". The two surfaces then never show different
data, and the ViewModel stays free of `Context` — the same lambda-instead-of-a-
dependency shape the project already uses for its `ViewModelProvider.Factory`.

The widget deliberately does **not** notify anything after its own fetch: it
re-renders directly. A cache-write notification would have the widget's fetch
trigger its own update broadcast, and that loop would poll Open-Meteo forever.

### WeatherWidget.kt

`updatePeriodMillis = 3600000`. It builds its own `Store(context.applicationContext)`
per update — the object is a thin `SharedPreferences` wrapper with no state of its
own, so there is nothing to share with the app's instance.

On update, for each widget id:

1. Render `cachedForecast(store)` immediately. A widget that waits for the
   network shows a blank tile for seconds; this shows the last known weather at
   once.
2. Take `goAsync()`, then in a coroutine call `loadForecast` inside
   `withTimeout(8_000)`. On success, re-render. On failure or timeout, leave the
   cached render in place. Call `PendingResult.finish()` on every path.

Two intents:

- body tap → `PendingIntent` to `MainActivity`
- refresh tap → `PendingIntent` broadcast to the provider's own component with
  `ACTION_APPWIDGET_UPDATE` and the widget ids

A widget receiver must be `exported`, so another app on the device can send that
refresh broadcast. It causes a weather fetch and nothing else: no data leaves
the device, and nothing is written that the app would not itself write. Accepted
rather than guarded with a signature permission.

## Layout

`res/layout/widget_weather.xml`, on a rounded background drawable:

```
┌───────────────────────────────┐
│ Lille                 ↻ 15:42 │
│  ⛅  22°         18° / 27°    │
│      Partiellement nuageux    │
│  16h    17h    18h    19h     │
│  ⛅     🌧     🌧     ☁       │
│  23°    22°    21°    20°     │
│  0%     45%    31%    10%     │
└───────────────────────────────┘
```

Four **fixed** hour cells, fed from `forecast.hours[0..3]` — that list already
starts at the current hour. A variable number of cells would need a collection
adapter; four static `LinearLayout`s cost nothing and never scroll.

Emoji are plain `TextView` text, from the same `weatherLook` mapping the app
uses, so no drawable assets are involved.

`res/xml/weather_widget_info.xml` declares the 4×2 target size, `resizeMode`
horizontal and vertical, the update period, and a preview layout.

## States

| State | Render |
|---|---|
| City chosen, fresh fetch succeeded | Full layout, timestamp = now |
| City chosen, fetch failed, cache present | Full layout from cache, timestamp = the cache's age |
| City chosen, no cache, fetch failed | "Météo indisponible", tap opens the app |
| No city chosen yet | "Choisir une ville", tap opens the app |
| A day's hours missing from the response | The affected hour cells render "—" rather than disappearing |

## Testing

Three pure unit tests, no new dependency, in a new `ForecastLoaderTest`:

1. `parseForecastAtNow` on the committed `forecast_paris.json` fixture returns a
   `Forecast` with seven days and a non-empty `hours`.
2. `parseForecastAtNow` on malformed JSON returns `null` rather than throwing —
   it runs on the offline path, where a throw would crash the widget.
3. `nowIn` returns a `yyyy-MM-dd'T'HH:mm` string ending in `:00` (truncated to
   the hour), and returns `""` for an unparseable timezone.

`RemoteViews` construction cannot be unit-tested without Robolectric, which
stays barred, so the widget is verified on the device: place it on the home
screen, screenshot it, and confirm the fetch-failure path by placing it with
airplane mode on.

The existing 23 unit tests must still pass — the loader extraction is the risk
this design carries, and those tests are what catch a regression in it.
