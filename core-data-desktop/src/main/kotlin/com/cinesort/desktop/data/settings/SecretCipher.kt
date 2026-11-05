package com.cinesort.desktop.data.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption at rest for the provider API keys (audit wave 1.1.0 — the
 * desktop counterpart of Android's Tink-backed store).
 *
 * Values are AES-256-GCM encrypted and stored in the settings file as
 * `enc:v1:<base64(iv‖ciphertext)>`, so the settings file itself never
 * contains a readable key. The master key is a random 256-bit value in
 * a sibling key file created owner-only (POSIX 0600 where supported).
 *
 * Honest scope: this protects the keys against disclosure of the
 * settings file (backups, sync, shoulder-surfing the protobuf) — the
 * threat the v1.0.0 audit raised. It is not a full OS-keychain
 * integration; an attacker with full read access to the user's home
 * directory can still read the key file. OS keychain/DPAPI binding
 * remains a possible future hardening step.
 *
 * Legacy values written by v1.0.0 (no `enc:` prefix) read back as
 * plaintext so existing installs keep working; the store migrates
 * them to encrypted form on first launch.
 */
class SecretCipher(
    private val keyFile: Path,
) {
    private val key: SecretKeySpec by lazy { loadOrCreateKey() }

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + sealed)
    }

    /**
     * Decrypts a stored value. Values without the [PREFIX] are legacy
     * plaintext and pass through unchanged. A value that fails GCM
     * verification (tampered, or the key file was replaced) yields
     * null — the key is then treated as unset and can be re-entered.
     */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val iv = raw.copyOfRange(0, IV_BYTES)
            val sealed = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun loadOrCreateKey(): SecretKeySpec {
        if (Files.isRegularFile(keyFile)) {
            val raw = Base64.getDecoder().decode(Files.readString(keyFile).trim())
            if (raw.size == KEY_BYTES) return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        keyFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw))
        restrictToOwner(keyFile)
        return SecretKeySpec(raw, "AES")
    }

    /** Owner-only permissions where the filesystem supports them. */
    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private companion object {
        const val PREFIX = "enc:v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
    }
}
