package com.emofah.agencies.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.emofah.agencies.data.local.dao.AuditDao
import com.emofah.agencies.data.local.dao.FinancialAndDraftDao
import com.emofah.agencies.data.local.dao.ProductDao
import com.emofah.agencies.data.local.dao.SaleDao
import com.emofah.agencies.data.local.dao.SyncQueueDao
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity

@Database(
    entities = [
        ProductEntity::class,
        ProductReferencePhotoEntity::class,
        LocalStockProjectionEntity::class,
        LocalSaleEntity::class,
        LocalSaleItemEntity::class,
        PendingFinancialEventEntity::class,
        PendingProductDraftEntity::class,
        SyncQueueEventEntity::class,
        LocalAuditEventEntity::class,
    ],
    version = EmofahDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class EmofahDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun saleDao(): SaleDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun financialAndDraftDao(): FinancialAndDraftDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DATABASE_NAME = "emofah_agencies.db"

        @Volatile
        private var instance: EmofahDatabase? = null

        fun getInstance(context: Context): EmofahDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(appContext: Context): EmofahDatabase {
            return Room.databaseBuilder(
                appContext,
                EmofahDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*EmofahDatabaseMigrations.ALL_MIGRATIONS)
                .addCallback(
                    object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            EmofahDatabaseMigrations.enableForeignKeys(db)
                        }
                    }
                )
                .build()
        }
    }
}
