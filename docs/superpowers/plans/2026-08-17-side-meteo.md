# side-meteo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideloadable Android weather app that shows current conditions, the next 24 hours and the next 7 days for a manually chosen city, using the key-free Open-Meteo API.

**Architecture:** One Gradle module. Pure Kotlin data + parsing + slicing logic first (unit-tested with a committed real API fixture), then a thin `ViewModel` holding one `StateFlow<UiState>`, then two Compose screens switched by a state value. HTTP is `URL.readText()` on `Dispatchers.IO`; persistence is `SharedPreferences`; there is no DI framework, no navigation library and no database.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, JDK 17, Jetpack Compose (BOM 2024.10.01) + Material 3, kotlinx.serialization 1.7.3, JUnit 4. Toolchain installed via mise.

**Spec:** `docs/superpowers/specs/2026-08-17-side-meteo-design.md`

## Global Constraints

- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`.
- `namespace` and `applicationId`: `fr.sidemeteo`.
- JDK 17 for the Gradle daemon and `jvmTarget = "17"`. The spec said JDK 21; 17 is used instead because it is the floor AGP 8.7.3 is validated against and nothing here needs 21.
- No new dependency beyond those listed in Task 1. Specifically: no Retrofit, OkHttp, Ktor, Hilt, Koin, Room, DataStore, or navigation-compose.
- All user-facing strings are French. Units are metric (°C, km/h, mm); there is no unit setting.
- Every network and parse call is wrapped so no failure reaches the UI as an exception.
- Arrays inside `hourly` and `daily` have nullable elements (`List<Double?>`, `List<Int?>`): Open-Meteo may return `null` for individual slots, and a non-null type would throw at parse time.
- Toolchain installs go through mise, never bare `brew install`.
- Never commit `local.properties` (it holds an absolute SDK path).

---

### Task 1: Toolchain and a buildable empty app

Installs the SDK and produces an APK from a placeholder `MainActivity`. Every later task builds on a green `assembleDebug`.

**Files:**
- Create: `mise.toml`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/fr/sidemeteo/MainActivity.kt`
- Create (generated): `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`
- Create (not committed): `local.properties`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `./gradlew assembleDebug` and `./gradlew test`; package `fr.sidemeteo`; `MainActivity` as the Compose host.

- [ ] **Step 1: Install JDK 17, Gradle and the Android SDK through mise**

```bash
cd /Users/t.fondville/personal/side-meteo
mise use java@17
mise use gradle@8.11.1
mise use android-sdk@latest
mise install
mise where android-sdk
```

`mise where android-sdk` prints the SDK root — capture it, it is needed in Step 2.

If `mise use android-sdk@latest` fails (the vfox plugin is third-party and can break), use this fallback instead and continue with the printed path:

```bash
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -sO https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip -q commandlinetools-mac-11076708_latest.zip && mv cmdline-tools latest
rm commandlinetools-mac-11076708_latest.zip
echo ~/android-sdk
```

- [ ] **Step 2: Install the SDK packages and point the build at them**

Replace `<SDK_ROOT>` with the path from Step 1.

```bash
cd /Users/t.fondville/personal/side-meteo
export ANDROID_HOME=<SDK_ROOT>
yes | "$ANDROID_HOME"/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
"$ANDROID_HOME"/cmdline-tools/latest/bin/sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

Then pin it for future shells by adding an `[env]` section to `mise.toml` (the `[tools]` section is already written by Step 1):

```toml
[env]
ANDROID_HOME = "<SDK_ROOT>"
```

- [ ] **Step 3: Write the Gradle build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "side-meteo"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "fr.sidemeteo"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.sidemeteo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Declared explicitly rather than leaned on transitively through lifecycle:
    // `MutableStateFlow.update` and `withContext` come from here.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4: Write the manifest and the placeholder activity**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <!--
      The framework DeviceDefault theme is used on purpose: a Compose-only project has
      no Theme.Material3.* style resource (that comes from the Material Components
      *View* library), and Compose paints its own surfaces anyway.
    -->
    <application
        android:label="Météo"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

`app/src/main/java/fr/sidemeteo/MainActivity.kt`:

```kotlin
package fr.sidemeteo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("side-meteo")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Generate the Gradle wrapper**

```bash
cd /Users/t.fondville/personal/side-meteo
gradle wrapper --gradle-version 8.11.1
```

- [ ] **Step 6: Build and verify the APK exists**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew assembleDebug
ls -l app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL` and a file listing for `app-debug.apk`.

If the build fails on plugin resolution (a version in Step 3 no longer resolves), run `./gradlew assembleDebug --refresh-dependencies` once; if it still fails, bump only the failing coordinate to its latest release and record the new version in this plan's Tech Stack line.

- [ ] **Step 7: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add mise.toml settings.gradle.kts build.gradle.kts gradle.properties \
        app/build.gradle.kts app/src/main/AndroidManifest.xml \
        app/src/main/java/fr/sidemeteo/MainActivity.kt \
        gradle/wrapper gradlew gradlew.bat .gitignore
git commit -m "build: android project skeleton that assembles a debug APK

Co-authored-by: Claude <claude@anthropic.com>"
```

Verify `git status` does not show `local.properties` as untracked-and-added; it must stay ignored.

---

### Task 2: API models and parsing, against a real committed fixture

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/Models.kt`
- Create: `app/src/test/resources/forecast_paris.json`
- Test: `app/src/test/java/fr/sidemeteo/ParsingTest.kt`

**Interfaces:**
- Consumes: Task 1's module and `kotlinx-serialization-json`.
- Produces:
  - `ForecastResponse(timezone: String, current: Current, hourly: Hourly, daily: Daily)`
  - `Current(time: String, temperature: Double, humidity: Int, apparentTemperature: Double, precipitation: Double, weatherCode: Int, windSpeed: Double)`
  - `Hourly(time: List<String>, temperature: List<Double?>, precipitationProbability: List<Int?>, precipitation: List<Double?>, weatherCode: List<Int?>)`
  - `Daily(time: List<String>, weatherCode: List<Int?>, tempMax: List<Double?>, tempMin: List<Double?>, precipitationSum: List<Double?>, precipitationProbabilityMax: List<Int?>, uvIndexMax: List<Double?>, sunrise: List<String>, sunset: List<String>)`
  - `GeocodingResponse(results: List<City> = emptyList())`
  - `City(name: String, latitude: Double, longitude: Double, country: String?, admin1: String?)`
  - `val lenientJson: Json` — configured with `ignoreUnknownKeys = true`

- [ ] **Step 1: Install the fixture**

A real response was already captured during design. Copy it, and if it is gone, re-fetch:

```bash
cd /Users/t.fondville/personal/side-meteo
mkdir -p app/src/test/resources
SCRATCH=/private/tmp/claude-503/-Users-t-fondville-personal-side-meteo/3d2f6ef1-5e3c-4331-8177-6328a87e274d/scratchpad/forecast.json
if [ -f "$SCRATCH" ]; then
  cp "$SCRATCH" app/src/test/resources/forecast_paris.json
else
  curl -s "https://api.open-meteo.com/v1/forecast?latitude=48.8566&longitude=2.3522&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,precipitation,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,uv_index_max,sunrise,sunset&timezone=auto&forecast_days=7" \
    -o app/src/test/resources/forecast_paris.json
fi
python3 -c "import json;d=json.load(open('app/src/test/resources/forecast_paris.json'));print(d['current']['time'], len(d['hourly']['time']), len(d['daily']['time']))"
```

Expected from the captured file: `2026-08-17T10:00 168 7`. Note that `hourly` holds 168 entries (7 × 24) even though only the first 24 are shown — the slicing in Task 3 depends on this.

- [ ] **Step 2: Write the failing test**

`app/src/test/java/fr/sidemeteo/ParsingTest.kt`:

```kotlin
package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a real forecast response`() {
        val response = lenientJson.decodeFromString<ForecastResponse>(fixture("forecast_paris.json"))

        assertEquals("Europe/Paris", response.timezone)
        assertEquals("2026-08-17T10:00", response.current.time)
        assertEquals(22.8, response.current.temperature, 0.001)
        assertEquals(55, response.current.humidity)
        assertEquals(3, response.current.weatherCode)
        assertEquals(168, response.hourly.time.size)
        assertEquals(168, response.hourly.temperature.size)
        assertEquals(7, response.daily.time.size)
        assertEquals(7, response.daily.sunrise.size)
        assertEquals("2026-08-17", response.daily.time.first())
    }

    @Test
    fun `ignores unknown fields and tolerates null array slots`() {
        val body = """
            {"timezone":"Europe/Paris","brand_new_field":42,
             "current":{"time":"2026-08-17T10:00","temperature_2m":20.0,
                        "relative_humidity_2m":50,"apparent_temperature":19.0,
                        "precipitation":0.0,"weather_code":0,"wind_speed_10m":5.0},
             "hourly":{"time":["2026-08-17T10:00"],"temperature_2m":[null],
                       "precipitation_probability":[null],"precipitation":[null],
                       "weather_code":[null]},
             "daily":{"time":["2026-08-17"],"weather_code":[null],
                      "temperature_2m_max":[null],"temperature_2m_min":[null],
                      "precipitation_sum":[null],"precipitation_probability_max":[null],
                      "uv_index_max":[null],"sunrise":["2026-08-17T06:46"],
                      "sunset":["2026-08-17T21:02"]}}
        """.trimIndent()

        val response = lenientJson.decodeFromString<ForecastResponse>(body)

        assertNull(response.hourly.temperature.first())
        assertNull(response.daily.uvIndexMax.first())
    }

    @Test
    fun `parses geocoding results`() {
        val body = """
            {"results":[{"id":2998324,"name":"Lille","latitude":50.63391,"longitude":3.05512,
                         "country":"France","admin1":"Hauts-de-France","population":238695}]}
        """.trimIndent()

        val cities = lenientJson.decodeFromString<GeocodingResponse>(body).results

        assertEquals(1, cities.size)
        assertEquals("Lille", cities[0].name)
        assertEquals(50.63391, cities[0].latitude, 0.00001)
        assertEquals("Hauts-de-France", cities[0].admin1)
    }

    @Test
    fun `a geocoding response without results is an empty list, not a failure`() {
        val cities = lenientJson.decodeFromString<GeocodingResponse>("""{"generationtime_ms":0.28}""").results

        assertTrue(cities.isEmpty())
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.ParsingTest'
```

Expected: compilation failure — `Unresolved reference: ForecastResponse` (and the other new symbols).

- [ ] **Step 4: Write the models**

`app/src/main/java/fr/sidemeteo/Models.kt`:

```kotlin
package fr.sidemeteo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ponytail: one shared Json, configured once. ignoreUnknownKeys means Open-Meteo
// can add fields without breaking the app in the field.
val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ForecastResponse(
    val timezone: String,
    val current: Current,
    val hourly: Hourly,
    val daily: Daily,
)

@Serializable
data class Current(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("relative_humidity_2m") val humidity: Int,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    val precipitation: Double,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
)

// Open-Meteo returns parallel arrays: hourly.time[i] pairs with hourly.temperature_2m[i].
// Elements are nullable because individual slots can come back null.
@Serializable
data class Hourly(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?>,
    val precipitation: List<Double?>,
    @SerialName("weather_code") val weatherCode: List<Int?>,
)

@Serializable
data class Daily(
    val time: List<String>,
    @SerialName("weather_code") val weatherCode: List<Int?>,
    @SerialName("temperature_2m_max") val tempMax: List<Double?>,
    @SerialName("temperature_2m_min") val tempMin: List<Double?>,
    @SerialName("precipitation_sum") val precipitationSum: List<Double?>,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?>,
    @SerialName("uv_index_max") val uvIndexMax: List<Double?>,
    val sunrise: List<String>,
    val sunset: List<String>,
)

@Serializable
data class GeocodingResponse(
    // Absent when nothing matched, so it defaults instead of failing to parse.
    val results: List<City> = emptyList(),
)

@Serializable
data class City(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.ParsingTest'
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/Models.kt \
        app/src/test/java/fr/sidemeteo/ParsingTest.kt \
        app/src/test/resources/forecast_paris.json
git commit -m "feat: open-meteo response models with lenient parsing

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 3: Turn parallel arrays into hour and day records

The API's parallel arrays are hostile to UI code. This converts them once into lists of records and slices the 24 hours that start now.

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/Forecast.kt`
- Test: `app/src/test/java/fr/sidemeteo/ForecastTest.kt`

**Interfaces:**
- Consumes: `ForecastResponse`, `Current`, `Hourly`, `Daily`, `lenientJson` from Task 2.
- Produces:
  - `HourEntry(time: String, temperature: Double?, precipitationProbability: Int?, precipitation: Double?, weatherCode: Int?)`
  - `DayEntry(date: String, weatherCode: Int?, tempMax: Double?, tempMin: Double?, precipitationSum: Double?, precipitationProbabilityMax: Int?, uvIndexMax: Double?, sunrise: String, sunset: String)`
  - `Forecast(timezone: String, current: Current, hours: List<HourEntry>, days: List<DayEntry>)`
  - `fun ForecastResponse.toForecast(): Forecast`
  - `fun String.hourLabel(): String` — `"2026-08-17T14:00"` → `"14h"`
  - `fun String.dayLabel(): String` — `"2026-08-17"` → `"lun. 17/08"`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/fr/sidemeteo/ForecastTest.kt`:

```kotlin
package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private val response = lenientJson.decodeFromString<ForecastResponse>(fixture("forecast_paris.json"))

    @Test
    fun `keeps 24 hours starting at the current hour`() {
        val forecast = response.toForecast()

        assertEquals(24, forecast.hours.size)
        // Fixture: current.time is 10:00 and hourly starts at midnight, so the slice starts at index 10.
        assertEquals("2026-08-17T10:00", forecast.hours.first().time)
        assertEquals("2026-08-18T09:00", forecast.hours.last().time)
        assertTrue(forecast.hours.all { it.time >= response.current.time })
    }

    @Test
    fun `zips hourly arrays into records at matching indices`() {
        val forecast = response.toForecast()
        val i = response.hourly.time.indexOf("2026-08-17T10:00")

        // Whole-record equality: one assertion, and no ambiguity over which
        // JUnit assertEquals overload nullable Doubles resolve to.
        val expected = HourEntry(
            time = response.hourly.time[i],
            temperature = response.hourly.temperature[i],
            precipitationProbability = response.hourly.precipitationProbability[i],
            precipitation = response.hourly.precipitation[i],
            weatherCode = response.hourly.weatherCode[i],
        )

        assertEquals(expected, forecast.hours.first())
    }

    @Test
    fun `zips all seven days`() {
        val forecast = response.toForecast()

        assertEquals(7, forecast.days.size)
        assertEquals("2026-08-17", forecast.days.first().date)
        assertEquals("Europe/Paris", forecast.timezone)
        assertEquals(response.daily.tempMax[2]!!, forecast.days[2].tempMax!!, 0.001)
        assertEquals(response.daily.sunrise[0], forecast.days[0].sunrise)
        assertEquals(response.daily.sunset[6], forecast.days[6].sunset)
    }

    @Test
    fun `a current time between two hours rounds forward`() {
        val shifted = response.copy(current = response.current.copy(time = "2026-08-17T10:15"))

        assertEquals("2026-08-17T11:00", shifted.toForecast().hours.first().time)
    }

    @Test
    fun `takes what is available when fewer than 24 hours remain`() {
        val trimmed = response.copy(
            hourly = response.hourly.copy(
                time = response.hourly.time.take(12),
                temperature = response.hourly.temperature.take(12),
                precipitationProbability = response.hourly.precipitationProbability.take(12),
                precipitation = response.hourly.precipitation.take(12),
                weatherCode = response.hourly.weatherCode.take(12),
            ),
        )

        assertEquals(2, trimmed.toForecast().hours.size)
    }

    @Test
    fun `formats hour and day labels`() {
        assertEquals("14h", "2026-08-17T14:00".hourLabel())
        assertEquals("00h", "2026-08-17T00:00".hourLabel())
        assertEquals("lun. 17/08", "2026-08-17".dayLabel())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.ForecastTest'
```

Expected: compilation failure — `Unresolved reference: toForecast`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/fr/sidemeteo/Forecast.kt`:

```kotlin
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
```

If `dayLabel` fails on the weekday text (the JDK abbreviation for French can be `"lun."` or `"lun"` depending on the CLDR version), fix the assertion in the test to whatever the JDK actually returns rather than reformatting the string by hand — the display name is what will show on the phone.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.ForecastTest'
```

Expected: `BUILD SUCCESSFUL`, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/Forecast.kt app/src/test/java/fr/sidemeteo/ForecastTest.kt
git commit -m "feat: flatten open-meteo arrays into hour and day records

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 4: WMO weather codes to French labels and emoji

Emoji instead of drawables: zero assets, no icon licensing, renders on any Android 8+ font.

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/WeatherCodes.kt`
- Test: `app/src/test/java/fr/sidemeteo/WeatherCodesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class WeatherLook(val label: String, val emoji: String)`
  - `fun weatherLook(code: Int?): WeatherLook` — never throws; unknown or null code returns `WeatherLook("Inconnu", "🌡️")`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/fr/sidemeteo/WeatherCodesTest.kt`:

```kotlin
package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `maps representative codes to distinct labels`() {
        val labels = listOf(0, 3, 45, 61, 95).map { weatherLook(it).label }

        assertEquals("Ciel dégagé", weatherLook(0).label)
        assertEquals("Couvert", weatherLook(3).label)
        assertEquals("Brouillard", weatherLook(45).label)
        assertEquals("Pluie", weatherLook(61).label)
        assertEquals("Orage", weatherLook(95).label)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `groups intensity variants under the same label`() {
        assertEquals(weatherLook(61).label, weatherLook(65).label)
        assertEquals(weatherLook(71).label, weatherLook(75).label)
        assertNotEquals(weatherLook(61).label, weatherLook(71).label)
    }

    @Test
    fun `unknown and null codes fall back instead of throwing`() {
        assertEquals("Inconnu", weatherLook(999).label)
        assertEquals("Inconnu", weatherLook(null).label)
    }

    @Test
    fun `every mapped code has a non-empty emoji`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65,
                           66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)

        assertTrue(codes.all { weatherLook(it).emoji.isNotBlank() })
        assertTrue(codes.all { weatherLook(it).label != "Inconnu" })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.WeatherCodesTest'
```

Expected: compilation failure — `Unresolved reference: weatherLook`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/fr/sidemeteo/WeatherCodes.kt`:

```kotlin
package fr.sidemeteo

data class WeatherLook(val label: String, val emoji: String)

private val LOOKS: Map<Int, WeatherLook> = mapOf(
    0 to WeatherLook("Ciel dégagé", "☀️"),
    1 to WeatherLook("Plutôt dégagé", "🌤️"),
    2 to WeatherLook("Partiellement nuageux", "⛅"),
    3 to WeatherLook("Couvert", "☁️"),
    45 to WeatherLook("Brouillard", "🌫️"),
    48 to WeatherLook("Brouillard givrant", "🌫️"),
    51 to WeatherLook("Bruine", "🌦️"),
    53 to WeatherLook("Bruine", "🌦️"),
    55 to WeatherLook("Bruine", "🌦️"),
    56 to WeatherLook("Bruine verglaçante", "🌧️"),
    57 to WeatherLook("Bruine verglaçante", "🌧️"),
    61 to WeatherLook("Pluie", "🌧️"),
    63 to WeatherLook("Pluie", "🌧️"),
    65 to WeatherLook("Pluie", "🌧️"),
    66 to WeatherLook("Pluie verglaçante", "🌧️"),
    67 to WeatherLook("Pluie verglaçante", "🌧️"),
    71 to WeatherLook("Neige", "🌨️"),
    73 to WeatherLook("Neige", "🌨️"),
    75 to WeatherLook("Neige", "🌨️"),
    77 to WeatherLook("Grains de neige", "🌨️"),
    80 to WeatherLook("Averses", "🌦️"),
    81 to WeatherLook("Averses", "🌦️"),
    82 to WeatherLook("Averses fortes", "⛈️"),
    85 to WeatherLook("Averses de neige", "🌨️"),
    86 to WeatherLook("Averses de neige", "🌨️"),
    95 to WeatherLook("Orage", "⛈️"),
    96 to WeatherLook("Orage et grêle", "⛈️"),
    99 to WeatherLook("Orage et grêle", "⛈️"),
)

private val UNKNOWN = WeatherLook("Inconnu", "🌡️")

/** WMO weather interpretation code to a French label and an emoji. Never throws. */
fun weatherLook(code: Int?): WeatherLook = LOOKS[code] ?: UNKNOWN
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.WeatherCodesTest'
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/WeatherCodes.kt app/src/test/java/fr/sidemeteo/WeatherCodesTest.kt
git commit -m "feat: map WMO weather codes to French labels and emoji

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 5: The HTTP layer

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/WeatherApi.kt`
- Test: `app/src/test/java/fr/sidemeteo/WeatherApiTest.kt`

**Interfaces:**
- Consumes: `ForecastResponse`, `GeocodingResponse`, `City`, `lenientJson` (Task 2).
- Produces, on `object WeatherApi`:
  - `fun forecastUrl(latitude: Double, longitude: Double): String`
  - `fun geocodeUrl(query: String): String`
  - `fun parseForecast(body: String): ForecastResponse`
  - `suspend fun fetch(url: String): Result<String>` — the raw body; used directly by the ViewModel, which needs the text to cache it
  - `suspend fun geocode(query: String): Result<List<City>>`

There is deliberately no `suspend fun forecast(lat, lon)`: it would hide the raw
body the cache needs, so the ViewModel composes `fetch` + `parseForecast` instead.

- [ ] **Step 1: Write the failing test**

Only URL construction and parsing are tested — asserting on live network responses would make the suite fail on a train.

`app/src/test/java/fr/sidemeteo/WeatherApiTest.kt`:

```kotlin
package fr.sidemeteo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherApiTest {

    @Test
    fun `forecast url carries coordinates and every requested field`() {
        val url = WeatherApi.forecastUrl(48.8566, 2.3522)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=48.8566"))
        assertTrue(url.contains("longitude=2.3522"))
        assertTrue(url.contains("timezone=auto"))
        assertTrue(url.contains("forecast_days=7"))
        listOf(
            "temperature_2m", "relative_humidity_2m", "apparent_temperature",
            "precipitation", "weather_code", "wind_speed_10m",
            "precipitation_probability", "temperature_2m_max", "temperature_2m_min",
            "precipitation_sum", "precipitation_probability_max", "uv_index_max",
            "sunrise", "sunset",
        ).forEach { assertTrue("missing $it in $url", url.contains(it)) }
    }

    @Test
    fun `forecast url uses a dot decimal separator regardless of locale`() {
        val url = WeatherApi.forecastUrl(-3.5, 0.0)

        assertTrue(url, url.contains("latitude=-3.5"))
        assertTrue(url, url.contains("longitude=0.0"))
    }

    @Test
    fun `geocode url percent-encodes the query`() {
        val url = WeatherApi.geocodeUrl("Saint-Étienne du Rouvray")

        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url, url.contains("name=Saint-%C3%89tienne+du+Rouvray"))
        assertTrue(url.contains("count=10"))
        assertTrue(url.contains("language=fr"))
    }

    @Test
    fun `parseForecast reads a real body`() {
        val body = checkNotNull(javaClass.classLoader?.getResourceAsStream("forecast_paris.json"))
            .bufferedReader().readText()

        assertEquals("Europe/Paris", WeatherApi.parseForecast(body).timezone)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.WeatherApiTest'
```

Expected: compilation failure — `Unresolved reference: WeatherApi`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/fr/sidemeteo/WeatherApi.kt`:

```kotlin
package fr.sidemeteo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Open-Meteo client. Two unauthenticated GETs, so `URL.readText()` is the whole
 * transport — no HTTP library earns its place here.
 */
object WeatherApi {

    private const val CURRENT_FIELDS =
        "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
    private const val HOURLY_FIELDS =
        "temperature_2m,precipitation_probability,precipitation,weather_code"
    private const val DAILY_FIELDS =
        "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,uv_index_max,sunrise,sunset"

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            // Locale.ROOT: a French default locale would render 48,8566 and break the query.
            "?latitude=%s&longitude=%s".format(Locale.ROOT, latitude, longitude) +
            "&current=$CURRENT_FIELDS" +
            "&hourly=$HOURLY_FIELDS" +
            "&daily=$DAILY_FIELDS" +
            "&timezone=auto&forecast_days=7"

    fun geocodeUrl(query: String): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=10&language=fr&format=json"

    fun parseForecast(body: String): ForecastResponse = lenientJson.decodeFromString(body)

    suspend fun geocode(query: String): Result<List<City>> =
        fetch(geocodeUrl(query)).mapCatching {
            lenientJson.decodeFromString<GeocodingResponse>(it).results
        }

    /** Raw body, or a failed Result. Callers never see an exception. */
    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { URL(url).readText() }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:test --tests 'fr.sidemeteo.WeatherApiTest'
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Check the URL against the live API once, by hand**

```bash
cd /Users/t.fondville/personal/side-meteo
curl -s -o /dev/null -w '%{http_code}\n' 'https://api.open-meteo.com/v1/forecast?latitude=48.8566&longitude=2.3522&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,precipitation,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,uv_index_max,sunrise,sunset&timezone=auto&forecast_days=7'
```

Expected: `200`.

- [ ] **Step 6: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/WeatherApi.kt app/src/test/java/fr/sidemeteo/WeatherApiTest.kt
git commit -m "feat: open-meteo forecast and geocoding client

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 6: Persist the chosen city and the last response

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/Store.kt`

**Interfaces:**
- Consumes: `City`, `lenientJson` (Task 2).
- Produces, on `class Store(context: Context)`:
  - `var city: City?` — persisted as JSON
  - `fun saveCache(body: String)` — stores the raw response and stamps `System.currentTimeMillis()`
  - `fun cachedBody(): String?`
  - `fun cachedAt(): Long?`

No test: this is `SharedPreferences` getters and setters, and testing it would mean pulling in Robolectric — more machinery than the code it guards. The JSON round-trip it relies on is already covered by Task 2.

- [ ] **Step 1: Write the implementation**

`app/src/main/java/fr/sidemeteo/Store.kt`:

```kotlin
package fr.sidemeteo

import android.content.Context
import kotlinx.serialization.encodeToString

/**
 * SharedPreferences-backed state: the selected city, and the last successful
 * response body with its timestamp.
 *
 * ponytail: the cache holds the raw body, so a cached read goes through the same
 * parsing path as a network read.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("side-meteo", Context.MODE_PRIVATE)

    var city: City?
        get() = prefs.getString(KEY_CITY, null)
            ?.let { runCatching { lenientJson.decodeFromString<City>(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CITY) else putString(KEY_CITY, lenientJson.encodeToString(value))
            }.apply()
        }

    fun saveCache(body: String) {
        prefs.edit()
            .putString(KEY_BODY, body)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun cachedBody(): String? = prefs.getString(KEY_BODY, null)

    fun cachedAt(): Long? = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }

    private companion object {
        const val KEY_CITY = "city"
        const val KEY_BODY = "cache_body"
        const val KEY_AT = "cache_at"
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/Store.kt
git commit -m "feat: persist selected city and last response

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 7: The ViewModel

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/WeatherViewModel.kt`

**Interfaces:**
- Consumes: `Store` (Task 6), `WeatherApi` (Task 5), `Forecast`/`toForecast()` (Task 3), `City` (Task 2).
- Produces:
  - `enum class Screen { FORECAST, SEARCH }`
  - `data class UiState(city: City?, forecast: Forecast?, fetchedAt: Long?, offline: Boolean, loading: Boolean, error: String?, screen: Screen, results: List<City>, searched: Boolean)`
  - `class WeatherViewModel(store: Store) : ViewModel()` with `val state: StateFlow<UiState>`, `fun refresh()`, `fun search(query: String)`, `fun selectCity(city: City)`, `fun openSearch()`, `fun closeSearch()`

No unit test: every method is a state assignment around the already-tested `WeatherApi` and `toForecast()`. If a branch here grows real logic, extract it into a pure function and test that instead.

- [ ] **Step 1: Write the implementation**

`app/src/main/java/fr/sidemeteo/WeatherViewModel.kt`:

```kotlin
package fr.sidemeteo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { FORECAST, SEARCH }

data class UiState(
    val city: City? = null,
    val forecast: Forecast? = null,
    val fetchedAt: Long? = null,
    val offline: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val screen: Screen = Screen.FORECAST,
    val results: List<City> = emptyList(),
    val searched: Boolean = false,
)

class WeatherViewModel(private val store: Store) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val saved = store.city
        if (saved == null) {
            _state.update { it.copy(screen = Screen.SEARCH) }
        } else {
            _state.update { it.copy(city = saved) }
            showCache()
            refresh()
        }
    }

    /** Renders whatever was last fetched, so a slow or dead network never shows a blank screen. */
    private fun showCache() {
        val body = store.cachedBody() ?: return
        val forecast = runCatching { WeatherApi.parseForecast(body).toForecast() }.getOrNull() ?: return
        _state.update { it.copy(forecast = forecast, fetchedAt = store.cachedAt(), offline = true) }
    }

    fun refresh() {
        val city = _state.value.city ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            WeatherApi.fetch(WeatherApi.forecastUrl(city.latitude, city.longitude))
                .mapCatching { body -> WeatherApi.parseForecast(body).toForecast() to body }
                .onSuccess { (forecast, body) ->
                    store.saveCache(body)
                    _state.update {
                        it.copy(
                            forecast = forecast,
                            fetchedAt = store.cachedAt(),
                            offline = false,
                            loading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    // Keep any cached forecast on screen; the banner explains why it is stale.
                    _state.update {
                        it.copy(
                            loading = false,
                            offline = it.forecast != null,
                            error = failure.message ?: "Échec de la mise à jour",
                        )
                    }
                }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        _state.update { it.copy(loading = true, error = null, searched = false) }
        viewModelScope.launch {
            WeatherApi.geocode(trimmed)
                .onSuccess { cities ->
                    _state.update { it.copy(results = cities, loading = false, searched = true) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            searched = true,
                            results = emptyList(),
                            error = failure.message ?: "Recherche impossible",
                        )
                    }
                }
        }
    }

    fun selectCity(city: City) {
        store.city = city
        _state.update {
            it.copy(
                city = city,
                screen = Screen.FORECAST,
                results = emptyList(),
                searched = false,
                forecast = null,
                fetchedAt = null,
                error = null,
            )
        }
        refresh()
    }

    fun openSearch() = _state.update { it.copy(screen = Screen.SEARCH, error = null) }

    fun closeSearch() {
        // Refuse to leave the search screen while no city is chosen — there is nothing behind it.
        if (_state.value.city == null) return
        _state.update { it.copy(screen = Screen.FORECAST, results = emptyList(), searched = false, error = null) }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/WeatherViewModel.kt
git commit -m "feat: weather view model with offline cache fallback

Co-authored-by: Claude <claude@anthropic.com>"
```

---

### Task 8: The two screens, wired into MainActivity

**Files:**
- Create: `app/src/main/java/fr/sidemeteo/ui/ForecastScreen.kt`
- Create: `app/src/main/java/fr/sidemeteo/ui/CitySearchScreen.kt`
- Modify: `app/src/main/java/fr/sidemeteo/MainActivity.kt` (replace the placeholder body from Task 1)

**Interfaces:**
- Consumes: `UiState`, `Screen`, `WeatherViewModel` (Task 7); `Forecast`, `HourEntry`, `DayEntry`, `hourLabel()`, `dayLabel()` (Task 3); `weatherLook()` (Task 4); `City` (Task 2).
- Produces: `@Composable fun ForecastScreen(state: UiState, onRefresh: () -> Unit, onOpenSearch: () -> Unit)` and `@Composable fun CitySearchScreen(state: UiState, onSearch: (String) -> Unit, onPick: (City) -> Unit, onBack: (() -> Unit)?)`.

- [ ] **Step 1: Write the forecast screen**

`app/src/main/java/fr/sidemeteo/ui/ForecastScreen.kt`:

```kotlin
package fr.sidemeteo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.sidemeteo.DayEntry
import fr.sidemeteo.HourEntry
import fr.sidemeteo.UiState
import fr.sidemeteo.dayLabel
import fr.sidemeteo.hourLabel
import fr.sidemeteo.weatherLook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ForecastScreen(state: UiState, onRefresh: () -> Unit, onOpenSearch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.city?.name ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSearch) { Text("Ville") }
            TextButton(onClick = onRefresh) { Text("Actualiser") }
        }

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
        }

        state.error?.let { message ->
            Text(
                text = "Hors ligne : $message",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.fetchedAt?.let { at ->
            val clock = SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date(at))
            Text(
                text = if (state.offline) "Données du $clock" else "Mis à jour à $clock",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val forecast = state.forecast
        if (forecast == null) {
            Spacer(Modifier.height(24.dp))
            Text("Pas encore de données.")
            TextButton(onClick = onRefresh) { Text("Réessayer") }
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        CurrentCard(state)

        Spacer(Modifier.height(24.dp))
        Text("Prochaines 24 h", style = MaterialTheme.typography.titleMedium)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(forecast.hours.size) { i -> HourColumn(forecast.hours[i]) }
        }

        Spacer(Modifier.height(16.dp))
        Text("7 jours", style = MaterialTheme.typography.titleMedium)
        forecast.days.forEach { day ->
            DayRow(day)
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Données Open-Meteo",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CurrentCard(state: UiState) {
    val forecast = state.forecast ?: return
    val current = forecast.current
    val today = forecast.days.firstOrNull()
    val look = weatherLook(current.weatherCode)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${look.emoji} ${current.temperature.roundedC()}", style = MaterialTheme.typography.displaySmall)
            Text(look.label, style = MaterialTheme.typography.titleMedium)
            Text("Ressenti ${current.apparentTemperature.roundedC()}")
            Text("Vent ${current.windSpeed.roundedInt()} km/h · Humidité ${current.humidity} %")
            today?.let { day ->
                Text("UV max ${day.uvIndexMax?.roundedOne() ?: "—"}")
                Text("Lever ${day.sunrise.timeOnly()} · Coucher ${day.sunset.timeOnly()}")
                Text("Pluie aujourd'hui ${day.precipitationSum?.roundedOne() ?: "—"} mm (${day.precipitationProbabilityMax ?: 0} %)")
            }
        }
    }
}

@Composable
private fun HourColumn(hour: HourEntry) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(hour.time.hourLabel(), style = MaterialTheme.typography.bodySmall)
        Text(weatherLook(hour.weatherCode).emoji)
        Text(hour.temperature?.roundedC() ?: "—")
        Text("${hour.precipitationProbability ?: 0} %", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DayRow(day: DayEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(day.date.dayLabel(), modifier = Modifier.weight(1f))
        Text(weatherLook(day.weatherCode).emoji)
        Text(
            text = "  ${day.tempMin?.roundedInt() ?: "—"} / ${day.tempMax?.roundedInt() ?: "—"}°",
            modifier = Modifier.weight(0.6f),
            textAlign = TextAlign.End,
        )
        Text(
            text = "  ${day.precipitationSum?.roundedOne() ?: "—"} mm · ${day.precipitationProbabilityMax ?: 0} %",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

private fun Double.roundedC(): String = "${Math.round(this)}°"
private fun Double.roundedInt(): String = Math.round(this).toString()
private fun Double.roundedOne(): String = String.format(Locale.FRENCH, "%.1f", this)

/** `"2026-08-17T06:46"` -> `"06:46"`. */
private fun String.timeOnly(): String = if (length >= 16) substring(11, 16) else this
```

- [ ] **Step 2: Write the city search screen**

`app/src/main/java/fr/sidemeteo/ui/CitySearchScreen.kt`:

```kotlin
package fr.sidemeteo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.sidemeteo.City
import fr.sidemeteo.UiState

@Composable
fun CitySearchScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onPick: (City) -> Unit,
    onBack: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Choisir une ville", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            onBack?.let { TextButton(onClick = it) { Text("Retour") } }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Nom de la ville") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // ponytail: search on an explicit tap, not per keystroke — no debounce to get wrong.
        Button(onClick = { onSearch(query) }, enabled = query.trim().length >= 2) {
            Text("Rechercher")
        }

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
        }

        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (state.searched && state.results.isEmpty() && state.error == null) {
            Text("Aucune ville trouvée.", modifier = Modifier.padding(vertical = 12.dp))
        }

        LazyColumn {
            items(state.results.size) { i ->
                val city = state.results[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(city) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(city.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOfNotNull(city.admin1, city.country).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
```

- [ ] **Step 3: Wire MainActivity**

Replace the whole body of `app/src/main/java/fr/sidemeteo/MainActivity.kt`:

```kotlin
package fr.sidemeteo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.sidemeteo.ui.CitySearchScreen
import fr.sidemeteo.ui.ForecastScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Follows the system setting; the stock Material 3 schemes are enough,
            // no hand-picked palette.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    App(Store(applicationContext))
                }
            }
        }
    }
}

@Composable
private fun App(store: Store) {
    // ponytail: a two-line factory instead of a DI framework for one dependency.
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WeatherViewModel(store) as T
        }
    }
    val vm: WeatherViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()

    when (state.screen) {
        Screen.FORECAST -> ForecastScreen(
            state = state,
            onRefresh = vm::refresh,
            onOpenSearch = vm::openSearch,
        )

        Screen.SEARCH -> CitySearchScreen(
            state = state,
            onSearch = vm::search,
            onPick = vm::selectCity,
            onBack = if (state.city == null) null else vm::closeSearch,
        )
    }
}
```

- [ ] **Step 4: Build the debug APK and run the full suite**

```bash
cd /Users/t.fondville/personal/side-meteo
./gradlew test assembleDebug
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, all 18 unit tests passing, and an APK on disk.

If the Compose compiler rejects `LazyRow`/`LazyColumn` `items(count)`, add `import androidx.compose.foundation.lazy.items` and switch to `items(list) { city -> ... }` — both forms are valid; use whichever resolves.

- [ ] **Step 5: Commit**

```bash
cd /Users/t.fondville/personal/side-meteo
git add app/src/main/java/fr/sidemeteo/ui app/src/main/java/fr/sidemeteo/MainActivity.kt
git commit -m "feat: forecast and city search screens

Co-authored-by: Claude <claude@anthropic.com>"
```

- [ ] **Step 6: Report the APK path for sideloading**

Print the absolute path and size of `app/build/outputs/apk/debug/app-debug.apk` for the user to copy to the phone. Note in the report that the phone must allow installing from unknown sources, and that a debug-signed APK cannot be upgraded over a release-signed one.

---

## Verification Summary

After Task 8, all spec success criteria are covered:

| Spec criterion | Verified by |
|---|---|
| `assembleDebug` produces a sideloadable APK | Task 1 Step 6, Task 8 Step 4 |
| Current + 24 h + 7 days for a chosen city | Tasks 3, 4, 8 |
| Offline shows last forecast with its age | Task 7 `showCache`, Task 8 staleness banner |
| No crash on network or parse failure | `runCatching` in `WeatherApi.fetch`, `mapCatching` in the ViewModel |
| Parsing and slicing correctness | `ParsingTest`, `ForecastTest`, `WeatherApiTest`, `WeatherCodesTest` (18 tests) |
