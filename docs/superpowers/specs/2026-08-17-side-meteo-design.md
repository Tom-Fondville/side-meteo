# side-meteo — Design

Date: 2026-08-17

## Purpose

A weather app for a de-Googled Android phone. No Google Play Services, no
API key, no account. Data comes from the free Open-Meteo API.

Success criteria:

1. `./gradlew assembleDebug` produces an APK that installs by sideload.
2. Launch shows current conditions, next 24 hours, and 7 days for a city
   the user picked.
3. With no network, the last fetched forecast is shown with its age.

## Scope

In scope:

- Current conditions: temperature, apparent temperature, weather condition,
  wind speed, relative humidity, precipitation.
- Next 24 hours, hourly: temperature, precipitation probability,
  precipitation amount, condition.
- Next 7 days, daily: min/max temperature, condition, precipitation sum,
  precipitation probability, UV index max, sunrise, sunset.
- City selection by name search, with the choice remembered across launches.
- Offline display of the last successful response.

Out of scope (and why):

- GPS / device location — the user chose manual city search; avoids a
  runtime permission and a battery cost for a phone that mostly sits in
  one city.
- Home-screen widget, notifications, alerts.
- Settings screen and unit switching — metric only.
- Multiple cities side by side.
- Dependency injection framework, navigation library, local database.

Add a favourites list when one city stops being enough.

## Architecture

One Gradle module, `:app`. Kotlin, Jetpack Compose, Material 3.
`minSdk 26`, `targetSdk 35`, `compileSdk 35`.

Layers, top to bottom:

- **UI** — Compose. `MainActivity` hosts a single composable that switches
  between two screens on a state value; there is no navigation library.
- **State** — one `WeatherViewModel` exposing a single `StateFlow<UiState>`.
- **Data** — `WeatherApi` performs HTTP and deserialization; `Store` reads
  and writes `SharedPreferences`.

Data flow on launch:

1. `WeatherViewModel.init` reads the saved city from `Store`.
2. No saved city → `UiState.NeedCity`, the search screen opens.
3. A saved city → emit the cached forecast if present (with its timestamp),
   then fetch. Success replaces the state and overwrites the cache; failure
   keeps the cached data and adds an error banner.

### Modules and responsibilities

| File | Responsibility | Depends on |
|---|---|---|
| `MainActivity.kt` | Compose host, screen switch, ViewModel wiring | ui, ViewModel |
| `ui/Forecast.kt` | Renders current + hourly + daily | Models, WeatherCodes |
| `ui/CitySearch.kt` | Text field, results list, selection callback | Models |
| `WeatherViewModel.kt` | Loading / error / data state, refresh, city change | WeatherApi, Store |
| `WeatherApi.kt` | Builds URLs, fetches text, deserializes, returns Result | Models |
| `Models.kt` | `@Serializable` DTOs for both endpoints | — |
| `WeatherCodes.kt` | WMO weather code → label + emoji | — |
| `Store.kt` | Persisted city, cached response body, cache timestamp | — |

Each unit is usable without reading the others' internals: `WeatherApi`
takes coordinates and returns a parsed forecast or a failure; `Store` is a
typed getter/setter pair; `WeatherCodes` is a pure function.

## Networking

`java.net.URL.readText()` inside `withContext(Dispatchers.IO)`, wrapped in
`runCatching`. No Retrofit, OkHttp, or Ktor: one GET per endpoint, no auth,
no interceptors, nothing those libraries would earn.

Deserialization uses `kotlinx.serialization` with
`ignoreUnknownKeys = true`, so Open-Meteo adding fields cannot crash the app.

### Forecast request

```
https://api.open-meteo.com/v1/forecast
  ?latitude={lat}&longitude={lon}
  &current=temperature_2m,relative_humidity_2m,apparent_temperature,
           precipitation,weather_code,wind_speed_10m
  &hourly=temperature_2m,precipitation_probability,precipitation,weather_code
  &daily=weather_code,temperature_2m_max,temperature_2m_min,
         precipitation_sum,precipitation_probability_max,uv_index_max,
         sunrise,sunset
  &timezone=auto&forecast_days=7
```

Open-Meteo returns parallel arrays (`hourly.time[i]` pairs with
`hourly.temperature_2m[i]`). The DTOs mirror that shape; the ViewModel zips
them into a list of hour and day records so the UI never indexes across
arrays.

The "next 24 hours" slice starts at the first hourly timestamp that is not
before `current.time`, then takes 24 entries — the API returns whole days,
so the array begins at midnight local time.

### Geocoding request

```
https://geocoding-api.open-meteo.com/v1/search?name={query}&count=10&language=fr&format=json
```

Queries fire on an explicit search action, not on each keystroke. A blank or
one-character query is not sent. A response with no `results` key means no
match, not an error.

## Persistence

`SharedPreferences`, four keys: `city_name`, `city_lat`, `city_lon`,
`cache_body`, `cache_at` (epoch millis). The cache stores the raw response
text, so a cached read reuses the same parsing path as a network read.

Cached data is displayed regardless of age, labelled with its fetch time.
There is no expiry: a nine-hour-old forecast beats an empty screen, and the
timestamp lets the reader judge.

## Error handling

| Failure | Behaviour |
|---|---|
| Network unreachable, cache present | Show cached forecast + "offline, as of HH:mm" |
| Network unreachable, no cache | Full-screen message + Retry button |
| Non-200 response | Same as unreachable; status code in the message |
| Malformed JSON | Same as unreachable; cache is left untouched |
| Geocoding returns no match | "No city found" under the search field |

No crash path: every network and parse call goes through `runCatching`, and
`UiState.Error` carries a message the UI can render.

## Testing

One JUnit test class, `ParsingTest`, run by `./gradlew test`:

1. Parse a checked-in real Open-Meteo response fixture; assert current
   temperature, hourly array length, and the seven daily entries.
2. Assert the hourly slice starts at or after `current.time` and holds 24
   entries.
3. Assert `WeatherCodes` maps a representative set (0, 3, 45, 61, 95) to
   distinct labels, and that an unknown code returns a fallback rather than
   throwing.

No instrumentation tests, no UI tests, no test framework beyond JUnit —
the parsing and slicing logic is the only part that can be silently wrong.

Manual verification: `./gradlew assembleDebug`, then sideload
`app/build/outputs/apk/debug/app-debug.apk`.

## Toolchain

Installed through mise, pinned in `mise.toml` at the repo root:

- `java` 21 (Android Gradle Plugin does not support the JDK 25 currently on
  PATH)
- `gradle`, used once to generate the Gradle wrapper; the wrapper is
  committed and used thereafter
- `android-sdk` (vfox plugin), then `sdkmanager` installs
  `platforms;android-35` and `build-tools;35.0.0`

If the vfox `android-sdk` plugin fails, the fallback is Google's
`commandlinetools` zip unpacked into `~/android-sdk` with `ANDROID_HOME`
set in `mise.toml` `[env]`. Either way `local.properties` is generated, not
committed.
