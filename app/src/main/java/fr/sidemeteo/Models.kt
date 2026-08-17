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
