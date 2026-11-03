package com.cinesort.desktop.data.metadata

import kotlin.math.abs

/** A provider candidate reduced to the fields the matching rules need. */
data class MatchCandidate(
    val title: String,
    val year: Int? = null,
    val popularity: Double = 0.0,
)

/**
 * Metadata acceptance rules (spec section 9.3). Pure JVM.
 *
 * A candidate is accepted only when the normalized title similarity is at
 * least [ACCEPT_THRESHOLD] and the year matches within ±1 when both years are
 * known. Among accepted candidates the best similarity wins; ties go to the
 * higher popularity. A missing year may be filled from a candidate only when
 * it is the unique candidate at [FILL_YEAR_THRESHOLD] or above.
 */
object MetadataMatcher {

    const val ACCEPT_THRESHOLD = 0.85
    const val FILL_YEAR_THRESHOLD = 0.9
    const val YEAR_TOLERANCE = 1

    fun accepts(
        queryTitle: String,
        queryYear: Int?,
        candidateTitle: String,
        candidateYear: Int?,
    ): Boolean {
        if (TitleSimilarity.similarity(queryTitle, candidateTitle) < ACCEPT_THRESHOLD) return false
        return yearCompatible(queryYear, candidateYear)
    }

    fun yearCompatible(queryYear: Int?, candidateYear: Int?): Boolean {
        if (queryYear == null || candidateYear == null) return true
        return abs(queryYear - candidateYear) <= YEAR_TOLERANCE
    }

    fun score(
        queryTitle: String,
        queryYear: Int?,
        candidate: MatchCandidate,
    ): Double? {
        if (!yearCompatible(queryYear, candidate.year)) return null
        val similarity = TitleSimilarity.similarity(queryTitle, candidate.title)
        return if (similarity >= ACCEPT_THRESHOLD) similarity else null
    }

    /** Index of the best accepted candidate, or null when none is confident. */
    fun bestIndex(
        queryTitle: String,
        queryYear: Int?,
        candidates: List<MatchCandidate>,
    ): Int? {
        var bestIndex: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPopularity = Double.NEGATIVE_INFINITY
        candidates.forEachIndexed { index, candidate ->
            val candidateScore = score(queryTitle, queryYear, candidate) ?: return@forEachIndexed
            val better = candidateScore > bestScore ||
                (candidateScore == bestScore && candidate.popularity > bestPopularity)
            if (better) {
                bestIndex = index
                bestScore = candidateScore
                bestPopularity = candidate.popularity
            }
        }
        return bestIndex
    }

    fun best(
        queryTitle: String,
        queryYear: Int?,
        candidates: List<MatchCandidate>,
    ): MatchCandidate? = bestIndex(queryTitle, queryYear, candidates)?.let { candidates[it] }

    /**
     * Fill-missing-year rule: returns the single candidate whose similarity is
     * at least [FILL_YEAR_THRESHOLD] when exactly one such candidate exists.
     */
    fun fillYearCandidate(
        queryTitle: String,
        candidates: List<MatchCandidate>,
    ): MatchCandidate? {
        val strong = candidates.filter { candidate ->
            candidate.year != null &&
                TitleSimilarity.similarity(queryTitle, candidate.title) >= FILL_YEAR_THRESHOLD
        }
        return strong.singleOrNull()
    }
}
