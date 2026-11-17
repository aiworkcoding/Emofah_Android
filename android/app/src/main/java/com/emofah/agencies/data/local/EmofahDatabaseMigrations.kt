package com.emofah.agencies.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Action 3.1.1 (12): Explicit non-destructive Room schema migration registry.
 * Every schema increment must add an explicit Migration(N, N+1) here rather than
 * dropping local offline transactions or pending financial records.
 */
object EmofahDatabaseMigrations {
    val ALL_MIGRATIONS: Array<Migration> = emptyArray()

    /**
     * Enables SQLite foreign key constraints on every connection open.
     */
    fun enableForeignKeys(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON;")
    }
}
