package com.suiji.assistant.core

import android.content.Context
import androidx.core.content.edit
import com.suiji.assistant.BuildConfig

/**
 * 设置项。API Key 单独走 SecureStore，此处只存非敏感项。
 */
class Settings(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("suiji_settings", Context.MODE_PRIVATE)

    var providerId: String
        get() = prefs.getString("provider_id", null) ?: BuildConfig.LLM_PROVIDER
        set(v) = prefs.edit { putString("provider_id", v) }

    /** 空串表示使用预设地址 */
    var customBaseUrl: String
        get() = prefs.getString("base_url", null) ?: BuildConfig.LLM_BASE_URL
        set(v) = prefs.edit { putString("base_url", v) }

    var customModel: String
        get() = prefs.getString("model", null) ?: BuildConfig.LLM_MODEL
        set(v) = prefs.edit { putString("model", v) }

    var reminderEnabled: Boolean
        get() = prefs.getBoolean("reminder_enabled", true)
        set(v) = prefs.edit { putBoolean("reminder_enabled", v) }

    /** 空闲阈值，越大越克制 */
    var idleThreshold: Int
        get() = prefs.getInt("idle_threshold", AppConfig.IDLE_SCORE_THRESHOLD)
        set(v) = prefs.edit { putInt("idle_threshold", v) }

    var quietStartMin: Int
        get() = prefs.getInt("quiet_start", AppConfig.QUIET_START_MIN)
        set(v) = prefs.edit { putInt("quiet_start", v) }

    var quietEndMin: Int
        get() = prefs.getInt("quiet_end", AppConfig.QUIET_END_MIN)
        set(v) = prefs.edit { putInt("quiet_end", v) }

    var lastConnTestAt: Long
        get() = prefs.getLong("last_conn_at", 0L)
        set(v) = prefs.edit { putLong("last_conn_at", v) }

    var lastConnTestOk: Boolean
        get() = prefs.getBoolean("last_conn_ok", false)
        set(v) = prefs.edit { putBoolean("last_conn_ok", v) }

    var lastConnLatency: Long
        get() = prefs.getLong("last_conn_latency", 0L)
        set(v) = prefs.edit { putLong("last_conn_latency", v) }

    var lastConnError: String?
        get() = prefs.getString("last_conn_err", null)
        set(v) = prefs.edit { putString("last_conn_err", v) }

    var lastFiredAt: Long
        get() = prefs.getLong("last_fired_at", 0L)
        set(v) = prefs.edit { putLong("last_fired_at", v) }

    var lastScreenOnAt: Long
        get() = prefs.getLong("screen_on_at", 0L)
        set(v) = prefs.edit { putLong("screen_on_at", v) }

    var lastInteractionAt: Long
        get() = prefs.getLong("interaction_at", 0L)
        set(v) = prefs.edit { putLong("interaction_at", v) }

    // ---- 提醒排期（v1.2） ----

    /** 上一次给系统排闹钟的时刻，用于避免重复排期 */
    var lastScheduleAt: Long
        get() = prefs.getLong("last_schedule_at", 0L)
        set(v) = prefs.edit { putLong("last_schedule_at", v) }

    /** 是否已经弹过一次通知权限申请，避免每次启动都打扰 */
    var notifAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(v) = prefs.edit { putBoolean("notif_asked", v) }

    /** 到点是否补一条「错过提醒」 */
    var catchUpMissed: Boolean
        get() = prefs.getBoolean("catch_up_missed", true)
        set(v) = prefs.edit { putBoolean("catch_up_missed", v) }

    // ---- 系统日历（v1.2） ----

    var calendarSyncEnabled: Boolean
        get() = prefs.getBoolean("cal_sync", false)
        set(v) = prefs.edit { putBoolean("cal_sync", v) }

    /** 选中的可写日历 id，-1 表示自动挑一个 */
    var calendarId: Long
        get() = prefs.getLong("cal_id", -1L)
        set(v) = prefs.edit { putLong("cal_id", v) }

    var calendarLeadMinutes: Int
        get() = prefs.getInt("cal_lead", AppConfig.CAL_LEAD_MINUTES)
        set(v) = prefs.edit { putInt("cal_lead", v) }

    /** 已写进日历的条数，用于设置页展示 */
    var calendarSyncedCount: Int
        get() = prefs.getInt("cal_synced", 0)
        set(v) = prefs.edit { putInt("cal_synced", v) }

    // ---- 解析后的有效配置 ----

    fun provider(): LlmProvider = LlmProviders.byId(providerId)

    fun effectiveBaseUrl(): String {
        val p = provider()
        return if (p.id == LlmProviders.CUSTOM_ID) customBaseUrl.trim() else p.baseUrl
    }

    fun effectiveModel(): String {
        val p = provider()
        val saved = customModel.trim()
        return when {
            p.id == LlmProviders.CUSTOM_ID -> saved
            saved.isNotBlank() -> saved
            else -> p.defaultModel
        }
    }

    fun apiKey(): String? {
        val k = SecureStore.getApiKey(ctx, providerId)
        if (!k.isNullOrBlank()) return k
        return BuildConfig.LLM_API_KEY.takeIf { it.isNotBlank() }
    }

    fun hasUsableConfig(): Boolean =
        effectiveBaseUrl().isNotBlank() && effectiveModel().isNotBlank() && !apiKey().isNullOrBlank()

    fun llmConfig(): LlmConfig = LlmConfig(
        providerId = providerId,
        providerName = provider().name,
        baseUrl = effectiveBaseUrl(),
        model = effectiveModel(),
        apiKey = apiKey(),
        jsonMode = provider().jsonMode
    )

    // ---- 语音转写 ----

    /** auto / system / cloud */
    var asrMode: String
        get() = prefs.getString("asr_mode", ASR_AUTO) ?: ASR_AUTO
        set(v) = prefs.edit { putString("asr_mode", v) }

    /** 空串表示跟随当前大模型服务商的 base_url */
    var asrBaseUrl: String
        get() = prefs.getString("asr_base_url", "") ?: ""
        set(v) = prefs.edit { putString("asr_base_url", v) }

    var asrModel: String
        get() = prefs.getString("asr_model", "") ?: ""
        set(v) = prefs.edit { putString("asr_model", v) }

    fun effectiveAsrBaseUrl(): String =
        asrBaseUrl.trim().ifBlank { effectiveBaseUrl() }

    fun effectiveAsrModel(): String {
        val saved = asrModel.trim()
        if (saved.isNotBlank()) return saved
        return provider().asrModel
    }

    /**
     * 云端转写是否真的可用。
     *
     * 关键：DeepSeek / 智谱 / 通义 / Kimi / 火山 / 千帆 / MiniMax 这些服务商
     * **根本没有 OpenAI 兼容的 /audio/transcriptions 接口**，它们的 asrModel 为空。
     * 以前这里不判断，会拿着 DeepSeek 的地址去请求转写，必然 404 ——
     * 用户看到的现象就是「大模型明明接上了，却转不了文字」。
     */
    fun cloudAsrUsable(): Boolean {
        if (asrMode == ASR_OFFLINE) return false
        if (apiKey().isNullOrBlank()) return false
        if (effectiveAsrBaseUrl().isBlank()) return false
        if (effectiveAsrModel().isBlank()) return false
        // 必须是「该服务商自带转写能力」或「用户单独指定了转写地址」，
        // 否则就是拿对话模型的地址去请求转写，必然 404。
        val dedicated = asrBaseUrl.trim().isNotBlank()
        val providerCapable = provider().asrModel.isNotBlank()
        return dedicated || providerCapable
    }

    fun asrConfig(): AsrConfig = AsrConfig(
        baseUrl = effectiveAsrBaseUrl(),
        model = effectiveAsrModel(),
        apiKey = apiKey(),
        language = "zh",
        mode = asrMode
    )

    companion object {
        /** 自动：能云端就云端，否则走内置离线识别 */
        const val ASR_AUTO = "auto"
        /** 强制系统 SpeechRecognizer（国产 ROM 多数不可用） */
        const val ASR_SYSTEM = "system"
        /** 强制云端 /audio/transcriptions */
        const val ASR_CLOUD = "cloud"
        /** 强制内置离线识别：不联网、不要 Key */
        const val ASR_OFFLINE = "offline"
    }
}

data class AsrConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val language: String = "zh",
    val mode: String = Settings.ASR_AUTO
)

data class LlmConfig(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val jsonMode: Boolean
)
