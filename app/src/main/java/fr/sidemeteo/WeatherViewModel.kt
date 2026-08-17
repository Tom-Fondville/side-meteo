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

    /** French sentence first, technical detail appended in parentheses when there is one. */
    private fun frenchError(message: String, failure: Throwable): String =
        message + (failure.message?.let { " ($it)" } ?: "")

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
                            error = frenchError("Échec de la mise à jour", failure),
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
                            error = frenchError("Recherche impossible", failure),
                        )
                    }
                }
        }
    }

    fun selectCity(city: City) {
        store.city = city
        store.clearCache()
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
