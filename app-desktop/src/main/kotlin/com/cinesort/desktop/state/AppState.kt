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
import com.cinesort.desktop.data.settings.DesktopSettingsStore
import kotlinx.coroutines.flow.first
import com.cinesort.desktop.data.db.createDesktopDatabase
import com.cinesort.desktop.data.files.DesktopStorageGateway
import com.cinesort.desktop.data.metadata.ApiKeys
import com.cinesort.desktop.data.metadata.DesktopMetadataService
import com.cinesort.desktop.data.session.DesktopOrganizeSession
import com.cinesort.desktop.data.work.DesktopPosterPhase
import com.cinesort.desktop.data.session.SessionPlan
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { SETUP, SCANNING, PREVIEW, RUNNING, REPORT, HISTORY, SETTINGS }

/**
 * The window's state holder: owns the session, the persistent journal
 * (Room database under the user's home), and every screen's state.
 * All file work runs on IO; state flips back on the UI scope.
 */
class AppState(
    private val scope: CoroutineScope,
) {
    private val dataDir: Path =
        Paths.get(System.getProperty("user.home"), ".cinesort-desktop")
    private val database = createDesktopDatabase(dataDir.resolve("cinesort.db"))
    private val settingsStore = DesktopSettingsStore(dataDir)
    private val journal = RoomJournalStore(database.runDao())
    private val metadataService = DesktopMetadataService()
    private val gateway = DesktopStorageGateway()
    private val session = DesktopOrganizeSession(gateway, journal, metadataService)
    private val posterPhase = DesktopPosterPhase(gateway, journal)

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

    var tmdbKey by mutableStateOf("")
    var omdbKey by mutableStateOf("")
    var downloadPosters by mutableStateOf(false)
        private set
    var keyTestResult by mutableStateOf<String?>(null)
        private set

    val canScan: Boolean get() = sources.isNotEmpty() && destination != null

    init {
        // Restore the library layout from the last session.
        scope.launch {
            runCatching {
                val saved = withContext(Dispatchers.IO) {
                    settingsStore.sources.first() to settingsStore.destination.first()
                }
                sources.clear()
                sources.addAll(saved.first)
                destination = saved.second
                runCatching {
                    val keys = withContext(Dispatchers.IO) {
                        settingsStore.tmdbKey.first() to settingsStore.omdbKey.first()
                    }
                    tmdbKey = keys.first.orEmpty()
                    omdbKey = keys.second.orEmpty()
                    downloadPosters = withContext(Dispatchers.IO) {
                        settingsStore.downloadPosters.first()
                    }
                }
            }
        }
    }

    fun addSource(path: Path) {
        if (path !in sources) {
            sources.add(path)
            persistSources()
        }
    }

    fun removeSource(path: Path) {
        sources.remove(path)
        persistSources()
    }

    fun chooseDestination(path: Path) {
        destination = path
        scope.launch {
            withContext(Dispatchers.IO) { settingsStore.setDestination(path) }
        }
    }

    private fun persistSources() {
        val snapshot = sources.toList()
        scope.launch {
            withContext(Dispatchers.IO) { settingsStore.setSources(snapshot) }
        }
    }

    fun scan() {
        val dest = destination ?: return
        phase = Phase.SCANNING
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.scanAndPlan(sources.toList(), dest, apiKeys = ApiKeys(tmdbKey.ifBlank { null }, omdbKey.ifBlank { null }))
                }
            }.onSuccess { result ->
                plan = result
                deselected.clear()
                deselected.addAll(result.items.filter { !it.selected }.map { it.id })
                inspectedItemId = result.items.firstOrNull()?.id
                phase = Phase.PREVIEW
            }.onFailure {
                error = it.message ?: "Scan failed"
                phase = Phase.SETUP
            }
        }
    }

    fun toggleSelected(itemId: String) {
        if (itemId in deselected) deselected.remove(itemId) else deselected.add(itemId)
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
            }.onSuccess { result ->
                report = result
                undoReport = null
                if (downloadPosters) {
                    // Poster phase (best effort): journaled poster.jpg
                    // files for folders whose items all finished DONE.
                    runCatching {
                        withContext(Dispatchers.IO) {
                            posterPhase.downloadPosters(
                                result.runId,
                                items,
                                dest.toDirRef(),
                            )
                        }
                    }
                }
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

    fun saveTmdbKey(value: String) {
        tmdbKey = value
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setTmdbKey(value) } }
    }

    fun saveOmdbKey(value: String) {
        omdbKey = value
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setOmdbKey(value) } }
    }

    fun setDownloadPosters(value: Boolean) {
        downloadPosters = value
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setDownloadPosters(value) } }
    }

    fun testTmdbKey() {
        keyTestResult = "Testing TMDB key…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                metadataService.testTmdbKey(tmdbKey)
            }
            keyTestResult = result.fold(
                onSuccess = { "TMDB: $it" },
                onFailure = { "TMDB: ${it.message ?: "failed"}" },
            )
        }
    }

    fun testOmdbKey() {
        keyTestResult = "Testing OMDb key…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                metadataService.testOmdbKey(omdbKey)
            }
            keyTestResult = result.fold(
                onSuccess = { "OMDb: $it" },
                onFailure = { "OMDb: ${it.message ?: "failed"}" },
            )
        }
    }

    fun showSettings() {
        phase = Phase.SETTINGS
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


private fun Path.toDirRef(): com.cinesort.core.domain.model.DirRef =
    com.cinesort.core.domain.model.DirRef(toString(), fileName?.toString() ?: toString())
