package com.cinesort.core.parser

/** Ancestor folder names between the file and its source root, nearest first. */
data class PathContext(
    val ancestors: List<String>,
    val sourceRootName: String? = null,
) {
    companion object {
        val None = PathContext(emptyList())
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }

enum class AnchorKind { EPISODE_MARKER, YEAR, RESOLUTION_FALLBACK, NOISE_RUN_FALLBACK, FOLDER_CONTEXT, NONE }

enum class TitleCaseMode { SMART, AS_IN_FILENAME }

/** Character ranges in the pre-cleaned name: title / anchor / ignored tail. */
data class ParseTrace(
    val titleRange: IntRange?,
    val anchorRange: IntRange?,
    val anchorKind: AnchorKind,
    val rules: List<String>,
)

sealed interface ParseResult {
    val fileName: String
    val confidence: Confidence
    val trace: ParseTrace

    data class Movie(
        override val fileName: String,
        val title: String,
        val year: Int?,
        override val confidence: Confidence,
        override val trace: ParseTrace,
    ) : ParseResult

    data class Episode(
        override val fileName: String,
        val seriesTitle: String,
        val seriesYearHint: Int?,
        val season: Int,
        val episodes: List<Int>,
        val fromFolderContext: Boolean,
        override val confidence: Confidence,
        override val trace: ParseTrace,
    ) : ParseResult

    data class Unrecognized(
        override val fileName: String,
        val reason: String,
        override val trace: ParseTrace,
    ) : ParseResult {
        override val confidence: Confidence = Confidence.LOW
    }
}

interface MediaNameParser {
    fun parse(
        fileName: String,
        context: PathContext = PathContext.None,
    ): ParseResult
}
