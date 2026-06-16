package com.pai.android.presentation.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Состояние одного разрешения.
 */
enum class PermissionState {
    /** Ещё не запрашивалось / нет информации */
    UNKNOWN,
    /** Разрешено пользователем */
    GRANTED,
    /** Запрещено */
    DENIED,
    /** Нужно открыть системные настройки (специальные разрешения) */
    SYSTEM_SETTINGS_REQUIRED
}

/**
 * Описание одного элемента разрешения на экране онбординга.
 */
data class PermissionItem(
    val id: String,
    /** Список runtime-разрешений для этого элемента (может быть пустым для system-permissions) */
    val runtimePermissions: List<String> = emptyList(),
    /** Resource ID заголовка */
    val titleResId: Int,
    /** Resource ID описания */
    val descriptionResId: Int,
    /** Emoji-иконка (для простоты) */
    val icon: String,
    /** Это специальное системное разрешение (не через requestPermissions)? */
    val isSystemSetting: Boolean = false,
    /** Intent для открытия системных настроек (для system-setting разрешений) */
    val systemSettingsIntent: ((Context) -> Intent)? = null
)

/**
 * ViewModel для экрана разрешений (онбординг).
 * Хранит состояние каждого разрешения и управляет флагами завершения онбординга.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor() : ViewModel() {

    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    val permissions: StateFlow<List<PermissionItem>> = _permissions.asStateFlow()

    private val _permissionStates = MutableStateFlow<Map<String, PermissionState>>(emptyMap())
    val permissionStates: StateFlow<Map<String, PermissionState>> = _permissionStates.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(false)
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _allGranted = MutableStateFlow(false)
    val allGranted: StateFlow<Boolean> = _allGranted.asStateFlow()

    init {
        buildPermissionList()
    }

    private fun buildPermissionList() {
        val list = mutableListOf<PermissionItem>()

        list += PermissionItem(
            id = "notifications",
            runtimePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            titleResId = com.pai.android.R.string.perm_notifications,
            descriptionResId = com.pai.android.R.string.perm_notifications_desc,
            icon = "🔔"
        )

        list += PermissionItem(
            id = "location",
            runtimePermissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            titleResId = com.pai.android.R.string.perm_location,
            descriptionResId = com.pai.android.R.string.perm_location_desc,
            icon = "📍"
        )

        list += PermissionItem(
            id = "microphone",
            runtimePermissions = listOf(Manifest.permission.RECORD_AUDIO),
            titleResId = com.pai.android.R.string.perm_microphone,
            descriptionResId = com.pai.android.R.string.perm_microphone_desc,
            icon = "🎤"
        )

        list += PermissionItem(
            id = "camera",
            runtimePermissions = listOf(Manifest.permission.CAMERA),
            titleResId = com.pai.android.R.string.perm_camera,
            descriptionResId = com.pai.android.R.string.perm_camera_desc,
            icon = "📷"
        )

        list += PermissionItem(
            id = "phone",
            runtimePermissions = listOf(Manifest.permission.CALL_PHONE),
            titleResId = com.pai.android.R.string.perm_phone,
            descriptionResId = com.pai.android.R.string.perm_phone_desc,
            icon = "📞"
        )

        list += PermissionItem(
            id = "call_log",
            runtimePermissions = listOf(Manifest.permission.READ_CALL_LOG),
            titleResId = com.pai.android.R.string.perm_call_log,
            descriptionResId = com.pai.android.R.string.perm_call_log_desc,
            icon = "📋"
        )

        list += PermissionItem(
            id = "sms",
            runtimePermissions = listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS),
            titleResId = com.pai.android.R.string.perm_sms,
            descriptionResId = com.pai.android.R.string.perm_sms_desc,
            icon = "✉️"
        )

        list += PermissionItem(
            id = "contacts",
            runtimePermissions = listOf(Manifest.permission.READ_CONTACTS),
            titleResId = com.pai.android.R.string.perm_contacts,
            descriptionResId = com.pai.android.R.string.perm_contacts_desc,
            icon = "👤"
        )

        list += PermissionItem(
            id = "calendar",
            runtimePermissions = listOf(Manifest.permission.READ_CALENDAR),
            titleResId = com.pai.android.R.string.perm_calendar,
            descriptionResId = com.pai.android.R.string.perm_calendar_desc,
            icon = "📅"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += PermissionItem(
                id = "media_images",
                runtimePermissions = listOf(Manifest.permission.READ_MEDIA_IMAGES),
                titleResId = com.pai.android.R.string.perm_media_images,
                descriptionResId = com.pai.android.R.string.perm_media_images_desc,
                icon = "🖼️"
            )
        }

        // Системное разрешение: Notification Listener
        list += PermissionItem(
            id = "notification_listener",
            isSystemSetting = true,
            titleResId = com.pai.android.R.string.perm_notification_listener,
            descriptionResId = com.pai.android.R.string.perm_notification_listener_desc,
            icon = "🔊",
            systemSettingsIntent = { ctx ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        )

        _permissions.value = list

        // Инициализируем состояния: UNKNOWN для всех
        _permissionStates.value = list.associate { it.id to PermissionState.UNKNOWN }
    }

    /**
     * Проверить текущий статус всех разрешений.
     * Должен вызываться из Activity/Fragment при старте.
     */
    fun checkAllPermissions(context: Context) {
        val currentStates = _permissionStates.value.toMutableMap()
        val permList = _permissions.value

        for (item in permList) {
            currentStates[item.id] = checkItemPermission(context, item)
        }

        _permissionStates.value = currentStates
        updateAllGranted()
    }

    /**
     * Проверить одно разрешение.
     */
    private fun checkItemPermission(context: Context, item: PermissionItem): PermissionState {
        if (item.isSystemSetting) {
            return checkSystemPermission(context, item)
        }

        if (item.runtimePermissions.isEmpty()) {
            // Нет runtime-разрешений — считаем granted (старые версии Android)
            return PermissionState.GRANTED
        }

        val allGranted = item.runtimePermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

        return if (allGranted) PermissionState.GRANTED else PermissionState.DENIED
    }

    /**
     * Проверить системное разрешение (например, NotificationListenerService).
     */
    private fun checkSystemPermission(context: Context, item: PermissionItem): PermissionState {
        return when (item.id) {
            "notification_listener" -> {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
                if (enabledListeners?.contains(context.packageName) == true) {
                    PermissionState.GRANTED
                } else {
                    PermissionState.SYSTEM_SETTINGS_REQUIRED
                }
            }
            else -> PermissionState.GRANTED
        }
    }

    /**
     * Установить новое состояние для разрешения (после ответа от диалога разрешений).
     */
    fun setPermissionState(id: String, state: PermissionState) {
        val current = _permissionStates.value.toMutableMap()
        current[id] = state
        _permissionStates.value = current
        updateAllGranted()
    }

    /**
     * Обновить состояние после возврата из системных настроек.
     */
    fun recheckSystemPermissions(context: Context) {
        val currentStates = _permissionStates.value.toMutableMap()
        val permList = _permissions.value

        for (item in permList.filter { it.isSystemSetting }) {
            currentStates[item.id] = checkSystemPermission(context, item)
        }

        _permissionStates.value = currentStates
        updateAllGranted()
    }

    /**
     * Сохранить флаг завершения онбординга.
     */
    fun completeOnboarding(context: Context) {
        context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_complete", true)
            .apply()
        _onboardingComplete.value = true
    }

    /**
     * Проверить, завершён ли онбординг.
     */
    fun isOnboardingComplete(context: Context): Boolean {
        val complete = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
            .getBoolean("onboarding_complete", false)
        _onboardingComplete.value = complete
        return complete
    }

    /**
     * Сбросить онбординг (для отладки или повторного запуска).
     */
    fun resetOnboarding(context: Context) {
        context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_complete", false)
            .apply()
        _onboardingComplete.value = false
    }

    /**
     * Получить список id runtime-разрешений, которые ещё не предоставлены.
     */
    fun getUngrantedRuntimePermissionIds(): List<String> {
        val states = _permissionStates.value
        return _permissions.value
            .filter { !it.isSystemSetting && it.runtimePermissions.isNotEmpty() }
            .filter { states[it.id] != PermissionState.GRANTED }
            .flatMap { it.runtimePermissions }
    }

    /**
     * Получить список PermissionItem, которые требуют системных настроек.
     */
    fun getSystemSettingsItems(): List<PermissionItem> {
        val states = _permissionStates.value
        return _permissions.value
            .filter { it.isSystemSetting }
            .filter { states[it.id] != PermissionState.GRANTED }
    }

    private fun updateAllGranted() {
        val states = _permissionStates.value
        val allGranted = _permissions.value.all { item ->
            states[item.id] == PermissionState.GRANTED
        }
        _allGranted.value = allGranted
    }

    /**
     * Принудительно установить все состояния в GRANTED (для пропуска онбординга).
     */
    fun markAllAsGranted() {
        val states = _permissions.value.associate { it.id to PermissionState.GRANTED }
        _permissionStates.value = states
        _allGranted.value = true
    }
}
