package com.cinesort.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cinesort.core.domain.execute.ExecutionProgress
import com.cinesort.core.domain.execute.ExecutionReport
import com.cinesort.core.domain.execute.UndoReport
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.parser.ParseResult
import com.cinesort.desktop.data.db.RoomJournalStore
import com.cinesort.desktop.data.settings.DesktopSettingsStore
import kotlinx.coroutines.flow.first
import com.cinesort.desktop.data.db.createDesktopDatabase
import com.cinesort.desktop.data.files.DesktopStorageGateway
import com.cinesort.desktop.data.metadata.ApiKeys
import com.cinesort.desktop.data.metadata.DesktopMetadataService
import com.cinesort.desktop.data.session.DesktopOrganizeSession
import com.cinesort.desktop.data.update.DesktopUpdateChecker
import com.cinesort.desktop.data.update.DesktopUpdateInfo
import com.cinesort.desktop.data.work.DesktopPosterPhase
import java.io.File
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
    var tmdbTestResult by mutableStateOf<String?>(null)
        private set
    var omdbTestResult by mutableStateOf<String?>(null)
        private set
    var isTestingTmdb by mutableStateOf(false)
        private set
    var isTestingOmdb by mutableStateOf(false)
        private set

    var scanSettings by mutableStateOf(com.cinesort.core.domain.scan.ScanSettings())
        private set
    var namingOptions by mutableStateOf(com.cinesort.core.domain.naming.NamingOptions())
        private set

    /** Preview search text and status filter (F-06). */
    var previewQuery by mutableStateOf("")
    var previewStatus by mutableStateOf<com.cinesort.core.domain.planner.PlannedStatus?>(null)

    /** Manual parse corrections by planned-item id (F-07), session-scoped. */
    val parseOverrides = androidx.compose.runtime.mutableStateMapOf<String, ParseResult>()

    private var undoInFlight by mutableStateOf(false)

    /** This build's version; kept in step with the packaging version. */
    val currentVersion: String = "1.1.0"

    sealed interface UpdateUiState {
        data object Idle : UpdateUiState
        data object Checking : UpdateUiState
        data object UpToDate : UpdateUiState
        data class Available(val info: DesktopUpdateInfo) : UpdateUiState
        data class Error(val message: String) : UpdateUiState
        data class Downloading(val info: DesktopUpdateInfo, val fraction: Float) : UpdateUiState
        data class Downloaded(val info: DesktopUpdateInfo, val file: File) : UpdateUiState
    }

    var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        private set
    var lastUpdateCheckAt by mutableStateOf(0L)
        private set

    val canScan: Boolean get() = sources.isNotEmpty() && destination != null

    init {
        // Restore the library layout from the last session.
        scope.launch {
            runCatching {
                // Upgrade any v1.0.0 plaintext keys to encrypted form
                // before anything reads them.
                withContext(Dispatchers.IO) { settingsStore.migrateLegacyKeys() }
                runCatching {
                    val restored = withContext(Dispatchers.IO) {
                        settingsStore.scanSettings.first() to settingsStore.namingOptions.first()
                    }
                    scanSettings = restored.first
                    namingOptions = restored.second
                }
                val saved = withContext(Dispatchers.IO) {
                    settingsStore.sources.first() to settingsStore.destination.first()
                }
                sources.clear()
                sources.addAll(saved.first)
                destination = saved.second
                runCatching {
                    lastUpdateCheckAt = withContext(Dispatchers.IO) {
                        settingsStore.lastUpdateCheckAt.first()
                    }
                    if (DesktopUpdateChecker.isAutoCheckDue(lastUpdateCheckAt, System.currentTimeMillis())) {
                        checkForUpdates()
                    }
                }
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

    /** Manual or automatic update check; stamps completion time only. */
    fun checkForUpdates() {
        if (updateState is UpdateUiState.Checking || updateState is UpdateUiState.Downloading) return
        updateState = UpdateUiState.Checking
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DesktopUpdateChecker.fetchLatestNewerThan(currentVersion)
                }
            }.onSuccess { info ->
                updateState = if (info == null) {
                    UpdateUiState.UpToDate
                } else {
                    UpdateUiState.Available(info)
                }
            }.onFailure {
                updateState = UpdateUiState.Error(it.message ?: "Update check failed")
            }
            // The throttle stamp is written only now that a check has
            // completed (the Android v1.0.7 lesson: stamping first
            // silenced launch checks for a day after any failure).
            val now = System.currentTimeMillis()
            lastUpdateCheckAt = now
            withContext(Dispatchers.IO) { settingsStore.setLastUpdateCheckAt(now) }
        }
    }

    fun downloadUpdate(info: DesktopUpdateInfo) {
        updateState = UpdateUiState.Downloading(info, 0f)
        scope.launch {
            runCatching {
                val dest = File(
                    File(System.getProperty("user.home"), ".cinesort-desktop/updates"),
                    info.assetName,
                )
                withContext(Dispatchers.IO) {
                    DesktopUpdateChecker.download(
                        url = info.downloadUrl,
                        dest = dest,
                        sha256Url = info.sha256Url,
                    ) { downloaded, total ->
                        if (total > 0) {
                            updateState = UpdateUiState.Downloading(
                                info, downloaded.toFloat() / total,
                            )
                        }
                    }
                }
                dest
            }.onSuccess { file ->
                updateState = UpdateUiState.Downloaded(info, file)
            }.onFailure {
                updateState = UpdateUiState.Error(it.message ?: "Download failed")
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
        parseOverrides.clear()
        previewQuery = ""
        previewStatus = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.scanAndPlan(
                        sources.toList(),
                        dest,
                        scanSettings = scanSettings,
                        naming = namingOptions,
                        apiKeys = ApiKeys(tmdbKey.ifBlank { null }, omdbKey.ifBlank { null }),
                    )
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
        if (undoInFlight) return
        undoInFlight = true
        scope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.IO) { session.undo(runId) }
                }.onSuccess {
                    undoReport = it
                    loadHistory()
                }.onFailure {
                    error = it.message ?: "Undo failed"
                }
            } finally {
                undoInFlight = false
            }
        }
    }

    /**
     * Bytes by which the currently selected READY items exceed the
     * destination's free space (the domain's own check, finally wired
     * to the UI), or null when space suffices or is unknown.
     */
    fun freeSpaceShortfall(): Long? {
        val current = plan ?: return null
        val free = current.destinationFreeBytes ?: return null
        val items = current.items.map { it.copy(selected = it.id !in deselected) }
        return com.cinesort.core.domain.planner.OrganizePlanner().freeSpaceShortfall(
            items,
            com.cinesort.core.domain.planner.DestinationSnapshot(freeBytes = free),
        )
    }

    /**
     * Applies a manual parse correction from the Inspector and
     * re-plans in place (F-07). Overrides live for this scan only.
     */
    fun applyOverride(itemId: String, corrected: ParseResult) {
        parseOverrides[itemId] = corrected
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.replan(
                        parseOverrides = parseOverrides.toMap(),
                        scanSettings = scanSettings,
                        naming = namingOptions,
                        apiKeys = ApiKeys(tmdbKey.ifBlank { null }, omdbKey.ifBlank { null }),
                    )
                }
            }.onSuccess { result ->
                if (result != null) plan = result
            }.onFailure {
                error = it.message ?: "Re-plan failed"
            }
        }
    }

    fun updateScanSettings(settings: com.cinesort.core.domain.scan.ScanSettings) {
        scanSettings = settings
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setScanSettings(settings) } }
    }

    fun updateNamingOptions(options: com.cinesort.core.domain.naming.NamingOptions) {
        namingOptions = options
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setNamingOptions(options) } }
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

    fun updateDownloadPosters(value: Boolean) {
        downloadPosters = value
        scope.launch { withContext(Dispatchers.IO) { settingsStore.setDownloadPosters(value) } }
    }

    fun testTmdbKey() {
        if (isTestingTmdb) return
        isTestingTmdb = true
        tmdbTestResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    metadataService.testTmdbKey(tmdbKey)
                }
                tmdbTestResult = result.fold(
                    onSuccess = { it },
                    onFailure = { it.message ?: "Test failed" },
                )
            } finally {
                isTestingTmdb = false
            }
        }
    }

    fun testOmdbKey() {
        if (isTestingOmdb) return
        isTestingOmdb = true
        omdbTestResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    metadataService.testOmdbKey(omdbKey)
                }
                omdbTestResult = result.fold(
                    onSuccess = { it },
                    onFailure = { it.message ?: "Test failed" },
                )
            } finally {
                isTestingOmdb = false
            }
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
