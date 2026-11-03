package com.cinesort.desktop.data.files

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The desktop file layer: real paths via java.nio, replacing Android's
 * SAF gateway with the same semantics the organizer was verified with
 * on Android (SafStorageGateway):
 *
 * - Same-volume moves are renames.
 * - Cross-volume moves stream-copy to a `<name>.cinesort-part` partial
 *   in the destination directory while hashing the source, verify size
 *   and then re-read the partial and compare SHA-256 before the partial
 *   is renamed to its final name and the source is deleted. Any failure
 *   deletes the partial and leaves the source untouched.
 * - The store never overwrites an existing destination file; conflicts
 *   are the planner's job, not the store's.
 */
class DesktopFileStore {

    data class WalkEntry(
        val path: Path,
        val sizeBytes: Long,
        val isDirectory: Boolean,
    )

    data class WalkResult(
        val entries: List<WalkEntry>,
        /** Entries that could not be read (permissions, races); counted, never fatal. */
        val unreadableCount: Int,
    )

    /**
     * Walks [root] up to [maxDepth] levels below it (the root itself is
     * depth 0 and is not included in the result). Directory sizes are 0.
     */
    fun walk(root: Path, maxDepth: Int): WalkResult {
        require(maxDepth >= 1) { "maxDepth must be >= 1" }
        if (!Files.isDirectory(root)) {
            throw IOException("not a directory: $root")
        }
        val entries = ArrayList<WalkEntry>()
        var unreadable = 0
        Files.walk(root, maxDepth).use { stream ->
            stream.forEach { path ->
                if (path == root) return@forEach
                try {
                    val isDir = Files.isDirectory(path)
                    val size = if (isDir) 0L else Files.size(path)
                    entries += WalkEntry(path, size, isDir)
                } catch (e: IOException) {
                    unreadable++
                } catch (e: SecurityException) {
                    unreadable++
                }
            }
        }
        return WalkResult(entries, unreadable)
    }

    fun freeSpaceBytes(path: Path): Long {
        var current: Path? = path
        while (current != null && !Files.exists(current)) {
            current = current.parent
        }
        val anchor = current ?: path.root
        return Files.getFileStore(anchor).usableSpace
    }

    fun sameFileStore(a: Path, b: Path): Boolean =
        Files.getFileStore(a) == Files.getFileStore(b)

    fun createDirectories(dir: Path) {
        Files.createDirectories(dir)
    }

    /** Deletes a file; false when it did not exist. */
    fun delete(file: Path): Boolean = Files.deleteIfExists(file)

    /** Deletes a directory only when it is empty. */
    fun deleteEmptyDirectory(dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        return try {
            Files.delete(dir)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Moves [source] into [targetDir] as [targetName] and returns the
     * final path. Throws IOException on any failure with the source
     * left in place. [forceCopyPath] skips the rename fast path (tests
     * and callers that must exercise verification).
     */
    suspend fun moveFile(
        source: Path,
        targetDir: Path,
        targetName: String,
        forceCopyPath: Boolean = false,
        onProgress: (copiedBytes: Long) -> Unit = {},
    ): Path = withContext(Dispatchers.IO) {
        require(targetName.isNotBlank() && !targetName.contains('/') && !targetName.contains('\\')) {
            "invalid target name: $targetName"
        }
        if (!Files.isRegularFile(source)) {
            throw IOException("source is not a file: $source")
        }
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(targetName)
        if (Files.exists(target)) {
            throw IOException("destination already exists: $target")
        }
        if (!forceCopyPath && sameFileStore(source, targetDir)) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                onProgress(Files.size(target))
                return@withContext target
            } catch (e: AtomicMoveNotSupportedException) {
                // Fall through to the verified copy below.
            }
        }
        copyVerifiedThenDeleteSource(source, target, onProgress)
        target
    }

    /**
     * The cross-volume path: stream-copy to `<target>.cinesort-part`
     * hashing the source as it flows, verify the copied size, re-read
     * the partial and compare SHA-256, rename to [target], then delete
     * the source. On any failure the partial is deleted and the source
     * survives.
     */
    internal suspend fun copyVerifiedThenDeleteSource(
        source: Path,
        target: Path,
        onProgress: (copiedBytes: Long) -> Unit = {},
    ) {
        val sourceSize = Files.size(source)
        val partial = target.resolveSibling(target.fileName.toString() + PART_SUFFIX)
        Files.deleteIfExists(partial)
        var copied = 0L
        try {
            val sourceDigest = newSha256()
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sourceDigest.update(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                    output.flush()
                }
            }
            if (copied != sourceSize) {
                throw IOException(
                    "size mismatch for ${source.fileName}: copied $copied of $sourceSize",
                )
            }
            val writtenSize = Files.size(partial)
            if (writtenSize != sourceSize) {
                throw IOException(
                    "size mismatch for ${source.fileName}: wrote $writtenSize of $sourceSize",
                )
            }
            val copiedHash = sha256Of(partial)
            if (!copiedHash.contentEquals(sourceDigest.digest())) {
                throw IOException(
                    "content hash mismatch for ${source.fileName}: copied bytes are corrupt",
                )
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(partial, target)
            }
        } catch (e: CancellationException) {
            runCatching { Files.deleteIfExists(partial) }
            throw e
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(partial) }
            throw e
        }
        Files.delete(source)
    }

    private suspend fun sha256Of(path: Path): ByteArray {
        val digest = newSha256()
        Files.newInputStream(path).use { input ->
            digestStream(input, digest)
        }
        return digest.digest()
    }

    private suspend fun digestStream(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    private fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    companion object {
        const val PART_SUFFIX = ".cinesort-part"
        private const val BUFFER_SIZE = 256 * 1024
    }
}
