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
}
