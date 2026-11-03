package com.cinesort.desktop.data.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/**
 * Why an update operation failed, in the only terms the UI needs.
 * (Ported from the Android updater.)
 */
enum class UpdateFailure { NETWORK, CHECKSUM, GENERIC }

/** An update failure classified by [UpdateFailure] for the UI layer. */
class UpdateException(
    val failure: UpdateFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A newer release published on GitHub, with this OS's package asset. */
data class DesktopUpdateInfo(
    val version: String,
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Url: String? = null,
    val expectedSha256: String? = null,
)

/** Strict `vX.Y.Z` version parsing/comparison for release tags. */
internal object VersionNumbers {
    fun parse(tag: String): List<Int>? {
        val core = tag.trim().removePrefix("v").removePrefix("V")
        val parts = core.split('.')
        if (parts.size != 3) return null
        return parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until 3) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }
}

@Serializable
private data class GhRelease(
    val tag_name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

private val GhJson = Json { ignoreUnknownKeys = true }

/**
 * In-app update check against the desktop app's own distribution
 * channel — the versioned GitHub releases of
 * PishiKuchulu/CineSort-Desktop. Deliberately NOT `/releases/latest`:
 * the list is scanned for strict `vX.Y.Z` tags and the highest wins,
 * so a rolling or stray release can never shadow a versioned one.
 *
 * Lessons baked in from the Android updater's history: a non-2xx
 * response is an ERROR carrying its HTTP code (v1.0.7/v1.0.29 — never
 * a silent "up to date"), and callers stamp their last-check time only
 * after a check COMPLETES, never before it starts.
 */
object DesktopUpdateChecker {

    const val RELEASES_URL =
        "https://api.github.com/repos/PishiKuchulu/CineSort-Desktop/releases?per_page=20"
    private const val USER_AGENT = "CineSort-Desktop-Updater"

    /** Automatic (on-launch) checks run at most once per day; manual
     *  checks from Settings are never throttled. */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun isAutoCheckDue(lastCheckAtMillis: Long, nowMillis: Long): Boolean =
        lastCheckAtMillis <= 0L || nowMillis - lastCheckAtMillis >= AUTO_CHECK_INTERVAL_MS

    /** The HTTP status code embedded in an update error message ("(HTTP 403)"). */
    fun httpCodeIn(message: String?): Int? =
        message?.let {
            Regex("""\(HTTP (\d{3})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

    /** The only hosts a release-download redirect may land on. */
    internal fun isAllowedUpdateRedirectHost(host: String?): Boolean =
        host == "github.com" ||
            host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com"

    /** The package extension for the current OS. */
    fun osAssetExtension(osName: String = System.getProperty("os.name").orEmpty()): String =
        when {
            osName.startsWith("Windows", ignoreCase = true) -> ".exe"
            osName.startsWith("Mac", ignoreCase = true) -> ".dmg"
            else -> ".deb"
        }

    /** Fetch the expected SHA-256 text for [info] (best-effort; null on failure). */
    suspend fun fetchExpectedSha256(info: DesktopUpdateInfo): String? =
        withContext(Dispatchers.IO) {
            val url = info.sha256Url?.takeIf { it.isNotBlank() } ?: return@withContext null
            runCatching {
                requireDesktopReleaseUrl(url)
                httpGet(url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            }.getOrNull()
        }

    /**
     * The newest release strictly newer than [currentVersion] with an
     * asset for [assetExtension], or null. Throws [UpdateException] on
     * HTTP failures — a failed check is never reported as "up to date".
     */
    suspend fun fetchLatestNewerThan(
        currentVersion: String,
        assetExtension: String = osAssetExtension(),
        releasesUrl: String = RELEASES_URL,
    ): DesktopUpdateInfo? = withContext(Dispatchers.IO) {
        val releases = GhJson.decodeFromString<List<GhRelease>>(httpGet(releasesUrl))
        var best: DesktopUpdateInfo? = null
        for (rel in releases) {
            if (rel.draft || rel.prerelease) continue
            val version = VersionNumbers.parse(rel.tag_name)?.joinToString(".") ?: continue
            if (!VersionNumbers.isNewer(version, currentVersion)) continue
            val current = best
            if (current != null && !VersionNumbers.isNewer(version, current.version)) continue
            val asset = pickAsset(rel.assets, assetExtension) ?: continue
            val shaAsset = rel.assets.firstOrNull { it.name == "${asset.name}.sha256" }
            best = DesktopUpdateInfo(
                version = version,
                tag = rel.tag_name,
                assetName = asset.name,
                downloadUrl = asset.browser_download_url,
                sizeBytes = asset.size,
                sha256Url = shaAsset?.browser_download_url?.takeIf { it.isNotBlank() },
            )
        }
        best
    }

    private fun pickAsset(assets: List<GhAsset>, extension: String): GhAsset? =
        assets.firstOrNull { it.name.endsWith(extension, ignoreCase = true) }

    /**
     * Downloads [url] to [dest] (via a `.part` sibling, renamed on
     * success), reporting (downloaded, total) progress. The checksum is
     * MANDATORY (a release without a published `.sha256` asset is
     * refused), both URLs must be CineSort-Desktop release-download
     * URLs, and the hash is computed in the same pass as the download.
     */
    suspend fun download(
        url: String,
        dest: File,
        sha256Url: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        requireDesktopReleaseUrl(url)
        if (sha256Url.isNullOrBlank()) {
            throw UpdateException(
                UpdateFailure.CHECKSUM,
                "Update has no published checksum — refusing to install.",
            )
        }
        requireDesktopReleaseUrl(sha256Url)
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw UpdateException(
                        UpdateFailure.GENERIC,
                        "Download failed (HTTP $code)",
                    )
                }
                val finalHost = conn.url.host
                if (!isAllowedUpdateRedirectHost(finalHost)) {
                    throw UpdateException(
                        UpdateFailure.GENERIC,
                        "Update download redirected to an unexpected host: $finalHost",
                    )
                }
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, dest.name + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, if (total > 0) total else downloaded)
                        }
                        if (total > 0 && downloaded != total) {
                            throw UpdateException(
                                UpdateFailure.CHECKSUM,
                                "Download incomplete ($downloaded of $total bytes).",
                            )
                        }
                    }
                }
                val expected = httpGet(sha256Url).trim()
                    .split(Regex("\\s+")).firstOrNull()?.lowercase()
                if (expected.isNullOrBlank() || !expected.matches(Regex("[0-9a-f]{64}"))) {
                    throw UpdateException(
                        UpdateFailure.CHECKSUM,
                        "Could not read a valid update checksum.",
                    )
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    throw UpdateException(
                        UpdateFailure.CHECKSUM,
                        "Update checksum mismatch — the downloaded file is " +
                            "corrupted or incomplete.",
                    )
                }
                if (dest.exists()) dest.delete()
                check(part.renameTo(dest)) { "Could not finalize the downloaded update" }
            } catch (e: IOException) {
                throw UpdateException(
                    UpdateFailure.NETWORK,
                    "Update download failed: ${e.message}",
                    e,
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * The download URL must be one of this app's own release assets:
     * the release list comes from api.github.com, but its
     * `browser_download_url` fields are data, not trust.
     */
    private fun requireDesktopReleaseUrl(url: String) {
        val u = runCatching { URL(url) }.getOrNull()
        require(
            u != null && u.protocol == "https" && u.host == "github.com" &&
                u.path.startsWith("/PishiKuchulu/CineSort-Desktop/releases/download/"),
        ) {
            "Unexpected update URL host or path: $url"
        }
    }

    /** GET with the updater's error contract: non-2xx throws with the code. */
    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException(
                    UpdateFailure.GENERIC,
                    "Update check failed (HTTP $code)",
                )
            }
            return conn.inputStream.bufferedReader().readText()
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.NETWORK, "Update check failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
