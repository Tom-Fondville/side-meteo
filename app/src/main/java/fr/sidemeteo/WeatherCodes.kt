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
