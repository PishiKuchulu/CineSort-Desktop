package com.cinesort.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for external review #4 parser findings (H5, H6, M14). */
class ParserReviewRegressionTest {

    private val parser = AnchorMediaNameParser(currentYear = 2026)

    private fun assertMovie(fileName: String, title: String, year: Int?) {
        val result = parser.parse(fileName)
        assertTrue(result is ParseResult.Movie, "expected Movie for $fileName but was $result")
        result as ParseResult.Movie
        assertEquals(title, result.title, "title for $fileName")
        assertEquals(year, result.year, "year for $fileName")
    }

    private fun assertEpisode(fileName: String, seriesTitle: String, season: Int, episodes: List<Int>) {
        val result = parser.parse(fileName)
        assertTrue(result is ParseResult.Episode, "expected Episode for $fileName but was $result")
        result as ParseResult.Episode
        assertEquals(seriesTitle, result.seriesTitle, "series for $fileName")
        assertEquals(season, result.season, "season for $fileName")
        assertEquals(episodes, result.episodes, "episodes for $fileName")
    }

    // H5: a single source/codec word inside the title must not hide the year.
    @Test
    fun yearSurvivesSourceWordInTitle() {
        assertMovie("Charlotte's.Web.2006.1080p.WEB-DL.mkv", "Charlotte's Web", 2006)
        assertMovie("Cam.2018.1080p.WEB-DL.mkv", "Cam", 2018)
        assertMovie("Dark.Web.2018.1080p.WEB-DL.mkv", "Dark Web", 2018)
    }

    // H6: a title that looks like a domain must not be stripped as one.
    @Test
    fun domainLikeTitleIsKept() {
        assertMovie("The.Net.1995.1080p.BluRay.mkv", "The Net", 1995)
    }

    // M-H residual: the strip only fires for www. hosts or a domain-like head
    // followed by a spaced dash; a longer domain-like title survives whole.
    @Test
    fun domainStripIsRestrictedToWwwOrSpacedDash() {
        assertMovie("The.Net.Of.Lies.2020.1080p.WEB-DL.mkv", "The Net of Lies", 2020)
        assertMovie("www.ExampleSite.com - Movie.Name.2020.1080p.mkv", "Movie Name", 2020)
        assertMovie("ExampleSite.com - Movie.Name.2020.1080p.mkv", "Movie Name", 2020)
    }

    // M14: hyphenated episode forms are episodes, not movies.
    @Test
    fun hyphenatedEpisodeForms() {
        assertEpisode("Show.S01E01-E03.720p.WEB-DL.mkv", "Show", 1, listOf(1, 3))
        assertEpisode("Show.S01-E01.720p.WEB-DL.mkv", "Show", 1, listOf(1))
    }

    // M-H residual: a range written without the second "E" is still an episode.
    @Test
    fun episodeRangeWithoutSecondE() {
        assertEpisode("Show.S01E01-02.1080p.mkv", "Show", 1, listOf(1, 2))
        assertEpisode("Show.S01E01-03.720p.WEB-DL.mkv", "Show", 1, listOf(1, 2, 3))
    }
}
