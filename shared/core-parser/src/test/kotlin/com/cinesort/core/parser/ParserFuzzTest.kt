package com.cinesort.core.parser

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Robustness fuzz for the parser (audit 2026-10-31): random release-name-shaped
 * inputs must never throw, must be deterministic, and produced titles must
 * never contain leftover path separators or be blank for Movie/Episode.
 */
class ParserFuzzTest {

    private val tokens = listOf(
        "The", "Movie", "2024", "1999", "S01E02", "s3e10", "1x05", "1080p", "2160p",
        "BluRay", "WEB-DL", "x264", "HEVC", "Farsi", "Dubbed", "HDR10+", "Season.2",
        "E07", "12", "007", "2049", "..", "__", "--", "[]", "()", "فیلم", "سریال",
        "Part.2", "CD1", "PROPER", "10bit", "REMUX", "a", "I", "of", "the",
    )
    private val seps = listOf(".", " ", "_", "-", ".-", ". ", "")

    private fun randomName(random: Random): String {
        val parts = (1..random.nextInt(1, 14)).map { tokens.random(random) }
        val body = parts.joinToString(separator = seps.random(random))
        val ext = listOf("mkv", "mp4", "avi", "").random(random)
        return if (ext.isEmpty()) body else "$body.$ext"
    }

    @Test
    fun `parser never throws and is deterministic on random names`() {
        val random = Random(42)
        val parser = AnchorMediaNameParser(currentYear = 2026)
        repeat(20_000) {
            val name = randomName(random)
            val first = parser.parse(name)
            val second = parser.parse(name)
            assertEquals(first.toString(), second.toString(), "non-deterministic for: $name")
            when (first) {
                is ParseResult.Movie -> {
                    assertTrue(first.title.isNotBlank(), "blank movie title for: $name")
                    assertTrue(!first.title.contains('/') && !first.title.contains('\\'))
                }
                is ParseResult.Episode -> {
                    assertTrue(first.seriesTitle.isNotBlank(), "blank series title for: $name")
                    assertTrue(first.season in 0..99, "season out of range for: $name")
                    assertTrue(first.episodes.all { it in 0..999 }, "episode out of range for: $name")
                }
                is ParseResult.Unrecognized -> Unit
            }
        }
    }

}
