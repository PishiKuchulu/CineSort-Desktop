package com.cinesort.desktop.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.ParseResult
import com.cinesort.desktop.state.AppState
import java.nio.file.Path
import javax.swing.JFileChooser

/** Opens a native directory picker; returns the chosen path or null. */
fun pickDirectory(title: String): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}

fun PlannedItem.displayTitle(): String = when (val r = effective) {
    is ParseResult.Movie -> if (r.year != null) "${r.title} (${r.year})" else r.title
    is ParseResult.Episode -> "${r.seriesTitle} — S${"%02d".format(r.season)}E${
        r.episodes.joinToString(",") { "%02d".format(it) }
    }"
    else -> scanned.file.name
}

@Composable
fun SetupScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Organize a library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "CineSort moves video files into a clean Movie / Tv series library. " +
                "Every run is journaled and can be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Text("Download folders", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.sources.forEach { source ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(source.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { state.removeSource(source) }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(
            onClick = { pickDirectory("Choose a download folder")?.let(state::addSource) },
        ) { Text("Add download folder") }

        Spacer(Modifier.height(24.dp))
        Text("Library (destination)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.destination?.toString() ?: "No library chosen yet",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { pickDirectory("Choose the library folder")?.let { state.destination = it } },
                ) { Text("Choose") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = state::scan, enabled = state.canScan) { Text("Scan & preview") }
            OutlinedButton(onClick = state::showHistory) { Text("History") }
        }
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun ScanningScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scanning…", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Reading folders, parsing names and planning the library.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PreviewScreen(state: AppState) {
    val plan = state.plan ?: return
    val inspected = plan.items.firstOrNull { it.id == state.inspectedItemId }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${plan.videosFound} video(s) found · ${plan.items.count { it.status == PlannedStatus.READY }} ready · " +
                        "destination: ${plan.destinationName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = state::backToSetup) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = state::execute,
                enabled = plan.items.any { it.status == PlannedStatus.READY && it.id !in state.deselected },
            ) { Text("Organize") }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plan.items, key = { it.id }) { item ->
                    PreviewCard(state, item)
                }
            }
            Spacer(Modifier.width(16.dp))
            InspectorCard(inspected)
        }
    }
}

@Composable
private fun PreviewCard(state: AppState, item: PlannedItem) {
    val selected = item.id !in state.deselected
    Card(
        modifier = Modifier.fillMaxWidth()
            .clickable { state.inspectedItemId = item.id },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.inspectedItemId == item.id) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { state.toggleSelected(item.id) },
                enabled = item.status == PlannedStatus.READY,
            )
            Column(Modifier.weight(1f)) {
                Text(item.displayTitle(), fontWeight = FontWeight.SemiBold)
                Text(
                    item.scanned.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "→ ${item.targetPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(item.status)
        }
    }
}

@Composable
fun StatusChip(status: PlannedStatus) {
    val (label, color) = when (status) {
        PlannedStatus.READY -> "Ready" to MaterialTheme.colorScheme.primary
        PlannedStatus.ALREADY_ORGANIZED -> "Already organized" to MaterialTheme.colorScheme.onSurfaceVariant
        PlannedStatus.CONFLICT -> "Conflict" to MaterialTheme.colorScheme.error
        PlannedStatus.SKIPPED -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
        PlannedStatus.NEEDS_REVIEW -> "Needs review" to MaterialTheme.colorScheme.error
        PlannedStatus.RECENTLY_MODIFIED -> "Still downloading" to MaterialTheme.colorScheme.error
    }
    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun InspectorCard(item: PlannedItem?) {
    Card(
        modifier = Modifier.width(320.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Inspector", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (item == null) {
                Text(
                    "Select an item to see its details.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InspectorRow("Title", item.displayTitle())
                InspectorRow("File", item.scanned.file.name)
                InspectorRow("Destination", item.targetPath)
                InspectorRow("Size", "${item.sizeBytes / 1024} KB")
                InspectorRow("Status", item.status.name)
                if (item.companions.isNotEmpty()) {
                    InspectorRow("Companions", item.companions.joinToString { it.name })
                }
                item.skipReason?.let { InspectorRow("Skip reason", it) }
                if (item.warnings.isNotEmpty()) {
                    InspectorRow("Warnings", item.warnings.joinToString("\n"))
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
