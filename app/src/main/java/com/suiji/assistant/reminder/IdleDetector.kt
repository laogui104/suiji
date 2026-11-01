package com.suiji.assistant.reminder

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.IdleAssessment
import com.suiji.assistant.core.IdleState
import com.suiji.assistant.core.Settings
import com.suiji.assistant.core.SuiClock
import java.time.Instant

/**
 * 闲时判定：决定「现在提这件事合不合适」。
 *
 * 改造前（v1.1）这里是提醒发不出去的第一原因：
 *  - `!power.isInteractive` 直接 BLOCKED（息屏），而手机放桌上时几乎总是息屏，
 *    所以到点的待办永远不会提醒；只有用户刚好亮着屏、且放下不动 90 秒以上才可能发。
 *  - 亮屏时长依赖 `settings.lastScreenOnAt`，而这个值只有收到 SCREEN_ON 广播才会写入。
 *    Application 冷启动时屏幕已经是亮的，收不到那次广播 → 该值长期为 0 →
 *    「亮屏时长」这项恒为 0 分 → 最高只能拿到 60 分 < 阈值 70 → 永远判不到 IDLE。
 *
 * 改造后：
 *  - 息屏不再拦截。柔性通知本身静默（无声音/无震动/不弹横幅），息屏发出也不打扰。
 *  - 只保留真正该拦的：安静时段、通话中、系统免打扰、正在打游戏/看视频。
 *  - 亮屏时长取不到时给中性分而不是 0 分，并且阈值下调到 60。
 *  - 评分只影响「用哪种措辞」，不再决定「发不发」。
 */
class IdleDetector(
    private val ctx: Context,
    private val settings: Settings
) {

    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun assess(now: Long = SuiClock.now()): IdleAssessment {
        val reasons = mutableListOf<String>()

        // ---------- 一票否决：真的不该出现的场景 ----------
        if (inQuietHours(now)) {
            return IdleAssessment(IdleState.BLOCKED, 0, listOf("安静时段"))
        }
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            return IdleAssessment(IdleState.BLOCKED, 0, listOf("通话中"))
        }
        val dnd = dndFilter()
        if (dnd != null && dnd != NotificationManager.INTERRUPTION_FILTER_ALL) {
            return IdleAssessment(IdleState.BLOCKED, 0, listOf("系统免打扰"))
        }

        // ---------- 息屏：允许，而且是静默发 ----------
        if (!power.isInteractive) {
            return IdleAssessment(IdleState.IDLE, 100, listOf("息屏", "静默发送"))
        }

        reasons += if (keyguard.isKeyguardLocked) "锁屏亮屏" else "亮屏使用中"

        var score = 0

        // 1) 亮屏时长（30）—— 取不到就给中性分，不能因为拿不到信号就判「不空闲」
        val screenOnAt = effectiveScreenOnAt(now)
        val screenOnFor = if (screenOnAt > 0) now - screenOnAt else -1L
        score += when {
            screenOnFor < 0 -> 15
            screenOnFor > 10 * 60_000 -> 30
            screenOnFor > 3 * 60_000 -> 22
            screenOnFor > 60_000 -> 10
            else -> 0
        }
        reasons += if (screenOnFor < 0) "亮屏时长未知" else "亮屏${screenOnFor / 1000}s"

        // 2) 无操作时长（35）
        // 两个来源都可能是 0（没授 UsageStats、冷启动没收到广播）。
        // 旧写法 maxOf(now - 0, ...) 会把「无操作时长」算成从 1970 年到现在，
        // 日志里出现「静置1793519160s」这种荒谬值，还白送满分。
        val lastTouch = listOf(lastInteractionAt(), settings.lastInteractionAt)
            .filter { it > 0 }
            .maxOrNull()
        val idleFor = if (lastTouch == null) -1L else now - lastTouch
        score += when {
            idleFor < 0 -> 15
            idleFor > AppConfig.IDLE_MIN_DURATION_MS * 4 -> 35
            idleFor > AppConfig.IDLE_MIN_DURATION_MS -> 25
            idleFor > 30_000 -> 12
            else -> 0
        }
        reasons += if (idleFor < 0) "无操作时长未知" else "静置${idleFor / 1000}s"

        // 3) 前台应用（20）
        val category = foregroundCategory()
        score += when (category) {
            "ENTERTAINMENT" -> 20
            "SOCIAL" -> 14
            "SYSTEM" -> 12
            "IM" -> 6
            "WORK" -> 0
            else -> 10
        }
        reasons += "前台=$category"

        // 4) 充电（5）
        if (isCharging()) score += 5

        // 5) 正在打游戏/看视频时不打扰（这类才真正需要拦）
        if (category == "ENTERTAINMENT" && idleFor < AppConfig.IDLE_MIN_DURATION_MS) {
            return IdleAssessment(IdleState.BLOCKED, score, reasons + "正在娱乐")
        }

        val threshold = settings.idleThreshold
        val state = when {
            score >= threshold -> IdleState.IDLE
            score >= (threshold - 25).coerceAtLeast(0) -> IdleState.SEMI_IDLE
            else -> IdleState.ACTIVE
        }
        return IdleAssessment(state, score, reasons)
    }

    fun inQuietHours(now: Long): Boolean {
        val zdt = Instant.ofEpochMilli(now).atZone(SuiClock.zone())
        val minutes = zdt.hour * 60 + zdt.minute
        val s = settings.quietStartMin
        val e = settings.quietEndMin
        return if (s <= e) minutes in s until e else minutes >= s || minutes < e
    }

    /**
     * 亮屏时刻。优先用自己记的；没有就用 UsageStats 的 SCREEN_INTERACTIVE 事件补；
     * 还没有就返回 -1 交给调用方给中性分。
     */
    private fun effectiveScreenOnAt(now: Long): Long {
        if (settings.lastScreenOnAt in 1..now) return settings.lastScreenOnAt
        val fromStats = runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val events = usm.queryEvents(now - 6 * 3600_000L, now)
            val e = android.app.usage.UsageEvents.Event()
            var last = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE) {
                    if (e.timeStamp > last) last = e.timeStamp
                }
            }
            last
        }.getOrDefault(0L)
        if (fromStats > 0) {
            settings.lastScreenOnAt = fromStats
            return fromStats
        }
        return -1L
    }

    private fun lastInteractionAt(): Long {
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val end = SuiClock.now()
            val events = usm.queryEvents(end - 6 * 3600_000L, end)
            val e = android.app.usage.UsageEvents.Event()
            var last = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    e.eventType == android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
                ) {
                    if (e.timeStamp > last) last = e.timeStamp
                }
            }
            last
        }.getOrDefault(0L)
    }

    private val ENTERTAINMENT = listOf(
        "douyin", "aweme", "kuaishou", "bilibili", "iqiyi", "qiyi", "youku", "tencentvideo",
        "weibo", "xiaohongshu", "game", "tmgp", "smoba"
    )
    private val SOCIAL = listOf("wechat", "tencent.mm", "moments", "qq")
    private val IM = listOf("dingtalk", "wxwork", "telegram", "whatsapp", "feishu", "lark")
    private val WORK = listOf("wps", "office", "notion", "chrome", "browser", "mail", "exmail")

    private fun foregroundCategory(): String {
        val pkg = runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val end = SuiClock.now()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, end - 3600_000L, end
            ) ?: return "UNKNOWN"
            stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: return "UNKNOWN"
        }.getOrDefault("UNKNOWN")

        val p = pkg.lowercase()
        return when {
            p == ctx.packageName -> "SYSTEM"
            ENTERTAINMENT.any { p.contains(it) } -> "ENTERTAINMENT"
            IM.any { p.contains(it) } -> "IM"
            SOCIAL.any { p.contains(it) } -> "SOCIAL"
            WORK.any { p.contains(it) } -> "WORK"
            else -> "UNKNOWN"
        }
    }

    private fun isCharging(): Boolean = runCatching {
        ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
    }.getOrDefault(false)

    private fun dndFilter(): Int? = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) null else nm.currentInterruptionFilter
    }.getOrNull()
}
