package com.cinesort.desktop.data.metadata

/**
 * Genre normalization (spec section 9.5): provider names are kept verbatim
 * except for one small mapping — `Science Fiction` becomes `Sci-Fi`,
 * `TV Movie` is dropped, and TMDB TV combo genres are split. De-duplicated,
 * order preserving. Pure JVM.
 */
object GenreNormalizer {

    private val renames = mapOf(
        "Science Fiction" to "Sci-Fi",
    )

    private val splits = mapOf(
        "Action & Adventure" to listOf("Action", "Adventure"),
        "Sci-Fi & Fantasy" to listOf("Sci-Fi", "Fantasy"),
        "War & Politics" to listOf("War", "Politics"),
    )

    private val dropped = setOf("TV Movie")

    fun normalizeSingle(genre: String): List<String> {
        val trimmed = genre.trim()
        if (trimmed.isEmpty() || trimmed in dropped) return emptyList()
        splits[trimmed]?.let { return it }
        renames[trimmed]?.let { return listOf(it) }
        return listOf(trimmed)
    }

    fun normalize(genres: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (genre in genres) {
            for (normalized in normalizeSingle(genre)) {
                if (normalized !in result) result += normalized
            }
        }
        return result
    }

    /** OMDb returns genres as one comma-separated string ("Action, Drama"). */
    fun normalizeFromCsv(csv: String?): List<String> {
        if (csv.isNullOrBlank() || csv == "N/A") return emptyList()
        return normalize(csv.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
}
