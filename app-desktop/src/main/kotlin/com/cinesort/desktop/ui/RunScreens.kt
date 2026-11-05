package com.cinesort.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cinesort.desktop.state.AppState
import java.text.DateFormat
import java.util.Date

@Composable
fun RunningScreen(state: AppState) {
    val progress = state.progress
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Organizing…", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        if (progress != null && progress.totalItems > 0) {
            LinearProgressIndicator(
                progress = { progress.doneItems.toFloat() / progress.totalItems },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("${progress.doneItems} of ${progress.totalItems} items")
            progress.currentFileName?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ReportScreen(state: AppState) {
    val report = state.report ?: return
    var confirmUndo by remember { mutableStateOf(false) }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo this run?") },
            text = {
                Text(
                    "Every file this run moved will be moved back to its " +
                        "original folder. This cannot be re-done automatically.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmUndo = false
                        state.undo(report.runId)
                    },
                ) { Text("Undo run") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmUndo = false }) { Text("Cancel") }
            },
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Done", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${report.done} organized · ${report.failed.size} failed · ${report.skipped} skipped",
            style = MaterialTheme.typography.titleMedium,
        )
        if (report.failed.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Failures", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report.failed) { (name, reason) ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text(reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        state.undoReport?.let {
            Text(
                "Undo: ${it.movedBack} file(s) moved back, ${it.removedDirs} folder(s) removed" +
                    if (it.problems.isEmpty()) "" else " — problems: ${it.problems.joinToString()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = state::backToSetup) { Text("New scan") }
            OutlinedButton(onClick = { confirmUndo = true }) { Text("Undo this run") }
            OutlinedButton(onClick = state::showHistory) { Text("History") }
        }
    }
}

@Composable
fun HistoryScreen(state: AppState) {
    var pendingUndoId by remember { mutableStateOf<String?>(null) }
    pendingUndoId?.let { runId ->
        AlertDialog(
            onDismissRequest = { pendingUndoId = null },
            title = { Text("Undo this run?") },
            text = {
                Text(
                    "Every file this run moved will be moved back to its " +
                        "original folder. This cannot be re-done automatically.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingUndoId = null
                        state.undo(runId)
                    },
                ) { Text("Undo run") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingUndoId = null }) { Text("Cancel") }
            },
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Every run is journaled; undo restores the original folders.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = state::backToSetup) { Text("Back") }
        }
        Spacer(Modifier.height(16.dp))
        if (state.history.isEmpty()) {
            Text("No runs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.history, key = { it.id }) { run ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(run.startedAt)),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${run.totalItems} item(s) → ${run.destinationName}" +
                                    (if (run.finishedAt == null) " · unfinished" else "") +
                                    (if (run.undone) " · undone" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!run.undone && run.finishedAt != null) {
                            OutlinedButton(onClick = { pendingUndoId = run.id }) { Text("Undo") }
                        }
                    }
                }
            }
        }
        state.undoReport?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "Undo: ${it.movedBack} file(s) moved back" +
                    if (it.problems.isEmpty()) "" else " — problems: ${it.problems.joinToString()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
