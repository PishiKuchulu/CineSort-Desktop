package com.cinesort.core.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Normalized key for grouping and matching (spec section 6.5):
 * lowercase, NFKD, strip diacritics, keep only letters/digits (Unicode-aware).
 */
object NormalizedKey {
    private val combiningMarks = Regex("""\p{Mn}+""")

    fun of(title: String): String {
        val lowered = title.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFKD)
        val stripped = combiningMarks.replace(decomposed, "")
        // Unify Arabic/Persian letter and digit variants (review L4), so the same
        // title typed with Arabic ي/ك or Persian digits keys identically.
        return stripped.map { foldChar(it) }.joinToString("").filter { it.isLetterOrDigit() }
    }

    private fun foldChar(c: Char): Char = when (c) {
        'ي' -> 'ی' // Arabic Yeh -> Persian Yeh
        'ك' -> 'ک' // Arabic Kaf -> Persian Kaf
        'ة' -> 'ه' // Teh Marbuta -> Heh
        'ؤ' -> 'و'
        'إ', 'أ' -> 'ا'
        in '۰'..'۹' -> '0' + (c - '۰') // Persian digits
        in '٠'..'٩' -> '0' + (c - '٠') // Arabic-Indic digits
        else -> c
    }

    /** Folds Persian/Arabic-Indic digits in [value] to ASCII digits. */
    fun foldDigits(value: String): String = value.map { foldChar(it) }.joinToString("")

    /** Movie keys include the year when known; series keys ignore the year hint. */
    fun movieKey(
        title: String,
        year: Int?,
    ): String = if (year != null) of(title) + year.toString() else of(title)

    fun seriesKey(title: String): String = of(title)
}
