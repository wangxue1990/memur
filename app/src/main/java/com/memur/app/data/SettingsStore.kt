package com.memur.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    /** direct：语音转写后直接发送 / confirm：先上屏到输入框，用户确认后再发送 */
    val voiceSendMode: String,
    /** 整理结果免确认直接执行 */
    val autoExecute: Boolean,
    /** 查询回答自动语音播报 */
    val autoSpeak: Boolean,
    /** system / light / dark */
    val darkMode: String,
    /** 创建提醒时同步写入系统日历 */
    val syncCalendar: Boolean
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val LLM_BASE = stringPreferencesKey("llm_base_url")
        val LLM_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val VOICE_SEND_MODE = stringPreferencesKey("voice_send_mode")
        val AUTO_EXECUTE = booleanPreferencesKey("auto_execute")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SYNC_CALENDAR = booleanPreferencesKey("sync_calendar")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
        const val DEFAULT_MODEL = "glm-5.2"
        val DEFAULT = AppSettings(
            llmBaseUrl = DEFAULT_BASE_URL, llmApiKey = "", llmModel = DEFAULT_MODEL,
            voiceSendMode = "direct", autoExecute = false, autoSpeak = false,
            darkMode = "system", syncCalendar = false
        )
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            llmBaseUrl = p[Keys.LLM_BASE] ?: DEFAULT_BASE_URL,
            llmApiKey = p[Keys.LLM_KEY] ?: "",
            llmModel = p[Keys.LLM_MODEL] ?: DEFAULT_MODEL,
            voiceSendMode = p[Keys.VOICE_SEND_MODE] ?: "direct",
            autoExecute = p[Keys.AUTO_EXECUTE] ?: false,
            autoSpeak = p[Keys.AUTO_SPEAK] ?: false,
            darkMode = p[Keys.DARK_MODE] ?: "system",
            syncCalendar = p[Keys.SYNC_CALENDAR] ?: false
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setLlmConfig(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.LLM_BASE] = baseUrl.trim()
            p[Keys.LLM_KEY] = apiKey.trim()
            p[Keys.LLM_MODEL] = model.trim()
        }
    }

    suspend fun setVoiceSendMode(v: String) = context.dataStore.edit { it[Keys.VOICE_SEND_MODE] = v }
    suspend fun setAutoExecute(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_EXECUTE] = v }
    suspend fun setAutoSpeak(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_SPEAK] = v }
    suspend fun setDarkMode(v: String) = context.dataStore.edit { it[Keys.DARK_MODE] = v }
    suspend fun setSyncCalendar(v: Boolean) = context.dataStore.edit { it[Keys.SYNC_CALENDAR] = v }
}
