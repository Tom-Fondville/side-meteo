package fr.sidemeteo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
            // targetSdk 35 means Android 15+ draws edge to edge with no opt-out.
            .safeDrawingPadding()
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
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.fetchedAt?.let { at ->
            // A day-old cache must not read like an hour-old one, so the date joins the clock
            // as soon as the timestamp is not from today.
            val clock = SimpleDateFormat(if (isToday(at)) "HH:mm" else "dd/MM HH:mm", Locale.FRENCH)
                .format(Date(at))
            Text(
                text = if (state.offline) "Données du $clock" else "Mis à jour à $clock",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val forecast = state.forecast
        if (forecast == null) {
            // Nothing to draw, but stay quiet while the first fetch is still running: the
            // spinner and "give up and retry" together read as a contradiction.
            if (!state.loading) {
                Spacer(Modifier.height(24.dp))
                Text("Pas encore de données.")
                TextButton(onClick = onRefresh) { Text("Réessayer") }
            }
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
            Text("Précipitations ${current.precipitation.roundedOne()} mm")
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
        // Only when there is rain to report — a column of "0,0 mm" would be noise.
        hour.precipitation?.takeIf { it > 0.0 }?.let {
            Text("${it.roundedOne()} mm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Two lines per day: the headline on top, then the detail — rain, UV and the sun times, which
 * are fetched for all seven days but used to be shown only for day 0 inside [CurrentCard].
 */
@Composable
private fun DayRow(day: DayEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(day.date.dayLabel(), modifier = Modifier.weight(1f))
            Text(weatherLook(day.weatherCode).emoji)
            Text(
                text = "${day.tempMin?.roundedInt() ?: "—"} / ${day.tempMax?.roundedInt() ?: "—"}°",
                modifier = Modifier.weight(0.6f),
                textAlign = TextAlign.End,
            )
        }
        Text(
            text = "${day.precipitationSum?.roundedOne() ?: "—"} mm" +
                " · ${day.precipitationProbabilityMax ?: 0} %" +
                " · UV ${day.uvIndexMax?.roundedOne() ?: "—"}" +
                " · ☀ ${day.sunrise.timeOnly()} – ${day.sunset.timeOnly()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun Double.roundedC(): String = "${Math.round(this)}°"
private fun Double.roundedInt(): String = Math.round(this).toString()
private fun Double.roundedOne(): String = String.format(Locale.FRENCH, "%.1f", this)

/** `"2026-08-17T06:46"` -> `"06:46"`. */
private fun String.timeOnly(): String = if (length >= 16) substring(11, 16) else this

private fun isToday(epochMillis: Long): Boolean {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
    return day.format(Date(epochMillis)) == day.format(Date())
}
