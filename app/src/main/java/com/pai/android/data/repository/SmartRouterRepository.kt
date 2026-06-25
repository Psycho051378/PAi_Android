package com.pai.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pai.android.data.model.SmartRouterConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smartRouterDataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_router")

/**
 * Репозиторий настроек Smart Router.
 * Хранит конфигурацию в DataStore (отдельно от Room).
 */
@Singleton
class SmartRouterRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val ENABLED_KEY = booleanPreferencesKey("enabled")
        private val NETWORK_PROVIDER_SETTINGS_ID_KEY = stringPreferencesKey("network_provider_settings_id")
        private val COMPLEXITY_THRESHOLD_KEY = floatPreferencesKey("complexity_threshold")
        private val MAX_LOCAL_TOKENS_KEY = intPreferencesKey("max_local_tokens")
        private val ROUTE_MULTIMODAL_TO_LOCAL_KEY = booleanPreferencesKey("route_multimodal_to_local")
        private val ENABLE_FALLBACK_KEY = booleanPreferencesKey("enable_fallback")
        private val ENABLE_HYBRID_KEY = booleanPreferencesKey("enable_hybrid")
        private val HYBRID_THRESHOLD_KEY = intPreferencesKey("hybrid_threshold")
    }

    /** Наблюдаемый поток конфига */
    val config: Flow<SmartRouterConfig> = context.smartRouterDataStore.data.map { prefs ->
        SmartRouterConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            networkProviderSettingsId = prefs[NETWORK_PROVIDER_SETTINGS_ID_KEY] ?: "",
            complexityThreshold = prefs[COMPLEXITY_THRESHOLD_KEY] ?: 0.5f,
            maxLocalTokens = prefs[MAX_LOCAL_TOKENS_KEY] ?: 512,
            routeMultimodalToLocal = prefs[ROUTE_MULTIMODAL_TO_LOCAL_KEY] ?: true,
            enableFallback = prefs[ENABLE_FALLBACK_KEY] ?: true,
            enableHybrid = prefs[ENABLE_HYBRID_KEY] ?: false,
            hybridThreshold = prefs[HYBRID_THRESHOLD_KEY] ?: 4
        )
    }

    /** Текущий конфиг (однократно) */
    suspend fun get(): SmartRouterConfig = config.first()

    /** Сохранить весь конфиг */
    suspend fun save(cfg: SmartRouterConfig) {
        context.smartRouterDataStore.edit { prefs ->
            prefs[ENABLED_KEY] = cfg.enabled
            prefs[NETWORK_PROVIDER_SETTINGS_ID_KEY] = cfg.networkProviderSettingsId
            prefs[COMPLEXITY_THRESHOLD_KEY] = cfg.complexityThreshold
            prefs[MAX_LOCAL_TOKENS_KEY] = cfg.maxLocalTokens
            prefs[ROUTE_MULTIMODAL_TO_LOCAL_KEY] = cfg.routeMultimodalToLocal
            prefs[ENABLE_FALLBACK_KEY] = cfg.enableFallback
            prefs[ENABLE_HYBRID_KEY] = cfg.enableHybrid
            prefs[HYBRID_THRESHOLD_KEY] = cfg.hybridThreshold
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.smartRouterDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setNetworkProviderSettingsId(id: String) {
        context.smartRouterDataStore.edit { it[NETWORK_PROVIDER_SETTINGS_ID_KEY] = id }
    }

    suspend fun setComplexityThreshold(threshold: Float) {
        context.smartRouterDataStore.edit { it[COMPLEXITY_THRESHOLD_KEY] = threshold }
    }
}
