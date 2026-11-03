package com.cinesort.desktop.data.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON configuration for provider payloads and the metadata cache. */
val CineSortJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

// --------------------------------------------------------------------- TMDB

@Serializable
data class TmdbSearchResponse(
    val results: List<TmdbSearchItem> = emptyList(),
)

@Serializable
data class TmdbSearchItem(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbGenre(
    val name: String,
)

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
data class TmdbMovieDetails(
    val id: Long,
    val title: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbTvDetails(
    val id: Long,
    val name: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val year: Int? get() = firstAirDate?.take(4)?.toIntOrNull()
    val imdbId: String? get() = externalIds?.imdbId
}

// --------------------------------------------------------------------- OMDb

@Serializable
data class OmdbTitleResponse(
    @SerialName("Title") val title: String? = null,
    @SerialName("Year") val year: String? = null,
    @SerialName("Genre") val genre: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdbID") val imdbId: String? = null,
    // The API returns Poster on title/id lookups too; the DTO never
    // declared it, so OMDb posters were dropped at parse time (v1.0.9).
    @SerialName("Poster") val poster: String? = null,
    @SerialName("Response") val response: String? = null,
    @SerialName("Error") val error: String? = null,
) {
    val isSuccess: Boolean get() = response.equals("True", ignoreCase = true)
    val yearValue: Int? get() = year?.take(4)?.toIntOrNull()
    val ratingValue: Double? get() = imdbRating?.takeIf { it != "N/A" }?.toDoubleOrNull()
}

@Serializable
data class OmdbSearchItem(
    @SerialName("Title") val title: String? = null,
    @SerialName("Year") val year: String? = null,
    @SerialName("imdbID") val imdbId: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Poster") val poster: String? = null,
) {
    val yearValue: Int? get() = year?.take(4)?.toIntOrNull()
}

@Serializable
data class OmdbSearchResponse(
    @SerialName("Search") val search: List<OmdbSearchItem> = emptyList(),
    @SerialName("totalResults") val totalResults: String? = null,
    @SerialName("Response") val response: String? = null,
    @SerialName("Error") val error: String? = null,
) {
    val isSuccess: Boolean get() = response.equals("True", ignoreCase = true)
}
