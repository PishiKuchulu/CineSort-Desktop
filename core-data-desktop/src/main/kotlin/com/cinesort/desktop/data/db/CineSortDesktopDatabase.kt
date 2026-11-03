package com.cinesort.desktop.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers

/**
 * The desktop database. Entity definitions are the Android app's
 * (journal tables first; library/overrides/metadata tables join with
 * their features), but the desktop database versions independently:
 * it starts at version 1 with the journal schema and grows by explicit
 * migrations as features land, instead of inheriting Android's
 * migration history.
 */
@Database(
    entities = [RunEntity::class, OperationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CineSortDesktopDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
}

/** Creates (or opens) the database at [file], creating parent dirs. */
fun createDesktopDatabase(file: Path): CineSortDesktopDatabase {
    file.parent?.let { java.nio.file.Files.createDirectories(it) }
    return Room.databaseBuilder<CineSortDesktopDatabase>(name = file.toString())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
