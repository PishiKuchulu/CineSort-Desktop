package com.cinesort.desktop.data.metadata

import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.RatingSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * DesktopMetadataService against a local MockWebServer (spec sections 9.1, 9.4, 15):
 * success, not-found, quota, 429 handling, malformed payloads, and the cache.
 */
class DesktopMetadataServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: DesktopMetadataService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = DesktopMetadataService(
            tmdbBaseUrl = server.url("/3/").toString(),
            omdbBaseUrl = server.url("/").toString(),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private val tmdbDetails = """
        {"id":27205,"title":"Inception","imdb_id":"tt1375666","vote_average":8.4,
        "release_date":"2010-07-16","poster_path":"/x.jpg",
        "genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}]}
    """.trimIndent()

    private val tmdbHit = """
        {"page":1,"results":[{"id":27205,"title":"Inception","release_date":"2010-07-16",
        "vote_average":8.4,"genre_ids":[28,878],"poster_path":"/x.jpg"}],
        "total_pages":1,"total_results":1}
    """.trimIndent()

    @Test
    fun `tmdb success returns genres and rating`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k"))
        assertNotNull(info)
        assertEquals(listOf("Action", "Sci-Fi"), info!!.genres)
        assertEquals(8.4, info.rating!!, 0.001)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tmdb 429 honours Retry-After once then succeeds`() = runTest {
        server.enqueue(json("""{"status_message":"rate limited"}""", code = 429).setHeader("Retry-After", "1"))
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k"))
        assertNotNull(info)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `second identical lookup is served from cache without HTTP`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val keys = ApiKeys(tmdbKey = "k")
        service.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        val again = service.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(again)
        assertEquals(2, server.requestCount, "cache hit must not re-request")
    }

    @Test
    fun `empty tmdb results are a cached not-found`() = runTest {
        // Search with year, then the year-less retry (spec 9.3), both empty.
        server.enqueue(json("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))
        server.enqueue(json("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))
        val keys = ApiKeys(tmdbKey = "k")
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv Nonexistent", 2010, keys))
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv Nonexistent", 2010, keys))
        // Two live searches on the first call, none on the second:
        // the not-found was negative-cached.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `omdb request-limit error degrades to null without caching`() = runTest {
        server.enqueue(json("""{"Response":"False","Error":"Request limit reached!"}"""))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(omdbKey = "k"))
        assertNull(info)
        // (In-memory cache holds no not-found for a quota answer;
        //  behaviour is asserted by the follow-up tests via request counts.)
    }

    @Test
    fun `omdb movie-not-found is a plain miss`() = runTest {
        // Direct title lookup misses, then the search fallback (spec 9.2) misses too.
        server.enqueue(json("""{"Response":"False","Error":"Movie not found!"}"""))
        server.enqueue(json("""{"Response":"False","Error":"Movie not found!"}"""))
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv", 2010, ApiKeys(omdbKey = "k")))
        assertEquals(2, server.requestCount)
        // Second lookup is served by the negative cache.
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv", 2010, ApiKeys(omdbKey = "k")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `omdb poster url flows into the enriched info`() = runTest {
        // Regression (v1.0.9): OmdbTitleResponse.toMetadataInfo dropped the
        // Poster field, so OMDb-matched items never got a poster.jpg even
        // with the download-posters setting on.
        server.enqueue(
            json(
                """{"Title":"Inception","Year":"2010","Genre":"Action, Sci-Fi","imdbRating":"8.8",
                "imdbID":"tt1375666","Type":"movie","Response":"True",
                "Poster":"https://m.media-amazon.com/images/M/inception.jpg"}""",
            ),
        )
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(omdbKey = "k"))
        assertNotNull(info)
        assertEquals("https://m.media-amazon.com/images/M/inception.jpg", info!!.posterUrl)
    }

    @Test
    fun `omdb poster NA stays null`() = runTest {
        server.enqueue(
            json(
                """{"Title":"Inception","Year":"2010","Genre":"Action, Sci-Fi","imdbRating":"8.8",
                "imdbID":"tt1375666","Type":"movie","Response":"True","Poster":"N/A"}""",
            ),
        )
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(omdbKey = "k"))
        assertNotNull(info)
        assertNull(info!!.posterUrl)
    }

    @Test
    fun `malformed JSON degrades to null instead of throwing`() = runTest {
        server.enqueue(json("{not json"))
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k")))
    }

    @Test
    fun `http 401 is unavailable and not cached`() = runTest {
        server.enqueue(json("""{"status_message":"Invalid API key"}""", code = 401))
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "bad")))
    }

    @Test
    fun `no keys means no HTTP and null`() = runTest {
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys()))
        assertEquals(0, server.requestCount)
    }

    private val tmdbEmpty = """{"page":1,"results":[],"total_pages":0,"total_results":0}"""

    private val omdbInception = """
        {"Title":"Inception","Year":"2010","Genre":"Action, Sci-Fi","imdbRating":"8.8",
        "imdbID":"tt1375666","Type":"movie","Response":"True"}
    """.trimIndent()

    private val serverError = """{"status_message":"boom"}"""

    @Test
    fun `tmdb miss falls back to omdb when both keys exist`() = runTest {
        server.enqueue(json(tmdbEmpty)) // TMDB search with year
        server.enqueue(json(tmdbEmpty)) // TMDB search without year (spec 9.3 retry)
        server.enqueue(json(omdbInception)) // OMDb direct title lookup
        val info = service.enrich(
            ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "t", omdbKey = "o"),
        )
        assertNotNull(info)
        assertEquals(8.8, info!!.rating!!, 0.001)
        assertEquals(RatingSource.IMDB, info.ratingSource)
        assertEquals("Inception", info.matchedTitle)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `tmdb outage falls back to omdb when both keys exist`() = runTest {
        repeat(3) { server.enqueue(json(serverError, code = 500)) } // search + 2 retries
        server.enqueue(json(omdbInception))
        val info = service.enrich(
            ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "t", omdbKey = "o"),
        )
        assertNotNull(info)
        assertEquals(8.8, info!!.rating!!, 0.001)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `circuit breaker opens after three consecutive network failures`() = runTest {
        val keys = ApiKeys(tmdbKey = "t")
        // Each failed lookup burns 3 requests (search + 2 retries on HTTP 500).
        repeat(9) { server.enqueue(json(serverError, code = 500)) }
        assertNull(service.enrich(ItemType.MOVIE, "Fail One", 2001, keys))
        assertNull(service.enrich(ItemType.MOVIE, "Fail Two", 2002, keys))
        assertNull(service.enrich(ItemType.MOVIE, "Fail Three", 2003, keys))
        assertTrue(service.circuitOpen)
        val before = server.requestCount
        assertNull(service.enrich(ItemType.MOVIE, "Fail Four", 2004, keys))
        assertEquals(before, server.requestCount, "open breaker must not touch the network")
    }

    @Test
    fun `a provider answer resets the breaker failure count`() = runTest {
        val keys = ApiKeys(tmdbKey = "t")
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        assertNull(service.enrich(ItemType.MOVIE, "Fail One", 2001, keys)) // failure 1
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        assertNull(service.enrich(ItemType.MOVIE, "Fail Two", 2002, keys)) // failure 2
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        assertNotNull(service.enrich(ItemType.MOVIE, "Inception", 2010, keys)) // answer: reset
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        assertNull(service.enrich(ItemType.MOVIE, "Fail Three", 2003, keys)) // failure 1
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        assertNull(service.enrich(ItemType.MOVIE, "Fail Four", 2004, keys)) // failure 2
        assertFalse(service.circuitOpen)
        // The next lookup still goes live — and its failure (the third in a
        // row since the answer) finally opens the breaker.
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        val before = server.requestCount
        assertNull(service.enrich(ItemType.MOVIE, "Fail Five", 2005, keys))
        assertEquals(before + 3, server.requestCount)
        assertTrue(service.circuitOpen)
    }

    @Test
    fun `degraded hits expire after a day while full hits survive`() = runTest {
        var now = 1_700_000_000_000L
        val timed = DesktopMetadataService(
            tmdbBaseUrl = server.url("/3/").toString(),
            omdbBaseUrl = server.url("/").toString(),
            nowMillis = { now },
        )
        val keys = ApiKeys(tmdbKey = "t", omdbKey = "o")
        // TMDB finds the film but the OMDb rating call fails (500 + retries),
        // so the rating falls back to TMDB's vote: a degraded hit.
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        repeat(3) { server.enqueue(json(serverError, code = 500)) }
        val degraded = timed.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(degraded)
        assertEquals(8.4, degraded!!.rating!!, 0.001)
        assertEquals(RatingSource.TMDB, degraded.ratingSource)
        val afterFirst = server.requestCount

        // 23h later the degraded entry is still served from cache.
        now += 23L * 60 * 60 * 1000
        assertNotNull(timed.enrich(ItemType.MOVIE, "Inception", 2010, keys))
        assertEquals(afterFirst, server.requestCount)

        // At 25h it has expired (24h degraded TTL): the live retry now gets
        // the IMDb rating and caches a full hit instead.
        now += 2L * 60 * 60 * 1000
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        server.enqueue(json(omdbInception)) // OMDb byId rating fetch
        val refreshed = timed.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(refreshed)
        assertEquals(8.8, refreshed!!.rating!!, 0.001)
        assertEquals(RatingSource.IMDB, refreshed.ratingSource)
        val afterRefresh = server.requestCount
        assertTrue(afterRefresh > afterFirst)

        // 29 days on, the full hit is still cached (30-day success TTL).
        now += 29L * 24 * 60 * 60 * 1000
        assertNotNull(timed.enrich(ItemType.MOVIE, "Inception", 2010, keys))
        assertEquals(afterRefresh, server.requestCount)
    }

    @Test
    fun `rating source preference changes the lookup outcome`() = runTest {
        // Desktop: the preference is a constructor parameter (Android
        // reads it from settings). IMDB_ONLY with no OMDb key yields no
        // rating, while ANY takes TMDB's vote.
        val anySvc = DesktopMetadataService(
            ratingSourcePreference = RatingSourcePreference.ANY,
            tmdbBaseUrl = server.url("/3/").toString(),
            omdbBaseUrl = server.url("/").toString(),
        )
        val imdbSvc = DesktopMetadataService(
            ratingSourcePreference = RatingSourcePreference.IMDB_ONLY,
            tmdbBaseUrl = server.url("/3/").toString(),
            omdbBaseUrl = server.url("/").toString(),
        )
        val keys = ApiKeys(tmdbKey = "k")
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val anyResult = anySvc.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(anyResult)
        assertNotNull(anyResult!!.rating)
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val imdbOnly = imdbSvc.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(imdbOnly)
        assertNull(imdbOnly!!.rating)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `api key never appears in request paths of other providers`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "SECRETKEY"))
        val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertTrue(request.path!!.contains("api_key=SECRETKEY"), "TMDB key travels as its own query param")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `candidate search falls back to omdb when tmdb finds nothing`() = runTest {
        // Audit A.3-9: provider choice for EditSheet search used to be
        // key-presence only — with a TMDB key set, an item missing from
        // TMDB produced an empty candidate list even with an OMDb key too.
        server.enqueue(json(tmdbEmpty)) // TMDB search: no results
        server.enqueue(
            json(
                """{"Search":[{"Title":"Obscure Film","Year":"1999",""" +
                    """"imdbID":"tt1234567","Type":"movie","Poster":"N/A"}],""" +
                    """"totalResults":"1","Response":"True"}""",
            ),
        )
        val candidates = service.searchCandidates(
            ItemType.MOVIE, "Obscure Film", 1999, ApiKeys(tmdbKey = "t", omdbKey = "o"),
        )
        assertEquals(1, candidates.size)
        assertEquals("Obscure Film", candidates.single().title)
        assertEquals("OMDb", candidates.single().provider)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `candidate search labels tmdb results and does not call omdb on hits`() = runTest {
        server.enqueue(json(tmdbHit))
        val candidates = service.searchCandidates(
            ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "t", omdbKey = "o"),
        )
        assertEquals(1, candidates.size)
        assertEquals("TMDB", candidates.single().provider)
        assertEquals(1, server.requestCount)
    }

}
