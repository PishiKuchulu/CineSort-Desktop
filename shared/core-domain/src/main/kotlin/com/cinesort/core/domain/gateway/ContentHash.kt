package com.cinesort.core.domain.gateway

import java.security.MessageDigest

/**
 * SHA-256 helpers for the verified-copy path (section-4 audit): a
 * cross-volume copy is only "verified" once the bytes that landed on the
 * destination hash to the same value as the bytes read from the source —
 * a size match alone lets silent content corruption (bad SD card, broken
 * provider) through right before the executor deletes the source.
 */
object ContentHash {

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** Lowercase hex of a finished digest. */
    fun hex(digest: MessageDigest): String = digest.digest().toHexString()

    fun sha256Hex(bytes: ByteArray): String {
        val digest = newDigest()
        digest.update(bytes)
        return hex(digest)
    }

    private fun ByteArray.toHexString(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt()
            out.append(HEX_DIGITS[value shr 4 and 0xF])
            out.append(HEX_DIGITS[value and 0xF])
        }
        return out.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
