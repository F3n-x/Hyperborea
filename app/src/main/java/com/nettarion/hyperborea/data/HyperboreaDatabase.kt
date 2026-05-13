package com.nettarion.hyperborea.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, RideSummaryEntity::class, WorkoutSampleEntity::class, DeviceConfigEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HyperboreaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun deviceConfigDao(): DeviceConfigDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS device_configs (
                        modelNumber INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        supportedMetrics TEXT NOT NULL,
                        maxResistance INTEGER NOT NULL,
                        minResistance INTEGER NOT NULL,
                        minIncline REAL NOT NULL,
                        maxIncline REAL NOT NULL,
                        maxPower INTEGER NOT NULL,
                        minPower INTEGER NOT NULL,
                        powerStep INTEGER NOT NULL,
                        resistanceStep REAL NOT NULL,
                        inclineStep REAL NOT NULL,
                        speedStep REAL NOT NULL,
                        maxSpeed REAL NOT NULL
                    )"""
                )
            }
        }

        /**
         * Slims `profiles` down to body/identity columns only. Drops:
         *  - `useImperial` — units are now a global preference on
         *    [com.nettarion.hyperborea.core.profile.UserPreferences] so guests
         *    can pick mph/km/h too.
         *  - `enabledBroadcasts`, `overlayEnabled`, `savedSensorAddress`,
         *    `fanMode` — app-level prefs that were never per-profile in
         *    practice; they all live on `UserPreferences` now (some always did).
         *    `RoomProfileRepository.toEntity` always overwrote
         *    `enabledBroadcasts` on save, and the others were read by nothing.
         *
         * SQLite shipped on `minSdk 22` (Android 5.1, SQLite ~3.8) predates
         * `ALTER TABLE … DROP COLUMN` (3.35, 2021), so we use the table-rebuild
         * recipe.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE profiles_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        weightKg REAL,
                        heightCm INTEGER,
                        age INTEGER,
                        ftpWatts INTEGER,
                        maxHeartRate INTEGER,
                        createdAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """INSERT INTO profiles_new
                        (id, name, weightKg, heightCm, age, ftpWatts, maxHeartRate,
                         createdAt, isActive)
                        SELECT id, name, weightKg, heightCm, age, ftpWatts, maxHeartRate,
                               createdAt, isActive
                        FROM profiles"""
                )
                db.execSQL("DROP TABLE profiles")
                db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            }
        }
    }
}
