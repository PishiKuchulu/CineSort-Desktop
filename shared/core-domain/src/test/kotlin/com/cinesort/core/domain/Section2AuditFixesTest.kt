package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.journal.JournalMaintenance
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.domain.journal.countRunOperations
import com.cinesort.core.domain.journal.retryableItemIds
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.DestinationSnapshot
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.domain.scan.ScanRules
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the section-2 (logic / engine↔UI) audit fixes:
 * centralized run counting, retry-after-undo exclusion, scan-depth
 * resolution, the planner free-space check, SKIPPED journaling and the
 * interrupted-run close.
 */
class Section2AuditFixesTest {

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

    // ------------------------------------------------------------ counting

    @Test
    fun `poster operations never inflate the done count`() {
        val ops = listOf(
            op("a", OperationState.DONE),
            op("a", OperationState.DONE, isCompanion = true),
            op("a", OperationState.DONE, isPoster = true),
            op("b", OperationState.DONE),
            op("b", OperationState.DONE, isPoster = true),
        )
        val counts = countRunOperations(ops, runFinished = true)
        assertEquals(2, counts.done)
        assertEquals(2, counts.posters)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `a stale failed row beside the done row of a resumed item counts once as done`() {
        val ops = listOf(
            op("a", OperationState.FAILED),
            op("a", OperationState.DONE),
        )
        val counts = countRunOperations(ops, runFinished = true)
        assertEquals(1, counts.done)
        assertEquals(0, counts.failed)
        assertTrue(counts.failedItemIds.isEmpty())
    }

    @Test
    fun `undone items form their own bucket and are never failed`() {
        val ops = listOf(
            op("a", OperationState.UNDONE),
            op("a", OperationState.UNDONE, isCompanion = true),
            op("b", OperationState.DONE),
        )
        val counts = countRunOperations(ops, runFinished = true)
        assertEquals(1, counts.undone)
        assertEquals(1, counts.done)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `skipped items count as skipped`() {
        val counts = countRunOperations(
            listOf(op("a", OperationState.SKIPPED)),
            runFinished = true,
        )
        assertEquals(1, counts.skipped)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `non-final items are failed only once the run is finished`() {
        val ops = listOf(op("a", OperationState.MOVING))
        val running = countRunOperations(ops, runFinished = false)
        assertEquals(1, running.inProgress)
        assertEquals(0, running.failed)
        val finished = countRunOperations(ops, runFinished = true)
        assertEquals(1, finished.failed)
        assertEquals(setOf("a"), finished.failedItemIds)
    }

    // ------------------------------------------------------------ retry

    @Test
    fun `retry excludes undone items but keeps failed and unfinished ones`() {
        val ops = listOf(
            op("undone", OperationState.UNDONE),
            op("failed", OperationState.FAILED),
            op("stuck", OperationState.COPYING),
            op("done", OperationState.DONE),
            op("skipped", OperationState.SKIPPED),
            // A partially undone item (video restored, companion left DONE)
            // is excluded too: its main operation is UNDONE.
            op("partial", OperationState.UNDONE),
            op("partial", OperationState.DONE, isCompanion = true),
        )
        assertEquals(setOf("failed", "stuck"), retryableItemIds(ops))
    }

    // ------------------------------------------------------------ scan depth

    @Test
    fun `effective depth is the per-source value capped by the settings value`() {
        assertEquals(5, ScanRules.effectiveDepth(sourceDepth = null, settingsDepth = 5))
        assertEquals(3, ScanRules.effectiveDepth(sourceDepth = 3, settingsDepth = 5))
        assertEquals(5, ScanRules.effectiveDepth(sourceDepth = 8, settingsDepth = 5))
        assertEquals(2, ScanRules.effectiveDepth(sourceDepth = 2, settingsDepth = 2))
        assertEquals(1, ScanRules.effectiveDepth(sourceDepth = 0, settingsDepth = 5))
    }

    // ------------------------------------------------------------ free space

    private fun plannedItem(
        id: String,
        size: Long,
        selected: Boolean = true,
        status: PlannedStatus = PlannedStatus.READY,
        crossVolume: Boolean = false,
    ): PlannedItem {
        val file = FileRef(
            id = "file-$id",
            name = "Movie.2020.mkv",
            size = size,
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
            targetFileName = "Movie.2020.mkv",
            status = status,
            selected = selected,
            crossVolume = crossVolume,
        )
    }

    @Test
    fun `free space shortfall is the missing bytes and only for selected ready items`() {
        val planner = OrganizePlanner()
        val items = listOf(
            plannedItem("a", size = 700, crossVolume = true),
            plannedItem("b", size = 500, crossVolume = true),
            plannedItem("c", size = 10_000, selected = false, crossVolume = true),
            plannedItem("d", size = 10_000, status = PlannedStatus.NEEDS_REVIEW, crossVolume = true),
        )
        assertNull(planner.freeSpaceShortfall(items, DestinationSnapshot(freeBytes = null)))
        assertNull(planner.freeSpaceShortfall(items, DestinationSnapshot(freeBytes = 1_200)))
        assertEquals(
            200L,
            planner.freeSpaceShortfall(items, DestinationSnapshot(freeBytes = 1_000)),
        )
    }

    @Test
    fun `same-volume items never create a free space shortfall`() {
        // The full-disk, single-volume library: 86 GB of movies moving
        // inside one storage consume no new destination space (provider
        // reparents), so even a nearly-full destination reports no
        // shortfall — the 2026-11-05 owner report.
        val planner = OrganizePlanner()
        val items = listOf(
            plannedItem("a", size = 50_000_000_000, crossVolume = false),
            plannedItem("b", size = 36_000_000_000, crossVolume = false),
        )
        assertNull(planner.freeSpaceShortfall(items, DestinationSnapshot(freeBytes = 1_000)))
        // Mixed plans charge only the cross-volume bytes.
        val mixed = items + plannedItem("c", size = 3_000, crossVolume = true)
        assertEquals(
            2_000L,
            planner.freeSpaceShortfall(mixed, DestinationSnapshot(freeBytes = 1_000)),
        )
    }

    // ------------------------------------------------------------ executor

    @Test
    fun `executor journals skipped items and stamps the run with destination and options`() =
        runTest {
            val gateway = InMemoryStorageGateway()
            gateway.mkdirs("primary:/Library")
            val destRoot = DirRef("primary:/Library", "Library")
            val journal = InMemoryJournalStore()
            val skippedItem = plannedItem("skip-1", size = 123)
                .copy(selected = false, status = PlannedStatus.SKIPPED)

            val report = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
                .execute(
                    runId = "run-1",
                    items = listOf(skippedItem),
                    optionsSnapshot = "movieAddGenres: true",
                    destinationId = "primary:/Library",
                    destinationName = "Library",
                )

            assertEquals(1, report.skipped)
            val ops = journal.operationsOf("run-1")
            assertEquals(1, ops.size)
            assertEquals(OperationState.SKIPPED, ops.single().state)
            assertEquals("skip-1", ops.single().itemId)
            val run = journal.run("run-1")!!
            assertEquals("primary:/Library", run.destinationId)
            assertEquals("Library", run.destinationName)
            assertEquals("movieAddGenres: true", run.optionsSnapshot)
            // And the shared counter sees the skip in the journal.
            assertEquals(
                1,
                countRunOperations(ops, runFinished = true).skipped,
            )
        }

    // ------------------------------------------------------------ interrupted runs

    @Test
    fun `closing an interrupted run fails its open operations and finishes the run`() = runTest {
        val journal = InMemoryJournalStore()
        journal.createRun(RunRecord(id = "run-1", startedAt = 1L, totalItems = 2))
        journal.insertOperation(op("a", OperationState.DONE))
        journal.insertOperation(op("b", OperationState.COPYING))

        JournalMaintenance.closeInterruptedRun(journal, "run-1", finishedAt = 99L)

        val ops = journal.operationsOf("run-1")
        assertEquals(
            OperationState.DONE,
            ops.first { it.itemId == "a" }.state,
        )
        val closed = ops.first { it.itemId == "b" }
        assertEquals(OperationState.FAILED, closed.state)
        assertEquals("interrupted", closed.failureReason)
        assertEquals(99L, journal.run("run-1")!!.finishedAt)
        assertTrue(journal.unfinishedRuns().isEmpty())
    }
}
