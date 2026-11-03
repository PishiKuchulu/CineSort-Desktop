package com.cinesort.desktop.data.metadata

import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.MetadataInfo
import com.cinesort.core.domain.planner.RatingSource
import com.cinesort.core.parser.NormalizedKey
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Which rating the folder name should prefer (Android: AppSettings). */
enum class RatingSourcePreference { ANY, IMDB_ONLY }

/** User-supplied provider keys; either may be absent (spec section 9.1). */
data class ApiKeys(
    val tmdbKey: String? = null,
    val omdbKey: String? = null,
) {
    val hasTmdb: Boolean get() = !tmdbKey.isNullOrBlank()
    val hasOmdb: Boolean get() = !omdbKey.isNullOrBlank()
}

/** One candidate for the preview edit sheet's metadata search. */
data class MetadataCandidate(
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingSource: RatingSource? = null,
    val imdbId: String? = null,
    /** Display label of the provider that produced this candidate ("TMDB" / "OMDb"). */
    val provider: String = "",
)

/** One unique lookup request for [MetadataService.lookupAll]. */
data class MetadataQuery(
    val type: ItemType,
    val title: String,
    val year: Int?,
    /** Map key for the result — the planner's normalized key for this item. */
    val key: String,
)

@Serializable
private data class CachedMetadata(
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingSource: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val matchedTitle: String? = null,
    /** Degraded hits (rating fell back, poster-only shell) get a short TTL. */
    val degraded: Boolean = false,
)

/**
 * TMDB/OMDb metadata enrichment (spec section 9).
 *
 * Implements the provider matrix of section 9.1, the matching rules of
 * section 9.3, and the resilience rules of section 9.4: Room cache (30 days
 * for hits, 24 hours for degraded hits, 7 days for negative results;
 * expired rows pruned and the table capped per scan batch), at
 * most 2 retries with exponential backoff for IO/5xx failures, one
 * `Retry-After`-honouring retry on 429, OMDb quota exhaustion persisted
 * until the next UTC midnight, a TMDB→OMDb search fallback (review M-D),
 * and a circuit breaker that short-circuits live lookups after 3 consecutive
 * network failures. Context-free: Retrofit instances are built internally
 * from the supplied base URLs and the per-call [ApiKeys].
 */
class DesktopMetadataService(
    private val ratingSourcePreference: RatingSourcePreference = RatingSourcePreference.ANY,
    private val genreNormalization: Boolean = true,
    private val tmdbBaseUrl: String = TMDB_BASE_URL,
    private val omdbBaseUrl: String = OMDB_BASE_URL,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * In-memory cache entry (Android persists these in Room; the
     * desktop keeps the same TTL semantics in memory — entries do not
     * survive an app restart, a documented v1 simplification).
     */
    private data class CacheEntry(
        val payloadJson: String,
        val fetchedAt: Long,
        val negative: Boolean,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** OMDb quota exhaustion, held in memory until the next UTC midnight. */
    @Volatile
    private var omdbExhaustedUntil: Long = 0L

    private sealed interface LookupResult {
        data class Found(val info: MetadataInfo) : LookupResult
        data object NotFound : LookupResult
        /** Provider unavailable (offline after retries, bad key, quota): never cached. */
        data object Unavailable : LookupResult
    }

    private class OmdbQuotaException : Exception("OMDb request limit reached")

    // Circuit breaker (review M-D): after CIRCUIT_FAILURE_THRESHOLD consecutive
    // network failures, live lookups short-circuit to unavailable for the rest
    // of this service instance's lifetime (the app-scoped "session"), so an
    // offline scan stops stalling on per-item retries. Any provider answer —
    // found OR not-found — resets the counter. OMDb quota exhaustion is a
    // provider answer, not a network failure, and never trips the breaker.
    private val consecutiveNetworkFailures = AtomicInteger(0)

    /** True while the circuit breaker is open (live lookups are short-circuited). */
    val circuitOpen: Boolean
        get() = consecutiveNetworkFailures.get() >= CIRCUIT_FAILURE_THRESHOLD

    private fun noteLiveAnswer() {
        consecutiveNetworkFailures.set(0)
    }

    private fun noteNetworkFailure() {
        consecutiveNetworkFailures.incrementAndGet()
    }

    // ------------------------------------------------------------ public API

    suspend fun enrich(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): MetadataInfo? {
        val cleanKeys = keys.normalized()
        if (!cleanKeys.hasTmdb && !cleanKeys.hasOmdb) return null
        val providerLabel = when {
            cleanKeys.hasTmdb && cleanKeys.hasOmdb -> "TMDB_OMDB"
            cleanKeys.hasTmdb -> "TMDB"
            else -> "OMDB"
        }
        val ratingSourceLabel = ratingSourcePreference.name
        val cacheKey = cacheKey(type, title, year, providerLabel, ratingSourceLabel)
        val cached = readCache(cacheKey)
        if (cached.hit) return cached.info
        // Breaker open: serve from cache only (above) and skip the network.
        if (circuitOpen) return null

        val result = try {
            lookupLive(type, title, year, cleanKeys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteNetworkFailure()
            LookupResult.Unavailable
        }
        return when (result) {
            is LookupResult.Found -> {
                writeCache(
                    cacheKey, providerLabel, result.info,
                    negative = false, degraded = isDegraded(result.info, cleanKeys),
                )
                result.info
            }
            LookupResult.NotFound -> {
                writeCache(cacheKey, providerLabel, null, negative = true, degraded = false)
                null
            }
            LookupResult.Unavailable -> null
        }
    }

    /**
     * A hit is degraded when its rating fell back to TMDB although an OMDb key
     * exists (OMDb quota/outage/no IMDb rating — the preferred rating under
     * both preferences), or when it is a poster-only shell with neither a
     * rating nor genres. Degraded hits are cached for a day, not 30 days, so a
     * recovered provider can replace them quickly (review M-D).
     */
    private fun isDegraded(info: MetadataInfo, keys: ApiKeys): Boolean {
        if (info.rating != null && info.ratingSource == RatingSource.TMDB && keys.hasOmdb) {
            return true
        }
        return info.posterUrl != null && info.rating == null && info.genres.isEmpty()
    }

    /** Drops every cached provider payload, positive and negative. */
    suspend fun clearCache() {
        cache.clear()
    }

    suspend fun searchCandidates(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): List<MetadataCandidate> {
        val cleanKeys = keys.normalized()
        if (!cleanKeys.hasTmdb && !cleanKeys.hasOmdb) return emptyList()
        if (cleanKeys.hasTmdb) {
            val tmdb = try {
                tmdbCandidates(type, title, year, cleanKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (tmdb.isNotEmpty() || !cleanKeys.hasOmdb) return tmdb
            // TMDB found nothing (or is down): a user holding both keys
            // still gets candidates from OMDb instead of an empty list
            // (audit A.3-9 — provider choice used to be key-presence only,
            // so items missing from TMDB showed no candidates at all).
        }
        return try {
            omdbCandidates(type, title, year, cleanKeys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun testTmdbKey(key: String): Result<String> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("TMDB key is empty"))
        return try {
            val api = tmdbApi(ApiKeys(tmdbKey = key.trim()))
            api.searchMovie(query = "Inception", year = 2010)
            Result.success("TMDB key is valid")
        } catch (e: HttpException) {
            Result.failure(
                Exception(
                    when (e.code()) {
                        401, 403 -> "TMDB key was rejected (HTTP ${e.code()})"
                        else -> "TMDB request failed (HTTP ${e.code()})"
                    },
                ),
            )
        } catch (e: Exception) {
            Result.failure(Exception("TMDB request failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    suspend fun testOmdbKey(key: String): Result<String> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("OMDb key is empty"))
        return try {
            val api = omdbApi()
            val response = api.byTitle(apiKey = key.trim(), title = "Inception", year = 2010, type = "movie")
            when {
                response.isSuccess -> Result.success("OMDb key is valid")
                response.error?.contains("Invalid API key", ignoreCase = true) == true ->
                    Result.failure(Exception("OMDb key was rejected: ${response.error}"))
                response.error?.contains("Request limit reached", ignoreCase = true) == true ->
                    Result.failure(Exception("OMDb daily request limit is reached"))
                else -> Result.failure(Exception("OMDb request failed: ${response.error ?: "unknown error"}"))
            }
        } catch (e: HttpException) {
            Result.failure(Exception("OMDb request failed (HTTP ${e.code()})"))
        } catch (e: Exception) {
            Result.failure(Exception("OMDb request failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * Batch lookup for a whole scan: de-duplicated by (type, key, year) and
     * capped at 4 concurrent provider calls (spec sections 8.3, 9.4).
     */
    suspend fun lookupAll(
        items: List<MetadataQuery>,
        keys: ApiKeys,
    ): Map<String, MetadataInfo> {
        pruneCache()
        return coroutineScope {
            val unique = items.distinctBy { Triple(it.type, it.key, it.year) }
            val semaphore = Semaphore(MAX_CONCURRENCY)
            val deferred = unique.map { query ->
                async {
                    semaphore.withPermit {
                        val info = enrich(query.type, query.title, query.year, keys)
                        if (info != null) query.key to info else null
                    }
                }
            }
            deferred.mapNotNull { it.await() }.toMap()
        }
    }

    /**
     * Cache upkeep (section-3 audit): expired rows used to be dead weight
     * forever — reads treated them as misses but nothing ever deleted
     * them, so the table grew by one row per unique title indefinitely.
     * Runs once per scan batch ([lookupAll]): negative results past their
     * 7-day TTL and anything past the 30-day success TTL are deleted, and
     * the table is capped at [MAX_CACHE_ROWS] rows, oldest first (the cap
     * also bounds how long a degraded row can linger past its 24-hour
     * TTL). Best effort — upkeep must never break a lookup.
     */
    private suspend fun pruneCache() {
        val now = nowMillis()
        cache.entries.removeIf { (_, entry) ->
            now - entry.fetchedAt > SUCCESS_TTL_MS ||
                (entry.negative && now - entry.fetchedAt > NEGATIVE_TTL_MS)
        }
        val excess = cache.size - MAX_CACHE_ROWS
        if (excess > 0) {
            cache.entries.sortedBy { it.value.fetchedAt }.take(excess)
                .forEach { cache.remove(it.key) }
        }
    }

    // ------------------------------------------------------------ live lookups

    /**
     * Provider order (review M-D): TMDB first when its key exists, but a TMDB
     * error or miss now falls back to OMDb when an OMDb key exists (previously
     * OMDb was only consulted for the IMDb rating of a TMDB match). Merge
     * rule: Found beats NotFound beats Unavailable — a definitive answer from
     * either provider wins over the other provider's outage. Rating rule: the
     * finding provider's result carries its rating (the TMDB path already
     * prefers the IMDb rating via OMDb under both rating preferences, with
     * TMDB's vote as its fallback; the OMDb path carries the IMDb rating), so
     * a fallback hit simply uses the rating of the provider that found it.
     */
    private suspend fun lookupLive(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): LookupResult {
        if (!keys.hasTmdb) {
            return if (keys.hasOmdb) {
                omdbAnswer(type, title, year, keys)
            } else {
                LookupResult.NotFound
            }
        }
        val tmdbResult = try {
            tmdbLookup(type, title, year, keys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteNetworkFailure()
            LookupResult.Unavailable
        }
        if (tmdbResult is LookupResult.Found) {
            noteLiveAnswer()
            return tmdbResult
        }
        if (tmdbResult is LookupResult.NotFound) noteLiveAnswer()
        if (!keys.hasOmdb) return tmdbResult
        val omdbResult = omdbAnswer(type, title, year, keys)
        return when {
            omdbResult is LookupResult.Found -> omdbResult
            tmdbResult is LookupResult.NotFound -> LookupResult.NotFound
            else -> omdbResult
        }
    }

    /** OMDb lookup with breaker bookkeeping: quota is an answer, IO is a failure. */
    private suspend fun omdbAnswer(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): LookupResult {
        val result = try {
            omdbLookup(type, title, year, keys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteNetworkFailure()
            return LookupResult.Unavailable
        }
        // Unavailable here means OMDb quota state, not a network failure: it
        // neither trips nor resets the breaker. Found/NotFound are answers.
        if (result !is LookupResult.Unavailable) noteLiveAnswer()
        return result
    }

    private suspend fun tmdbLookup(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): LookupResult {
        val api = tmdbApi(keys)
        var search = withRetries {
            if (type == ItemType.MOVIE) api.searchMovie(title, year) else api.searchTv(title, year)
        }
        var candidates = search.results.map {
            MatchCandidate(it.displayTitle, it.year, it.popularity)
        }
        var bestIndex = MetadataMatcher.bestIndex(title, year, candidates)
        if (bestIndex == null && year != null) {
            // Retry without the year and accept a year within ±1 (spec 9.3).
            search = withRetries {
                if (type == ItemType.MOVIE) api.searchMovie(title, null) else api.searchTv(title, null)
            }
            candidates = search.results.map { MatchCandidate(it.displayTitle, it.year, it.popularity) }
            bestIndex = MetadataMatcher.bestIndex(title, year, candidates)
        }
        val chosenIndex = bestIndex ?: return LookupResult.NotFound
        val chosenItem = search.results[chosenIndex]

        var genres: List<String>
        var tmdbRating: Double?
        var imdbId: String?
        var matchedYear: Int?
        var matchedTitle: String
        var posterPath: String?
        if (type == ItemType.MOVIE) {
            val details = withRetries { api.movieDetails(chosenItem.id) }
            genres = details.genres.map { it.name }
            tmdbRating = details.voteAverage?.takeIf { it > 0.0 }
            imdbId = details.imdbId
            matchedYear = details.year ?: chosenItem.year
            matchedTitle = details.title ?: chosenItem.displayTitle
            posterPath = details.posterPath ?: chosenItem.posterPath
        } else {
            val details = withRetries { api.tvDetails(chosenItem.id) }
            genres = details.genres.map { it.name }
            tmdbRating = details.voteAverage?.takeIf { it > 0.0 }
            imdbId = details.imdbId
            matchedYear = details.year ?: chosenItem.year
            matchedTitle = details.name ?: chosenItem.displayTitle
            posterPath = details.posterPath ?: chosenItem.posterPath
        }

        if (genreNormalization) {
            genres = GenreNormalizer.normalize(genres)
        }

        // Rating selection (spec 9.1): IMDb via OMDb when possible, else TMDB.
        var rating: Double? = null
        var ratingSource: RatingSource? = null
        val wantImdbOnly = ratingSourcePreference == RatingSourcePreference.IMDB_ONLY
        if (keys.hasOmdb && imdbId != null && !omdbExhausted()) {
            try {
                val omdb = withRetries { omdbApi().byId(apiKey = keys.omdbKey.orEmpty(), imdbId = imdbId) }
                checkOmdbError(omdb.error)
                omdb.ratingValue?.let {
                    rating = it
                    ratingSource = RatingSource.IMDB
                }
            } catch (e: OmdbQuotaException) {
                markOmdbExhausted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        if (rating == null && !wantImdbOnly) {
            rating = tmdbRating
            ratingSource = if (tmdbRating != null) RatingSource.TMDB else null
        }

        val filledYear = when {
            year != null -> year
            else -> MetadataMatcher.fillYearCandidate(title, candidates)?.year
                ?: matchedYear?.takeIf {
                    TitleSimilarity.similarity(title, matchedTitle) >= MetadataMatcher.FILL_YEAR_THRESHOLD
                }
        }

        return LookupResult.Found(
            MetadataInfo(
                genres = genres,
                rating = rating,
                ratingSource = ratingSource,
                year = filledYear,
                posterUrl = posterPath?.let { POSTER_BASE_URL + it },
                matchedTitle = matchedTitle,
            ),
        )
    }

    private suspend fun omdbLookup(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): LookupResult {
        if (omdbExhausted()) return LookupResult.Unavailable
        val api = omdbApi()
        val omdbType = if (type == ItemType.MOVIE) "movie" else "series"
        val key = keys.omdbKey.orEmpty()
        try {
            val direct = withRetries { api.byTitle(apiKey = key, title = title, year = year, type = omdbType) }
            checkOmdbError(direct.error)
            if (direct.isSuccess &&
                MetadataMatcher.accepts(title, year, direct.title.orEmpty(), direct.yearValue)
            ) {
                return LookupResult.Found(direct.toMetadataInfo())
            }
            if (direct.error?.contains("Invalid API key", ignoreCase = true) == true) {
                return LookupResult.Unavailable
            }
            // Fallback: search, pick by similarity, then fetch by IMDb id (spec 9.2).
            val search = withRetries { api.search(apiKey = key, query = title, year = year, type = omdbType) }
            checkOmdbError(search.error)
            if (!search.isSuccess) return LookupResult.NotFound
            val candidates = search.search.map { MatchCandidate(it.title.orEmpty(), it.yearValue) }
            val bestIndex = MetadataMatcher.bestIndex(title, year, candidates)
                ?: return LookupResult.NotFound
            val imdbId = search.search[bestIndex].imdbId ?: return LookupResult.NotFound
            val details = withRetries { api.byId(apiKey = key, imdbId = imdbId) }
            checkOmdbError(details.error)
            if (!details.isSuccess) return LookupResult.NotFound
            return LookupResult.Found(details.toMetadataInfo())
        } catch (e: OmdbQuotaException) {
            markOmdbExhausted()
            return LookupResult.Unavailable
        }
    }

    private suspend fun OmdbTitleResponse.toMetadataInfo(): MetadataInfo {
        val genres = if (genreNormalization) {
            GenreNormalizer.normalizeFromCsv(genre)
        } else {
            genre?.takeIf { it != "N/A" }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
        return MetadataInfo(
            genres = genres,
            rating = ratingValue,
            ratingSource = if (ratingValue != null) RatingSource.IMDB else null,
            year = yearValue,
            // The poster URL must flow into the final info like the TMDB
            // path does — without this, OMDb-matched items silently never
            // got a poster.jpg (device report on v1.0.8; fixed in 1.0.9).
            posterUrl = poster?.takeIf { it != "N/A" },
            matchedTitle = title,
        )
    }

    private fun checkOmdbError(error: String?) {
        if (error != null && error.contains("Request limit reached", ignoreCase = true)) {
            throw OmdbQuotaException()
        }
    }

    // ------------------------------------------------------------ candidates

    private suspend fun tmdbCandidates(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): List<MetadataCandidate> {
        val api = tmdbApi(keys)
        val search = withRetries {
            if (type == ItemType.MOVIE) api.searchMovie(title, year) else api.searchTv(title, year)
        }
        return search.results.take(MAX_CANDIDATES).map { item ->
            MetadataCandidate(
                title = item.displayTitle,
                year = item.year,
                posterUrl = item.posterPath?.let { POSTER_BASE_URL + it },
                rating = item.voteAverage?.takeIf { it > 0.0 },
                ratingSource = if (item.voteAverage != null) RatingSource.TMDB else null,
                provider = "TMDB",
            )
        }
    }

    private suspend fun omdbCandidates(
        type: ItemType,
        title: String,
        year: Int?,
        keys: ApiKeys,
    ): List<MetadataCandidate> {
        if (omdbExhausted()) return emptyList()
        val api = omdbApi()
        val omdbType = if (type == ItemType.MOVIE) "movie" else "series"
        val search = try {
            val result = withRetries {
                api.search(apiKey = keys.omdbKey.orEmpty(), query = title, year = year, type = omdbType)
            }
            checkOmdbError(result.error)
            result
        } catch (e: OmdbQuotaException) {
            markOmdbExhausted()
            return emptyList()
        }
        if (!search.isSuccess) return emptyList()
        return search.search.take(MAX_CANDIDATES).map { item ->
            MetadataCandidate(
                title = item.title.orEmpty(),
                year = item.yearValue,
                posterUrl = item.poster?.takeIf { it != "N/A" },
                imdbId = item.imdbId,
                provider = "OMDb",
            )
        }
    }

    // ------------------------------------------------------------ cache

    private fun cacheKey(
        type: ItemType,
        title: String,
        year: Int?,
        providerLabel: String,
        ratingSourceLabel: String,
    ): String {
        val normalized = if (type == ItemType.MOVIE) {
            NormalizedKey.movieKey(title, year)
        } else {
            NormalizedKey.seriesKey(title)
        }
        // The rating-source preference is part of the key (review M-D): the
        // same title can legitimately cache different ratings per preference.
        return "${type.name}|$normalized|${year?.toString() ?: ""}|$providerLabel|$ratingSourceLabel"
    }

    /** Cache read outcome: [hit] separates a cached negative (info = null) from a miss. */
    private data class CacheRead(val hit: Boolean, val info: MetadataInfo?)

    private suspend fun readCache(cacheKey: String): CacheRead {
        val entity = cache[cacheKey] ?: return CacheRead(hit = false, info = null)
        if (entity.negative) {
            if (nowMillis() - entity.fetchedAt > NEGATIVE_TTL_MS) {
                return CacheRead(hit = false, info = null)
            }
            return CacheRead(hit = true, info = null)
        }
        return try {
            val cached = CineSortJson.decodeFromString<CachedMetadata>(entity.payloadJson)
            // Degraded hits expire after a day instead of 30 days (review M-D).
            val ttl = if (cached.degraded) DEGRADED_TTL_MS else SUCCESS_TTL_MS
            if (nowMillis() - entity.fetchedAt > ttl) {
                CacheRead(hit = false, info = null)
            } else {
                CacheRead(
                    hit = true,
                    info = MetadataInfo(
                        genres = cached.genres,
                        rating = cached.rating,
                        ratingSource = cached.ratingSource?.let {
                            runCatching { RatingSource.valueOf(it) }.getOrNull()
                        },
                        year = cached.year,
                        posterUrl = cached.posterUrl,
                        matchedTitle = cached.matchedTitle,
                    ),
                )
            }
        } catch (e: Exception) {
            CacheRead(hit = false, info = null)
        }
    }

    private suspend fun writeCache(
        cacheKey: String,
        providerLabel: String,
        info: MetadataInfo?,
        negative: Boolean,
        degraded: Boolean,
    ) {
        val payload = if (info == null) {
            ""
        } else {
            CineSortJson.encodeToString(
                CachedMetadata(
                    genres = info.genres,
                    rating = info.rating,
                    ratingSource = info.ratingSource?.name,
                    year = info.year,
                    posterUrl = info.posterUrl,
                    matchedTitle = info.matchedTitle,
                    degraded = degraded,
                ),
            )
        }
        cache[cacheKey] = CacheEntry(
            payloadJson = payload,
            fetchedAt = nowMillis(),
            negative = negative,
        )
    }

    // ------------------------------------------------------------ OMDb quota


    private fun omdbExhausted(): Boolean = nowMillis() < omdbExhaustedUntil

    private fun markOmdbExhausted() {
        val now = nowMillis()
        omdbExhaustedUntil = Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    // ------------------------------------------------------------ HTTP plumbing

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        var retryAfterUsed = false
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                when {
                    e.code() == 429 && !retryAfterUsed -> {
                        retryAfterUsed = true
                        val retryAfterSeconds = e.response()?.headers()?.get("Retry-After")
                            ?.toLongOrNull()?.coerceIn(1, 15) ?: 2L
                        delay(retryAfterSeconds * 1000)
                    }
                    e.code() in 500..599 && attempt < MAX_RETRIES -> {
                        attempt++
                        delay(BASE_BACKOFF_MS shl (attempt - 1))
                    }
                    else -> throw e
                }
            } catch (e: IOException) {
                if (attempt < MAX_RETRIES) {
                    attempt++
                    delay(BASE_BACKOFF_MS shl (attempt - 1))
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * TMDB auth (spec 9.1): a v4 read-access token (JWT, `eyJ` prefix) goes in
     * the Authorization header; a v3 key goes in the api_key query parameter.
     */
    private class TmdbAuthInterceptor(private val key: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val builder = request.newBuilder()
            if (key.startsWith(V4_TOKEN_PREFIX)) {
                builder.header("Authorization", "Bearer $key")
            } else if (request.url.queryParameter("api_key") == null) {
                val url = request.url.newBuilder().addQueryParameter("api_key", key).build()
                builder.url(url)
            }
            return chain.proceed(builder.build())
        }
    }

    private fun redactingLogger(): HttpLoggingInterceptor {
        // Desktop has no Timber; the redacted log line is simply dropped.
        val logger = HttpLoggingInterceptor { message -> redactSecrets(message) }
        logger.level = HttpLoggingInterceptor.Level.BASIC
        logger.redactHeader("Authorization")
        return logger
    }

    // API clients are cached (review M7): building an OkHttpClient + Retrofit per
    // call — even per retry — threw away connection pools and churned threads.
    @Volatile
    private var tmdbApiCache: Pair<String, TmdbApi>? = null

    @Volatile
    private var omdbApiCache: OmdbApi? = null

    private fun tmdbApi(keys: ApiKeys): TmdbApi {
        val key = keys.tmdbKey.orEmpty()
        val cached = tmdbApiCache
        if (cached != null && cached.first == key) return cached.second
        return buildTmdbApi(keys).also { tmdbApiCache = key to it }
    }

    private fun omdbApi(): OmdbApi =
        omdbApiCache ?: buildOmdbApi().also { omdbApiCache = it }

    private fun buildOkHttp(vararg interceptors: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply { interceptors.forEach { addInterceptor(it) } }
            .addInterceptor(redactingLogger())
            .build()

    private fun buildTmdbApi(keys: ApiKeys): TmdbApi {
        val key = keys.tmdbKey.orEmpty()
        val client = buildOkHttp(TmdbAuthInterceptor(key))
        return Retrofit.Builder()
            .baseUrl(tmdbBaseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(CineSortJson.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(TmdbApi::class.java)
    }

    private fun buildOmdbApi(): OmdbApi =
        Retrofit.Builder()
            .baseUrl(omdbBaseUrl.ensureTrailingSlash())
            .client(buildOkHttp())
            .addConverterFactory(CineSortJson.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(OmdbApi::class.java)

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private fun ApiKeys.normalized(): ApiKeys = ApiKeys(
        tmdbKey = tmdbKey?.trim()?.takeIf { it.isNotEmpty() },
        omdbKey = omdbKey?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
        const val OMDB_BASE_URL = "https://www.omdbapi.com/"
        const val POSTER_BASE_URL = "https://image.tmdb.org/t/p/w185"
        private const val V4_TOKEN_PREFIX = "eyJ"
        private const val MAX_CONCURRENCY = 4
        private const val MAX_RETRIES = 2
        private const val MAX_CANDIDATES = 10
        private const val BASE_BACKOFF_MS = 500L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val DEGRADED_TTL_MS = 24L * 60 * 60 * 1000
        private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val CIRCUIT_FAILURE_THRESHOLD = 3

        /** Row cap for the metadata cache; oldest rows evicted first. */
        private const val MAX_CACHE_ROWS = 2_000
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()


        /** Redacts api keys and bearer tokens from any log line (spec section 14). */
        fun redactSecrets(message: String): String =
            message
                .replace(Regex("""(api_key|apikey)=[^&\s"']+"""), "$1=REDACTED")
                .replace(Regex("""(Authorization:\s*Bearer\s+)[^\s"']+"""), "$1REDACTED")
    }
}
