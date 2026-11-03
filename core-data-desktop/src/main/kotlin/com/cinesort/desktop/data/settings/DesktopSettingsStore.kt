package com.cinesort.desktop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
    }
}
