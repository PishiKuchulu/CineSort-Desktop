package com.cinesort.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cinesort.desktop.state.AppState
import java.awt.Desktop
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Settings as accordion cards — the pattern approved on Android
 * v1.0.8/v1.4.2: one large rounded card per section, bold title,
 * rotating chevron, one section open at a time.
 */
@Composable
fun SettingsScreen(state: AppState) {
    var openSection by rememberSaveable { mutableStateOf("metadata") }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Changes are saved as you type.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = state::backToSetup) { Text("Back") }
        }
        Spacer(Modifier.height(20.dp))

        AccordionCard(
            title = "Metadata providers",
            expanded = openSection == "metadata",
            onToggle = { openSection = if (openSection == "metadata") "" else "metadata" },
        ) {
            Text(
                "API keys unlock genre/rating folder decoration and posters. " +
                    "Keys are stored in the app's local settings file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.tmdbKey,
                onValueChange = state::saveTmdbKey,
                label = { Text("TMDB API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.omdbKey,
                onValueChange = state::saveOmdbKey,
                label = { Text("OMDb API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = state::testTmdbKey, enabled = state.tmdbKey.isNotBlank()) {
                    Text("Test TMDB")
                }
                Button(onClick = state::testOmdbKey, enabled = state.omdbKey.isNotBlank()) {
                    Text("Test OMDb")
                }
            }
            state.keyTestResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        AccordionCard(
            title = "About & updates",
            expanded = openSection == "about",
            onToggle = { openSection = if (openSection == "about") "" else "about" },
        ) {
            Text("CineSort Desktop ${state.currentVersion}")
            if (state.lastUpdateCheckAt > 0) {
                Text(
                    "Last checked: ${DateFormat.getDateTimeInstance().format(Date(state.lastUpdateCheckAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            when (val update = state.updateState) {
                AppState.UpdateUiState.Idle -> {
                    Button(onClick = state::checkForUpdates) { Text("Check for updates") }
                }
                AppState.UpdateUiState.Checking -> {
                    Text("Checking…")
                }
                AppState.UpdateUiState.UpToDate -> {
                    Text("You're up to date.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = state::checkForUpdates) { Text("Check again") }
                }
                is AppState.UpdateUiState.Available -> {
                    Text(
                        "Version ${update.info.version} is available (${update.info.assetName}).",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { state.downloadUpdate(update.info) }) {
                        Text("Download update")
                    }
                }
                is AppState.UpdateUiState.Downloading -> {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { update.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading ${update.info.assetName}…")
                }
                is AppState.UpdateUiState.Downloaded -> {
                    Text("Downloaded and checksum-verified: ${update.file.name}")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching { Desktop.getDesktop().open(update.file.parentFile) }
                    }) { Text("Open downloads folder") }
                }
                is AppState.UpdateUiState.Error -> {
                    Text(
                        update.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = state::checkForUpdates) { Text("Try again") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        AccordionCard(
            title = "Posters",
            expanded = openSection == "posters",
            onToggle = { openSection = if (openSection == "posters") "" else "posters" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download posters", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Save poster.jpg into each organized folder (needs a metadata key).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.downloadPosters,
                    onCheckedChange = state::updateDownloadPosters,
                )
            }
        }
    }
}

@Composable
private fun AccordionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("⌄", modifier = Modifier.rotate(rotation))
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}
