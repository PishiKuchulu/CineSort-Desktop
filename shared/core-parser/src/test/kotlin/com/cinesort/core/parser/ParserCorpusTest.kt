package com.cinesort.core.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec section 15.1 — the parser corpus must pass exactly. Fixed year for determinism. */
class ParserCorpusTest {

    private val parser = AnchorMediaNameParser(currentYear = 2026)

    private fun assertMovie(
        fileName: String,
        title: String,
        year: Int?,
        confidence: Confidence,
    ) {
        val result = parser.parse(fileName)
        assertTrue(result is ParseResult.Movie, "expected Movie for $fileName but was $result")
        result as ParseResult.Movie
        assertEquals(title, result.title, "title for $fileName")
        assertEquals(year, result.year, "year for $fileName")
        assertEquals(confidence, result.confidence, "confidence for $fileName")
    }

    /** Optional expectations for [assertEpisode], bundled to keep its signature short. */
    private data class EpisodeOptions(
        val yearHint: Int? = null,
        val context: PathContext = PathContext.None,
        val confidence: Confidence = Confidence.HIGH,
        val fromFolder: Boolean = false,
    )

    private fun assertEpisode(
        fileName: String,
        seriesTitle: String,
        season: Int,
        episodes: List<Int>,
        options: EpisodeOptions = EpisodeOptions(),
    ) {
        val result = parser.parse(fileName, options.context)
        assertTrue(result is ParseResult.Episode, "expected Episode for $fileName but was $result")
        result as ParseResult.Episode
        assertEquals(seriesTitle, result.seriesTitle, "series title for $fileName")
        assertEquals(season, result.season, "season for $fileName")
        assertEquals(episodes, result.episodes, "episodes for $fileName")
        assertEquals(options.yearHint, result.seriesYearHint, "year hint for $fileName")
        assertEquals(options.confidence, result.confidence, "confidence for $fileName")
        assertEquals(options.fromFolder, result.fromFolderContext, "fromFolderContext for $fileName")
    }

    @Test
    fun corpusMovies() {
        assertMovie(
            "Fall.2.Deadpoint.2026.1080p.10bit.WEB-DL.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "Fall 2 Deadpoint", 2026, Confidence.HIGH,
        )
        assertMovie(
            "Spider-Man.Brand.New.Day.2026.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media (1).mkv",
            "Spider-Man Brand New Day", 2026, Confidence.HIGH,
        )
        assertMovie(
            "The.Rivals.of.Amziah.King.2025.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "The Rivals of Amziah King", 2025, Confidence.HIGH,
        )
        assertMovie(
            "The.Weight.2026.1080p.SCREENER.WEB-DL.Farsi.Dubbed.Film2Media.mkv",
            "The Weight", 2026, Confidence.HIGH,
        )
        assertMovie(
            "Just.Play.Dead.2026.10bit.1080p.x265.WEB-DL.6CH.PSA.Farsi.Sub.Film2Media.mkv",
            "Just Play Dead", 2026, Confidence.HIGH,
        )
        assertMovie(
            "Mutiny.2026.1080p.WEB-DL.x265.6CH.Pahe.Farsi.Sub.Film2Media.mkv",
            "Mutiny", 2026, Confidence.HIGH,
        )
        assertMovie(
            "Evolution.2001.720p.BluRay.10bit.x265.Farsi.Sub.Film2Media.mkv",
            "Evolution", 2001, Confidence.HIGH,
        )
        assertMovie(
            "Mortal.Kombat.II.2026.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "Mortal Kombat II", 2026, Confidence.HIGH,
        )
        assertMovie("2012.2009.1080p.BluRay.x264-GROUP.mkv", "2012", 2009, Confidence.HIGH)
        assertMovie(
            "Blade.Runner.2049.2017.2160p.UHD.BluRay.x265.mkv",
            "Blade Runner 2049", 2017, Confidence.HIGH,
        )
        assertMovie("1917.2019.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", "1917", 2019, Confidence.HIGH)
        assertMovie("2012.1080p.BluRay.mkv", "2012", null, Confidence.MEDIUM)
        assertMovie("Charlotte's.Web.1080p.WEB-DL.mkv", "Charlotte's Web", null, Confidence.MEDIUM)
        assertMovie(
            "Dune.Part.Two.2024.2160p.UHD.BluRay.x265.10bit.HDR.DDP5.1.mkv",
            "Dune Part Two", 2024, Confidence.HIGH,
        )
        assertMovie(
            "Top.Gun.Maverick.2022.IMAX.1080p.WEB-DL.mkv",
            "Top Gun Maverick", 2022, Confidence.HIGH,
        )
        assertMovie("[Site.com] Some.Movie.2020.720p.BluRay.mkv", "Some Movie", 2020, Confidence.HIGH)
        assertMovie(
            "www.Example.com - Some Movie (2018) 1080p.mp4",
            "Some Movie", 2018, Confidence.HIGH,
        )
        assertMovie("Movie Title (2019) 1080p BluRay.mkv", "Movie Title", 2019, Confidence.HIGH)
    }

    @Test
    fun corpusEpisodes() {
        assertEpisode(
            "Dexter.Resurrection.S02E01.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "Dexter Resurrection", 2, listOf(1),
        )
        assertEpisode(
            "Gangs.Of.London.S01E01.1080p.6CH.Pahe.Farsi.Sub.Film2Media.mkv",
            "Gangs of London", 1, listOf(1),
        )
        assertEpisode(
            "A.Bona.Fide.Killer.S01E01.1080p.WEB-DL.Farsi.Sub.Film2Media.mkv",
            "A Bona Fide Killer", 1, listOf(1),
        )
        assertEpisode(
            "The.Agency.2024.S01E01.10bit.x265.1080p.WEBRip.6CH.PSA.Farsi.Sub.Film2Media.mkv",
            "The Agency", 1, listOf(1), options = EpisodeOptions(yearHint = 2024),
        )
        assertEpisode(
            "3.Body.Problem.S01E01.1080p.10bit.WEBRip.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "3 Body Problem", 1, listOf(1),
        )
        assertEpisode(
            "The.Gentlemen.S02E03.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            "The Gentlemen", 2, listOf(3),
        )
        assertEpisode("show.name.s03e10.720p.hdtv.x264-lol.mkv", "Show Name", 3, listOf(10))
        assertEpisode("Show.Name.1x05.HDTV.XviD-GRP.mkv", "Show Name", 1, listOf(5))
        assertEpisode(
            "Show.Name.S01E01E02.1080p.WEB-DL.mkv",
            "Show Name", 1, listOf(1, 2),
        )
        assertEpisode("The.Office.US.S02E03.720p.WEB-DL.mkv", "The Office US", 2, listOf(3))
        assertEpisode(
            "S.W.A.T.2017.S01E01.1080p.WEB-DL.mkv",
            "S.W.A.T", 1, listOf(1), options = EpisodeOptions(yearHint = 2017),
        )
        assertEpisode(
            "Dexter.Resurrection.S02E01.1080p.دوبله.فارسی.mkv",
            "Dexter Resurrection", 2, listOf(1),
        )
    }

    @Test
    fun corpusUnrecognized() {
        val result = parser.parse("random_clip.mkv")
        assertTrue(result is ParseResult.Unrecognized, "expected Unrecognized but was $result")
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun corpusFolderContext() {
        assertEpisode(
            "E01.mkv", "Squid Game", 1, listOf(1),
            options = EpisodeOptions(
                context = PathContext(ancestors = listOf("Season 1", "Squid Game")),
                confidence = Confidence.MEDIUM, fromFolder = true,
            ),
        )
        assertEpisode(
            "01.mkv", "Squid Game", 1, listOf(1),
            options = EpisodeOptions(
                context = PathContext(ancestors = listOf("Squid.Game.S01.1080p.WEB-DL")),
                confidence = Confidence.MEDIUM, fromFolder = true,
            ),
        )
    }

    @Test
    fun parserNeverThrowsOnGarbage() {
        val samples = listOf(
            "", ".mkv", "...", "(((", "S.mkv", "E.mkv", "9999.mkv", "فیلم.mkv",
            "a".repeat(500) + ".mkv", "S99E999.mkv", "....2020....mkv", "x".repeat(10_000),
        )
        for (sample in samples) {
            parser.parse(sample) // must return a result, never throw
            parser.parse(sample, PathContext(listOf("Season 2", "Some Show"), "Root"))
        }
    }

    @Test
    fun normalizedKeyIgnoresCaseAndPunctuation() {
        assertEquals(
            NormalizedKey.seriesKey("The.Gentlemen"),
            NormalizedKey.seriesKey("the gentlemen"),
        )
        assertEquals("thegentlemen", NormalizedKey.seriesKey("The Gentlemen"))
    }
}
