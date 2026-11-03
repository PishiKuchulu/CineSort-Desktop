package com.cinesort.core.parser

import java.util.Locale

/**
 * Noise vocabulary (spec section 6.4). Used ONLY to (a) choose a fallback anchor when no
 * year/episode marker exists, and (b) as a guard when choosing a year. It is never used to
 * strip words from the whole string (spec section 6.1).
 */
object NoiseVocabulary {
    val resolutionRegex = Regex("""^\d{3,4}[pi]$""", RegexOption.IGNORE_CASE)

    val resolutionWords = setOf("4k", "8k", "uhd")

    val source = setOf(
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web", "web-dl", "webdl",
        "webrip", "web-rip", "hdtv", "pdtv", "dvdrip", "dvdscr", "scr", "screener", "hdrip",
        "cam", "camrip", "ts", "telesync", "tc", "hdts", "vodrip", "amzn", "nf", "dsnp",
        "hmax", "atvp",
    )

    val codec = setOf(
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "av1", "10bit",
        "8bit", "hdr", "hdr10", "hdr10+", "dv",
    )

    val audio = setOf(
        "aac", "ac3", "eac3", "ddp", "dd5", "dts", "truehd", "atmos", "flac", "6ch", "2ch",
        "5.1", "7.1",
    )

    val language = setOf(
        "farsi", "persian", "dubbed", "dub", "sub", "subbed", "multi", "dual", "hardsub",
        "softsub",
    )

    val misc = setOf(
        "proper", "repack", "extended", "unrated", "uncut", "remastered", "imax", "internal",
        "limited",
    )

    val all: Set<String> = source + codec + audio + language + misc + resolutionWords

    fun isResolution(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT)
        return resolutionRegex.matches(t) || t in resolutionWords
    }

    /** Resolution / source / codec — the categories that bound the year search (spec 6.3 C2). */
    fun isYearGuardNoise(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT)
        return isResolution(t) || matchesVocabulary(t, source) || matchesVocabulary(t, codec)
    }

    fun isNoise(
        token: String,
        extra: Set<String> = emptySet(),
    ): Boolean {
        val t = token.lowercase(Locale.ROOT)
        return isResolution(t) || matchesVocabulary(t, all) || t in extra
    }

    /** Token-level match; a trailing "-GROUP" style suffix does not hide a noise head. */
    private fun matchesVocabulary(
        tokenLower: String,
        vocab: Set<String>,
    ): Boolean {
        if (tokenLower in vocab) return true
        val head = tokenLower.substringBefore('-')
        return head != tokenLower && head in vocab
    }
}
