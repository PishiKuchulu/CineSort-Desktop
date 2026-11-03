package com.cinesort.core.domain

import com.cinesort.core.domain.naming.FolderNamer
import com.cinesort.core.domain.naming.NameSanitizer
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.AnchorMediaNameParser
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Folder naming must stay filesystem-safe for any parser output (audit 2026-10-31). */
class FolderNamesFuzzTest {

    private val tokens = listOf(
        "The", "Movie", "2024", "S01E02", "1080p", "BluRay", "فیلم", "a/b", "x:y",
        "..", "CON", "Name.", " trailing ", "S.W.A.T", "Marvel's", "?", "*", "|",
    )
    private val options = NamingOptions(
        movieAddGenres = true, movieAddRating = true,
        seriesAddGenres = true, seriesAddRating = true,
    )

    @Test
    fun `folder names are safe for random parser outputs`() {
        val random = Random(7)
        val parser = AnchorMediaNameParser(currentYear = 2026)
        repeat(5_000) {
            val name = (1..random.nextInt(1, 10)).joinToString(".") { tokens.random(random) } + ".mkv"
            when (val result = parser.parse(name)) {
                is ParseResult.Movie -> {
                    val folder = FolderNamer.movieFolderName(
                        result.title, result.year, listOf("Drama", "Sci-Fi"), 7.5, options,
                    )
                    assertTrue(folder.isNotBlank(), "blank folder for: $name")
                    assertTrue(!folder.contains('/') && !folder.contains('\\'),
                        "separator in folder '$folder' for: $name")
                    assertTrue(!folder.endsWith('.') && !folder.endsWith(' '),
                        "trailing dot/space in folder '$folder' for: $name")
                    assertTrue(NameSanitizer.utf8Length(folder) <= 240,
                        "folder over 240 bytes: '$folder' for: $name")
                }
                is ParseResult.Episode -> {
                    val series = FolderNamer.seriesFolderName(
                        result.seriesTitle, result.seriesYearHint, listOf("Drama"), 8.0, options,
                    )
                    val season = FolderNamer.seasonFolderName(result.season)
                    assertTrue(series.isNotBlank() && !series.contains('/'),
                        "bad series folder '$series' for: $name")
                    assertTrue(season.matches(Regex("S\\d\\d")), "bad season folder '$season'")
                }
                is ParseResult.Unrecognized -> Unit
            }
        }
    }

    @Test
    fun `nasty titles are sanitized`() {
        val folder = FolderNamer.movieFolderName(
            "a/b\\c:d*e?f\"g<h>i|j", 2020, emptyList(), null, NamingOptions(),
        )
        assertTrue(!folder.contains(Regex("[/\\\\:*?\"<>|]")), "unsafe chars kept: $folder")
    }

    @Test
    fun `very long titles are truncated under the byte cap`() {
        val longTitle = ("Very Long Movie Title ".repeat(30)).trim()
        val folder = FolderNamer.movieFolderName(
            longTitle, 2020, listOf("Drama"), 7.0,
            NamingOptions(movieAddGenres = true, movieAddRating = true),
        )
        assertTrue(NameSanitizer.utf8Length(folder) <= 240, "over cap: ${NameSanitizer.utf8Length(folder)}")
        assertTrue(folder.contains("(2020)"), "year must survive truncation: $folder")
    }
}
