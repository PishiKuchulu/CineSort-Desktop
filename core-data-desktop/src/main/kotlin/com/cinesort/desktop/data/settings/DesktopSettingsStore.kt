package com.cinesort.desktop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.scan.ScanSettings
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * Desktop settings persistence: a DataStore Preferences file in the
 * app's data directory (the desktop counterpart of Android's
 * DataStoreSettingsRepository). Persists the library layout, the scan
 * and naming preferences, and the provider API keys — the keys
 * encrypted at rest via [SecretCipher] (see its doc for the exact
 * protection scope).
 */
class DesktopSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    constructor(dataDir: Path) : this(
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { dataDir.resolve(SETTINGS_FILE).toString().toPath() },
        ),
        SecretCipher(dataDir.resolve(KEY_FILE)),
    )

    val sources: Flow<List<Path>> = dataStore.data.map { prefs ->
        prefs[SOURCES].orEmpty().sorted().map { Path.of(it) }
    }

    val destination: Flow<Path?> = dataStore.data.map { prefs ->
        prefs[DESTINATION]?.let { Path.of(it) }
    }

    /** Provider API keys, decrypted on read (never stored readable). */
    val tmdbKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[TMDB_KEY]?.let { cipher.decrypt(it) }
    }
    val omdbKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[OMDB_KEY]?.let { cipher.decrypt(it) }
    }

    /**
     * One-time upgrade of v1.0.0 plaintext keys to encrypted form.
     * Safe to call on every launch: already-encrypted values are
     * left untouched.
     */
    suspend fun migrateLegacyKeys() {
        val prefs = dataStore.data.first()
        val tmdb = prefs[TMDB_KEY]
        val omdb = prefs[OMDB_KEY]
        if ((tmdb == null || cipher.isEncrypted(tmdb)) &&
            (omdb == null || cipher.isEncrypted(omdb))
        ) {
            return
        }
        dataStore.edit { edit ->
            tmdb?.takeIf { it.isNotBlank() && !cipher.isEncrypted(it) }?.let {
                edit[TMDB_KEY] = cipher.encrypt(it)
            }
            omdb?.takeIf { it.isNotBlank() && !cipher.isEncrypted(it) }?.let {
                edit[OMDB_KEY] = cipher.encrypt(it)
            }
        }
    }

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
            if (key.isNullOrBlank()) {
                prefs.remove(TMDB_KEY)
            } else {
                prefs[TMDB_KEY] = cipher.encrypt(key.trim())
            }
        }
    }

    suspend fun setOmdbKey(key: String?) {
        dataStore.edit { prefs ->
            if (key.isNullOrBlank()) {
                prefs.remove(OMDB_KEY)
            } else {
                prefs[OMDB_KEY] = cipher.encrypt(key.trim())
            }
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

    /** Scan preferences (F-03 controls), assembled over the raw keys. */
    val scanSettings: Flow<ScanSettings> = dataStore.data.map { prefs ->
        val defaults = ScanSettings()
        ScanSettings(
            ignoreSamples = prefs[SCAN_IGNORE_SAMPLES] ?: defaults.ignoreSamples,
            minFileAgeSeconds = prefs[SCAN_MIN_FILE_AGE] ?: defaults.minFileAgeSeconds,
            includeSubfolders = prefs[SCAN_INCLUDE_SUBFOLDERS] ?: defaults.includeSubfolders,
            maxDepth = prefs[SCAN_MAX_DEPTH] ?: defaults.maxDepth,
            moveCompanions = prefs[SCAN_MOVE_COMPANIONS] ?: defaults.moveCompanions,
        )
    }

    suspend fun setScanSettings(settings: ScanSettings) {
        dataStore.edit { prefs ->
            prefs[SCAN_IGNORE_SAMPLES] = settings.ignoreSamples
            prefs[SCAN_MIN_FILE_AGE] = settings.minFileAgeSeconds
            prefs[SCAN_INCLUDE_SUBFOLDERS] = settings.includeSubfolders
            prefs[SCAN_MAX_DEPTH] = settings.maxDepth
            prefs[SCAN_MOVE_COMPANIONS] = settings.moveCompanions
        }
    }

    /** Folder-naming preferences (F-05 controls). */
    val namingOptions: Flow<NamingOptions> = dataStore.data.map { prefs ->
        val defaults = NamingOptions()
        NamingOptions(
            movieAddYear = prefs[NAMING_MOVIE_YEAR] ?: defaults.movieAddYear,
            movieAddGenres = prefs[NAMING_MOVIE_GENRES] ?: defaults.movieAddGenres,
            movieAddRating = prefs[NAMING_MOVIE_RATING] ?: defaults.movieAddRating,
            seriesAddYear = prefs[NAMING_SERIES_YEAR] ?: defaults.seriesAddYear,
            seriesAddGenres = prefs[NAMING_SERIES_GENRES] ?: defaults.seriesAddGenres,
            seriesAddRating = prefs[NAMING_SERIES_RATING] ?: defaults.seriesAddRating,
            maxGenres = prefs[NAMING_MAX_GENRES] ?: defaults.maxGenres,
            fillMissingYear = defaults.fillMissingYear,
        )
    }

    suspend fun setNamingOptions(options: NamingOptions) {
        dataStore.edit { prefs ->
            prefs[NAMING_MOVIE_YEAR] = options.movieAddYear
            prefs[NAMING_MOVIE_GENRES] = options.movieAddGenres
            prefs[NAMING_MOVIE_RATING] = options.movieAddRating
            prefs[NAMING_SERIES_YEAR] = options.seriesAddYear
            prefs[NAMING_SERIES_GENRES] = options.seriesAddGenres
            prefs[NAMING_SERIES_RATING] = options.seriesAddRating
            prefs[NAMING_MAX_GENRES] = options.maxGenres
        }
    }

    private companion object {
        const val SETTINGS_FILE = "settings.preferences_pb"
        const val KEY_FILE = ".secret-key"
        val SOURCES = stringSetPreferencesKey("sources")
        val DESTINATION = stringPreferencesKey("destination")
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val OMDB_KEY = stringPreferencesKey("omdb_key")
        val DOWNLOAD_POSTERS = booleanPreferencesKey("download_posters")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
        val SCAN_IGNORE_SAMPLES = booleanPreferencesKey("scan_ignore_samples")
        val SCAN_MIN_FILE_AGE = longPreferencesKey("scan_min_file_age_seconds")
        val SCAN_INCLUDE_SUBFOLDERS = booleanPreferencesKey("scan_include_subfolders")
        val SCAN_MAX_DEPTH = intPreferencesKey("scan_max_depth")
        val SCAN_MOVE_COMPANIONS = booleanPreferencesKey("scan_move_companions")
        val NAMING_MOVIE_YEAR = booleanPreferencesKey("naming_movie_year")
        val NAMING_MOVIE_GENRES = booleanPreferencesKey("naming_movie_genres")
        val NAMING_MOVIE_RATING = booleanPreferencesKey("naming_movie_rating")
        val NAMING_SERIES_YEAR = booleanPreferencesKey("naming_series_year")
        val NAMING_SERIES_GENRES = booleanPreferencesKey("naming_series_genres")
        val NAMING_SERIES_RATING = booleanPreferencesKey("naming_series_rating")
        val NAMING_MAX_GENRES = intPreferencesKey("naming_max_genres")
    }
}
