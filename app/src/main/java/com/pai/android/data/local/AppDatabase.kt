package com.pai.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pai.android.data.model.Attachment
import com.pai.android.data.model.Chat
import com.pai.android.data.model.DailyMemory
import com.pai.android.data.model.Message
import com.pai.android.data.model.PermanentMemory
import com.pai.android.data.model.ProviderSettings
import com.pai.android.data.model.QueryAnalysisResult
import com.pai.android.data.model.Role
import com.pai.android.data.model.Summary
import com.pai.android.data.model.WebSearchSettings
import com.pai.android.data.model.GeoTask

/**
 * Основная база данных приложения.
 */
@Database(
    entities = [
        Chat::class,
        Message::class,
        ProviderSettings::class,
        Role::class,
        Attachment::class,
        WebSearchSettings::class,
        DailyMemory::class,
        PermanentMemory::class,
        Summary::class,
        QueryAnalysisResult::class,
        GeoTask::class
    ],
    version = 19,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun chatDao(): ChatDao
    
    abstract fun messageDao(): MessageDao
    
    abstract fun providerSettingsDao(): ProviderSettingsDao
    
    abstract fun roleDao(): RoleDao
    
    abstract fun attachmentDao(): AttachmentDao
    
    abstract fun webSearchSettingsDao(): WebSearchSettingsDao
    
    abstract fun memoryDao(): MemoryDao
    
    abstract fun summaryDao(): SummaryDao

    abstract fun geoTaskDao(): GeoTaskDao

    companion object {
        const val DATABASE_NAME = "pai_database"
    }
}

// ════════════════════ MIGRATIONS ════════════════════

// Миграция с версии 1 на 2: пересоздание таблицы provider_settings
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS provider_settings")
        database.execSQL("""
            CREATE TABLE provider_settings (
                id TEXT PRIMARY KEY NOT NULL,
                provider TEXT NOT NULL,
                apiKey TEXT,
                baseUrl TEXT,
                modelName TEXT,
                isDefault INTEGER NOT NULL DEFAULT 0,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                metadata TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
    }
}

// Миграция с версии 2 на 3: гарантированное обновление схемы (destructive)
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS provider_settings")
        database.execSQL("""
            CREATE TABLE provider_settings (
                id TEXT PRIMARY KEY NOT NULL,
                provider TEXT NOT NULL,
                apiKey TEXT,
                baseUrl TEXT,
                modelName TEXT,
                isDefault INTEGER NOT NULL DEFAULT 0,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                metadata TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
    }
}

// Миграция с версии 3 на 4: финальное обновление для UUID
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS provider_settings")
        database.execSQL("""
            CREATE TABLE provider_settings (
                id TEXT PRIMARY KEY NOT NULL,
                provider TEXT NOT NULL,
                apiKey TEXT,
                baseUrl TEXT,
                modelName TEXT,
                isDefault INTEGER NOT NULL DEFAULT 0,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                metadata TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
    }
}

// Миграция с версии 4 на 5: добавление таблицы roles и поля roleId в chats
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE roles (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                systemPrompt TEXT NOT NULL,
                temperature REAL,
                maxTokens INTEGER,
                createdAt INTEGER NOT NULL,
                isDefault INTEGER NOT NULL DEFAULT 0
            )
        """)
        database.execSQL("ALTER TABLE chats ADD COLUMN roleId TEXT")
    }
}

// Миграция с версии 5 на 6: добавление таблицы attachments
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE attachments (
                id TEXT PRIMARY KEY NOT NULL,
                messageId TEXT NOT NULL,
                type TEXT NOT NULL,
                fileName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                contentBase64 TEXT,
                localPath TEXT,
                fileSize INTEGER NOT NULL DEFAULT 0,
                metadata TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY (messageId) REFERENCES messages(id) ON DELETE CASCADE
            )
        """)
        database.execSQL("CREATE INDEX idx_attachments_messageId ON attachments (messageId)")
    }
}

// Миграция с версии 6 на 7: добавление таблицы веб-поиска
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE web_search_settings (
                id TEXT PRIMARY KEY NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 0,
                provider TEXT NOT NULL,
                google_api_key TEXT,
                google_search_engine_id TEXT,
                tavily_api_key TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        database.execSQL("""
            INSERT INTO web_search_settings (id, enabled, provider, created_at, updated_at)
            VALUES ('web_search_settings', 0, 'GOOGLE', ?, ?)
        """, arrayOf(System.currentTimeMillis(), System.currentTimeMillis()))
    }
}

// Миграция с версии 7 на 8: добавление таблиц памяти
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE daily_memory (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL UNIQUE,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT ''
            )
        """)
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        database.execSQL("""
            CREATE TABLE permanent_memory (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.8,
                sourceChatId TEXT,
                sourceMessageId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
    }
}

// Миграция с версии 8 на 9: исправление индекса daily_memory
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS idx_daily_memory_date")
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
    }
}

// Миграция с версии 9 на 10: исправление имён индексов permanent_memory
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_category")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_key")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_confidence")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_category_key")
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
    }
}

// Миграция с версии 10 на 11: окончательное исправление всех индексов памяти
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS idx_daily_memory_date")
        database.execSQL("DROP INDEX IF EXISTS index_daily_memory_date")
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        val oldIndexes = listOf(
            "idx_permanent_memory_category", "idx_permanent_memory_key",
            "idx_permanent_memory_confidence", "idx_permanent_memory_category_key",
            "index_permanent_memory_category", "index_permanent_memory_key",
            "index_permanent_memory_confidence", "index_permanent_memory_category_key"
        )
        oldIndexes.forEach { indexName ->
            database.execSQL("DROP INDEX IF EXISTS $indexName")
        }
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
        println("✅ MIGRATION_10_11: Индексы памяти исправлены")
    }
}

// Миграция с версии 11 на 12: полное пересоздание таблиц памяти с правильной схемой
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_11_12: Начинаем полное пересоздание таблиц памяти")
        database.execSQL("DROP TABLE IF EXISTS daily_memory")
        database.execSQL("DROP TABLE IF EXISTS permanent_memory")
        database.execSQL("""
            CREATE TABLE daily_memory (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT ''
            )
        """)
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        database.execSQL("""
            CREATE TABLE permanent_memory (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.8,
                sourceChatId TEXT,
                sourceMessageId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
        println("✅ MIGRATION_11_12: Таблицы памяти полностью пересозданы с правильной схемой")
    }
}

// Миграция с версии 12 на 13: добавление таблицы суммаризаций
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_12_13: Добавляем таблицу суммаризаций")
        database.execSQL("""
            CREATE TABLE summaries (
                id TEXT PRIMARY KEY NOT NULL,
                chatId TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                messageIds TEXT NOT NULL,
                compressionRatio REAL NOT NULL DEFAULT 1.0,
                createdAt INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT '',
                qualityScore REAL
            )
        """)
        database.execSQL("CREATE INDEX index_summaries_chatId ON summaries (chatId)")
        database.execSQL("CREATE INDEX index_summaries_type ON summaries (type)")
        database.execSQL("CREATE INDEX index_summaries_createdAt ON summaries (createdAt)")
        println("✅ MIGRATION_12_13: Таблица summaries создана")
    }
}

// Миграция с версии 13 на 14: добавление полей scope, tags, metadata в permanent_memory
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_13_14: Добавляем поля scope, tags, metadata в permanent_memory")
        database.execSQL("""
            CREATE TABLE permanent_memory_new (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.8,
                scope TEXT NOT NULL DEFAULT 'user',
                tags TEXT,
                metadata TEXT,
                sourceChatId TEXT,
                sourceMessageId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        database.execSQL("""
            INSERT INTO permanent_memory_new 
            (id, category, key, value, confidence, sourceChatId, sourceMessageId, createdAt, updatedAt)
            SELECT id, category, key, value, confidence, sourceChatId, sourceMessageId, createdAt, updatedAt
            FROM permanent_memory
        """)
        database.execSQL("DROP TABLE permanent_memory")
        database.execSQL("ALTER TABLE permanent_memory_new RENAME TO permanent_memory")
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE INDEX index_permanent_memory_scope ON permanent_memory (scope)")
        database.execSQL("CREATE INDEX index_permanent_memory_tags ON permanent_memory (tags)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_scope_category_key ON permanent_memory (scope, category, key)")
        println("✅ MIGRATION_13_14: Поля scope, tags, metadata добавлены в permanent_memory")
    }
}

// Миграция с версии 14 на 15: добавление таблицы кэша анализа запросов
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_14_15: Создаём таблицу query_analysis_cache для AI-анализа запросов")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS query_analysis_cache (
                query TEXT PRIMARY KEY NOT NULL,
                keywords TEXT,
                suggested_keys TEXT,
                suggested_categories TEXT,
                suggested_scope TEXT NOT NULL DEFAULT 'user',
                confidence REAL NOT NULL DEFAULT 0.0,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL
            )
        """)
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_created_at ON query_analysis_cache (created_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_last_used_at ON query_analysis_cache (last_used_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_confidence ON query_analysis_cache (confidence)")
        println("✅ MIGRATION_14_15: Таблица query_analysis_cache создана с индексами")
    }
}

// Миграция с версии 15 на 16: удаление колонки use_web_search из web_search_settings
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_15_16: Удаляем колонку use_web_search из web_search_settings")
        database.execSQL("ALTER TABLE web_search_settings DROP COLUMN use_web_search")
        println("✅ MIGRATION_15_16: Колонка use_web_search удалена")
    }
}

// Миграция с версии 16 на 17: добавление новых колонок в provider_settings
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_16_17: Добавляем новые колонки в provider_settings")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN max_tokens INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN model_max_context INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN model_max_output INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN thinking_mode_enabled INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN context_management TEXT NOT NULL DEFAULT 'truncate'")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN context_buffer_percent INTEGER NOT NULL DEFAULT 90")
        println("✅ MIGRATION_16_17: Колонки добавлены")
    }
}

// Миграция с версии 17 на 18: добавление колонок use_custom_params, temperature, top_p
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_17_18: Добавляем колонки temperature, top_p, use_custom_params")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN use_custom_params INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN temperature REAL DEFAULT NULL")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN top_p REAL DEFAULT NULL")
        println("✅ MIGRATION_17_18: Колонки temperature, top_p, use_custom_params добавлены")
    }
}

// Миграция с версии 18 на 19: добавление таблицы geo_tasks
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_18_19: Создаём таблицу geo_tasks")
        database.execSQL("""
            CREATE TABLE geo_tasks (
                id TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                address TEXT,
                radiusMeters INTEGER NOT NULL DEFAULT 300,
                isActive INTEGER NOT NULL DEFAULT 1,
                oneShot INTEGER NOT NULL DEFAULT 1,
                lastTriggeredAt INTEGER,
                lastTriggeredAddress TEXT,
                createdAt INTEGER NOT NULL
            )
        """)
        println("✅ MIGRATION_18_19: Таблица geo_tasks создана")
    }
}
