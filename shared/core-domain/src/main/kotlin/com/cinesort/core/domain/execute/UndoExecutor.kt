package com.cinesort.core.domain.execute

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import kotlinx.coroutines.CancellationException

data class UndoReport(
    val runId: String,
    val movedBack: Int,
    val problems: List<String>,
    val removedDirs: Int,
)

/**
 * Undo (spec section 8.5): moves a run's files back to their original parents, then
 * removes only directories created by that run that are still empty. Items whose file
 * vanished or whose original name is now occupied are reported, never forced.
 */
class UndoExecutor(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
    private val destinationRoot: DirRef,
) {
    suspend fun undo(runId: String): UndoReport {
        val run = journal.run(runId) ?: return UndoReport(runId, 0, listOf("run not found"), 0)
        if (run.undone) return UndoReport(runId, 0, listOf("run already undone"), 0)
        // Only DONE ops are walked (review M-C): ops a previous undo attempt
        // already restored are persisted as UNDONE and skipped silently here,
        // instead of being re-walked and misreported as missing files.
        val ops = journal.operationsOf(runId).filter { it.state == OperationState.DONE }
        val problems = mutableListOf<String>()
        // Deepest first; the path tiebreak matters (review H1): a depth-only
        // comparator makes TreeSet treat distinct same-depth paths as duplicates
        // and silently drop all but one of them.
        val createdDirs = sortedSetOf<String>(
            compareByDescending<String> { it.count { c -> c == '/' } }.thenBy { it },
        )

        // Resolve destination dirs per operation from its segments.
        val executor = OrganizeExecutor(gateway, journal, destinationRoot)
        val dirCache = mutableMapOf<String, DirRef>()
        var movedBack = 0
        for (op in ops.asReversed()) {
            if (op.isPoster) continue
            createdDirs += op.createdDirs
            if (undoOperation(op, executor, dirCache, problems)) movedBack++
        }

        // Downloaded posters are deleted, not moved back (review M-A), and
        // they must go before the created-dir cleanup: a folder still holding
        // poster.jpg is not empty and would survive the undo.
        for (op in ops.filter { it.isPoster }) {
            createdDirs += op.createdDirs
            undoPoster(op, executor, dirCache, problems)
        }

        val removedDirs = removeEmptyCreatedDirs(createdDirs, problems)
        if (problems.isEmpty()) journal.markRunUndone(runId)
        return UndoReport(runId, movedBack, problems, removedDirs)
    }

    /** Undoes one operation; returns true when its file was moved back. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun undoOperation(
        op: OperationRecord,
        executor: OrganizeExecutor,
        dirCache: MutableMap<String, DirRef>,
        problems: MutableList<String>,
    ): Boolean {
        try {
            val located = locateMovedFile(op, executor, dirCache, problems) ?: return false
            val moved = moveBack(op, located.first, located.second, problems)
            if (moved) {
                // Review M-C: persist the per-op undone marker so a later undo
                // attempt skips this op and the run can reach fully-undone.
                journal.updateOperation(op.copy(state = OperationState.UNDONE))
            }
            return moved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Review H-A: any provider/runtime failure is reported for this op
            // and the undo continues with the remaining ops.
            problems += "${op.finalName}: ${e.message ?: e.javaClass.simpleName}"
            return false
        }
    }

    /**
     * Deletes one journaled poster file from its destination folder (review
     * M-A) and marks the op UNDONE. A folder or poster that is already gone
     * needs no action — the desired end state (no poster) already holds. A
     * failed delete is reported and the op stays DONE, so a later undo
     * attempt retries it (same semantics as file ops).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun undoPoster(
        op: OperationRecord,
        executor: OrganizeExecutor,
        dirCache: MutableMap<String, DirRef>,
        problems: MutableList<String>,
    ) {
        try {
            val folder = executor.resolveDirs(op.destinationSegments, dirCache)
            val poster = folder?.let {
                gateway.listChildren(it)
                    .firstOrNull { child -> !child.isDirectory && child.ref.name == op.finalName }
                    ?.ref
            }
            if (poster != null) gateway.delete(poster)
            journal.updateOperation(op.copy(state = OperationState.UNDONE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problems += "${op.finalName}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Finds the moved file and its current directory, recording why when it is gone. */
    private suspend fun locateMovedFile(
        op: OperationRecord,
        executor: OrganizeExecutor,
        dirCache: MutableMap<String, DirRef>,
        problems: MutableList<String>,
    ): Pair<FileRef, DirRef>? {
        val currentDir = executor.resolveDirs(op.destinationSegments, dirCache)
        if (currentDir == null) {
            problems += "${op.finalName}: destination folder is gone"
            return null
        }
        val currentFile = gateway.listChildren(currentDir)
            .firstOrNull { !it.isDirectory && it.ref.name == op.finalName }
            ?.ref as? FileRef
        if (currentFile == null) {
            problems += "${op.finalName}: file no longer in destination"
            return null
        }
        return currentFile to currentDir
    }

    /** Moves [file] from [currentDir] back to the operation's original parent folder. */
    private suspend fun moveBack(
        op: OperationRecord,
        file: FileRef,
        currentDir: DirRef,
        problems: MutableList<String>,
    ): Boolean {
        val originalParent = DirRef(op.sourceParentId, op.sourceParentName)
        // The post-run cleanup may have removed the emptied folder (review M6);
        // spec 8.5 recreates missing parents instead of failing the undo.
        val liveParent = if (gateway.isValid(originalParent)) {
            originalParent
        } else {
            gateway.recreateDir(originalParent)
        }
        if (liveParent == null) {
            problems += "${op.finalName}: original folder is gone"
            return false
        }
        val nameTaken = gateway.listChildren(liveParent)
            .any { !it.isDirectory && it.ref.name == op.fileName }
        if (nameTaken) {
            problems += "${op.finalName}: original name is occupied"
            return false
        }
        val sameVolume = gateway.volumeIdOf(file) == gateway.volumeIdOf(liveParent)
        if (sameVolume && op.finalName == op.fileName) {
            gateway.move(file, currentDir, liveParent)
        } else {
            gateway.copyVerified(file, liveParent, targetName = op.fileName)
            gateway.delete(file)
        }
        return true
    }

    /** Removes run-created directories that are still empty, deepest first. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun removeEmptyCreatedDirs(
        createdDirs: Set<String>,
        problems: MutableList<String>,
    ): Int {
        var removedDirs = 0
        for (dirPath in createdDirs) {
            try {
                val dir = resolveFromDestination(dirPath) ?: continue
                if (gateway.listChildren(dir).isEmpty()) {
                    gateway.delete(dir)
                    removedDirs++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "dir $dirPath: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return removedDirs
    }

    /** Walks [dirPath] segments down from the destination root; null when any is missing. */
    private suspend fun resolveFromDestination(dirPath: String): DirRef? {
        var current: DirRef = destinationRoot
        for (segment in dirPath.split('/')) {
            current = gateway.findChildDir(current, segment, ignoreCase = true) ?: return null
        }
        return current
    }
}
