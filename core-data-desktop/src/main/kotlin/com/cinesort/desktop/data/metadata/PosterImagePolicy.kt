package com.cinesort.desktop.data.metadata

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/**
 * Pure decision logic for poster downloads (review M-A): which URLs may be
 * fetched at all, how much may be read from a response, and which payloads
 * count as images. Kept free of Android and concrete-network types so it is
 * unit-testable on the JVM; [PosterDownloader] is the only caller.
 */
object PosterImagePolicy {

    const val MAX_BYTES = 4 * 1024 * 1024

    /** Poster CDNs of the two metadata providers; nothing else is fetched. */
    private val ALLOWED_HOSTS = setOf("image.tmdb.org", "m.media-amazon.com")

    /**
     * True only for https URLs on an allowed poster host. User-info tricks
     * (`https://image.tmdb.org@evil.example/…`) resolve to the real host and
     * are refused, as are look-alike subdomains.
     */
    fun isAllowedUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme?.equals("https", ignoreCase = true) == true && host in ALLOWED_HOSTS
    }

    /** JPEG (FF D8 FF) or PNG (89 50 4E 47) signature check. */
    fun hasImageMagic(bytes: ByteArray): Boolean =
        startsWith(bytes, JPEG_MAGIC) || startsWith(bytes, PNG_MAGIC)

    /**
     * Reads at most [maxBytes] + 1 bytes from [input] and returns them, or
     * null when the stream holds more than [maxBytes] or nothing at all.
     * The stream is never buffered beyond the cap plus a single byte.
     */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total <= maxBytes) {
            val want = minOf(buffer.size, maxBytes + 1 - total)
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            total += read
            out.write(buffer, 0, read)
        }
        if (total > maxBytes) return null
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private const val BUFFER_SIZE = 8192

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
}
