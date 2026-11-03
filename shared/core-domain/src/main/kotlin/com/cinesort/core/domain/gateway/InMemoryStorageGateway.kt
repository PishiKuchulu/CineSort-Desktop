package com.cinesort.core.domain.gateway

import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.EntryInfo
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.Ref
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory [StorageGateway] fake shipped for use-case and planner tests (spec section 4).
 * Models a tree of directories/files across named volumes; ids are slash paths prefixed
 * with the volume, e.g. `primary:/Downloads/a.mkv`.
 */
class InMemoryStorageGateway : StorageGateway {

    private class Node(
        val name: String,
        val isDirectory: Boolean,
        var size: Long = 0,
        var lastModified: Long = 0,
        val children: LinkedHashMap<String, Node> = linkedMapOf(),
        var parent: Node? = null,
        /** Identity of the file's bytes (section-4 audit): copies must preserve it. */
        var contentHash: String = "",
    )

    private val volumes = linkedMapOf<String, Node>()
    private val freeSpace = linkedMapOf<String, Long>()

    /**
     * Test hook (section-4 audit): when true, [copyVerified] "writes" bytes
     * whose hash differs from the source while the size stays identical —
     * the silent same-size corruption the verified copy exists to catch.
     */
    var corruptCopyContent: Boolean = false

    fun addVolume(volume: String) {
        volumes.getOrPut(volume) { Node("", true) }
        freeSpace.putIfAbsent(volume, Long.MAX_VALUE / 4)
    }

    fun setFreeBytes(volume: String, bytes: Long) {
        freeSpace[volume] = bytes
    }

    fun mkdirs(path: String): DirRef {
        val (volume, segments) = splitPath(path)
        addVolume(volume)
        var node = volumes.getValue(volume)
        for (segment in segments) {
            node = node.children.getOrPut(segment) { Node(segment, true, parent = node) }
                .also { it.parent = node }
        }
        return dirRef(path)
    }

    fun addFile(
        path: String,
        size: Long = 1024,
        lastModified: Long = 0,
    ): FileRef {
        val (volume, segments) = splitPath(path)
        require(segments.isNotEmpty()) { "file path needs a name" }
        val parentRef = mkdirs(joinPath(volume, segments.dropLast(1)))
        val (_, parentSegments) = splitPath(parentRef.id)
        var node = volumes.getValue(volume)
        for (segment in parentSegments) node = node.children.getValue(segment)
        val name = segments.last()
        node.children[name] = Node(
            name, false, size, lastModified, parent = node,
            // Synthetic but stable content identity for the fake's bytes.
            contentHash = ContentHash.sha256Hex(
                "fake-content:$path:$size:$lastModified".toByteArray(Charsets.UTF_8),
            ),
        )
        return FileRef(path, name, size, lastModified, parentRef.id)
    }

    fun exists(path: String): Boolean = resolve(path) != null

    fun fileSize(path: String): Long? = resolve(path)?.size

    /** The stored content hash of a file, or null when the path is unknown. */
    fun contentHashOf(path: String): String? = resolve(path)?.contentHash

    fun listNames(dirPath: String): List<String> =
        resolve(dirPath)?.children?.keys?.toList().orEmpty()

    // ------------------------------------------------------------- internals

    private fun splitPath(path: String): Pair<String, List<String>> {
        val volume = path.substringBefore(':')
        val rest = path.substringAfter(':', "")
        val segments = rest.split('/').filter { it.isNotEmpty() }
        return volume to segments
    }

    private fun joinPath(volume: String, segments: List<String>): String =
        volume + ":" + segments.joinToString("/", prefix = "/")

    private fun childPath(parentId: String, name: String): String = when {
        parentId.endsWith(':') -> "$parentId/$name"
        parentId.endsWith('/') -> parentId + name
        else -> "$parentId/$name"
    }

    private fun resolve(path: String): Node? {
        val (volume, segments) = splitPath(path)
        var node = volumes[volume] ?: return null
        for (segment in segments) {
            node = node.children[segment] ?: return null
        }
        return node
    }

    private fun dirRef(path: String): DirRef = DirRef(path, path.substringAfterLast('/'))

    private fun fileRef(path: String, node: Node): FileRef {
        val parentPath = path.substringBeforeLast('/', path.substringBefore(':') + ":")
        return FileRef(path, node.name, node.size, node.lastModified, parentPath)
    }

    private fun refFor(path: String, node: Node): Ref =
        if (node.isDirectory) dirRef(path) else fileRef(path, node)

    // ------------------------------------------------------------- gateway

    override fun scan(
        root: DirRef,
        recursive: Boolean,
        maxDepth: Int,
        exclude: Set<DirRef>,
    ): Flow<ScanEvent> = flow {
        val excludedIds = exclude.map { it.id }.toSet()
        var scanned = 0
        val queue = ArrayDeque<Triple<Node, String, Int>>()
        val rootNode = resolve(root.id) ?: return@flow
        queue.add(Triple(rootNode, root.id, 0))
        val filesByDir = linkedMapOf<String, MutableList<Pair<Node, String>>>()
        val dirOrder = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val (node, path, depth) = queue.removeFirst()
            if (path !in filesByDir) {
                filesByDir[path] = mutableListOf()
                dirOrder += path
            }
            for ((name, child) in node.children) {
                scanned++
                val childPath = childPath(path, name)
                if (child.isDirectory) {
                    if (childPath in excludedIds) continue
                    if (recursive && depth + 1 <= maxDepth) {
                        queue.add(Triple(child, childPath, depth + 1))
                    }
                } else {
                    filesByDir.getValue(path).add(child to childPath)
                }
            }
            emit(ScanEvent.Progress(scanned))
        }
        for (dirPath in dirOrder) {
            val entries = filesByDir.getValue(dirPath)
            val siblings = entries.map { (node, path) -> fileRef(path, node) }
            val (rootVolume, _) = splitPath(root.id)
            val ancestors = ancestorsOf(root.id, dirPath)
            for ((node, path) in entries) {
                val file = fileRef(path, node)
                emit(
                    ScanEvent.Found(
                        ScannedFile(
                            file = file,
                            parentDir = dirRef(dirPath),
                            ancestors = ancestors,
                            siblings = siblings.filter { it.id != file.id },
                            sourceVolume = rootVolume,
                        ),
                    ),
                )
            }
        }
    }

    private fun ancestorsOf(rootPath: String, dirPath: String): List<String> {
        val (_, rootSegments) = splitPath(rootPath)
        val (_, dirSegments) = splitPath(dirPath)
        if (dirSegments.size <= rootSegments.size) return emptyList()
        return dirSegments.drop(rootSegments.size).reversed()
    }

    override suspend fun listChildren(dir: DirRef): List<EntryInfo> {
        val node = resolve(dir.id) ?: return emptyList()
        return node.children.map { (name, child) ->
            val childPath = childPath(dir.id, name)
            EntryInfo(
                ref = refFor(childPath, child),
                isDirectory = child.isDirectory,
                size = child.size,
                lastModified = child.lastModified,
                mimeType = null,
            )
        }
    }

    override suspend fun findChildDir(
        parent: DirRef,
        name: String,
        ignoreCase: Boolean,
    ): DirRef? {
        val node = resolve(parent.id) ?: return null
        val match = node.children.entries.firstOrNull { (childName, child) ->
            child.isDirectory &&
                if (ignoreCase) childName.equals(name, ignoreCase = true) else childName == name
        } ?: return null
        val path = childPath(parent.id, match.key)
        return DirRef(path, match.key)
    }

    override suspend fun createDir(parent: DirRef, name: String): DirRef {
        val parentNode = resolve(parent.id) ?: error("parent not found: ${parent.id}")
        require(parentNode.children[name]?.isDirectory != false || parentNode.children[name] == null) {
            "a file with that name exists"
        }
        parentNode.children.getOrPut(name) { Node(name, true, parent = parentNode) }
        val path = childPath(parent.id, name)
        return DirRef(path, name)
    }

    override suspend fun createFile(dir: DirRef, name: String, mimeType: String, content: ByteArray): FileRef {
        val parentNode = resolve(dir.id) ?: error("parent not found: ${dir.id}")
        require(parentNode.isDirectory) { "not a directory: ${dir.id}" }
        parentNode.children[name] = Node(
            name, false, size = content.size.toLong(), parent = parentNode,
            contentHash = ContentHash.sha256Hex(content),
        )
        return FileRef(childPath(dir.id, name), name, content.size.toLong(), 0L, dir.id)
    }

    override suspend fun move(
        file: FileRef,
        fromDir: DirRef,
        toDir: DirRef,
    ): FileRef {
        // The fake models move as an atomic relink; cross-volume verified copy goes
        // through copyVerified + delete at the executor level, like the SAF gateway.
        val fromNode = resolve(fromDir.id) ?: error("source dir not found")
        val toNode = resolve(toDir.id) ?: error("destination dir not found")
        val node = fromNode.children.remove(file.name) ?: error("file not found: ${file.name}")
        require(toNode.children[file.name] == null) { "target already exists" }
        node.parent = toNode
        toNode.children[file.name] = node
        val newPath = childPath(toDir.id, file.name)
        return FileRef(newPath, file.name, node.size, node.lastModified, toDir.id)
    }

    override suspend fun copyVerified(
        file: FileRef,
        toDir: DirRef,
        targetName: String,
        onProgress: (Long) -> Unit,
    ): FileRef {
        val fromNode = resolve(file.id) ?: error("file not found: ${file.id}")
        val toNode = resolve(toDir.id) ?: error("destination dir not found")
        val (destVolume, _) = splitPath(toDir.id)
        val free = freeSpace[destVolume] ?: Long.MAX_VALUE
        require(free >= fromNode.size) { "insufficient space" }
        require(toNode.children[targetName] == null) { "target already exists" }
        onProgress(fromNode.size)
        // The "written" bytes: identical to the source normally; the
        // corruptCopyContent hook swaps in same-size garbage instead.
        val writtenHash = if (corruptCopyContent) {
            ContentHash.sha256Hex("corrupted:${fromNode.contentHash}".toByteArray(Charsets.UTF_8))
        } else {
            fromNode.contentHash
        }
        toNode.children[targetName] = Node(
            targetName, false, fromNode.size, fromNode.lastModified, parent = toNode,
            contentHash = writtenHash,
        )
        freeSpace[destVolume] = free - fromNode.size
        // Content verification (section-4 audit): a size match is not
        // enough — when the landed bytes hash differently, the copy is
        // rolled back (the SAF gateway deletes its .cinesort-part file the
        // same way) and the copy fails, so the caller never deletes the
        // source on the strength of a corrupt copy.
        if (writtenHash != fromNode.contentHash) {
            toNode.children.remove(targetName)
            freeSpace[destVolume] = free
            throw IOException("content hash mismatch for ${file.name}: copied bytes are corrupt")
        }
        val newPath = childPath(toDir.id, targetName)
        return FileRef(newPath, targetName, fromNode.size, fromNode.lastModified, toDir.id)
    }

    override suspend fun delete(ref: Ref) {
        val (volume, segments) = splitPath(ref.id)
        if (segments.isEmpty()) return
        var node = volumes[volume] ?: return
        for (segment in segments.dropLast(1)) {
            node = node.children[segment] ?: return
        }
        node.children.remove(segments.last())
    }

    override suspend fun volumeIdOf(ref: Ref): String = ref.id.substringBefore(':')

    override suspend fun freeBytes(dir: DirRef): Long? = freeSpace[dir.id.substringBefore(':')]

    override suspend fun isValid(dir: DirRef): Boolean = resolve(dir.id)?.isDirectory == true

    override suspend fun recreateDir(dir: DirRef): DirRef = mkdirs(dir.id)
}
