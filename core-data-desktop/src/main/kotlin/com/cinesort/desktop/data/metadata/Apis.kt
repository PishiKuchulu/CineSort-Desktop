package com.cinesort.desktop.data.metadata

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB API (spec section 9.2). Authentication is handled by an OkHttp
 * interceptor in [DesktopMetadataService]: a v3 key is added as the api_key query
 * parameter, a v4 read-access token as the Authorization Bearer header, so
 * neither appears in these signatures or in call sites.
 */
interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int?,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("language") language: String = "en-US",
    ): TmdbSearchResponse

    @GET("movie/{id}")
    suspend fun movieDetails(
        @Path("id") id: Long,
        @Query("language") language: String = "en-US",
    ): TmdbMovieDetails

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") firstAirDateYear: Int?,
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("language") language: String = "en-US",
    ): TmdbSearchResponse

    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Long,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String = "external_ids",
    ): TmdbTvDetails
}

/** OMDb API (spec section 9.2); the apikey travels as an explicit parameter. */
interface OmdbApi {

    @GET("/")
    suspend fun byTitle(
        @Query("apikey") apiKey: String,
        @Query("t") title: String,
        @Query("y") year: Int?,
        @Query("type") type: String,
    ): OmdbTitleResponse

    @GET("/")
    suspend fun search(
        @Query("apikey") apiKey: String,
        @Query("s") query: String,
        @Query("y") year: Int?,
        @Query("type") type: String,
    ): OmdbSearchResponse

    @GET("/")
    suspend fun byId(
        @Query("apikey") apiKey: String,
        @Query("i") imdbId: String,
    ): OmdbTitleResponse
}
