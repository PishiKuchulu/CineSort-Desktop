package com.cinesort.desktop.data.db

import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunOperationSummary
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.domain.journal.bestStateOf

/** Room-backed [JournalStore] — ported verbatim from the Android app. */
class RoomJournalStore(
    private val runDao: RunDao,
) : JournalStore {

    override suspend fun createRun(run: RunRecord) {
        runDao.insertRun(run.toEntity())
    }

    override suspend fun insertOperation(op: OperationRecord) {
        runDao.insertOperation(op.toEntity())
    }

    override suspend fun updateOperation(op: OperationRecord) {
        runDao.updateOperation(op.toEntity())
    }

    override suspend fun operationsOf(runId: String): List<OperationRecord> =
        runDao.operationsOf(runId).map { it.toRecord() }

    override suspend fun operationSummary(runId: String): RunOperationSummary {
        val bestByItem = runDao.itemStates(runId)
            .groupBy({ it.itemId }, { it.state.toOperationState() })
            .mapValues { (_, states) -> bestStateOf(states) ?: OperationState.PENDING }
        return RunOperationSummary(
            bestStateByItem = bestByItem,
            postersDone = runDao.countDonePosters(runId),
        )
    }

    override suspend fun run(id: String): RunRecord? = runDao.run(id)?.toRecord()

    override suspend fun recentRuns(limit: Int): List<RunRecord> =
        runDao.recentRuns(limit).map { it.toRecord() }

    override suspend fun markRunFinished(runId: String, finishedAt: Long) {
        runDao.markFinished(runId, finishedAt)
    }

    override suspend fun markRunUndone(runId: String) {
        runDao.markUndone(runId)
    }

    override suspend fun unfinishedRuns(): List<RunRecord> =
        runDao.unfinishedRuns().map { it.toRecord() }

    // ------------------------------------------------------------ mapping

    private fun String.toOperationState(): OperationState =
        runCatching { OperationState.valueOf(this) }.getOrDefault(OperationState.PENDING)

    private fun RunRecord.toEntity(): RunEntity = RunEntity(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        optionsSnapshot = optionsSnapshot,
        totalItems = totalItems,
        undone = undone,
        destinationId = destinationId,
        destinationName = destinationName,
    )

    private fun RunEntity.toRecord(): RunRecord = RunRecord(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        optionsSnapshot = optionsSnapshot,
        totalItems = totalItems,
        undone = undone,
        destinationId = destinationId,
        destinationName = destinationName,
    )

    private fun OperationRecord.toEntity(): OperationEntity = OperationEntity(
        id = id,
        runId = runId,
        itemId = itemId,
        sourceParentId = sourceParentId,
        sourceParentName = sourceParentName,
        sourceFileId = sourceFileId,
        fileName = fileName,
        finalName = finalName,
        destinationParentId = destinationParentId,
        destinationSegments = destinationSegments.joinToString("/"),
        state = state.name,
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        isCompanion = isCompanion,
        isPoster = isPoster,
        failureReason = failureReason,
        createdDirs = createdDirs.joinToString("\n"),
        crossVolume = crossVolume,
    )

    private fun OperationEntity.toRecord(): OperationRecord = OperationRecord(
        id = id,
        runId = runId,
        itemId = itemId,
        sourceParentId = sourceParentId,
        sourceParentName = sourceParentName,
        sourceFileId = sourceFileId,
        fileName = fileName,
        finalName = finalName,
        destinationParentId = destinationParentId,
        destinationSegments = if (destinationSegments.isEmpty()) {
            emptyList()
        } else {
            destinationSegments.split('/')
        },
        state = runCatching { OperationState.valueOf(state) }.getOrDefault(OperationState.PENDING),
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        isCompanion = isCompanion,
        isPoster = isPoster,
        failureReason = failureReason,
        createdDirs = if (createdDirs.isEmpty()) emptyList() else createdDirs.split('\n'),
        crossVolume = crossVolume,
    )
}
