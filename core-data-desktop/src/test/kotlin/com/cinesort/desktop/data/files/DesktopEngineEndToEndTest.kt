package com.cinesort.desktop.data.files

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The whole organize engine on the real desktop gateway: scan a messy
 * folder, execute a hand-built plan with the domain executor, verify
 * the library on disk, then undo and verify the restore. This is the
 * desktop counterpart of the Android app's proven engine path — same
 * domain classes, real filesystem underneath.
 */
class DesktopEngineEndToEndTest {

    private val gateway = DesktopStorageGateway()

    @TempDir
    lateinit var tempDir: Path

    private fun write(dir: Path, name: String, bytes: ByteArray): Path {
        Files.createDirectories(dir)
        val path = dir.resolve(name)
        Files.write(path, bytes)
        return path
    }

    private fun bytes(seed: Int, size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 17 + seed) % 241).toByte() }

    @Test
    fun scanReportsAncestorsAndSiblingsLikeThePlannerExpects() = runBlocking {
        val source = tempDir.resolve("downloads")
        write(source, "Inception.2010.1080p.mkv", bytes(1, 100))
        write(source, "Inception.2010.1080p.srt", bytes(2, 40))
        write(source.resolve("Season 1"), "Show.S01E02.720p.mkv", bytes(3, 200))

        val root = DirRef(source.toString(), "downloads")
        val found = gateway.scan(root, recursive = true, maxDepth = 3, exclude = emptySet())
            .toList().filterIsInstance<ScanEvent.Found>().map { it.item }

        assertEquals(3, found.size)
        val episode = found.single { it.file.name.startsWith("Show") }
        assertEquals(listOf("Season 1"), episode.ancestors)
        val movie = found.single { it.file.name.endsWith(".mkv") && it.file.name.startsWith("Inception") }
        assertEquals(listOf("Inception.2010.1080p.srt"), movie.siblings.map { it.name })
        assertTrue(movie.ancestors.isEmpty())
    }

    @Test
    fun executeThenUndoRestoresTheOriginalTree() = runBlocking {
        val source = tempDir.resolve("downloads")
        val dest = tempDir.resolve("library")
        Files.createDirectories(dest)
        val movieBytes = bytes(7, 500_000)
        val subBytes = bytes(8, 300)
        val episodeBytes = bytes(9, 400_000)
        write(source, "Inception.2010.1080p.mkv", movieBytes)
        write(source, "Inception.2010.1080p.srt", subBytes)
        write(source, "Show.S01E02.720p.mkv", episodeBytes)

        val root = DirRef(source.toString(), "downloads")
        val found = gateway.scan(root, recursive = true, maxDepth = 2, exclude = emptySet())
            .toList().filterIsInstance<ScanEvent.Found>().map { it.item }
            .associateBy { it.file.name }
        val movieScan = found.getValue("Inception.2010.1080p.mkv")
        val episodeScan = found.getValue("Show.S01E02.720p.mkv")
        val subtitle = movieScan.siblings.single { it.name.endsWith(".srt") }

        val items = listOf(
            PlannedItem(
                id = "movie-1",
                scanned = movieScan,
                companions = listOf(subtitle),
                effective = null,
                type = ItemType.MOVIE,
                targetSegments = listOf("Movie", "Inception (2010)"),
                targetFileName = movieScan.file.name,
                status = PlannedStatus.READY,
                selected = true,
            ),
            PlannedItem(
                id = "ep-1",
                scanned = episodeScan,
                companions = emptyList(),
                effective = null,
                type = ItemType.EPISODE,
                targetSegments = listOf("Tv series", "Show", "S01"),
                targetFileName = episodeScan.file.name,
                status = PlannedStatus.READY,
                selected = true,
            ),
        )

        val journal = InMemoryJournalStore()
        val destRoot = DirRef(dest.toString(), "library")
        val report = OrganizeExecutor(gateway, journal, destRoot)
            .execute("run-1", items, destinationId = destRoot.id, destinationName = "library")

        assertEquals(2, report.done)
        assertTrue(report.failed.isEmpty(), "failures: ${report.failed}")
        val movieTarget = dest.resolve("Movie/Inception (2010)/Inception.2010.1080p.mkv")
        val subTarget = dest.resolve("Movie/Inception (2010)/Inception.2010.1080p.srt")
        val epTarget = dest.resolve("Tv series/Show/S01/Show.S01E02.720p.mkv")
        assertArrayEquals(movieBytes, Files.readAllBytes(movieTarget))
        assertArrayEquals(subBytes, Files.readAllBytes(subTarget))
        assertArrayEquals(episodeBytes, Files.readAllBytes(epTarget))
        assertFalse(Files.exists(source.resolve("Inception.2010.1080p.mkv")))
        val ops = journal.operationsOf("run-1")
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.state == OperationState.DONE })

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(3, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), "undo problems: ${undo.problems}")
        assertArrayEquals(movieBytes, Files.readAllBytes(source.resolve("Inception.2010.1080p.mkv")))
        assertArrayEquals(subBytes, Files.readAllBytes(source.resolve("Inception.2010.1080p.srt")))
        assertArrayEquals(episodeBytes, Files.readAllBytes(source.resolve("Show.S01E02.720p.mkv")))
        assertFalse(Files.exists(dest.resolve("Movie")))
        assertFalse(Files.exists(dest.resolve("Tv series")))
    }

    @Test
    fun scanExcludesTheDestinationWhenItSitsInsideTheSource() = runBlocking {
        val source = tempDir.resolve("all")
        val destInside = source.resolve("library")
        write(source, "Movie.2020.mkv", bytes(4, 10))
        write(destInside, "Already.Sorted.2019.mkv", bytes(5, 10))

        val root = DirRef(source.toString(), "all")
        val destRef = DirRef(destInside.toString(), "library")
        val found: List<ScannedFile> = gateway.scan(root, true, 3, setOf(destRef))
            .toList().filterIsInstance<ScanEvent.Found>().map { it.item }
        assertEquals(listOf("Movie.2020.mkv"), found.map { it.file.name })
    }
}
