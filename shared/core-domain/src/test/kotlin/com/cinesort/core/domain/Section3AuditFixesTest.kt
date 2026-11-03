package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.bestStateOf
import com.cinesort.core.domain.journal.countFromItemBestStates
import com.cinesort.core.domain.journal.countRunOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus

/**
 * Regression tests for the section-3 (performance) audit fixes: the
 * counting-size journal summary must tally exactly like the full
 * operation list, and the executor must stop re-reading the whole
 * journal per failed item.
 */
class Section3AuditFixesTest {

    private var opSeq = 0

    private fun op(
        itemId: String,
        state: OperationState,
        isCompanion: Boolean = false,
        isPoster: Boolean = false,
    ): OperationRecord = OperationRecord(
        id = "op-${opSeq++}",
        runId = "run-1",
        itemId = itemId,
        sourceParentId = "parent",
        sourceParentName = "Downloads",
        fileName = "file-$itemId.mkv",
        finalName = "file-$itemId.mkv",
        destinationParentId = "dest",
        destinationSegments = listOf("Movie", "Movie (2020)"),
        state = state,
        isCompanion = isCompanion,
        isPoster = isPoster,
    )

    // ------------------------------------------------------------ summary counting

    @Test
    fun `best state follows the counting priority`() {
        assertEquals(
            OperationState.DONE,
            bestStateOf(listOf(OperationState.FAILED, OperationState.DONE)),
        )
        assertEquals(
            OperationState.UNDONE,
            bestStateOf(listOf(OperationState.FAILED, OperationState.UNDONE)),
        )
        assertEquals(
            OperationState.SKIPPED,
            bestStateOf(listOf(OperationState.COPYING, OperationState.SKIPPED)),
        )
        assertEquals(
            OperationState.FAILED,
            bestStateOf(listOf(OperationState.COPYING, OperationState.FAILED)),
        )
        assertEquals(
            OperationState.COPYING,
            bestStateOf(listOf(OperationState.COPYING)),
        )
        // All non-final states share the lowest priority: any of them may
        // win the tie, and the counting treats them identically.
        val nonFinal = bestStateOf(listOf(OperationState.PENDING, OperationState.COPYING))
        assertTrue(
            nonFinal == OperationState.PENDING || nonFinal == OperationState.COPYING,
        )
        assertEquals(null, bestStateOf(emptyList()))
    }

    @Test
    fun `summary counts agree with full-list counts on a mixed journal`() = runTest {
        val ops = listOf(
            op("done", OperationState.DONE),
            op("done", OperationState.DONE, isCompanion = true),
            op("done", OperationState.DONE, isPoster = true),
            op("resumed", OperationState.FAILED),
            op("resumed", OperationState.DONE),
            op("failed", OperationState.FAILED),
            op("failed", OperationState.FAILED, isCompanion = true),
            op("undone", OperationState.UNDONE),
            op("skipped", OperationState.SKIPPED),
            op("stuck", OperationState.COPYING),
            op("posterless", OperationState.DONE, isPoster = true),
        )
        val journal = InMemoryJournalStore()
        ops.forEach { journal.insertOperation(it) }
        val summary = journal.operationSummary("run-1")
        for (finished in listOf(false, true)) {
            assertEquals(
                countRunOperations(ops, runFinished = finished),
                countFromItemBestStates(summary, runFinished = finished),
                "counts must agree (runFinished=$finished)",
            )
        }
        // The summary itself: one best state per item with a main op —
        // the poster-only "item" never appears.
        assertEquals(
            setOf("done", "resumed", "failed", "undone", "skipped", "stuck"),
            summary.bestStateByItem.keys,
        )
        assertEquals(OperationState.DONE, summary.bestStateByItem["resumed"])
        assertEquals(2, summary.postersDone)
    }

    @Test
    fun `summary counts agree on an empty journal`() = runTest {
        val journal = InMemoryJournalStore()
        val summary = journal.operationSummary("run-1")
        assertTrue(summary.bestStateByItem.isEmpty())
        assertEquals(0, summary.postersDone)
        assertEquals(
            countRunOperations(emptyList(), runFinished = true),
            countFromItemBestStates(summary, runFinished = true),
        )
    }

    // ------------------------------------------------------------ executor journal reads

    /** Counts full-journal reads while delegating everything. */
    private class CountingJournalStore(
        private val delegate: JournalStore,
    ) : JournalStore by delegate {
        var operationsOfCalls = 0
            private set

        override suspend fun operationsOf(runId: String): List<OperationRecord> {
            operationsOfCalls++
            return delegate.operationsOf(runId)
        }
    }

    private fun plannedItem(id: String): PlannedItem {
        val file = FileRef(
            id = "file-$id",
            name = "Movie.$id.mkv",
            size = 100,
            lastModified = 0,
            parentId = "parent",
        )
        return PlannedItem(
            id = id,
            scanned = ScannedFile(
                file = file,
                parentDir = DirRef("parent", "Downloads"),
                ancestors = emptyList(),
                siblings = emptyList(),
                sourceVolume = "primary",
            ),
            companions = emptyList(),
            effective = null,
            type = ItemType.MOVIE,
            targetSegments = listOf("Movie", "Movie (2020)"),
            targetFileName = "Movie.$id.mkv",
            status = PlannedStatus.READY,
            selected = true,
        )
    }

    @Test
    fun `a systemic failure reads the journal once not once per failed item`() = runTest {
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        // Every move fails — the systemic case (destination volume gone)
        // that used to cost one full journal read per failed item.
        val dead = object : StorageGateway by gateway {
            override suspend fun move(
                file: FileRef,
                fromDir: DirRef,
                toDir: DirRef,
            ): FileRef = error("volume gone")
        }
        val backing = InMemoryJournalStore()
        val journal = CountingJournalStore(backing)
        val items = (1..6).map { plannedItem("item-$it") }

        val report = OrganizeExecutor(dead, journal, destRoot, clock = { 42L })
            .execute("run-1", items)

        assertEquals(0, report.done)
        assertEquals(6, report.failed.size)
        // One seed snapshot for the whole run, independent of failure count.
        assertEquals(1, journal.operationsOfCalls)
        // And the failures are still all journaled, via the live index.
        val ops = backing.operationsOf("run-1")
        assertEquals(6, ops.size)
        assertTrue(ops.all { it.state == OperationState.FAILED })
        assertTrue(ops.all { it.failureReason == "volume gone" })
        assertEquals(42L, backing.run("run-1")!!.finishedAt)
    }
}
