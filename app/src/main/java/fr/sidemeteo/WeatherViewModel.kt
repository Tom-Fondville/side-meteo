package fr.sidemeteo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    /**
     * Date of the expanded day in the 7-day list, or null when all rows are collapsed. Keyed by
     * date rather than list index so a refresh mid-expand cannot shift which row is open.
     */
    val expandedDay: String? = null,
)

class WeatherViewModel(private val store: Store) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null

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

    /**
     * French sentence first, technical detail appended in parentheses when there is one.
     *
     * `HttpURLConnection` puts the whole request URL in its messages ("… code: 400 for URL:
     * https://api.open-meteo.com/v1/forecast?latitude=…"), which would fill the banner with a
     * 250-character link. The detail is cut at " for URL" so the status code survives, and
     * dropped entirely when what remains is blank or is itself only a URL — the shape of the
     * `FileNotFoundException` a 404 raises.
     */
    private fun frenchError(message: String, failure: Throwable): String {
        val detail = failure.message?.substringBefore(" for URL")?.trim().orEmpty()
        val usable = detail.isNotEmpty() && !detail.startsWith("http://") && !detail.startsWith("https://")
        return if (usable) "$message ($detail)" else message
    }

    /**
     * Real now in the city's own timezone, truncated to the hour and shaped like the API's
     * timestamps, so a replayed cache slices its 24-hour strip from now instead of the fetch
     * time. An unusable timezone degrades to `""`, which makes `toForecast` fall back to
     * `current.time` — this runs on the offline path and must never throw.
     */
    private fun nowIn(timezone: String): String =
        runCatching {
            ZonedDateTime.now(ZoneId.of(timezone))
                .truncatedTo(ChronoUnit.HOURS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        }.getOrElse { "" }

    /** Renders whatever was last fetched, so a slow or dead network never shows a blank screen. */
    private fun showCache() {
        val body = store.cachedBody() ?: return
        val forecast = runCatching {
            val response = WeatherApi.parseForecast(body)
            response.toForecast(nowIn(response.timezone))
        }.getOrNull() ?: return
        _state.update { it.copy(forecast = forecast, fetchedAt = store.cachedAt(), offline = true) }
    }

    fun refresh() {
        val city = _state.value.city ?: return
        // A second tap must not race the first: a late success would overwrite a newer failure.
        refreshJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            WeatherApi.fetch(WeatherApi.forecastUrl(city.latitude, city.longitude))
                .mapCatching { body ->
                    val response = WeatherApi.parseForecast(body)
                    response.toForecast(nowIn(response.timezone)) to body
                }
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
                            error = frenchError("Échec de la mise à jour", failure),
                        )
                    }
                }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        searchJob?.cancel()
        _state.update { it.copy(loading = true, error = null, searched = false) }
        searchJob = viewModelScope.launch {
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
                            error = frenchError("Recherche impossible", failure),
                        )
                    }
                }
        }
    }

    fun selectCity(city: City) {
        // Re-picking the city already selected keeps its cache: wiping it would turn a working
        // offline screen into "Pas encore de données." for nothing.
        val changed = store.city != city
        // Clear before committing the city, not after: a kill between the two statements then
        // leaves the old city with no cache — which self-heals on the next refresh — instead of
        // the new city holding the old city's forecast.
        if (changed) store.clearCache()
        store.city = city
        _state.update {
            it.copy(
                city = city,
                screen = Screen.FORECAST,
                results = emptyList(),
                searched = false,
                forecast = if (changed) null else it.forecast,
                fetchedAt = if (changed) null else it.fetchedAt,
                expandedDay = null,
                error = null,
            )
        }
        refresh()
    }

    /** Opens the tapped day's hourly detail, or collapses it if it was already open. */
    fun toggleDay(date: String) = _state.update {
        it.copy(expandedDay = if (it.expandedDay == date) null else date)
    }

    /**
     * Clears everything the two screens share, not just the error: `results`, `searched` and
     * `loading` belong to whichever screen is in front, and a forecast refresh still in flight
     * would otherwise put its spinner and its "Échec de la mise à jour" banner on the search
     * screen — where that stale error also suppresses "Aucune ville trouvée.".
     */
    fun openSearch() = _state.update {
        it.copy(screen = Screen.SEARCH, results = emptyList(), searched = false, loading = false, error = null)
    }

    fun closeSearch() {
        // Refuse to leave the search screen while no city is chosen — there is nothing behind it.
        if (_state.value.city == null) return
        _state.update { it.copy(screen = Screen.FORECAST, results = emptyList(), searched = false, error = null) }
    }
}
