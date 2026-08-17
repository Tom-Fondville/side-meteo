# side-meteo

A weather app for a de-Googled Android phone. No Google Play Services, no account,
no API key. Data comes from [Open-Meteo](https://open-meteo.com/).

Shows, for a city you pick by name: current conditions, the next 24 hours, and the
next 7 days. Offline it shows the last forecast it fetched, labelled with its age.

## Status

First version works. Verified on a real device on 2026-08-17: installed on a
de-Googled SP01_FE_GE running Android 12, launched without a crash, geocoding
search and the forecast screen both render, and airplane mode produces the offline
error banner instead of a blank screen.

## Build and install

The toolchain is pinned in `mise.toml` (JDK 17, Gradle 8.11.1, Android SDK).

```sh
mise install
./gradlew test          # 20 unit tests
./gradlew assembleDebug # -> app/build/outputs/apk/debug/app-debug.apk
```

Install over USB with debugging enabled on the phone:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is debug-signed, so it cannot be upgraded in place by a release-signed
build later — uninstall first if you ever switch.

`local.properties` is generated, not committed; `mise.toml` pins `ANDROID_HOME`.

## How it is built

Deliberately small. Each of these was chosen over the heavier standard option:

| Concern | Choice | Not |
|---|---|---|
| HTTP | `HttpURLConnection` + `runCatching` | Retrofit, OkHttp, Ktor |
| JSON | kotlinx.serialization, `ignoreUnknownKeys` | — |
| Storage | `SharedPreferences` (city + last raw body) | Room, DataStore |
| State | one ViewModel, one `StateFlow<UiState>` | any DI framework |
| Navigation | `when (state.screen)` over a 2-value enum | navigation-compose |
| Icons | emoji mapped from WMO codes | drawable assets |

Unit tests cover the pure logic only — JSON parsing against a committed real API
response, the parallel-array flattening and 24-hour slicing, URL building, and the
WMO code mapping. `Store`, the ViewModel and the composables are untested on
purpose: testing them needs Robolectric, kotlinx-coroutines-test or
compose-ui-test, and staying dependency-free was worth more here.

## Docs

- Design: `docs/superpowers/specs/2026-08-17-side-meteo-design.md`
- Implementation plan: `docs/superpowers/plans/2026-08-17-side-meteo.md`

## Known gaps

- `openSearch()` does not cancel an in-flight refresh, so a refresh that fails
  just after you open the city search can surface its banner there once.
- The city search query is lost on rotation.
- The widget shows one city (the app's selected city) and refreshes hourly; Android
  will not schedule widget updates more often than every 30 minutes without a
  scheduling dependency.
