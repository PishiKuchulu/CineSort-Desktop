package com.cinesort.core.domain.journal

/** Persisted per-operation state machine (spec section 8.3). */
enum class OperationState {
    PENDING,
    DIRS_READY,
    MOVING,
    COPYING,
    VERIFIED,
    DONE,
    FAILED,
    SKIPPED,

    /** Restored by a successful undo (review M-C); persisted via the state name,
     *  so no schema change is needed. */
    UNDONE,
}

data class OperationRecord(
    val id: String,
    val runId: String,
    val itemId: String,
    /** Original parent directory id and the file's original name. */
    val sourceParentId: String,
    val sourceParentName: String,
    /** The file's own full id (SAF document URI), recorded at execution time so
     *  retry drafts never reconstruct URIs from names (review H7). */
    val sourceFileId: String = "",
    val fileName: String,
    /** Final name in the destination (differs only for the Keep-both conflict choice). */
    val finalName: String,
    val destinationParentId: String,
    /** Destination path segments relative to the destination root. */
    val destinationSegments: List<String>,
    val state: OperationState = OperationState.PENDING,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val isCompanion: Boolean = false,
    /** A downloaded poster file living in the item's destination folder
     *  (review M-A); undo deletes it instead of moving it back. */
    val isPoster: Boolean = false,
    val failureReason: String? = null,
    /** Directories this run created for this operation, relative segments, deepest last. */
    val createdDirs: List<String> = emptyList(),
    val crossVolume: Boolean = false,
)

data class RunRecord(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val optionsSnapshot: String = "",
    val totalItems: Int = 0,
    val undone: Boolean = false,
    /**
     * The destination tree this run wrote into, recorded at execution time
     * (audit B.8): Undo must resolve files under the run's own root, not
     * whatever destination the library points at today. Empty for runs
     * recorded before the field existed — callers fall back to the current
     * destination for those.
     */
    val destinationId: String = "",
    val destinationName: String = "",
)

/** True for states an operation can never leave once reached. */
val OperationState.isTerminal: Boolean
    get() = this == OperationState.DONE || this == OperationState.FAILED ||
        this == OperationState.SKIPPED || this == OperationState.UNDONE

/**
 * Shared journal upkeep for interrupted runs (audit A.3-4 / B.9): a run
 * whose process died keeps operations in non-final states and a null
 * `finishedAt` forever unless someone closes it. Closing marks the stale
 * operations FAILED ("interrupted") AND finishes the run, so History stops
 * showing it as eternally in progress and its Undo eligibility is computed
 * from a finished record. Used by the organize worker (for other runs when
 * a new run starts) and by History's explicit "close as interrupted".
 */
object JournalMaintenance {

    /** Operation states an interrupted run can be stuck in. */
    val NON_FINAL_STATES: Set<OperationState> = setOf(
        OperationState.PENDING,
        OperationState.DIRS_READY,
        OperationState.MOVING,
        OperationState.COPYING,
        OperationState.VERIFIED,
    )

    /** Failure reason recorded on operations closed by [closeInterruptedRun]. */
    const val INTERRUPTED_REASON = "interrupted"

    suspend fun closeInterruptedRun(journal: JournalStore, runId: String, finishedAt: Long) {
        journal.operationsOf(runId)
            .filter { it.state in NON_FINAL_STATES }
            .forEach { op ->
                journal.updateOperation(
                    op.copy(
                        state = OperationState.FAILED,
                        failureReason = INTERRUPTED_REASON,
                    ),
                )
            }
        journal.markRunFinished(runId, finishedAt)
    }
}

/** Persistence port for the journal; Room implements it in core:data. */
interface JournalStore {
    suspend fun createRun(run: RunRecord)
    suspend fun insertOperation(op: OperationRecord)
    suspend fun updateOperation(op: OperationRecord)
    suspend fun operationsOf(runId: String): List<OperationRecord>

    /**
     * Counting-size view of a run's journal (section-3 audit): the best
     * main-operation state per item plus the DONE poster tally, without
     * materializing every operation record. The Run screen polls this
     * while a run executes; Report keeps the full [operationsOf] list
     * because it renders individual failed operations.
     */
    suspend fun operationSummary(runId: String): RunOperationSummary
    suspend fun run(id: String): RunRecord?
    suspend fun recentRuns(limit: Int): List<RunRecord>
    suspend fun markRunFinished(runId: String, finishedAt: Long)
    suspend fun markRunUndone(runId: String)
    suspend fun unfinishedRuns(): List<RunRecord>
}

/** In-memory journal used by tests and previews. */
class InMemoryJournalStore : JournalStore {
    private val runs = linkedMapOf<String, RunRecord>()
    private val ops = linkedMapOf<String, OperationRecord>()

    override suspend fun createRun(run: RunRecord) {
        runs[run.id] = run
    }

    override suspend fun insertOperation(op: OperationRecord) {
        ops[op.id] = op
    }

    override suspend fun updateOperation(op: OperationRecord) {
        ops[op.id] = op
    }

    override suspend fun operationsOf(runId: String): List<OperationRecord> =
        ops.values.filter { it.runId == runId }

    override suspend fun operationSummary(runId: String): RunOperationSummary {
        val runOps = ops.values.filter { it.runId == runId }
        val bestByItem = runOps
            .filter { !it.isCompanion && !it.isPoster }
            .groupBy({ it.itemId }, { it.state })
            .mapValues { (_, states) -> bestStateOf(states) ?: OperationState.PENDING }
        return RunOperationSummary(
            bestStateByItem = bestByItem,
            postersDone = runOps.count {
                it.isPoster && it.state == OperationState.DONE
            },
        )
    }

    override suspend fun run(id: String): RunRecord? = runs[id]

    override suspend fun recentRuns(limit: Int): List<RunRecord> =
        runs.values.sortedByDescending { it.startedAt }.take(limit)

    override suspend fun markRunFinished(runId: String, finishedAt: Long) {
        runs[runId]?.let { runs[runId] = it.copy(finishedAt = finishedAt) }
    }

    override suspend fun markRunUndone(runId: String) {
        runs[runId]?.let { runs[runId] = it.copy(undone = true) }
    }

    override suspend fun unfinishedRuns(): List<RunRecord> =
        runs.values.filter { it.finishedAt == null }
}
