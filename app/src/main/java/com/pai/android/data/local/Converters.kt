package com.pai.android.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pai.android.data.model.AiProvider
import com.pai.android.data.model.MessageRole
import com.pai.android.data.model.SummaryType
import com.pai.android.data.model.WebSearchProvider
import java.lang.reflect.Type

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringToListString(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val type: Type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson<List<String>>(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromListStringToString(list: List<String>?): String {
        return gson.toJson(list.orEmpty())   // ← ИСПРАВЛЕНО
    }

    @TypeConverter
    fun fromStringToMapStringString(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        val type: Type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson<Map<String, String>>(value, type) ?: emptyMap()
    }

    @TypeConverter
    fun fromMapStringStringToString(map: Map<String, String>?): String {
        return gson.toJson(map.orEmpty())    // ← ИСПРАВЛЕНО
    }

    @TypeConverter
    fun fromStringToAiProvider(value: String?): AiProvider? {
        if (value == null) return null
        return try {
            AiProvider.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromAiProviderToString(provider: AiProvider?): String? {
        return provider?.name
    }

    @TypeConverter
    fun fromStringToMessageRole(value: String?): MessageRole? {
        if (value == null) return null
        return try {
            MessageRole.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromMessageRoleToString(role: MessageRole?): String? {
        return role?.name
    }

    @TypeConverter
    fun fromStringToWebSearchProvider(value: String?): WebSearchProvider? {
        if (value == null) return null
        return try {
            WebSearchProvider.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromWebSearchProviderToString(provider: WebSearchProvider?): String? {
        return provider?.name
    }

    @TypeConverter
    fun fromStringToSummaryType(value: String?): SummaryType? {
        if (value == null) return null
        return try {
            SummaryType.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromSummaryTypeToString(type: SummaryType?): String? {
        return type?.name
    }
}