package com.cinesort.desktop.data.work

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Post-run poster phase, mirroring the Android worker's: for every
 * distinct target folder of the run whose items all finished DONE and
 * that has a matched poster, save `poster.jpg` into the folder (the
 * series root for episodes). Existing posters are kept; failures never
 * fail the run. Each written poster is journaled as an isPoster
 * operation so Undo deletes it.
 */
class DesktopPosterPhase(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
) {
    private data class PosterTarget(
        val segments: List<String>,
        val url: String,
        val item: PlannedItem,
        val createdDirs: List<String>,
    )

    suspend fun downloadPosters(
        runId: String,
        items: List<PlannedItem>,
        destinationRoot: DirRef,
    ) {
        val opsByItem = journal.operationsOf(runId).groupBy { it.itemId }
        val byFolder = LinkedHashMap<String, PosterTarget>()
        for (item in items) {
            val itemOps = opsByItem[item.id].orEmpty()
            if (itemOps.isEmpty() || itemOps.any { it.state != OperationState.DONE }) continue
            val url = item.metadata?.posterUrl ?: continue
            val folderSegments = when (item.type) {
                ItemType.EPISODE -> item.targetSegments.dropLast(1)
                else -> item.targetSegments
            }
            if (folderSegments.isEmpty()) continue
            val createdDirs = itemOps
                .firstOrNull { !it.isCompanion && !it.isPoster }
                ?.createdDirs
                .orEmpty()
            byFolder.putIfAbsent(
                folderSegments.joinToString("/"),
                PosterTarget(folderSegments, url, item, createdDirs),
            )
        }
        coroutineScope {
            val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
            byFolder.values.map { target ->
                async {
                    semaphore.withPermit {
                        downloadOne(runId, target, destinationRoot)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadOne(
        runId: String,
        target: PosterTarget,
        destinationRoot: DirRef,
    ) {
        try {
            var dir: DirRef? = destinationRoot
            for (segment in target.segments) {
                dir = dir?.let { gateway.findChildDir(it, segment) }
                if (dir == null) break
            }
            val folder = dir ?: return
            val exists = gateway.listChildren(folder).any { child ->
                !child.isDirectory && child.ref.name.equals(POSTER_FILE_NAME, ignoreCase = true)
            }
            if (exists) return
            val bytes = DesktopPosterDownloader.fetch(target.url) ?: return
            val created = gateway.createFile(folder, POSTER_FILE_NAME, "image/jpeg", bytes)
            journal.insertOperation(
                OperationRecord(
                    id = UUID.randomUUID().toString(),
                    runId = runId,
                    itemId = target.item.id,
                    sourceParentId = folder.id,
                    sourceParentName = folder.name,
                    sourceFileId = created.id,
                    fileName = POSTER_FILE_NAME,
                    finalName = POSTER_FILE_NAME,
                    destinationParentId = folder.id,
                    destinationSegments = target.segments,
                    state = OperationState.DONE,
                    bytesTotal = bytes.size.toLong(),
                    bytesDone = bytes.size.toLong(),
                    isPoster = true,
                    createdDirs = target.createdDirs,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort by design: a poster must never fail a run.
        }
    }

    private companion object {
        const val POSTER_FILE_NAME = "poster.jpg"
        const val MAX_PARALLEL_DOWNLOADS = 3
    }
}
