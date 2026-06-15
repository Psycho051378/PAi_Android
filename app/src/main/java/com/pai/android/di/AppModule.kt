package com.pai.android.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pai.android.data.local.AppDatabase
import com.pai.android.data.local.AttachmentDao
import com.pai.android.data.local.GeoTaskDao
import com.pai.android.data.local.ChatDao
import com.pai.android.data.local.MemoryDao
import com.pai.android.data.local.MessageDao
import com.pai.android.data.local.ProviderSettingsDao
import com.pai.android.data.local.RoleDao
import com.pai.android.data.local.SummaryDao
import com.pai.android.data.local.WebSearchSettingsDao
import com.pai.android.data.network.AiChatService
import com.pai.android.data.repository.AiRepository
import com.pai.android.data.repository.AttachmentRepository
import com.pai.android.data.repository.ChatRepository
import com.pai.android.data.repository.GeoTaskRepository
import com.pai.android.data.repository.MemoryRepository
import com.pai.android.data.repository.ProviderSettingsRepository
import com.pai.android.data.repository.RoleRepository
import com.pai.android.data.repository.SummaryRepository
import com.pai.android.data.repository.ThemePreferencesRepository
import com.pai.android.data.repository.WebSearchRepository
import com.pai.android.data.detector.FactExtractor
import com.pai.android.data.detector.SignificanceDetector
import com.pai.android.data.summarizer.SmartSummarizer
import com.pai.android.data.processor.MessageProcessor
import com.pai.android.data.service.VoiceRecognitionService
import com.pai.android.data.service.CameraService
import com.pai.android.data.service.WebSearchService
import com.pai.android.data.service.QueryAnalyzer
import com.pai.android.data.service.DailyMemoryService
import com.pai.android.data.service.TemporalQueryParser
import com.pai.android.data.service.DailyMemorySearchService
import com.pai.android.agent.FileManager
import com.pai.android.agent.DecisionEngine
import com.pai.android.agent.SkillRegistry
import com.pai.android.agent.IntentRecognizer
import com.pai.android.agent.FileSystemSkill
import com.pai.android.agent.skills.PythonSkill
import com.pai.android.agent.skills.EmailSkill
import com.pai.android.agent.skills.NotificationSkill
import com.pai.android.agent.skills.HomeSkill
import com.pai.android.agent.skills.home.router.RouterScanner
import com.pai.android.agent.OpenFileSkill
import com.pai.android.agent.AppLaunchSkill
import com.pai.android.agent.AgentPlanner
import com.pai.android.agent.ToolRegistry
import com.pai.android.agent.ToolSkillAdapter
import com.pai.android.agent.ReActAgent
import com.pai.android.agent.TaskQueue
import com.pai.android.agent.TaskScheduler
import com.pai.android.agent.PersistentContext
import com.pai.android.agent.ProjectManager
import com.pai.android.agent.tools.DocumentAnalysisTool
import com.pai.android.agent.tools.FileSystemTool
import com.pai.android.agent.tools.MemoryTool
import com.pai.android.agent.tools.WebSearchTool
import com.pai.android.agent.tools.WeatherTool
import com.pai.android.agent.tools.WebFetchTool
import com.pai.android.agent.tools.ProjectAnalyzerTool
import com.pai.android.agent.tools.TaskSchedulerTool
import com.pai.android.agent.tools.NotificationTool
import com.pai.android.agent.tools.LocationTool
import com.pai.android.agent.tools.ContextTool
import com.pai.android.agent.tools.ClipboardTool
import com.pai.android.agent.tools.CalendarTool
import com.pai.android.agent.tools.MapsTool
import com.pai.android.agent.WeatherSkill
import com.pai.android.agent.ContextEngine
import com.pai.android.agent.skills.OfficeSkill
import com.pai.android.agent.skills.LocationSkill
import com.pai.android.data.service.LocationService
import com.pai.android.agent.WebFetchSkill
import com.pai.android.agent.WebSearchSkill
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// Миграция с версии 1 на 2: пересоздание таблицы provider_settings
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Удаляем старую таблицу и создаём заново с правильной схемой
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
        // Пересоздаём таблицу для гарантии применения UUID схемы
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
        // Окончательное пересоздание таблицы с UUID гарантией
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
        // Создаём таблицу ролей
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
        
        // Добавляем поле roleId в таблицу chats
        database.execSQL("ALTER TABLE chats ADD COLUMN roleId TEXT")
    }
}

// Миграция с версии 5 на 6: добавление таблицы attachments
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Создаём таблицу вложений
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
        
        // Создаём индекс для быстрого поиска вложений по messageId
        database.execSQL("CREATE INDEX idx_attachments_messageId ON attachments (messageId)")
    }
}

// Миграция с версии 6 на 7: добавление таблицы веб-поиска
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Создаём таблицу настроек веб-поиска
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
        
        // Вставляем запись по умолчанию
        database.execSQL("""
            INSERT INTO web_search_settings (id, enabled, provider, created_at, updated_at)
            VALUES ('web_search_settings', 0, 'GOOGLE', ?, ?)
        """, arrayOf(System.currentTimeMillis(), System.currentTimeMillis()))
    }
}

// Миграция с версии 7 на 8: добавление таблиц памяти (daily_memory и permanent_memory)
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Создаём таблицу daily_memory
        database.execSQL("""
            CREATE TABLE daily_memory (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL UNIQUE,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT ''
            )
        """)
        
        // Создаём уникальный индекс для поля date (Room ожидает unique индекс с именем index_daily_memory_date)
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        
        // Создаём таблицу permanent_memory
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
        
        // Создаём индексы для быстрого поиска (имена должны соответствовать тем, что генерирует Room)
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        
        // Создаём уникальный индекс для предотвращения дубликатов (категория + ключ)
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
    }
}

// Миграция с версии 8 на 9: исправление индекса daily_memory (делаем его уникальным)
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Удаляем старый не-уникальный индекс
        database.execSQL("DROP INDEX IF EXISTS idx_daily_memory_date")
        // Создаём новый уникальный индекс с именем, которое ожидает Room (index_daily_memory_date)
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
    }
}

// Миграция с версии 9 на 10: исправление имён индексов permanent_memory
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Удаляем старые индексы с неправильными именами
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_category")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_key")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_confidence")
        database.execSQL("DROP INDEX IF EXISTS idx_permanent_memory_category_key")
        
        // Создаём новые индексы с правильными именами (такими, какие генерирует Room)
        database.execSQL("CREATE INDEX index_permanent_memory_category ON permanent_memory (category)")
        database.execSQL("CREATE INDEX index_permanent_memory_key ON permanent_memory (key)")
        database.execSQL("CREATE INDEX index_permanent_memory_confidence ON permanent_memory (confidence)")
        database.execSQL("CREATE UNIQUE INDEX index_permanent_memory_category_key ON permanent_memory (category, key)")
    }
}

// Миграция с версии 10 на 11: окончательное исправление всех индексов памяти
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // === Исправление индексов daily_memory ===
        // Удаляем старые индексы (если существуют)
        database.execSQL("DROP INDEX IF EXISTS idx_daily_memory_date")
        database.execSQL("DROP INDEX IF EXISTS index_daily_memory_date")
        
        // Создаём правильный уникальный индекс (Room ожидает именно это имя)
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        
        // === Исправление индексов permanent_memory ===
        // Удаляем ВСЕ возможные старые индексы (как с idx_, так и с index_)
        val oldIndexes = listOf(
            "idx_permanent_memory_category",
            "idx_permanent_memory_key", 
            "idx_permanent_memory_confidence",
            "idx_permanent_memory_category_key",
            "index_permanent_memory_category",
            "index_permanent_memory_key",
            "index_permanent_memory_confidence",
            "index_permanent_memory_category_key"
        )
        
        oldIndexes.forEach { indexName ->
            database.execSQL("DROP INDEX IF EXISTS $indexName")
        }
        
        // Создаём правильные индексы с именами, которые Room генерирует автоматически
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
        
        // === 1. Удаляем старые таблицы памяти (если существуют) ===
        database.execSQL("DROP TABLE IF EXISTS daily_memory")
        database.execSQL("DROP TABLE IF EXISTS permanent_memory")
        
        // === 2. Создаём daily_memory БЕЗ UNIQUE в определении столбца ===
        database.execSQL("""
            CREATE TABLE daily_memory (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT ''
            )
        """)
        
        // === 3. Создаём правильный уникальный индекс (Room ожидает index_daily_memory_date) ===
        database.execSQL("CREATE UNIQUE INDEX index_daily_memory_date ON daily_memory (date)")
        
        // === 4. Создаём permanent_memory без индексов в определении таблицы ===
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
        
        // === 5. Создаём все индексы permanent_memory с правильными именами ===
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
        
        // Создаём таблицу summaries
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
        
        // Создаём индексы для быстрого поиска
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
        
        // 1. Создаём временную таблицу с новой схемой
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
        
        // 2. Копируем данные из старой таблицы в новую
        database.execSQL("""
            INSERT INTO permanent_memory_new 
            (id, category, key, value, confidence, sourceChatId, sourceMessageId, createdAt, updatedAt)
            SELECT id, category, key, value, confidence, sourceChatId, sourceMessageId, createdAt, updatedAt
            FROM permanent_memory
        """)
        
        // 3. Удаляем старую таблицу
        database.execSQL("DROP TABLE permanent_memory")
        
        // 4. Переименовываем новую таблицу в permanent_memory
        database.execSQL("ALTER TABLE permanent_memory_new RENAME TO permanent_memory")
        
        // 5. Создаём индексы (включая новые индексы для scope и tags)
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
        
        // Создаём таблицу для кэширования результатов AI-анализа запросов
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
        
        // Индексы для быстрого поиска и очистки устаревших записей
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_created_at ON query_analysis_cache (created_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_last_used_at ON query_analysis_cache (last_used_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_query_analysis_confidence ON query_analysis_cache (confidence)")
        
        println("✅ MIGRATION_14_15: Таблица query_analysis_cache создана с индексами")
    }
}

/**
 * Миграция с версии 15 на 16: удаление колонки use_web_search из web_search_settings.
 */
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_15_16: Удаляем колонку use_web_search из web_search_settings")
        database.execSQL("ALTER TABLE web_search_settings DROP COLUMN use_web_search")
        println("✅ MIGRATION_15_16: Колонка use_web_search удалена")
    }
}

/**
 * Миграция с версии 16 на 17: добавление новых колонок в provider_settings.
 * - max_tokens (лимит ответа)
 * - model_max_context (контекстное окно модели)
 * - model_max_output (максимальный вывод)
 * - thinking_mode_enabled (режим мышления DeepSeek V4)
 * - context_management (стратегия управления контекстом)
 * - context_buffer_percent (буфер перед триммингом)
 */
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

private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        println("🔄 MIGRATION_17_18: Добавляем колонки temperature, top_p, use_custom_params")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN use_custom_params INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN temperature REAL DEFAULT NULL")
        database.execSQL("ALTER TABLE provider_settings ADD COLUMN top_p REAL DEFAULT NULL")
        println("✅ MIGRATION_17_18: Колонки temperature, top_p, use_custom_params добавлены")
    }
}

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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    // ============= Dispatchers =============
    
    @Provides
    @Singleton
    fun provideDefaultDispatcher() = Dispatchers.IO
    
    // ============= Database =============
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pai_database"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19) // Явные миграции с версии 1 на 19
        .fallbackToDestructiveMigration() // Уничтожает БД при несовпадении схемы (резерв)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                println("✅ База данных создана")
            }
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                println("✅ База данных открыта")
            }
        })
        .build()
    }
    
    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    @Singleton
    fun provideProviderSettingsDao(database: AppDatabase): ProviderSettingsDao {
        return database.providerSettingsDao()
    }
    
    @Provides
    @Singleton
    fun provideRoleDao(database: AppDatabase): RoleDao {
        return database.roleDao()
    }
    
    @Provides
    @Singleton
    fun provideAttachmentDao(database: AppDatabase): AttachmentDao {
        return database.attachmentDao()
    }
    
    @Provides
    @Singleton
    fun provideWebSearchSettingsDao(database: AppDatabase): WebSearchSettingsDao {
        return database.webSearchSettingsDao()
    }
    
    @Provides
    @Singleton
    fun provideMemoryDao(database: AppDatabase): MemoryDao {
        return database.memoryDao()
    }
    
    @Provides
    @Singleton
    fun provideSummaryDao(database: AppDatabase): SummaryDao {
        return database.summaryDao()
    }

    @Provides
    @Singleton
    fun provideGeoTaskDao(database: AppDatabase): GeoTaskDao {
        return database.geoTaskDao()
    }

    @Provides
    @Singleton
    fun provideGeoTaskRepository(dao: GeoTaskDao): GeoTaskRepository {
        return GeoTaskRepository(dao)
    }
    
    @Provides
    @Singleton
    fun provideRouterScanner(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): RouterScanner {
        return RouterScanner(context, okHttpClient)
    }
    
    // ============= Network =============
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideAiChatServiceFactory(): AiChatServiceFactory {
        return AiChatServiceFactory()
    }
    
    // ============= Repositories =============
    
    @Provides
    @Singleton
    fun provideChatRepository(
        chatDao: ChatDao,
        messageDao: MessageDao
    ): ChatRepository {
        return ChatRepository(chatDao, messageDao)
    }
    
    @Provides
    @Singleton
    fun provideProviderSettingsRepository(
        providerSettingsDao: ProviderSettingsDao
    ): ProviderSettingsRepository {
        return ProviderSettingsRepository(providerSettingsDao)
    }
    
    @Provides
    @Singleton
    fun provideRoleRepository(
        roleDao: RoleDao
    ): RoleRepository {
        return RoleRepository(roleDao)
    }
    
    @Provides
    @Singleton
    fun provideAttachmentRepository(
        attachmentDao: AttachmentDao
    ): AttachmentRepository {
        return AttachmentRepository(attachmentDao)
    }
    
    @Provides
    @Singleton
    fun provideWebSearchRepository(
        webSearchSettingsDao: WebSearchSettingsDao
    ): WebSearchRepository {
        return WebSearchRepository(webSearchSettingsDao)
    }
    
    @Provides
    @Singleton
    fun provideMemoryRepository(
        memoryDao: MemoryDao,
        queryAnalyzer: QueryAnalyzer,
        dailyMemorySearchService: DailyMemorySearchService
    ): MemoryRepository {
        return MemoryRepository(memoryDao, queryAnalyzer, dailyMemorySearchService)
    }

    @Provides
    @Singleton
    fun provideSummaryRepository(
        summaryDao: SummaryDao
    ): SummaryRepository {
        return SummaryRepository(summaryDao)
    }
    
    @Provides
    @Singleton
    fun provideSignificanceDetector(): SignificanceDetector {
        return SignificanceDetector()
    }
    
    @Provides
    @Singleton
    fun provideFactExtractor(): FactExtractor {
        return FactExtractor()
    }
    
    @Provides
    @Singleton
    fun provideSmartSummarizer(
        aiRepository: AiRepository
    ): SmartSummarizer {
        return SmartSummarizer(aiRepository)
    }
    
    @Provides
    @Singleton
    fun provideMessageProcessor(
        significanceDetector: SignificanceDetector,
        factExtractor: FactExtractor,
        memoryRepository: MemoryRepository,
        chatRepository: ChatRepository,
        summaryRepository: SummaryRepository,
        smartSummarizer: SmartSummarizer,
        dailyMemoryService: DailyMemoryService
    ): MessageProcessor {
        return MessageProcessor(
            significanceDetector,
            factExtractor,
            memoryRepository,
            chatRepository,
            summaryRepository,
            smartSummarizer,
            dailyMemoryService
        )
    }
    
    @Provides
    @Singleton
    fun provideAiRepository(
        settingsRepository: ProviderSettingsRepository,
        aiChatServiceFactory: AiChatServiceFactory,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        webSearchService: WebSearchService,
        webSearchRepository: WebSearchRepository
    ): AiRepository {
        return AiRepository(
            settingsRepository,
            aiChatServiceFactory,
            defaultDispatcher,
            webSearchService,
            webSearchRepository
        )
    }
    
    // ============= Voice Recognition =============
    
    @Provides
    @Singleton
    fun provideVoiceRecognitionService(@ApplicationContext context: Context): VoiceRecognitionService {
        return VoiceRecognitionService(context)
    }
    
    // ============= Camera =============
    
    @Provides
    @Singleton
    fun provideCameraService(@ApplicationContext context: Context): CameraService {
        return CameraService(context)
    }
    
    // ============= Web Search =============
    
    @Provides
    @Singleton
    fun provideWebSearchService(
        webSearchRepository: WebSearchRepository,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        okHttpClient: OkHttpClient
    ): WebSearchService {
        return WebSearchService(webSearchRepository, defaultDispatcher, okHttpClient)
    }
    
    // ============= Daily Memory Service =============
    
    @Provides
    @Singleton
    fun provideDailyMemoryService(
        memoryDao: MemoryDao,
        significanceDetector: SignificanceDetector,
        smartSummarizer: SmartSummarizer,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): DailyMemoryService {
        return DailyMemoryService(
            memoryDao = memoryDao,
            significanceDetector = significanceDetector,
            smartSummarizer = smartSummarizer,
            defaultDispatcher = defaultDispatcher
        )
    }
    
    // ============= Temporal Query Parser =============
    
    @Provides
    @Singleton
    fun provideTemporalQueryParser(): TemporalQueryParser {
        return TemporalQueryParser()
    }
    
    // ============= Daily Memory Search Service =============
    
    @Provides
    @Singleton
    fun provideDailyMemorySearchService(
        memoryDao: MemoryDao,
        temporalQueryParser: TemporalQueryParser,
        aiRepository: AiRepository,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): DailyMemorySearchService {
        return DailyMemorySearchService(
            memoryDao = memoryDao,
            temporalQueryParser = temporalQueryParser,
            aiRepository = aiRepository,
            defaultDispatcher = defaultDispatcher
        )
    }
    
    // ============= Query Analyzer =============
    
    @Provides
    @Singleton
    fun provideQueryAnalyzer(
        aiRepository: AiRepository,
        memoryDao: MemoryDao,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): QueryAnalyzer {
        return QueryAnalyzer(aiRepository, memoryDao, defaultDispatcher)
    }
    
    // ============= Agent System =============
    
    @Provides
    @Singleton
    fun provideFileManager(
        @ApplicationContext context: Context
    ): FileManager {
        return FileManager(context).apply {
            initWorkspace()
        }
    }
    
    @Provides
    @Singleton
    fun provideSkillRegistry(): SkillRegistry {
        return SkillRegistry()
    }
    
    @Provides
    @Singleton
    fun provideIntentRecognizer(
        aiRepository: AiRepository,
        fileManager: FileManager
    ): IntentRecognizer {
        return IntentRecognizer(
            aiRepository = aiRepository,
            fileManager = fileManager
        )
    }
    
    @Provides
    @Singleton
    fun provideFileSystemSkill(
        fileManager: FileManager,
        aiRepository: AiRepository
    ): FileSystemSkill {
        return FileSystemSkill(fileManager = fileManager, aiRepository = aiRepository)
    }

    @Provides
    @Singleton
    fun provideOpenFileSkill(
        @ApplicationContext context: Context,
        fileManager: FileManager
    ): OpenFileSkill {
        return OpenFileSkill(context = context, fileManager = fileManager)
    }

    @Provides
    @Singleton
    fun provideWeatherSkill(): WeatherSkill {
        return WeatherSkill()
    }

    @Provides
    @Singleton
    fun provideAppLaunchSkill(
        @ApplicationContext context: Context,
        aiRepository: AiRepository
    ): AppLaunchSkill {
        return AppLaunchSkill(context = context, aiRepository = aiRepository)
    }

    @Provides
    @Singleton
    fun provideWebFetchSkill(
        aiRepository: AiRepository
    ): WebFetchSkill {
        return WebFetchSkill(aiRepository = aiRepository)
    }

    // ============= Tools =============

    @Provides
    @Singleton
    fun provideMemoryTool(
        memoryRepository: com.pai.android.data.repository.MemoryRepository,
        aiRepository: AiRepository
    ): MemoryTool {
        return MemoryTool(memoryRepository, aiRepository)
    }

    @Provides
    @Singleton
    fun provideWebSearchTool(
        webSearchService: WebSearchService,
        webSearchRepository: WebSearchRepository,
        aiRepository: AiRepository
    ): WebSearchTool {
        return WebSearchTool(webSearchService, webSearchRepository, aiRepository)
    }

    @Provides
    @Singleton
    fun provideWeatherTool(
        aiRepository: AiRepository
    ): WeatherTool {
        return WeatherTool(aiRepository)
    }

    @Provides
    @Singleton
    fun provideProjectAnalyzerTool(
        aiRepository: AiRepository,
        fileManager: FileManager,
        projectManager: ProjectManager
    ): ProjectAnalyzerTool {
        return ProjectAnalyzerTool(aiRepository, fileManager, projectManager)
    }

    @Provides
    @Singleton
    fun provideWebFetchTool(): WebFetchTool {
        return WebFetchTool()
    }
    @Provides
    @Singleton
    fun provideFileSystemTool(
        fileManager: com.pai.android.agent.FileManager
    ): FileSystemTool {
        return FileSystemTool(fileManager)
    }

    @Provides
    @Singleton
    fun provideDocumentAnalysisTool(
        fileManager: com.pai.android.agent.FileManager
    ): DocumentAnalysisTool {
        return DocumentAnalysisTool(fileManager)
    }


    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry {
        return ToolRegistry()
    }

    @Provides
    @Singleton
    fun provideWebSearchSkill(
        aiRepository: AiRepository,
        webSearchRepository: WebSearchRepository
    ): WebSearchSkill {
        return WebSearchSkill(
            aiRepository = aiRepository,
            webSearchRepository = webSearchRepository
        )
    }

    @Provides
    @Singleton
    fun providePythonSkill(
        @ApplicationContext context: android.content.Context,
        aiRepository: AiRepository
    ): PythonSkill {
        return PythonSkill(context, aiRepository)
    }

    @Provides
    @Singleton
    fun provideEmailSkill(
        @ApplicationContext context: android.content.Context
    ): EmailSkill {
        return EmailSkill(context)
    }

    @Provides
    @Singleton
    fun provideHomeSkill(
        @ApplicationContext context: android.content.Context,
        memoryRepository: MemoryRepository,
        okHttpClient: OkHttpClient,
        routerScanner: RouterScanner
    ): HomeSkill {
        return HomeSkill(context, memoryRepository, okHttpClient, routerScanner)
    }

    @Provides
    @Singleton
    fun provideContactsSkill(
        @ApplicationContext context: android.content.Context
    ): com.pai.android.agent.skills.ContactsSkill {
        return com.pai.android.agent.skills.ContactsSkill(context)
    }

    @Provides
    @Singleton
    fun provideGeoSkill(
        @ApplicationContext context: Context,
        geoTaskRepository: GeoTaskRepository,
        locationService: LocationService
    ): com.pai.android.agent.skills.GeoSkill {
        return com.pai.android.agent.skills.GeoSkill(context, geoTaskRepository, locationService)
    }

    @Provides
    @Singleton
    fun provideOfficeSkill(
        @ApplicationContext context: android.content.Context,
        aiRepository: com.pai.android.data.repository.AiRepository
    ): OfficeSkill {
        return OfficeSkill(context, aiRepository)
    }

    @Provides
    @Singleton
    fun provideDecisionEngine(
        @ApplicationContext context: Context,

        aiRepository: AiRepository,
        memoryRepository: MemoryRepository,
        skillRegistry: SkillRegistry,
        toolRegistry: ToolRegistry,
        memoryTool: MemoryTool,
        fileSystemTool: FileSystemTool,
        documentAnalysisTool: DocumentAnalysisTool,
        webSearchTool: WebSearchTool,
        weatherTool: WeatherTool,
        webFetchTool: WebFetchTool,
        projectAnalyzerTool: ProjectAnalyzerTool,
        taskSchedulerTool: TaskSchedulerTool,
        notificationTool: NotificationTool,
        locationTool: LocationTool,
        contextTool: ContextTool,
        clipboardTool: ClipboardTool,
        calendarTool: CalendarTool,
        mapsTool: MapsTool,
        locationService: LocationService,
        contextEngine: ContextEngine,
        intentRecognizer: IntentRecognizer,
        fileSystemSkill: FileSystemSkill,
        openFileSkill: OpenFileSkill,
        webSearchSkill: WebSearchSkill,
        webFetchSkill: WebFetchSkill,
        weatherSkill: WeatherSkill,
        appLaunchSkill: AppLaunchSkill,
        externalSkillRepository: com.pai.android.agent.skills.ExternalSkillRepository,
        agentPlanner: AgentPlanner,
        reactAgent: ReActAgent,
        pythonSkill: PythonSkill,
        emailSkill: EmailSkill,
        homeSkill: HomeSkill,
        officeSkill: OfficeSkill,
        geoTaskRepository: GeoTaskRepository,
        taskQueue: TaskQueue,
        persistentContext: PersistentContext,
        projectManager: ProjectManager,
        taskScheduler: com.pai.android.agent.TaskScheduler,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): DecisionEngine {
        println("AppModule: provideDecisionEngine called")
        // Регистрируем инструменты в ToolRegistry
        toolRegistry.register(memoryTool)
        toolRegistry.register(fileSystemTool)
        toolRegistry.register(documentAnalysisTool)
        toolRegistry.register(webSearchTool)
        toolRegistry.register(weatherTool)
        toolRegistry.register(webFetchTool)
        toolRegistry.register(projectAnalyzerTool)
        toolRegistry.register(taskSchedulerTool)
        toolRegistry.register(notificationTool)
        toolRegistry.register(locationTool)
        toolRegistry.register(contextTool)
        toolRegistry.register(clipboardTool)
        toolRegistry.register(calendarTool)
        toolRegistry.register(mapsTool)
        println("🔧 Зарегистрировано инструментов: ${toolRegistry.getAllTools().size}")
        
        // Регистрируем инструменты как навыки (через адаптер)
        skillRegistry.register(ToolSkillAdapter(memoryTool))
        skillRegistry.register(ToolSkillAdapter(fileSystemTool))
        skillRegistry.register(ToolSkillAdapter(documentAnalysisTool))
        
        // Регистрируем навыки
        skillRegistry.register(fileSystemSkill)
        skillRegistry.register(openFileSkill)
        skillRegistry.register(webFetchSkill)
        skillRegistry.register(webSearchSkill)
        skillRegistry.register(weatherSkill)
        skillRegistry.register(appLaunchSkill)
        skillRegistry.register(pythonSkill)
        skillRegistry.register(emailSkill)
        skillRegistry.register(officeSkill)
        skillRegistry.register(com.pai.android.agent.skills.ContactsSkill(context))
        skillRegistry.register(com.pai.android.agent.skills.CallSkill(context))
        skillRegistry.register(com.pai.android.agent.skills.SmsSkill(context))
        skillRegistry.register(com.pai.android.agent.skills.NotificationSkill(context))
        skillRegistry.register(ToolSkillAdapter(taskSchedulerTool))
        skillRegistry.register(ToolSkillAdapter(notificationTool))
        skillRegistry.register(ToolSkillAdapter(locationTool))
        skillRegistry.register(ToolSkillAdapter(contextTool))
        skillRegistry.register(ToolSkillAdapter(clipboardTool))
        skillRegistry.register(ToolSkillAdapter(calendarTool))
        skillRegistry.register(ToolSkillAdapter(mapsTool))
        skillRegistry.register(com.pai.android.agent.skills.LocationSkill(context, locationService))
        skillRegistry.register(homeSkill)
        skillRegistry.register(com.pai.android.agent.skills.GeoSkill(context, geoTaskRepository, locationService))
        println("🔧 HomeSkill registered: enabled=" + com.pai.android.agent.skills.HomeSkill.enabled)
        
        // Load installed external skills from persistence
        CoroutineScope(Dispatchers.IO).launch {
            try {
                externalSkillRepository.attachRegistry(skillRegistry)
                externalSkillRepository.pythonSkill = pythonSkill
                externalSkillRepository.skillsDirectory = (context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath) + "/workspace/skills"
                println("AppModule: scanning local skills at '" + externalSkillRepository.skillsDirectory + "'")
                externalSkillRepository.registerInstalledSkills(skillRegistry)
            } catch (e: Exception) {
                println("AppModule: registerInstalledSkills error: " + e.message)
            }
        }
        
        val de = DecisionEngine(
            aiRepository = aiRepository,
            memoryRepository = memoryRepository,
            skillRegistry = skillRegistry,
            toolRegistry = toolRegistry,
            intentRecognizer = intentRecognizer,
            agentPlanner = agentPlanner,
            reactAgent = reactAgent,
            taskQueue = taskQueue,
            persistentContext = persistentContext,
            projectManager = projectManager,
            contextEngine = contextEngine,
            taskScheduler = taskScheduler,
            homeSkill = homeSkill,
            defaultDispatcher = defaultDispatcher
        )
        de.skillsDirectory = (context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath) + "/workspace/skills"
        return de
    }
    





}

