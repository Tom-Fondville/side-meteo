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
import androidx.compose.runtime.getValue
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
                    App(Store(applicationContext), onForecastSaved = {
                        WeatherWidget.renderFromCache(applicationContext)
                    })
                }
            }
        }
    }
}

@Composable
private fun App(store: Store, onForecastSaved: () -> Unit) {
    // A two-line factory instead of a DI framework for one dependency.
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WeatherViewModel(store, onForecastSaved) as T
        }
    }
    val vm: WeatherViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()

    when (state.screen) {
        Screen.FORECAST -> ForecastScreen(
            state = state,
            onRefresh = vm::refresh,
            onOpenSearch = vm::openSearch,
            onToggleDay = vm::toggleDay,
        )

        Screen.SEARCH -> CitySearchScreen(
            state = state,
            onSearch = vm::search,
            onPick = vm::selectCity,
            onBack = if (state.city == null) null else vm::closeSearch,
        )
    }
}
