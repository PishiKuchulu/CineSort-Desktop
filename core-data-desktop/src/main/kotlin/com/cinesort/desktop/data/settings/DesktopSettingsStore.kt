package com.cinesort.desktop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * Desktop settings persistence: a DataStore Preferences file in the
 * app's data directory (the desktop counterpart of Android's
 * DataStoreSettingsRepository). This slice persists the library
 * layout — download folders and the destination — so the app reopens
 * where the user left it; scan/naming preferences join as their
 * Settings UI lands.
 */
class DesktopSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(dataDir: Path) : this(
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { dataDir.resolve(SETTINGS_FILE).toString().toPath() },
        ),
    )

    val sources: Flow<List<Path>> = dataStore.data.map { prefs ->
        prefs[SOURCES].orEmpty().sorted().map { Path.of(it) }
    }

    val destination: Flow<Path?> = dataStore.data.map { prefs ->
        prefs[DESTINATION]?.let { Path.of(it) }
    }

    /**
     * Provider API keys. NOTE: unlike Android (Tink-encrypted), the
     * desktop v1 stores keys in the local DataStore file unencrypted —
     * encryption at rest is a tracked hardening follow-up.
     */
    val tmdbKey: Flow<String?> = dataStore.data.map { it[TMDB_KEY] }
    val omdbKey: Flow<String?> = dataStore.data.map { it[OMDB_KEY] }

    /** Save poster.jpg into organized folders (Android default: off). */
    val downloadPosters: Flow<Boolean> = dataStore.data.map { it[DOWNLOAD_POSTERS] ?: false }

    suspend fun setDownloadPosters(value: Boolean) {
        dataStore.edit { prefs -> prefs[DOWNLOAD_POSTERS] = value }
    }

    /** When the last update check COMPLETED (stamped on completion only). */
    val lastUpdateCheckAt: Flow<Long> = dataStore.data.map { it[LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setLastUpdateCheckAt(epochMillis: Long) {
        dataStore.edit { prefs -> prefs[LAST_UPDATE_CHECK] = epochMillis }
    }

    suspend fun setTmdbKey(key: String?) {
        dataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(TMDB_KEY) else prefs[TMDB_KEY] = key.trim()
        }
    }

    suspend fun setOmdbKey(key: String?) {
        dataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(OMDB_KEY) else prefs[OMDB_KEY] = key.trim()
        }
    }

    suspend fun setSources(paths: List<Path>) {
        dataStore.edit { prefs ->
            prefs[SOURCES] = paths.map { it.toString() }.toSet()
        }
    }

    suspend fun setDestination(path: Path?) {
        dataStore.edit { prefs ->
            if (path == null) prefs.remove(DESTINATION) else prefs[DESTINATION] = path.toString()
        }
    }

    private companion object {
        const val SETTINGS_FILE = "settings.preferences_pb"
        val SOURCES = stringSetPreferencesKey("sources")
        val DESTINATION = stringPreferencesKey("destination")
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val OMDB_KEY = stringPreferencesKey("omdb_key")
        val DOWNLOAD_POSTERS = booleanPreferencesKey("download_posters")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
    }
}
