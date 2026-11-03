package com.cinesort.desktop.data.files

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.EntryInfo
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.Ref
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The desktop [StorageGateway]: real filesystem paths instead of SAF
 * document URIs. A ref's id is the absolute normalized path; names are
 * the last path segment. Scan mirrors the in-memory fake's semantics
 * exactly (breadth-first collection, then Found events carrying the
 * directory's siblings and the file's ancestors nearest-first), so the
 * domain planner/executor behave identically to the Android build.
 *
 * All verified-copy discipline lives in [DesktopFileStore]; this class
 * only translates between paths and domain refs.
 */
class DesktopStorageGateway(
    private val store: DesktopFileStore = DesktopFileStore(),
) : StorageGateway {

    override fun scan(
        root: DirRef,
        recursive: Boolean,
        maxDepth: Int,
        exclude: Set<DirRef>,
    ): Flow<ScanEvent> = flow {
        val rootPath = pathOf(root)
        if (!Files.isDirectory(rootPath)) return@flow
        val excludedIds = exclude.map { it.id }.toSet()
        var scanned = 0
        val filesByDir = linkedMapOf<Path, MutableList<Path>>()
        val dirOrder = mutableListOf<Path>()
        val queue = ArrayDeque<Pair<Path, Int>>()
        queue.add(rootPath to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (dir !in filesByDir) {
                filesByDir[dir] = mutableListOf()
                dirOrder.add(dir)
            }
            val children = try {
                Files.list(dir).use { stream -> stream.toList() }
            } catch (e: IOException) {
                emit(ScanEvent.Failed(dir.fileName?.toString().orEmpty(), e.message ?: "unreadable"))
                continue
            } catch (e: SecurityException) {
                emit(ScanEvent.Failed(dir.fileName?.toString().orEmpty(), "access denied"))
                continue
            }
            for (child in children.sortedBy { it.fileName.toString() }) {
                scanned++
                if (Files.isDirectory(child)) {
                    if (child.toString() in excludedIds) continue
                    if (recursive && depth + 1 <= maxDepth) {
                        queue.add(child to depth + 1)
                    }
                } else if (Files.isRegularFile(child)) {
                    filesByDir.getValue(dir).add(child)
                }
            }
            emit(ScanEvent.Progress(scanned))
        }
        val volume = volumeIdOf(root)
        for (dirPath in dirOrder) {
            val filePaths = filesByDir.getValue(dirPath)
            if (filePaths.isEmpty()) continue
            val siblings = filePaths.map { fileRef(it) }
            val ancestors = ancestorsOf(rootPath, dirPath)
            val parentRef = dirRef(dirPath)
            for (filePath in filePaths) {
                val file = fileRef(filePath)
                emit(
                    ScanEvent.Found(
                        ScannedFile(
                            file = file,
                            parentDir = parentRef,
                            ancestors = ancestors,
                            siblings = siblings.filter { it.id != file.id },
                            sourceVolume = volume,
                        ),
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listChildren(dir: DirRef): List<EntryInfo> = withContext(Dispatchers.IO) {
        val path = pathOf(dir)
        if (!Files.isDirectory(path)) return@withContext emptyList()
        Files.list(path).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }.map { child ->
                val isDir = Files.isDirectory(child)
                EntryInfo(
                    ref = if (isDir) dirRef(child) else fileRef(child),
                    isDirectory = isDir,
                    size = if (isDir) 0L else sizeOrZero(child),
                    lastModified = lastModifiedOrZero(child),
                    mimeType = null,
                )
            }
        }
    }

    override suspend fun findChildDir(
        parent: DirRef,
        name: String,
        ignoreCase: Boolean,
    ): DirRef? = withContext(Dispatchers.IO) {
        val path = pathOf(parent)
        if (!Files.isDirectory(path)) return@withContext null
        Files.list(path).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter {
                    val childName = it.fileName.toString()
                    if (ignoreCase) childName.equals(name, ignoreCase = true) else childName == name
                }
                .findFirst()
                .map { dirRef(it) }
                .orElse(null)
        }
    }

    override suspend fun createDir(parent: DirRef, name: String): DirRef = withContext(Dispatchers.IO) {
        val target = pathOf(parent).resolve(name)
        if (Files.isRegularFile(target)) {
            throw IOException("a file with that name exists: $target")
        }
        Files.createDirectories(target)
        dirRef(target)
    }

    override suspend fun move(
        file: FileRef,
        fromDir: DirRef,
        toDir: DirRef,
    ): FileRef {
        val source = pathOf(file)
        val targetDir = pathOf(toDir)
        if (!store.sameFileStore(source, targetDir)) {
            // The executor routes cross-volume transfers through
            // copyVerified + delete; a raw move must never silently
            // become an unverified copy.
            throw IOException("cross-volume move requires the verified copy path: $source")
        }
        val moved = store.moveFile(source, targetDir, file.name)
        return fileRef(moved)
    }

    override suspend fun copyVerified(
        file: FileRef,
        toDir: DirRef,
        targetName: String,
        onProgress: (Long) -> Unit,
    ): FileRef {
        val source = pathOf(file)
        val targetDir = pathOf(toDir)
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(targetName)
        store.copyVerified(source, target, onProgress)
        return fileRef(target)
    }

    override suspend fun createFile(
        dir: DirRef,
        name: String,
        mimeType: String,
        content: ByteArray,
    ): FileRef = withContext(Dispatchers.IO) {
        val target = pathOf(dir).resolve(name)
        Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        fileRef(target)
    }

    override suspend fun delete(ref: Ref): Unit = withContext(Dispatchers.IO) {
        // Mirror of the Android guard: never delete a filesystem root.
        val path = pathOf(ref)
        require(path.parent != null) { "refusing to delete a root: $path" }
        Files.deleteIfExists(path)
        Unit
    }

    override suspend fun volumeIdOf(ref: Ref): String = withContext(Dispatchers.IO) {
        Files.getFileStore(pathOf(ref)).toString()
    }

    override suspend fun freeBytes(dir: DirRef): Long = withContext(Dispatchers.IO) {
        store.freeSpaceBytes(pathOf(dir))
    }

    override suspend fun isValid(dir: DirRef): Boolean = withContext(Dispatchers.IO) {
        Files.isDirectory(pathOf(dir))
    }

    override suspend fun recreateDir(dir: DirRef): DirRef? = withContext(Dispatchers.IO) {
        val path = pathOf(dir)
        val root = path.root ?: return@withContext null
        if (!Files.exists(root)) return@withContext null
        try {
            Files.createDirectories(path)
            dirRef(path)
        } catch (e: IOException) {
            null
        }
    }

    // ------------------------------------------------------------ helpers

    private fun pathOf(ref: Ref): Path = Path.of(ref.id)

    private fun dirRef(path: Path): DirRef =
        DirRef(path.toString(), path.fileName?.toString() ?: path.toString())

    private fun fileRef(path: Path): FileRef = FileRef(
        id = path.toString(),
        name = path.fileName.toString(),
        size = sizeOrZero(path),
        lastModified = lastModifiedOrZero(path),
        parentId = path.parent?.toString() ?: path.toString(),
    )

    private fun sizeOrZero(path: Path): Long = try {
        Files.size(path)
    } catch (e: IOException) {
        0L
    } catch (e: SecurityException) {
        0L
    }

    private fun lastModifiedOrZero(path: Path): Long = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (e: IOException) {
        0L
    } catch (e: SecurityException) {
        0L
    }

    /** Folder names between [root] (exclusive) and [dir] (inclusive), nearest first. */
    private fun ancestorsOf(root: Path, dir: Path): List<String> {
        if (dir == root) return emptyList()
        val relative = try {
            root.relativize(dir)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return (0 until relative.nameCount)
            .map { relative.getName(it).toString() }
            .reversed()
    }
}
