package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DeviceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "regonex_prefs")

class ProfileDataStore(private val context: Context) {

    companion object {
        private val KEY_WIDTH = intPreferencesKey("profile_width")
        private val KEY_HEIGHT = intPreferencesKey("profile_height")
        private val KEY_FPS = intPreferencesKey("profile_fps")
        private val KEY_BITRATE = intPreferencesKey("profile_bitrate")
        private val KEY_AUDIO_BITRATE = intPreferencesKey("profile_audio_bitrate")
        private val KEY_AUDIO_SAMPLE_RATE = intPreferencesKey("profile_audio_sample_rate")
        private val KEY_ENCODER = stringPreferencesKey("profile_encoder")
        private val KEY_VERSION = intPreferencesKey("profile_version")
        private val KEY_TIMESTAMP = longPreferencesKey("profile_timestamp")

        private val KEY_IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        private val KEY_APP_AUDIO = booleanPreferencesKey("is_app_audio_enabled")
        private val KEY_MIC_AUDIO = booleanPreferencesKey("is_mic_audio_enabled")
        private val KEY_AUTO_DND = booleanPreferencesKey("is_auto_dnd_enabled")
        private val KEY_FLOATING_BUTTON = booleanPreferencesKey("is_floating_button_enabled")
        private val KEY_GAME_VOICE_CHAT = booleanPreferencesKey("is_game_voice_chat_enabled")
        private val KEY_AUDIO_BALANCE = floatPreferencesKey("audio_balance")
        private val KEY_BATTERY_OPT_PROMPT_SHOWN = booleanPreferencesKey("battery_opt_prompt_shown")
        private val KEY_VOICE_MODE = stringPreferencesKey("voice_mode")

        private val KEY_SELECTED_RESOLUTION = stringPreferencesKey("selected_resolution")
        private val KEY_SELECTED_FPS = intPreferencesKey("selected_fps")
        private val KEY_SELECTED_BITRATE = intPreferencesKey("selected_bitrate")
        private val KEY_SELECTED_AUDIO_SOURCE = stringPreferencesKey("selected_audio_source")
        private val KEY_SELECTED_ORIENTATION = stringPreferencesKey("selected_orientation")
        private val KEY_ENABLE_OVERLAY_TRIGGER = booleanPreferencesKey("enable_overlay_trigger")
        private val KEY_INITIAL_SCAN_COMPLETED = booleanPreferencesKey("initial_hardware_scan_completed")
        private val KEY_SHORT_CLIP_SECONDS = intPreferencesKey("short_clip_duration_seconds")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DRIVE_ACCOUNT = stringPreferencesKey("drive_account_email")

        private val KEY_LIVE_PLATFORM = stringPreferencesKey("live_platform")
        private val KEY_LIVE_STREAM_URL = stringPreferencesKey("live_stream_url")
        private val KEY_LIVE_STREAM_KEY = stringPreferencesKey("live_stream_key")
        private val KEY_LIVE_STREAM_TITLE = stringPreferencesKey("live_stream_title")
        private val KEY_LIVE_ORIENTATION = stringPreferencesKey("live_stream_orientation")
        private val KEY_LAYOUT_ENABLED = booleanPreferencesKey("live_layout_enabled")
        private val KEY_LAYOUT_BANNER_TEXT = stringPreferencesKey("live_layout_banner_text")
        private val KEY_LAYOUT_BANNER_POS = stringPreferencesKey("live_layout_banner_pos")
        private val KEY_LAYOUT_BADGE_STYLE = stringPreferencesKey("live_layout_badge_style")
        private val KEY_LAYOUT_CAM_PIP = booleanPreferencesKey("live_layout_cam_pip")
        private val KEY_LAYOUT_CAM_POS = stringPreferencesKey("live_layout_cam_pos")
        private val KEY_LAYOUT_WATERMARK = booleanPreferencesKey("live_layout_watermark")
        private val KEY_LAYOUT_WATERMARK_TEXT = stringPreferencesKey("live_layout_watermark_text")
        private val KEY_LIVE_STREAM_HISTORY = stringPreferencesKey("live_stream_history_json")
    }

    val savedProfile: Flow<DeviceProfile?> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val version = prefs[KEY_VERSION] ?: 0
            val width = prefs[KEY_WIDTH] ?: 0
            val height = prefs[KEY_HEIGHT] ?: 0
            val fps = prefs[KEY_FPS] ?: 0
            val bitrate = prefs[KEY_BITRATE] ?: 0

            if (version < DeviceProfile.CURRENT_VERSION || width <= 0 || height <= 0 || fps <= 0 || bitrate <= 0) {
                null
            } else {
                DeviceProfile(
                    width = width,
                    height = height,
                    frameRate = fps,
                    bitrate = bitrate,
                    audioBitrate = prefs[KEY_AUDIO_BITRATE] ?: 160_000,
                    audioSampleRate = prefs[KEY_AUDIO_SAMPLE_RATE] ?: 44_100,
                    encoderName = prefs[KEY_ENCODER] ?: "Hardware H.264",
                    analysisVersion = version,
                    analyzedTimestamp = prefs[KEY_TIMESTAMP] ?: System.currentTimeMillis()
                )
            }
        }

    val isDarkTheme: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_IS_DARK_THEME] ?: true }

    val isAppAudioEnabled: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_APP_AUDIO] ?: true }

    val isMicEnabled: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_MIC_AUDIO] ?: false }

    val isAutoDndEnabled: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_AUTO_DND] ?: true }

    val isFloatingButtonEnabled: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_FLOATING_BUTTON] ?: true }

    val isGameVoiceChatEnabled: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_GAME_VOICE_CHAT] ?: false }

    val audioBalance: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_AUDIO_BALANCE] ?: 0.5f }

    val isBatteryOptPromptShown: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_BATTERY_OPT_PROMPT_SHOWN] ?: false }

    val voiceMode: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_VOICE_MODE] ?: "TOGGLE" }

    suspend fun saveProfile(profile: DeviceProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WIDTH] = profile.width
            prefs[KEY_HEIGHT] = profile.height
            prefs[KEY_FPS] = profile.frameRate
            prefs[KEY_BITRATE] = profile.bitrate
            prefs[KEY_AUDIO_BITRATE] = profile.audioBitrate
            prefs[KEY_AUDIO_SAMPLE_RATE] = profile.audioSampleRate
            prefs[KEY_ENCODER] = profile.encoderName
            prefs[KEY_VERSION] = profile.analysisVersion
            prefs[KEY_TIMESTAMP] = profile.analyzedTimestamp
        }
    }

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_DARK_THEME] = isDark
        }
    }

    suspend fun setAppAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_AUDIO] = enabled
        }
    }

    suspend fun setMicEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MIC_AUDIO] = enabled
        }
    }

    suspend fun setAutoDndEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_DND] = enabled
        }
    }

    suspend fun setFloatingButtonEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FLOATING_BUTTON] = enabled
        }
    }

    suspend fun setGameVoiceChatEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GAME_VOICE_CHAT] = enabled
        }
    }

    suspend fun setAudioBalance(balance: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUDIO_BALANCE] = balance.coerceIn(0.0f, 1.0f)
        }
    }

    suspend fun setBatteryOptPromptShown(shown: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BATTERY_OPT_PROMPT_SHOWN] = shown
        }
    }

    val selectedResolution: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SELECTED_RESOLUTION] ?: "1080p" }

    val selectedFps: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SELECTED_FPS] ?: 60 }

    val selectedBitrate: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SELECTED_BITRATE] ?: 12_000_000 }

    val selectedAudioSource: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SELECTED_AUDIO_SOURCE] ?: "INTERNAL" }

    val enableOverlayTrigger: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_ENABLE_OVERLAY_TRIGGER] ?: false }

    suspend fun setSelectedResolution(res: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SELECTED_RESOLUTION] = res }
    }

    suspend fun setSelectedFps(fps: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_SELECTED_FPS] = fps }
    }

    suspend fun setSelectedBitrate(bitrate: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_SELECTED_BITRATE] = bitrate }
    }

    suspend fun setSelectedAudioSource(source: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SELECTED_AUDIO_SOURCE] = source }
    }

    suspend fun setVoiceMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_MODE] = mode }
    }

    val selectedOrientation: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SELECTED_ORIENTATION] ?: "AUTO" }

    suspend fun setSelectedOrientation(orientation: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SELECTED_ORIENTATION] = orientation }
    }

    suspend fun setEnableOverlayTrigger(enable: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ENABLE_OVERLAY_TRIGGER] = enable }
    }

    val isInitialScanCompleted: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_INITIAL_SCAN_COMPLETED] ?: false }

    suspend fun setInitialScanCompleted(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_INITIAL_SCAN_COMPLETED] = completed }
    }

    val shortClipDurationSeconds: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_SHORT_CLIP_SECONDS] ?: 30 }

    suspend fun setShortClipDurationSeconds(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_SHORT_CLIP_SECONDS] = seconds }
    }

    val themeMode: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_THEME_MODE] ?: "SYSTEM" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode }
    }

    val driveAccountEmail: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_DRIVE_ACCOUNT] ?: "" }

    suspend fun setDriveAccountEmail(email: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DRIVE_ACCOUNT] = email }
    }

    val liveStreamConfig: Flow<com.example.model.LiveStreamConfig> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val platform = prefs[KEY_LIVE_PLATFORM] ?: com.example.model.LiveStreamConfig.PLATFORM_YOUTUBE
            val defaultUrl = com.example.model.LiveStreamConfig.getDefaultUrlForPlatform(platform)
            com.example.model.LiveStreamConfig(
                platform = platform,
                streamUrl = prefs[KEY_LIVE_STREAM_URL] ?: defaultUrl,
                streamKey = prefs[KEY_LIVE_STREAM_KEY] ?: "",
                streamTitle = prefs[KEY_LIVE_STREAM_TITLE] ?: "Mobile Game Live Stream",
                orientation = prefs[KEY_LIVE_ORIENTATION] ?: "HORIZONTAL",
                isLayoutEnabled = prefs[KEY_LAYOUT_ENABLED] ?: true,
                bannerText = prefs[KEY_LAYOUT_BANNER_TEXT] ?: "GAMER_PRO • LIVE",
                bannerPosition = prefs[KEY_LAYOUT_BANNER_POS] ?: "BOTTOM_LEFT",
                badgeStyle = prefs[KEY_LAYOUT_BADGE_STYLE] ?: "LIVE_RED",
                isCameraPipEnabled = prefs[KEY_LAYOUT_CAM_PIP] ?: true,
                cameraPipPosition = prefs[KEY_LAYOUT_CAM_POS] ?: "TOP_RIGHT",
                isWatermarkEnabled = true,
                watermarkText = prefs[KEY_LAYOUT_WATERMARK_TEXT] ?: "GAMER PRO"
            )
        }

    suspend fun saveLiveStreamConfig(config: com.example.model.LiveStreamConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIVE_PLATFORM] = config.platform
            prefs[KEY_LIVE_STREAM_URL] = config.streamUrl
            prefs[KEY_LIVE_STREAM_KEY] = config.streamKey
            prefs[KEY_LIVE_STREAM_TITLE] = config.streamTitle
            prefs[KEY_LIVE_ORIENTATION] = config.orientation
            prefs[KEY_LAYOUT_ENABLED] = config.isLayoutEnabled
            prefs[KEY_LAYOUT_BANNER_TEXT] = config.bannerText
            prefs[KEY_LAYOUT_BANNER_POS] = config.bannerPosition
            prefs[KEY_LAYOUT_BADGE_STYLE] = config.badgeStyle
            prefs[KEY_LAYOUT_CAM_PIP] = config.isCameraPipEnabled
            prefs[KEY_LAYOUT_CAM_POS] = config.cameraPipPosition
            prefs[KEY_LAYOUT_WATERMARK] = true
            prefs[KEY_LAYOUT_WATERMARK_TEXT] = config.watermarkText
        }
    }

    val liveStreamHistory: Flow<List<com.example.model.LiveStreamHistoryItem>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val jsonStr = prefs[KEY_LIVE_STREAM_HISTORY] ?: "[]"
            try {
                val array = org.json.JSONArray(jsonStr)
                val list = mutableListOf<com.example.model.LiveStreamHistoryItem>()
                for (i in 0 until array.length()) {
                    list.add(com.example.model.LiveStreamHistoryItem.fromJson(array.getJSONObject(i)))
                }
                list.sortedByDescending { it.timestamp }
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun addLiveStreamHistoryItem(item: com.example.model.LiveStreamHistoryItem) {
        context.dataStore.edit { prefs ->
            val jsonStr = prefs[KEY_LIVE_STREAM_HISTORY] ?: "[]"
            val array = try {
                org.json.JSONArray(jsonStr)
            } catch (_: Exception) {
                org.json.JSONArray()
            }
            array.put(item.toJson())
            prefs[KEY_LIVE_STREAM_HISTORY] = array.toString()
        }
    }
}
