package com.cinesort.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cinesort.core.domain.execute.ExecutionProgress
import com.cinesort.core.domain.execute.ExecutionReport
import com.cinesort.core.domain.execute.UndoReport
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.desktop.data.db.RoomJournalStore
import com.cinesort.desktop.data.db.createDesktopDatabase
import com.cinesort.desktop.data.files.DesktopStorageGateway
import com.cinesort.desktop.data.session.DesktopOrganizeSession
import com.cinesort.desktop.data.session.SessionPlan
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { SETUP, SCANNING, PREVIEW, RUNNING, REPORT, HISTORY }

/**
 * The window's state holder: owns the session, the persistent journal
 * (Room database under the user's home), and every screen's state.
 * All file work runs on IO; state flips back on the UI scope.
 */
class AppState(
    private val scope: CoroutineScope,
) {
    private val database = createDesktopDatabase(
        Paths.get(System.getProperty("user.home"), ".cinesort-desktop", "cinesort.db"),
    )
    private val journal = RoomJournalStore(database.runDao())
    private val session = DesktopOrganizeSession(DesktopStorageGateway(), journal)

    var phase by mutableStateOf(Phase.SETUP)
        private set
    val sources = mutableStateListOf<Path>()
    var destination by mutableStateOf<Path?>(null)
    var error by mutableStateOf<String?>(null)
        private set

    var plan by mutableStateOf<SessionPlan?>(null)
        private set
    val deselected = mutableStateListOf<String>()
    var inspectedItemId by mutableStateOf<String?>(null)

    var progress by mutableStateOf<ExecutionProgress?>(null)
        private set
    var report by mutableStateOf<ExecutionReport?>(null)
        private set
    var undoReport by mutableStateOf<UndoReport?>(null)
        private set

    var history by mutableStateOf<List<RunRecord>>(emptyList())
        private set

    val canScan: Boolean get() = sources.isNotEmpty() && destination != null

    fun addSource(path: Path) {
        if (path !in sources) sources += path
    }

    fun removeSource(path: Path) {
        sources -= path
    }

    fun scan() {
        val dest = destination ?: return
        phase = Phase.SCANNING
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.scanAndPlan(sources.toList(), dest)
                }
            }.onSuccess { result ->
                plan = result
                deselected.clear()
                deselected += result.items.filter { !it.selected }.map { it.id }
                inspectedItemId = result.items.firstOrNull()?.id
                phase = Phase.PREVIEW
            }.onFailure {
                error = it.message ?: "Scan failed"
                phase = Phase.SETUP
            }
        }
    }

    fun toggleSelected(itemId: String) {
        if (itemId in deselected) deselected -= itemId else deselected += itemId
    }

    fun execute() {
        val dest = destination ?: return
        val current = plan ?: return
        val items = current.items.map { it.copy(selected = it.id !in deselected) }
        phase = Phase.RUNNING
        progress = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.execute(items, dest) { p -> progress = p }
                }
            }.onSuccess {
                report = it
                undoReport = null
                phase = Phase.REPORT
            }.onFailure {
                error = it.message ?: "Run failed"
                phase = Phase.PREVIEW
            }
        }
    }

    fun undo(runId: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { session.undo(runId) }
            }.onSuccess {
                undoReport = it
                loadHistory()
            }.onFailure {
                error = it.message ?: "Undo failed"
            }
        }
    }

    fun loadHistory() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { journal.recentRuns(50) }
            }.onSuccess { history = it }
        }
    }

    fun showHistory() {
        phase = Phase.HISTORY
        loadHistory()
    }

    fun backToSetup() {
        phase = Phase.SETUP
        plan = null
        report = null
        progress = null
    }

    fun backToPreview() {
        if (plan != null) phase = Phase.PREVIEW
    }
}
