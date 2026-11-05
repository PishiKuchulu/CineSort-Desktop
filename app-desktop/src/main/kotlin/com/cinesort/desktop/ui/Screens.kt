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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.AnchorKind
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.ParseTrace
import com.cinesort.desktop.state.AppState
import java.io.File
import java.net.URI
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SetupScreen(state: AppState) {
    // Drag & drop (F-15): folders dropped anywhere on this screen are
    // added as download folders.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData() as? DragData.FilesList ?: return false
                var handled = false
                for (entry in data.readFiles()) {
                    val file = runCatching { File(URI(entry)) }
                        .getOrElse { File(entry) }
                    if (file.isDirectory) {
                        state.addSource(file.toPath())
                        handled = true
                    }
                }
                return handled
            }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                target = dropTarget,
            ),
    ) {
        Text("Organize a library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "CineSort moves video files into a clean Movie / Tv series library. " +
                "Every run is journaled and can be undone. " +
                "You can also drag folders onto this window to add them.",
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
                    onClick = { pickDirectory("Choose the library folder")?.let(state::chooseDestination) },
                ) { Text("Choose") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = state::scan, enabled = state.canScan) { Text("Scan & preview") }
            OutlinedButton(onClick = state::showHistory) { Text("History") }
            OutlinedButton(onClick = state::showSettings) { Text("Settings") }
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
    val counts = plan.items.groupingBy { it.status }.eachCount()
    val visibleItems = plan.items.filter { item ->
        (state.previewStatus == null || item.status == state.previewStatus) &&
            (state.previewQuery.isBlank() ||
                item.scanned.file.name.contains(state.previewQuery, ignoreCase = true) ||
                item.targetPath.contains(state.previewQuery, ignoreCase = true))
    }
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
        state.freeSpaceShortfall()?.let { shortfall ->
            Spacer(Modifier.height(10.dp))
            Text(
                "Not enough free space at the destination: the selected items need " +
                    "${"%.1f".format(shortfall / 1_000_000.0)} MB more than is available. " +
                    "Deselect items, free up space, or choose another destination.",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.previewQuery,
            onValueChange = { state.previewQuery = it },
            label = { Text("Search files and destinations") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.previewStatus == null,
                onClick = { state.previewStatus = null },
                label = { Text("All (${plan.items.size})") },
            )
            PlannedStatus.entries
                .filter { (counts[it] ?: 0) > 0 }
                .forEach { status ->
                    FilterChip(
                        selected = state.previewStatus == status,
                        onClick = {
                            state.previewStatus =
                                if (state.previewStatus == status) null else status
                        },
                        label = {
                            Text(
                                "${status.name.lowercase().replace('_', ' ')} (${counts[status]})",
                            )
                        },
                    )
                }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleItems, key = { it.id }) { item ->
                    PreviewCard(state, item)
                }
            }
            Spacer(Modifier.width(16.dp))
            InspectorCard(state, inspected)
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
            containerColor = when {
                // A conflicted card is tinted as an error at card level,
                // not only by its label (audit wave 1.1.0).
                item.status == PlannedStatus.CONFLICT ->
                    MaterialTheme.colorScheme.errorContainer
                state.inspectedItemId == item.id ->
                    MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
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
                Text(
                    item.displayTitle(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.scanned.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "→ ${item.targetPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
private fun InspectorCard(state: AppState, item: PlannedItem?) {
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
                Spacer(Modifier.height(12.dp))
                InspectorEditForm(state, item)
            }
        }
    }
}

/**
 * Manual parse correction (F-07): edit the title/year (movie) or the
 * series/season/episodes (episode), or define an unrecognized file as
 * a movie. Applying re-plans the preview; the correction lasts for
 * this scan.
 */
@Composable
private fun InspectorEditForm(state: AppState, item: PlannedItem) {
    val effective = item.effective
    var title by remember(item.id, effective) {
        mutableStateOf(
            when (effective) {
                is ParseResult.Movie -> effective.title
                is ParseResult.Episode -> effective.seriesTitle
                else -> ""
            },
        )
    }
    var year by remember(item.id, effective) {
        mutableStateOf(
            when (effective) {
                is ParseResult.Movie -> effective.year?.toString() ?: ""
                is ParseResult.Episode -> effective.seriesYearHint?.toString() ?: ""
                else -> ""
            },
        )
    }
    var season by remember(item.id, effective) {
        mutableStateOf((effective as? ParseResult.Episode)?.season?.toString() ?: "")
    }
    var episodes by remember(item.id, effective) {
        mutableStateOf(
            (effective as? ParseResult.Episode)?.episodes?.joinToString(", ") ?: "",
        )
    }
    val isEpisode = effective is ParseResult.Episode
    Text(
        if (effective == null) "Define this file" else "Correct the parse",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text(if (isEpisode) "Series title" else "Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = year,
        onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
        label = { Text("Year (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (isEpisode) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = season,
                onValueChange = { season = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("Season") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = episodes,
                onValueChange = { episodes = it },
                label = { Text("Episodes") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    val manualTrace = ParseTrace(
        titleRange = null,
        anchorRange = null,
        anchorKind = AnchorKind.NONE,
        rules = listOf("manual-edit"),
    )
    Button(
        onClick = {
            val corrected: ParseResult = when (effective) {
                is ParseResult.Episode -> effective.copy(
                    seriesTitle = title.trim(),
                    seriesYearHint = year.toIntOrNull(),
                    season = season.toIntOrNull() ?: effective.season,
                    episodes = episodes.split(",", " ")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .ifEmpty { effective.episodes },
                )
                is ParseResult.Movie -> effective.copy(
                    title = title.trim(),
                    year = year.toIntOrNull(),
                )
                else -> ParseResult.Movie(
                    fileName = item.scanned.file.name,
                    title = title.trim(),
                    year = year.toIntOrNull(),
                    confidence = Confidence.LOW,
                    trace = manualTrace,
                )
            }
            state.applyOverride(item.id, corrected)
        },
        enabled = title.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Apply & re-plan") }
    if (item.id in state.parseOverrides) {
        Text(
            "Manually corrected for this scan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
