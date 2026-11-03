package com.cinesort.core.domain.execute

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalMaintenance
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class ExecutionProgress(
    val doneItems: Int,
    val totalItems: Int,
    val currentFileName: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class ExecutionReport(
    val runId: String,
    val done: Int,
    val failed: List<Pair<String, String>>,
    val skipped: Int,
)

/**
 * Sequential executor (spec section 8.3): journals every step, resumes from the journal,
 * and never lets one item's failure abort the run. The WorkManager worker in the app
 * layer drives this class; all storage goes through [StorageGateway].
 */
class OrganizeExecutor(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
    private val destinationRoot: DirRef,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(
        runId: String,
        items: List<PlannedItem>,
        optionsSnapshot: String = "",
        destinationId: String = "",
        destinationName: String = "",
        onProgress: (ExecutionProgress) -> Unit = {},
    ): ExecutionReport {
        val runnable = items.filter { it.selected && it.status == PlannedStatus.READY }
        val runnableIds = runnable.map { it.id }.toSet()
        journal.createRun(
            RunRecord(
                id = runId,
                startedAt = clock(),
                optionsSnapshot = optionsSnapshot,
                totalItems = runnable.size,
                // The run remembers its own destination (audit B.8): Undo
                // resolves files under this root even after the user points
                // the library at a different destination.
                destinationId = destinationId,
                destinationName = destinationName,
            ),
        )
        // One journal snapshot seeds everything this attempt needs
        // (section-3 audit): hygiene, the SKIPPED bookkeeping and the
        // resume index all derive from it, and a live in-memory index —
        // kept current by routing every operation write through
        // [insertOp]/[updateOp] — replaces the per-item re-reads that
        // made a systemic failure cost O(n²) full-journal queries. The
        // executor is the run's only journal writer while it executes
        // (posters are journaled by the worker after execute returns),
        // so the index can never drift from the persisted journal.
        val opsIndex = journal.operationsOf(runId)
            .groupByTo(mutableMapOf()) { it.itemId }
            .mapValuesTo(mutableMapOf()) { (_, ops) -> ops.toMutableList() }
        // Resume hygiene (reviews H8/M5): operations left in a non-final state by
        // an interrupted earlier attempt of this same run are closed as FAILED, so
        // the report never shows phantom in-progress rows. Terminal states —
        // including SKIPPED and UNDONE — are never rewritten.
        opsIndex.values.flatten()
            .filter {
                it.itemId in runnableIds && it.state in JournalMaintenance.NON_FINAL_STATES
            }
            .forEach {
                updateOp(
                    opsIndex,
                    it.copy(
                        state = OperationState.FAILED,
                        failureReason = "interrupted before completion",
                    ),
                )
            }
        // Items handed in but not runnable are journaled as SKIPPED
        // (audit A.3-1): the SKIPPED state existed and the report counted
        // it from the journal, but nothing ever wrote it, so the counter
        // was permanently zero and the only skip tally lived in this
        // in-memory report. One main row per skipped item; rows an earlier
        // attempt already wrote are left alone.
        items.filter { it.id !in runnableIds && it.id !in opsIndex }
            .forEach { item ->
                insertOp(
                    opsIndex,
                    OperationRecord(
                        id = idFactory(),
                        runId = runId,
                        itemId = item.id,
                        sourceParentId = item.scanned.file.parentId,
                        sourceParentName = item.scanned.parentDir.name,
                        sourceFileId = item.scanned.file.id,
                        fileName = item.scanned.file.name,
                        finalName = item.targetFileName,
                        destinationParentId = destinationRoot.id,
                        destinationSegments = item.targetSegments,
                        state = OperationState.SKIPPED,
                        bytesTotal = item.scanned.file.size,
                        crossVolume = item.crossVolume,
                    ),
                )
            }
        // Per-operation resume (review M-B): operations an earlier attempt of
        // this run already completed are skipped instead of re-executed. An item
        // whose video op is DONE but whose companion op FAILED must not re-run
        // the video move — its source is gone, so the move would fail and the
        // companion would never be retried.
        val doneOpsByItem = opsIndex.mapValues { (_, ops) ->
            ops.filter { it.state == OperationState.DONE }
        }
        val bytesTotal = runnable.sumOf { it.sizeBytes }
        var bytesDone = 0L
        var done = 0
        // Items handed in but not runnable (unselected / not READY) are the
        // report's skipped count (review L8 — it used to stay 0 forever).
        val skipped = items.size - runnable.size
        val failed = mutableListOf<Pair<String, String>>()
        val dirCache = mutableMapOf<String, DirRef>()

        for ((index, item) in runnable.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(ExecutionProgress(index, runnable.size, item.scanned.file.name, bytesDone, bytesTotal))
            try {
                executeItem(runId, item, dirCache, doneOpsByItem[item.id].orEmpty(), opsIndex)
                done++
                bytesDone += item.sizeBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Review H-A: ANY failure of one item (a provider's
                // IllegalStateException, a malformed-URI IllegalArgumentException,
                // not just IOException/SecurityException) is recorded and the run
                // continues — one bad file must never abort the remaining items
                // or skip markRunFinished.
                val reason = e.message ?: e.javaClass.simpleName
                failed += item.scanned.file.name to reason
                markItemFailed(runId, item, reason, opsIndex)
            }
        }
        journal.markRunFinished(runId, clock())
        onProgress(ExecutionProgress(runnable.size, runnable.size, null, bytesDone, bytesTotal))
        return ExecutionReport(runId, done, failed, skipped)
    }

    /** Moves one item's file and companions, skipping ops already DONE (review M-B). */
    private suspend fun executeItem(
        runId: String,
        item: PlannedItem,
        dirCache: MutableMap<String, DirRef>,
        doneOps: List<OperationRecord>,
        opsIndex: MutableMap<String, MutableList<OperationRecord>>,
    ) {
        val createdDirs = mutableListOf<String>()
        val targetDir = ensureDirs(item.targetSegments, dirCache, createdDirs)
        if (!isAlreadyDone(doneOps, item.scanned.file, isCompanion = false)) {
            moveOne(
                item.scanned.file, item, targetDir, runId, createdDirs,
                isCompanion = false, opsIndex = opsIndex,
            )
        }
        for (companion in item.companions) {
            if (!isAlreadyDone(doneOps, companion, isCompanion = true)) {
                moveOne(
                    companion, item, targetDir, runId, createdDirs,
                    isCompanion = true, opsIndex = opsIndex,
                )
            }
        }
    }

    /**
     * True when [doneOps] already contains a completed operation for [file]
     * (review M-B). Matching prefers the recorded source file id; journals from
     * before the id was recorded fall back to the file name.
     */
    private fun isAlreadyDone(
        doneOps: List<OperationRecord>,
        file: FileRef,
        isCompanion: Boolean,
    ): Boolean = doneOps.any { op ->
        op.isCompanion == isCompanion &&
            (op.sourceFileId == file.id || (op.sourceFileId.isEmpty() && op.fileName == file.name))
    }

    /**
     * Persists an item's failure in the journal (review M5): unfinished operations
     * become FAILED with the reason; when the failure happened before any operation
     * row existed (e.g. ensureDirs), a FAILED row is created so the report and the
     * history show it.
     *
     * The item's operations come from the run's live [opsIndex] (section-3
     * audit): this used to re-read the whole run journal per failed item,
     * which turns a systemic failure — every item failing, e.g. a full or
     * vanished destination volume — into O(n²) full-journal queries
     * exactly while the run is already in trouble.
     */
    private suspend fun markItemFailed(
        runId: String,
        item: PlannedItem,
        reason: String,
        opsIndex: MutableMap<String, MutableList<OperationRecord>>,
    ) {
        val ops = opsIndex[item.id].orEmpty()
        val unfinished = ops.filter {
            it.state != OperationState.DONE && it.state != OperationState.FAILED
        }
        if (unfinished.isEmpty() && ops.none { !it.isCompanion }) {
            insertOp(
                opsIndex,
                OperationRecord(
                    id = idFactory(),
                    runId = runId,
                    itemId = item.id,
                    sourceParentId = item.scanned.file.parentId,
                    sourceParentName = item.scanned.parentDir.name,
                    sourceFileId = item.scanned.file.id,
                    fileName = item.scanned.file.name,
                    finalName = item.targetFileName,
                    destinationParentId = destinationRoot.id,
                    destinationSegments = item.targetSegments,
                    state = OperationState.FAILED,
                    bytesTotal = item.scanned.file.size,
                    failureReason = reason,
                    crossVolume = item.crossVolume,
                ),
            )
        } else {
            unfinished.forEach {
                updateOp(
                    opsIndex,
                    it.copy(state = OperationState.FAILED, failureReason = reason),
                )
            }
        }
    }

    private suspend fun moveOne(
        file: FileRef,
        item: PlannedItem,
        targetDir: DirRef,
        runId: String,
        createdDirs: List<String>,
        isCompanion: Boolean,
        opsIndex: MutableMap<String, MutableList<OperationRecord>>,
    ) {
        val finalName = if (isCompanion) file.name else item.targetFileName
        var op = OperationRecord(
            id = idFactory(),
            runId = runId,
            itemId = item.id,
            sourceParentId = file.parentId,
            sourceParentName = item.scanned.parentDir.name,
            sourceFileId = file.id,
            fileName = file.name,
            finalName = finalName,
            destinationParentId = targetDir.id,
            destinationSegments = item.targetSegments,
            bytesTotal = file.size,
            isCompanion = isCompanion,
            createdDirs = createdDirs.toList(),
            crossVolume = item.crossVolume,
        )
        insertOp(opsIndex, op)
        op = op.copy(state = OperationState.DIRS_READY)
        updateOp(opsIndex, op)
        val fromDir = item.scanned.parentDir
        if (item.crossVolume) {
            op = op.copy(state = OperationState.COPYING)
            updateOp(opsIndex, op)
            gateway.copyVerified(file, targetDir, targetName = finalName) { progress ->
                op = op.copy(bytesDone = progress)
            }
            updateOp(opsIndex, op.copy(state = OperationState.VERIFIED))
            gateway.delete(file)
        } else {
            op = op.copy(state = OperationState.MOVING)
            updateOp(opsIndex, op)
            if (finalName == file.name) {
                gateway.move(file, fromDir, targetDir)
            } else {
                gateway.copyVerified(file, targetDir, targetName = finalName)
                gateway.delete(file)
            }
            op = op.copy(state = OperationState.VERIFIED)
            updateOp(opsIndex, op)
        }
        updateOp(opsIndex, op.copy(state = OperationState.DONE, bytesDone = file.size))
    }

    /**
     * Journal writes that keep the run's in-memory operation index in
     * sync (section-3 audit): every operation insert/update of this
     * executor goes through here, so [markItemFailed] can read the item's
     * current rows from memory instead of re-querying the whole run.
     */
    private suspend fun insertOp(
        opsIndex: MutableMap<String, MutableList<OperationRecord>>,
        op: OperationRecord,
    ) {
        journal.insertOperation(op)
        opsIndex.getOrPut(op.itemId) { mutableListOf() }.add(op)
    }

    private suspend fun updateOp(
        opsIndex: MutableMap<String, MutableList<OperationRecord>>,
        op: OperationRecord,
    ) {
        journal.updateOperation(op)
        val rows = opsIndex.getOrPut(op.itemId) { mutableListOf() }
        val position = rows.indexOfFirst { it.id == op.id }
        if (position >= 0) rows[position] = op else rows.add(op)
    }

    /** Resolves existing dirs under the destination root without creating anything. */
    suspend fun resolveDirs(
        segments: List<String>,
        cache: MutableMap<String, DirRef> = mutableMapOf(),
    ): DirRef? {
        var current = destinationRoot
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append('/')
            path.append(segment)
            val key = path.toString()
            val cached = cache[key]
            if (cached != null) {
                current = cached
            } else {
                current = gateway.findChildDir(current, segment, ignoreCase = true) ?: return null
                cache[key] = current
            }
        }
        return current
    }

    /** Creates (or reuses, case-insensitively) each segment under the destination root. */
    suspend fun ensureDirs(
        segments: List<String>,
        cache: MutableMap<String, DirRef> = mutableMapOf(),
        createdDirs: MutableList<String>? = null,
    ): DirRef {
        var current = destinationRoot
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append('/')
            path.append(segment)
            val key = path.toString()
            val cached = cache[key]
            if (cached != null) {
                current = cached
                continue
            }
            val existing = gateway.findChildDir(current, segment, ignoreCase = true)
            current = if (existing != null) {
                existing
            } else {
                createdDirs?.add(key)
                gateway.createDir(current, segment)
            }
            cache[key] = current
        }
        return current
    }
}
