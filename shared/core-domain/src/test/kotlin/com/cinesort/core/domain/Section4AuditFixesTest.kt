package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the section-4 (security/integrity) audit fix: a
 * cross-volume copy used to be "verified" by size alone, and the executor
 * deleted the source immediately after — a same-size corrupted copy
 * (faulty SD card, broken provider) silently destroyed the only good
 * file. Copies now verify the SHA-256 content hash before the source
 * may be deleted.
 */
class Section4AuditFixesTest {

    private val sourcePath = "primary:/Downloads/Movie.2020.mkv"
    private val destRootPath = "sd:/Sorted"
    private val destFilePath = "sd:/Sorted/Movie/Movie (2020)/Movie.2020.mkv"

    private fun gatewayWithSource(): InMemoryStorageGateway {
        val gateway = InMemoryStorageGateway()
        gateway.addFile(sourcePath, size = 4096, lastModified = 7L)
        gateway.mkdirs(destRootPath)
        return gateway
    }

    private fun crossVolumeItem(): PlannedItem {
        val file = FileRef(
            id = sourcePath,
            name = "Movie.2020.mkv",
            size = 4096,
            lastModified = 7L,
            parentId = "primary:/Downloads",
        )
        return PlannedItem(
            id = "item-1",
            scanned = ScannedFile(
                file = file,
                parentDir = DirRef("primary:/Downloads", "Downloads"),
                ancestors = emptyList(),
                siblings = emptyList(),
                sourceVolume = "primary",
            ),
            companions = emptyList(),
            effective = null,
            type = ItemType.MOVIE,
            targetSegments = listOf("Movie", "Movie (2020)"),
            targetFileName = "Movie.2020.mkv",
            status = PlannedStatus.READY,
            selected = true,
            crossVolume = true,
        )
    }

    @Test
    fun `verified cross-volume copy deletes the source only after the hash matches`() = runTest {
        val gateway = gatewayWithSource()
        val sourceHash = gateway.contentHashOf(sourcePath)
        val journal = InMemoryJournalStore()
        val destRoot = DirRef(destRootPath, "Sorted")

        val report = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
            .execute("run-1", listOf(crossVolumeItem()))

        assertEquals(1, report.done)
        assertTrue(report.failed.isEmpty())
        // The copy landed with the source's content identity…
        assertTrue(gateway.exists(destFilePath))
        assertEquals(sourceHash, gateway.contentHashOf(destFilePath))
        // …and only then was the source deleted.
        assertFalse(gateway.exists(sourcePath))
        val ops = journal.operationsOf("run-1")
        assertEquals(1, ops.size)
        assertEquals(OperationState.DONE, ops.single().state)
    }

    @Test
    fun `same-size corrupted copy fails the item and keeps the source`() = runTest {
        val gateway = gatewayWithSource()
        gateway.corruptCopyContent = true
        val journal = InMemoryJournalStore()
        val destRoot = DirRef(destRootPath, "Sorted")

        val report = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
            .execute("run-1", listOf(crossVolumeItem()))

        assertEquals(0, report.done)
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.single().second.contains("content hash mismatch"))
        // The source is untouched and no corrupt copy survives anywhere:
        // the partial destination was rolled back by the gateway.
        assertTrue(gateway.exists(sourcePath))
        assertFalse(gateway.exists(destFilePath))
        assertTrue(gateway.listNames(destFilePath.substringBeforeLast('/')).isEmpty())
        val ops = journal.operationsOf("run-1")
        assertEquals(1, ops.size)
        assertEquals(OperationState.FAILED, ops.single().state)
        assertTrue(ops.single().failureReason.orEmpty().contains("content hash mismatch"))
    }

    @Test
    fun `copyVerified itself rejects a corrupted copy and leaves nothing behind`() = runTest {
        val gateway = gatewayWithSource()
        gateway.corruptCopyContent = true
        val destDir = gateway.mkdirs("sd:/Sorted/Target")
        val source = FileRef(sourcePath, "Movie.2020.mkv", 4096, 7L, "primary:/Downloads")

        val error = runCatching { gateway.copyVerified(source, destDir) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("content hash mismatch"))
        assertTrue(gateway.listNames("sd:/Sorted/Target").isEmpty())
        assertTrue(gateway.exists(sourcePath))
    }
}
