package com.cinesort.desktop.data.db

import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The persistent journal on a real SQLite file: runs and operations
 * round-trip through Room, summaries count like the domain expects,
 * and unfinished runs survive for resume. Runs in CI (Room codegen is
 * a Gradle/KSP step, unavailable to the local kotlinc harness).
 */
class RoomJournalStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun op(id: String, runId: String, itemId: String, state: OperationState) =
        OperationRecord(
            id = id,
            runId = runId,
            itemId = itemId,
            sourceParentId = "/downloads",
            sourceParentName = "downloads",
            sourceFileId = "/downloads/Movie.2024.mkv",
            fileName = "Movie.2024.mkv",
            finalName = "Movie.2024.mkv",
            destinationParentId = "/library",
            destinationSegments = listOf("Movie", "Movie (2024)"),
            state = state,
            bytesTotal = 100,
            bytesDone = if (state == OperationState.DONE) 100 else 0,
            createdDirs = listOf("Movie", "Movie/Movie (2024)"),
        )

    @Test
    fun journalRoundTripsThroughSqlite() = runBlocking {
        val db = createDesktopDatabase(tempDir.resolve("cinesort.db"))
        try {
            val store = RoomJournalStore(db.runDao())
            store.createRun(
                RunRecord(
                    id = "run-1",
                    startedAt = 1_000L,
                    totalItems = 2,
                    destinationId = "/library",
                    destinationName = "library",
                ),
            )
            store.insertOperation(op("op-1", "run-1", "item-1", OperationState.MOVING))
            store.updateOperation(op("op-1", "run-1", "item-1", OperationState.DONE))
            store.insertOperation(op("op-2", "run-1", "item-2", OperationState.FAILED))

            val ops = store.operationsOf("run-1")
            assertEquals(2, ops.size)
            val done = ops.single { it.id == "op-1" }
            assertEquals(OperationState.DONE, done.state)
            assertEquals(listOf("Movie", "Movie (2024)"), done.destinationSegments)
            assertEquals(listOf("Movie", "Movie/Movie (2024)"), done.createdDirs)
            assertEquals(100L, done.bytesDone)

            val summary = store.operationSummary("run-1")
            assertEquals(OperationState.DONE, summary.bestStateByItem["item-1"])
            assertEquals(OperationState.FAILED, summary.bestStateByItem["item-2"])
            assertEquals(0, summary.postersDone)

            assertEquals(listOf("run-1"), store.unfinishedRuns().map { it.id })
            store.markRunFinished("run-1", 2_000L)
            assertTrue(store.unfinishedRuns().isEmpty())
            assertEquals(2_000L, store.run("run-1")?.finishedAt)
            assertEquals("/library", store.run("run-1")?.destinationId)

            store.markRunUndone("run-1")
            assertEquals(true, store.run("run-1")?.undone)
            assertEquals(listOf("run-1"), store.recentRuns(5).map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun journalSurvivesReopen() = runBlocking {
        val file = tempDir.resolve("reopen.db")
        val db1 = createDesktopDatabase(file)
        RoomJournalStore(db1.runDao()).createRun(RunRecord(id = "run-x", startedAt = 5L))
        db1.close()

        val db2 = createDesktopDatabase(file)
        try {
            val store = RoomJournalStore(db2.runDao())
            assertEquals("run-x", store.run("run-x")?.id)
            assertEquals(listOf("run-x"), store.unfinishedRuns().map { it.id })
        } finally {
            db2.close()
        }
    }
}
