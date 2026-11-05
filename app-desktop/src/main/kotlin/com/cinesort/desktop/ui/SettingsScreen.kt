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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
                    "Keys are encrypted in the app's local settings file.",
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
                Button(
                    onClick = state::testTmdbKey,
                    enabled = state.tmdbKey.isNotBlank() && !state.isTestingTmdb,
                ) {
                    if (state.isTestingTmdb) {
                        CircularProgressIndicator(
                            Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test TMDB")
                }
                Button(
                    onClick = state::testOmdbKey,
                    enabled = state.omdbKey.isNotBlank() && !state.isTestingOmdb,
                ) {
                    if (state.isTestingOmdb) {
                        CircularProgressIndicator(
                            Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test OMDb")
                }
            }
            state.tmdbTestResult?.let {
                Spacer(Modifier.height(8.dp))
                Text("TMDB: $it", style = MaterialTheme.typography.bodyMedium)
            }
            state.omdbTestResult?.let {
                Spacer(Modifier.height(8.dp))
                Text("OMDb: $it", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        AccordionCard(
            title = "Scanning",
            expanded = openSection == "scanning",
            onToggle = { openSection = if (openSection == "scanning") "" else "scanning" },
        ) {
            val scan = state.scanSettings
            SwitchRow(
                title = "Ignore sample files",
                subtitle = "Skip small files with a \"sample\" token in the name.",
                checked = scan.ignoreSamples,
                onCheckedChange = {
                    state.updateScanSettings(scan.copy(ignoreSamples = it))
                },
            )
            SwitchRow(
                title = "Include subfolders",
                subtitle = "Scan inside subfolders of each download folder.",
                checked = scan.includeSubfolders,
                onCheckedChange = {
                    state.updateScanSettings(scan.copy(includeSubfolders = it))
                },
            )
            SwitchRow(
                title = "Move companion files",
                subtitle = "Subtitles next to a video move along with it.",
                checked = scan.moveCompanions,
                onCheckedChange = {
                    state.updateScanSettings(scan.copy(moveCompanions = it))
                },
            )
            StepperRow(
                title = "Still-downloading guard",
                value = "${scan.minFileAgeSeconds}s",
                subtitle = "Files modified more recently than this wait for the next scan.",
                onDecrement = {
                    state.updateScanSettings(
                        scan.copy(minFileAgeSeconds = (scan.minFileAgeSeconds - 30).coerceAtLeast(0)),
                    )
                },
                onIncrement = {
                    state.updateScanSettings(
                        scan.copy(minFileAgeSeconds = (scan.minFileAgeSeconds + 30).coerceAtMost(600)),
                    )
                },
            )
            StepperRow(
                title = "Max folder depth",
                value = scan.maxDepth.toString(),
                subtitle = "How deep below a download folder the scan looks.",
                onDecrement = {
                    state.updateScanSettings(scan.copy(maxDepth = (scan.maxDepth - 1).coerceAtLeast(1)))
                },
                onIncrement = {
                    state.updateScanSettings(scan.copy(maxDepth = (scan.maxDepth + 1).coerceAtMost(10)))
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        AccordionCard(
            title = "Folder naming",
            expanded = openSection == "naming",
            onToggle = { openSection = if (openSection == "naming") "" else "naming" },
        ) {
            val naming = state.namingOptions
            Text(
                "Movie folders",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SwitchRow(
                title = "Add year",
                subtitle = null,
                checked = naming.movieAddYear,
                onCheckedChange = { state.updateNamingOptions(naming.copy(movieAddYear = it)) },
            )
            SwitchRow(
                title = "Add genres",
                subtitle = "Needs a metadata key.",
                checked = naming.movieAddGenres,
                onCheckedChange = { state.updateNamingOptions(naming.copy(movieAddGenres = it)) },
            )
            SwitchRow(
                title = "Add rating",
                subtitle = "Needs a metadata key.",
                checked = naming.movieAddRating,
                onCheckedChange = { state.updateNamingOptions(naming.copy(movieAddRating = it)) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Series folders",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SwitchRow(
                title = "Add year",
                subtitle = null,
                checked = naming.seriesAddYear,
                onCheckedChange = { state.updateNamingOptions(naming.copy(seriesAddYear = it)) },
            )
            SwitchRow(
                title = "Add genres",
                subtitle = "Needs a metadata key.",
                checked = naming.seriesAddGenres,
                onCheckedChange = { state.updateNamingOptions(naming.copy(seriesAddGenres = it)) },
            )
            SwitchRow(
                title = "Add rating",
                subtitle = "Needs a metadata key.",
                checked = naming.seriesAddRating,
                onCheckedChange = { state.updateNamingOptions(naming.copy(seriesAddRating = it)) },
            )
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

/** A titled setting with a switch; subtitle may be null. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A titled numeric setting adjusted in fixed steps. */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    subtitle: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onDecrement) { Text("−") }
        Text(
            value,
            Modifier.width(52.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(onClick = onIncrement) { Text("+") }
    }
}
