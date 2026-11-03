package com.cinesort.core.domain.naming

import com.cinesort.core.parser.NormalizedKey
import java.text.Normalizer
import java.util.Locale

/** Naming options snapshot (spec sections 7.1, 7.2, 11). */
data class NamingOptions(
    val movieAddYear: Boolean = true,
    val movieAddGenres: Boolean = false,
    val movieAddRating: Boolean = false,
    val seriesAddYear: Boolean = false,
    val seriesAddGenres: Boolean = false,
    val seriesAddRating: Boolean = false,
    val maxGenres: Int = 4,
    val fillMissingYear: Boolean = true,
)

object NameSanitizer {
    private const val MAX_BYTES = 240

    /** Spec section 7.4 sanitization (without the length cap; see [limitFolderName]). */
    // Invisible format controls stripped from names (review L5): bidi embedding /
    // override / isolate controls and zero-width spaces can make a folder display
    // as a different name than it is. ZWNJ/ZWJ are KEPT — Persian orthography
    // (and emoji sequences) need them.
    private fun isInvisibleFormat(c: Char): Boolean =
        c == '\u200B' || // zero-width space
            c == '\u200E' || // left-to-right mark
            c == '\u200F' || // right-to-left mark
            c == '\u061C' || // arabic letter mark
            c in '\u202A'..'\u202E' || // bidi embeddings / overrides
            c in '\u2066'..'\u2069' || // bidi isolates
            c == '\uFEFF' // byte-order mark / zero-width no-break space

    private val whitespaceRun = Regex("""\s+""")

    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            when {
                isInvisibleFormat(c) -> Unit // removed
                c == ':' -> sb.append(" -")
                c == '/' -> sb.append('-')
                c == '\\' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' ||
                    c == '|' || c.isISOControl() -> Unit // removed
                else -> sb.append(c)
            }
        }
        var out = sb.toString().replace(whitespaceRun, " ").trim()
        out = out.trimEnd('.', ' ')
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * Applies the 240-byte cap in spec order: drop genres first, then rating, then
     * truncate the title at a word boundary. The year is never dropped.
     */
    fun limitFolderName(
        title: String,
        yearPart: String,
        genresPart: String,
        ratingPart: String,
    ): String {
        fun assemble(t: String, g: String, r: String) = sanitize(t + yearPart + g + r)
        val full = assemble(title, genresPart, ratingPart)
        if (utf8Length(full) <= MAX_BYTES) return full
        val noGenres = assemble(title, "", ratingPart)
        if (utf8Length(noGenres) <= MAX_BYTES) return noGenres
        val bare = assemble(title, "", "")
        if (utf8Length(bare) <= MAX_BYTES) return bare
        // Truncate the title at a word boundary until title + year fits.
        var truncated = title
        while (truncated.isNotEmpty() && utf8Length(assemble(truncated, "", "")) > MAX_BYTES) {
            val cut = truncated.lastIndexOf(' ')
            truncated = if (cut > 0) {
                truncated.substring(0, cut)
            } else {
                // Drop the last code point, never half of a surrogate pair (review L5).
                val drop = if (truncated.last().isLowSurrogate() && truncated.length >= 2) 2 else 1
                truncated.dropLast(drop)
            }
        }
        return assemble(truncated, "", "")
    }
}

object FolderNamer {
    const val MOVIE_ROOT = "Movie"
    const val SERIES_ROOT = "Tv series"

    /** Rating format: exactly one decimal, dot separator, ASCII digits (spec 7.1). */
    fun formatRating(rating: Double): String =
        String.format(Locale.ROOT, "%.1f", rating)

    fun seasonFolderName(season: Int): String =
        if (season in 0..99) "S" + season.toString().padStart(2, '0') else "S$season"

    fun movieFolderName(
        title: String,
        year: Int?,
        genres: List<String>,
        rating: Double?,
        options: NamingOptions,
    ): String {
        val yearPart = if (options.movieAddYear && year != null) " ($year)" else ""
        val genresPart = if (options.movieAddGenres && genres.isNotEmpty()) {
            " (" + genres.take(options.maxGenres.coerceIn(1, 6)).joinToString(", ") + ")"
        } else {
            ""
        }
        val ratingPart = if (options.movieAddRating && rating != null) {
            " " + formatRating(rating)
        } else {
            ""
        }
        return NameSanitizer.limitFolderName(NameSanitizer.sanitize(title), yearPart, genresPart, ratingPart)
    }

    fun seriesFolderName(
        title: String,
        year: Int?,
        genres: List<String>,
        rating: Double?,
        options: NamingOptions,
    ): String {
        val yearPart = if (options.seriesAddYear && year != null) " ($year)" else ""
        val genresPart = if (options.seriesAddGenres && genres.isNotEmpty()) {
            " (" + genres.take(options.maxGenres.coerceIn(1, 6)).joinToString(", ") + ")"
        } else {
            ""
        }
        val ratingPart = if (options.seriesAddRating && rating != null) {
            " " + formatRating(rating)
        } else {
            ""
        }
        return NameSanitizer.limitFolderName(NameSanitizer.sanitize(title), yearPart, genresPart, ratingPart)
    }
}

/**
 * Matching existing destination folders (spec section 7.3): decoration is stripped in the
 * given order — rating, genres, year — then the normalized key is compared.
 */
object ExistingFolderMatcher {
    private val ratingRegex = Regex("""\s\d{1,2}\.\d$""")
    private val genresRegex = Regex("""\s\((?=[A-Za-z])[^()]*\)$""")
    private val yearRegex = Regex("""\s\((19|20)\d{2}\)$""")

    fun stripDecoration(folderName: String): String {
        var out = folderName
        out = ratingRegex.replace(out, "")
        out = genresRegex.replace(out, "")
        out = yearRegex.replace(out, "")
        return out.trim()
    }

    fun keyOf(folderName: String): String = NormalizedKey.of(stripDecoration(folderName))

    fun matches(
        folderName: String,
        titleKey: String,
    ): Boolean = keyOf(folderName) == titleKey
}
