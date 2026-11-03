package com.cinesort.core.domain.model

/** Opaque reference to a storage node. [id] is provider-specific (a tree document id for SAF). */
sealed interface Ref {
    val id: String
    val name: String
}

data class DirRef(
    override val id: String,
    override val name: String,
) : Ref

data class FileRef(
    override val id: String,
    override val name: String,
    val size: Long,
    val lastModified: Long,
    val parentId: String,
) : Ref

data class EntryInfo(
    val ref: Ref,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
)

/** One scanned video file with everything the planner needs. */
data class ScannedFile(
    val file: FileRef,
    val parentDir: DirRef,
    /** Ancestor folder names between the file and its source root, nearest first. */
    val ancestors: List<String>,
    /** All other files in the same directory (companion candidates). */
    val siblings: List<FileRef>,
    val sourceVolume: String,
    /** Path segments relative to the destination root when the file already sits inside it. */
    val locationInDestination: List<String>? = null,
)

sealed interface ScanEvent {
    data class Progress(val scannedEntries: Int) : ScanEvent
    data class Found(val item: ScannedFile) : ScanEvent
    data class Skipped(val name: String, val reason: String) : ScanEvent
    data class Failed(val name: String, val reason: String) : ScanEvent
}
