package com.emofah.agencies.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Action 3.1.1 (12): Explicit non-destructive Room schema migration registry.
 * Every schema increment must add an explicit Migration(N, N+1) here rather than
 * dropping local offline transactions or pending financial records.
 */
object EmofahDatabaseMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN classification_status TEXT NOT NULL DEFAULT 'PENDING_CLASSIFICATION'")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN server_event_id TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_sale_id INTEGER")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_client_sale_id TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_service_id TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_client_service_id TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_product_id INTEGER")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_product_name TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN linked_quantity INTEGER")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN classification_note TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN classified_at_epoch_ms INTEGER")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN classified_by TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN classified_device TEXT")
            db.execSQL("ALTER TABLE pending_financial_events ADD COLUMN excludes_from_balance INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_financial_events_classification_status ON pending_financial_events (classification_status)")
            db.execSQL("UPDATE pending_financial_events SET classification_status = 'BUSINESS_SERVICE' WHERE source = 'ANDROID_SERVICE'")
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

    /**
     * Enables SQLite foreign key constraints on every connection open.
     */
    fun enableForeignKeys(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON;")
    }
}
