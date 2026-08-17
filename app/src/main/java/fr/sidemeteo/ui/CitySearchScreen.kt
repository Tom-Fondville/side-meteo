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
        // Search on an explicit tap, not per keystroke — no debounce to get wrong.
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
