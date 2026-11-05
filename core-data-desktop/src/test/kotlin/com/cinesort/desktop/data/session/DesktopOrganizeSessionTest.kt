package com.cinesort.desktop.data.session

import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.desktop.data.files.DesktopStorageGateway
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The full pipeline through the session layer: scan → classify →
 * parse → plan → execute → undo, on the real gateway, with the
 * planner's own output driving execution (no hand-built plan).
 */
class DesktopOrganizeSessionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeAged(dir: Path, name: String, size: Int): Path {
        Files.createDirectories(dir)
        val path = dir.resolve(name)
        Files.write(path, ByteArray(size) { (it % 251).toByte() })
        // Older than the still-downloading age rule, so it plans READY.
        Files.setLastModifiedTime(path, FileTime.fromMillis(1_000_000L))
        return path
    }

    @Test
    fun pipelinePlansExecutesAndUndoes() = runBlocking {
        val source = tempDir.resolve("downloads")
        val library = tempDir.resolve("library")
        Files.createDirectories(library)
        writeAged(source, "Inception.2010.1080p.BluRay.mkv", 2048)
        writeAged(source, "Inception.2010.1080p.BluRay.en.srt", 128)
        writeAged(source, "notes.txt", 16)

        val journal = InMemoryJournalStore()
        val session = DesktopOrganizeSession(DesktopStorageGateway(), journal)

        val plan = session.scanAndPlan(
            sources = listOf(source),
            destination = library,
            now = 2_000_000L,
        )
        assertEquals(1, plan.videosFound)
        assertEquals(1, plan.items.size)
        val item = plan.items.single()
        assertEquals(PlannedStatus.READY, item.status)
        assertEquals("Movie", item.targetSegments.first())
        assertTrue(item.targetFolderPath.contains("Inception"), item.targetFolderPath)
        assertEquals(1, item.companions.size)

        val report = session.execute(plan.items, library, runId = "run-session")
        assertEquals(1, report.done)
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertTrue(Files.exists(library.resolve(item.targetPath)))
        assertFalse(Files.exists(source.resolve("Inception.2010.1080p.BluRay.mkv")))
        assertEquals("library", journal.run("run-session")?.destinationName)

        val undo = session.undo("run-session")
        assertEquals(2, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(Files.exists(source.resolve("Inception.2010.1080p.BluRay.mkv")))
        assertTrue(Files.exists(source.resolve("Inception.2010.1080p.BluRay.en.srt")))
    }

    @Test
    fun replanAppliesManualOverrideWithoutRescan() = runBlocking {
        val source = tempDir.resolve("downloads")
        val library = tempDir.resolve("library")
        Files.createDirectories(library)
        writeAged(source, "Inception.2010.1080p.BluRay.mkv", 2048)

        val session = DesktopOrganizeSession(
            DesktopStorageGateway(),
            InMemoryJournalStore(),
        )
        val plan = session.scanAndPlan(
            sources = listOf(source),
            destination = library,
            now = 2_000_000L,
        )
        val item = plan.items.single()
        val original = item.effective as com.cinesort.core.parser.ParseResult.Movie
        assertEquals("Inception", original.title)
        assertTrue(plan.destinationFreeBytes != null)

        // The user corrects the title in the Inspector: the re-plan
        // must target the corrected folder without a new scan.
        val corrected = original.copy(title = "Interstellar", year = 2014)
        val replanned = session.replan(
            parseOverrides = mapOf(item.id to corrected),
        )
        assertTrue(replanned != null)
        val updated = replanned!!.items.single()
        assertTrue(
            updated.targetFolderPath.contains("Interstellar (2014)"),
            updated.targetFolderPath,
        )
        assertEquals(PlannedStatus.READY, updated.status)

        // No scan yet -> no re-plan.
        val fresh = DesktopOrganizeSession(
            DesktopStorageGateway(),
            InMemoryJournalStore(),
        )
        assertEquals(null, fresh.replan(emptyMap()))
    }

    @Test
    fun recentlyModifiedFilesAreNotPlannedReady() = runBlocking {
        val source = tempDir.resolve("downloads")
        val library = tempDir.resolve("library")
        Files.createDirectories(library)
        // Fresh file: still "downloading" under the default age rule.
        Files.createDirectories(source)
        Files.write(source.resolve("Fresh.2024.mkv"), ByteArray(64))

        val session = DesktopOrganizeSession(DesktopStorageGateway(), InMemoryJournalStore())
        val plan = session.scanAndPlan(listOf(source), library)
        assertEquals(1, plan.videosFound)
        assertTrue(plan.items.single().status != PlannedStatus.READY)
    }
}
