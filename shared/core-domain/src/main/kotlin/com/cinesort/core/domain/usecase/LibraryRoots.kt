package com.cinesort.core.domain.usecase

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.naming.FolderNamer

data class LibraryRootsResult(
    val movieRoot: DirRef,
    val seriesRoot: DirRef,
    val created: List<String>,
    val found: List<String>,
)

/**
 * Destination bootstrap (spec section 5.6): find (case-insensitive) or create the
 * `Movie` and `Tv series` roots inside the destination. Idempotent.
 */
class EnsureLibraryRootsUseCase(
    private val gateway: StorageGateway,
) {
    suspend operator fun invoke(destination: DirRef): LibraryRootsResult {
        val created = mutableListOf<String>()
        val found = mutableListOf<String>()
        suspend fun ensure(name: String): DirRef {
            val existing = gateway.findChildDir(destination, name, ignoreCase = true)
            return if (existing != null) {
                found += name
                existing
            } else {
                created += name
                gateway.createDir(destination, name)
            }
        }
        val movie = ensure(FolderNamer.MOVIE_ROOT)
        val series = ensure(FolderNamer.SERIES_ROOT)
        return LibraryRootsResult(movie, series, created, found)
    }
}

/** Folder-set validation (spec section 5.8). Ids are tree document ids. */
object FolderSetValidator {
    sealed interface Result {
        data object Ok : Result
        data class Error(val message: String) : Result
    }

    fun validate(
        destination: DirRef,
        sources: List<DirRef>,
    ): Result {
        for (source in sources) {
            if (source.id == destination.id) {
                return Result.Error("destination equals a source")
            }
            if (isInside(source.id, destination.id)) {
                return Result.Error("source is inside the destination")
            }
        }
        return Result.Ok
    }

    /** True when [child] is strictly inside [parent] in the same tree. */
    fun isInside(childId: String, parentId: String): Boolean {
        // Callers pass full SAF content URIs while tests pass bare document ids
        // (review M4): normalize both to document ids before comparing, or the
        // segment comparison never matches real URIs.
        val childDoc = documentIdOf(childId)
        val parentDoc = documentIdOf(parentId)
        if (childDoc == parentDoc) return false
        // SAF tree document ids look like "primary:Movies/Library"; compare on segments.
        val childSegments = childDoc.split(':', '/').filter { it.isNotEmpty() }
        val parentSegments = parentDoc.split(':', '/').filter { it.isNotEmpty() }
        if (childSegments.size <= parentSegments.size) return false
        return childSegments.subList(0, parentSegments.size) == parentSegments &&
            childDoc.substringBefore(':') == parentDoc.substringBefore(':')
    }

    /**
     * Extracts the bare document id ("primary:Movies/Library") from a SAF URI:
     * the segment after "/document/", or after "/tree/" for a picked root.
     * Bare ids pass through unchanged.
     */
    private fun documentIdOf(id: String): String {
        if (!id.startsWith("content://")) return id
        val decoded = percentDecode(id)
        val docMarker = "/document/"
        val treeMarker = "/tree/"
        return when {
            decoded.contains(docMarker) -> decoded.substringAfterLast(docMarker)
            decoded.contains(treeMarker) -> decoded.substringAfterLast(treeMarker)
            else -> decoded
        }
    }

    /** Percent-decodes %XX sequences (UTF-8), leaving '+' untouched. */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val byte = if (c == '%' && i + 2 <= value.lastIndex) {
                value.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            if (byte != null) {
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** De-duplicates sources and drops sources nested inside another source. */
    fun dedupeSources(sources: List<DirRef>): List<DirRef> {
        val unique = sources.distinctBy { it.id }
        return unique.filter { candidate ->
            unique.none { other ->
                other.id != candidate.id && isInside(candidate.id, other.id)
            }
        }
    }
}
