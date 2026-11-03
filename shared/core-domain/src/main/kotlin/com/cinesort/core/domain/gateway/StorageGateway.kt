package com.cinesort.core.domain.gateway

import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.EntryInfo
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.Ref
import com.cinesort.core.domain.model.ScanEvent
import kotlinx.coroutines.flow.Flow

/**
 * All file-system access goes through this interface (spec section 5.7).
 * The production implementation is SAF-based (core:data); tests use the in-memory fake.
 */
interface StorageGateway {
    fun scan(
        root: DirRef,
        recursive: Boolean,
        maxDepth: Int,
        exclude: Set<DirRef>,
    ): Flow<ScanEvent>

    suspend fun listChildren(dir: DirRef): List<EntryInfo>
    suspend fun findChildDir(
        parent: DirRef,
        name: String,
        ignoreCase: Boolean = true,
    ): DirRef?

    suspend fun createDir(
        parent: DirRef,
        name: String,
    ): DirRef

    /** Same-volume move when the provider supports it; otherwise verified copy + delete. */
    suspend fun move(
        file: FileRef,
        fromDir: DirRef,
        toDir: DirRef,
    ): FileRef

    /**
     * Streams the file into [toDir] under [targetName], verifying the byte
     * count AND the SHA-256 content hash: the destination bytes are re-read
     * and hashed before this returns, so a same-size corrupted copy throws
     * instead of letting the caller delete the only good source
     * (section-4 audit).
     */
    suspend fun copyVerified(
        file: FileRef,
        toDir: DirRef,
        targetName: String = file.name,
        onProgress: (Long) -> Unit = {},
    ): FileRef

    /** Creates a small file with [content] inside [dir] (poster downloads, spec addendum). */
    suspend fun createFile(dir: DirRef, name: String, mimeType: String, content: ByteArray): FileRef

    suspend fun delete(ref: Ref)
    suspend fun volumeIdOf(ref: Ref): String
    suspend fun freeBytes(dir: DirRef): Long?
    suspend fun isValid(dir: DirRef): Boolean

    /**
     * Recreates [dir] (and any missing ancestors inside its tree) when it was
     * deleted after being recorded — used by Undo when the post-run cleanup
     * removed an emptied source folder (review M6, spec 8.5). Returns the live
     * ref, or null when the directory cannot be recreated (e.g. its tree root
     * itself is gone).
     */
    suspend fun recreateDir(dir: DirRef): DirRef?
}
