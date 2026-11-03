package com.cinesort.desktop.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Journal DAO — ported from the Android app's RunDao, queries unchanged. */
@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(entity: RunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(entity: OperationEntity)

    @Update
    suspend fun updateOperation(entity: OperationEntity)

    @Query("SELECT * FROM operations WHERE runId = :runId")
    suspend fun operationsOf(runId: String): List<OperationEntity>

    /**
     * Distinct (item, state) pairs of a run's MAIN operations (section-3
     * audit): the counting summary the Run screen polls, instead of a
     * full entity list per tick.
     */
    @Query(
        "SELECT DISTINCT itemId, state FROM operations WHERE runId = :runId " +
            "AND isCompanion = 0 AND isPoster = 0",
    )
    suspend fun itemStates(runId: String): List<ItemStateRow>

    /** DONE poster operations of a run (tallied apart from items). */
    @Query(
        "SELECT COUNT(*) FROM operations WHERE runId = :runId " +
            "AND isPoster = 1 AND state = 'DONE'",
    )
    suspend fun countDonePosters(runId: String): Int

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun run(id: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int): Flow<List<RunEntity>>

    @Query("UPDATE runs SET finishedAt = :finishedAt WHERE id = :runId")
    suspend fun markFinished(runId: String, finishedAt: Long)

    @Query("UPDATE runs SET undone = 1 WHERE id = :runId")
    suspend fun markUndone(runId: String)

    @Query("SELECT * FROM runs WHERE finishedAt IS NULL")
    suspend fun unfinishedRuns(): List<RunEntity>
}

/** Projection of [RunDao.itemStates]: one distinct state of one item. */
data class ItemStateRow(
    val itemId: String,
    val state: String,
)
