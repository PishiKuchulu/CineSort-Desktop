package com.cinesort.desktop.data.settings

import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The persisted library layout round-trips through the DataStore file. */
class DesktopSettingsStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun sourcesAndDestinationRoundTrip() = runBlocking {
        val store = DesktopSettingsStore(tempDir)
        assertEquals(emptyList<Path>(), store.sources.first())
        assertNull(store.destination.first())

        val a = tempDir.resolve("downloads-a")
        val b = tempDir.resolve("downloads-b")
        val library = tempDir.resolve("library")
        store.setSources(listOf(b, a))
        store.setDestination(library)

        assertEquals(listOf(a, b), store.sources.first())
        assertEquals(library, store.destination.first())

        store.setDestination(null)
        assertNull(store.destination.first())

        store.setTmdbKey("  tmdb-key  ")
        store.setOmdbKey("omdb-key")
        store.setDownloadPosters(true)
        assertEquals("tmdb-key", store.tmdbKey.first())
        assertEquals("omdb-key", store.omdbKey.first())
        assertEquals(true, store.downloadPosters.first())
        store.setTmdbKey(null)
        assertNull(store.tmdbKey.first())
    }

    @Test
    fun apiKeysAreEncryptedAtRest() = runBlocking {
        val store = DesktopSettingsStore(tempDir)
        store.setTmdbKey("tmdb-secret-123")
        // The raw settings file must never contain the readable key
        // (the v1.0.0 audit finding): values are AES-GCM sealed.
        val raw = String(
            java.nio.file.Files.readAllBytes(tempDir.resolve("settings.preferences_pb")),
            Charsets.ISO_8859_1,
        )
        org.junit.jupiter.api.Assertions.assertFalse(raw.contains("tmdb-secret-123"))
        assertEquals("tmdb-secret-123", store.tmdbKey.first())
    }

    @Test
    fun legacyPlaintextValuesStillReadAndMigrationIsIdempotent() = runBlocking {
        // v1.0.0 values carry no enc: prefix: the cipher passes them
        // through as plaintext so existing installs keep working, and
        // running the migration over already-encrypted values changes
        // nothing.
        val cipher = SecretCipher(tempDir.resolve("test-key"))
        org.junit.jupiter.api.Assertions.assertFalse(cipher.isEncrypted("plain-key"))
        assertEquals("plain-key", cipher.decrypt("plain-key"))
        val sealed = cipher.encrypt("plain-key")
        org.junit.jupiter.api.Assertions.assertTrue(cipher.isEncrypted(sealed))
        assertEquals("plain-key", cipher.decrypt(sealed))
        // Tampered ciphertext fails GCM verification -> null (unset).
        val tampered = sealed.dropLast(4) + "AAAA"
        assertNull(cipher.decrypt(tampered))

        val store = DesktopSettingsStore(tempDir)
        store.setTmdbKey("plain-key")
        store.migrateLegacyKeys()
        store.migrateLegacyKeys()
        assertEquals("plain-key", store.tmdbKey.first())
    }

    @Test
    fun scanAndNamingPreferencesRoundTrip() = runBlocking {
        val store = DesktopSettingsStore(tempDir)
        val scan = com.cinesort.core.domain.scan.ScanSettings(
            ignoreSamples = false,
            minFileAgeSeconds = 30,
            includeSubfolders = false,
            maxDepth = 2,
            moveCompanions = false,
        )
        store.setScanSettings(scan)
        assertEquals(scan, store.scanSettings.first())

        val naming = com.cinesort.core.domain.naming.NamingOptions(
            movieAddGenres = true,
            movieAddRating = true,
            seriesAddYear = true,
        )
        store.setNamingOptions(naming)
        val restored = store.namingOptions.first()
        assertEquals(true, restored.movieAddGenres)
        assertEquals(true, restored.movieAddRating)
        assertEquals(true, restored.seriesAddYear)
        assertEquals(naming.maxGenres, restored.maxGenres)
    }
}
