package com.cinesort.desktop.data.work

import com.cinesort.desktop.data.metadata.PosterImagePolicy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a matched poster image for the optional "poster in folder"
 * feature. Best effort by design: any failure returns null and the organize
 * run carries on — a poster must never fail a move.
 *
 * Hardened per review M-A: only the two providers' poster CDNs may be
 * fetched (checked before the request and again after redirects), the body
 * read is bounded by [PosterImagePolicy.MAX_BYTES], and the payload must
 * carry a JPEG or PNG signature before it is saved as `poster.jpg`.
 */
object DesktopPosterDownloader {

    private const val TIMEOUT_MS = 15_000

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // TMDB's metadata API hands out w185 thumbnails; folders deserve w500.
            val effective = url.replace("image.tmdb.org/t/p/w185/", "image.tmdb.org/t/p/w500/")
            if (!PosterImagePolicy.isAllowedUrl(effective)) {
                return@withContext null
            }
            val connection = URL(effective).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "CineSort-Desktop")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            // Redirects must not smuggle the download off the allow-list.
            if (!PosterImagePolicy.isAllowedUrl(connection.url.toString())) {
                return@withContext null
            }
            val bytes = connection.inputStream.use { PosterImagePolicy.readBounded(it) }
                ?: return@withContext null
            if (!PosterImagePolicy.hasImageMagic(bytes)) {
                return@withContext null
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }
}
