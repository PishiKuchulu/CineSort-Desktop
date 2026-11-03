package com.cinesort.desktop.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Journal persistence for the desktop build. The entity shapes are the
 * Android app's v5 journal tables, field for field (the desktop
 * database versions independently — see [CineSortDesktopDatabase]).
 */

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val optionsSnapshot: String = "",
    val totalItems: Int = 0,
    val undone: Boolean = false,
    /** Destination tree the run wrote into (audit B.8). */
    val destinationId: String = "",
    val destinationName: String = "",
)

/** One journaled file operation inside a run (spec sections 8.3, 8.5). */
@Entity(
    tableName = "operations",
    indices = [Index(value = ["runId"])],
)
data class OperationEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val itemId: String,
    val sourceParentId: String,
    val sourceParentName: String,
    /** The file's own full path on desktop (the SAF document URI on Android). */
    val sourceFileId: String = "",
    val fileName: String,
    val finalName: String,
    val destinationParentId: String,
    /** Destination segments relative to the destination root, joined with '/'. */
    val destinationSegments: String,
    val state: String,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val isCompanion: Boolean = false,
    /** Downloaded poster inside the destination folder (review M-A). */
    val isPoster: Boolean = false,
    val failureReason: String? = null,
    /** Directories created by this run for this operation, joined with '\n'. */
    val createdDirs: String = "",
    val crossVolume: Boolean = false,
)
