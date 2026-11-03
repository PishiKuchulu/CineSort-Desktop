package com.cinesort.desktop.data.metadata

import java.text.Normalizer
import java.util.Locale

/**
 * Title similarity for metadata matching (spec section 9.3): the score is the
 * maximum of a fuzzywuzzy-style token-set ratio and a normalized Levenshtein
 * ratio, both computed on diacritic-stripped, case-folded titles. Pure JVM.
 */
object TitleSimilarity {

    /** Cap for strict subset matches: below exact (1.0), at the fill-year bar. */
    const val SUBSET_SCORE_CAP = 0.9

    fun similarity(a: String, b: String): Double {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) {
            return if (normalizedA == normalizedB) 1.0 else 0.0
        }
        if (normalizedA == normalizedB) return 1.0
        val levenshtein = ratio(normalizedA, normalizedB)
        val tokenSet = tokenSetRatio(normalizedA, normalizedB)
        val score = maxOf(levenshtein, tokenSet)
        // Strict token-subset matches in EITHER direction are strong but never
        // exact (review M-D): cap them below 1.0 so an exact title always wins
        // the matcher's popularity tie-break — an exact "Lucky" must beat the
        // subset match "Lucky Hank". The deliberate subset acceptance itself
        // stays ("Matrix" -> "The Matrix" still clears the accept threshold).
        return if (isStrictTokenSubset(normalizedA, normalizedB) ||
            isStrictTokenSubset(normalizedB, normalizedA)
        ) {
            minOf(score, SUBSET_SCORE_CAP)
        } else {
            score
        }
    }

    /** True when [a]'s token multiset is a strict subset of [b]'s. */
    private fun isStrictTokenSubset(a: String, b: String): Boolean {
        val tokensA = a.split(' ').filter { it.isNotEmpty() }
        val tokensB = b.split(' ').filter { it.isNotEmpty() }
        if (tokensA.size >= tokensB.size) return false
        val counts = tokensB.groupingBy { it }.eachCount().toMutableMap()
        for (token in tokensA) {
            val remaining = counts[token] ?: return false
            if (remaining == 0) return false
            counts[token] = remaining - 1
        }
        return true
    }

    private val combiningMarksRegex = Regex("""\p{Mn}+""")
    private val whitespaceRegex = Regex("""\s+""")

    /** Lowercase, NFKD, strip diacritics, keep letters/digits, single spaces. */
    fun normalize(title: String): String {
        val lowered = title.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFKD)
        val stripped = combiningMarksRegex.replace(decomposed, "")
        val kept = stripped.map { c -> if (c.isLetterOrDigit()) c else ' ' }
            .joinToString("")
        return kept.replace(whitespaceRegex, " ").trim()
    }

    fun tokens(title: String): List<String> =
        normalize(title).split(' ').filter { it.isNotEmpty() }

    /** Normalized Levenshtein ratio: 1 - distance / maxLength, in [0, 1]. */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxLength.toDouble()
    }

    /**
     * Token-set ratio: compares the shared token core against each side's full
     * token multiset, so a title that is a subset of the other scores 1.0.
     */
    fun tokenSetRatio(a: String, b: String): Double {
        val tokensA = a.split(' ').filter { it.isNotEmpty() }
        val tokensB = b.split(' ').filter { it.isNotEmpty() }
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = mutableListOf<String>()
        val restA = tokensA.toMutableList()
        val restB = tokensB.toMutableList()
        for (token in tokensA) {
            if (restB.remove(token)) {
                intersection += token
                restA.remove(token)
            }
        }
        val core = intersection.sorted().joinToString(" ")
        val combinedA = (intersection + restA).sorted().joinToString(" ")
        val combinedB = (intersection + restB).sorted().joinToString(" ")
        // Subset guard (review H9): when the CANDIDATE's tokens are a strict subset
        // of the query's (the local title is more specific), the classic token-set
        // formula scores 1.0 (core == combined), so "Elite Force" would perfectly
        // match "Elite" and "Fall 2" would match "Fall". Score the two full titles
        // against each other instead. The reverse (query subset of candidate, e.g.
        // "Matrix" -> "The Matrix") keeps the classic score: a more complete
        // candidate title is normally the same work.
        if (restB.isEmpty() && restA.isNotEmpty()) {
            return ratio(combinedA, combinedB)
        }
        return maxOf(
            ratio(core, combinedA),
            ratio(core, combinedB),
            ratio(combinedA, combinedB),
        )
    }

    /** Classic two-row Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
