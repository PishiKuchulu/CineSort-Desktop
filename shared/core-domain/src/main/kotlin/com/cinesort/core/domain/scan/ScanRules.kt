package com.cinesort.core.domain.scan

import java.util.Locale

data class ScanSettings(
    val videoExtensions: Set<String> = DEFAULT_VIDEO_EXTENSIONS,
    val companionExtensions: Set<String> = DEFAULT_COMPANION_EXTENSIONS,
    val ignoreSamples: Boolean = true,
    val minFileAgeSeconds: Long = 120,
    val includeSubfolders: Boolean = true,
    val maxDepth: Int = 5,
    val moveCompanions: Boolean = true,
) {
    companion object {
        val DEFAULT_VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg",
            "mpeg", "3gp",
        )
        val DEFAULT_COMPANION_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "vtt", "idx")
        val TEMP_EXTENSIONS = setOf("part", "crdownload", "tmp", "download", "!ut", "aria2")
        const val SAMPLE_SIZE_LIMIT: Long = 100L * 1024 * 1024
    }
}

enum class ScanDisposition {
    VIDEO,
    COMPANION_CANDIDATE,
    IGNORED_TEMP,
    IGNORED_HIDDEN,
    IGNORED_SAMPLE,
    IGNORED_EXTENSION,
    RECENTLY_MODIFIED,
}

/** Pure scan-rule classification (spec section 5.3). Times are epoch millis. */
object ScanRules {
    fun classify(
        name: String,
        size: Long,
        lastModified: Long,
        now: Long,
        settings: ScanSettings,
    ): ScanDisposition {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        val isVideo = ext in settings.videoExtensions
        val isCompanion = ext in settings.companionExtensions
        val ageMillis = settings.minFileAgeSeconds * 1000
        return when {
            name.startsWith(".") -> ScanDisposition.IGNORED_HIDDEN
            lower.endsWith(".cinesort-part") || ext in ScanSettings.TEMP_EXTENSIONS ->
                ScanDisposition.IGNORED_TEMP
            !isVideo && !isCompanion -> ScanDisposition.IGNORED_EXTENSION
            isVideo && settings.ignoreSamples && size < ScanSettings.SAMPLE_SIZE_LIMIT &&
                hasSampleToken(name) -> ScanDisposition.IGNORED_SAMPLE
            // A future timestamp (clock skew on the source device) must not pin
            // the file as "recently modified" forever (review L3).
            isVideo && ageMillis > 0 && lastModified <= now && now - lastModified < ageMillis ->
                ScanDisposition.RECENTLY_MODIFIED
            isVideo -> ScanDisposition.VIDEO
            else -> ScanDisposition.COMPANION_CANDIDATE
        }
    }

    private val tokenSeparatorRegex = Regex("""[\s._\-()\[\]]+""")

    /** A `sample` token delimited by separators — not a substring of a longer word. */
    fun hasSampleToken(name: String): Boolean {
        val tokens = name.lowercase(Locale.ROOT).split(tokenSeparatorRegex)
        return tokens.any { it == "sample" }
    }

    /**
     * Effective scan depth for one source (audit A.2-1): the per-source
     * depth capped by the global Settings depth. Both controls are real —
     * the Settings stepper is the ceiling every scan obeys, and the
     * per-source value can only narrow it. Before this, the session used
     * `sourceDepth ?: settingsDepth` and every stored source carries a
     * depth, so the Settings stepper never reached a single scan.
     */
    fun effectiveDepth(sourceDepth: Int?, settingsDepth: Int): Int =
        minOf(sourceDepth ?: settingsDepth, settingsDepth).coerceIn(1, 10)

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * A companion shares the video's directory and its name starts with the video's base
     * name followed by `.` (spec section 5.4): `Movie.fa.srt`, `Movie.en.forced.srt`.
     */
    fun isCompanionOf(
        candidateName: String,
        videoName: String,
        settings: ScanSettings,
    ): Boolean {
        if (extensionOf(candidateName) !in settings.companionExtensions) return false
        val videoBase = videoName.substringBeforeLast('.', videoName)
        return candidateName.length > videoBase.length &&
            candidateName.startsWith(videoBase) &&
            candidateName[videoBase.length] == '.'
    }
}
