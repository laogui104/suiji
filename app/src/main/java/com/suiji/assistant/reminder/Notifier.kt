package com.suiji.assistant.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suiji.assistant.MainActivity
import com.suiji.assistant.R
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.SuiClock
import com.suiji.assistant.core.TimeNormalizer

/**
 * 通知出口。
 *
 * 改造前的两个问题：
 *  1. 渠道用 IMPORTANCE_LOW —— 系统不会在状态栏给图标，也不会有任何横幅，
 *     用户主观上就是「一条通知都没有」。
 *  2. `canPost()` 为 false 时直接 return，连日志都不写，排查时无从下手。
 *
 * 改造后：
 *  - 渠道升级为 IMPORTANCE_DEFAULT，但 sound/vibration 全关 → 出现在通知栏、不响不震；
 *    渠道重要性创建后不可修改，所以 id 换成了 suiji_soft_v2，保证升级生效。
 *  - 通知带「稍后 10 分钟」「标记完成」两个动作，不用打开 App 就能处理。
 *  - post() 返回布尔并写 reminder_log，失败原因可查。
 */
class Notifier(private val ctx: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val soft = NotificationChannel(
            AppConfig.CH_SOFT, "待办提醒", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "到点提醒你，无声、无震动，只在通知栏出现"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val sys = NotificationChannel(
            AppConfig.CH_SYSTEM, "系统提示", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "权限引导与异常提示"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(soft)
        nm.createNotificationChannel(sys)
    }

    /** @return true 表示真的进了通知栏 */
    fun showSoftReminder(memo: Memo, extraCount: Int = 0, idleScore: Int = 0): Boolean {
        ensureChannels()
        if (!canPost()) return false

        val open = PendingIntent.getActivity(
            ctx, memo.id.hashCode(),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_memo_id", memo.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = TimeNormalizer.format(memo.dueAt, memo.duePrecision)
        val text = buildString {
            append(timeText)
            // 准点发出时不要显示「已过点」——那一秒的毫秒差没有意义
            val lateBy = memo.dueAt?.let { SuiClock.now() - it } ?: 0L
            if (lateBy > 60_000L) append(" · 已过点")
            if (extraCount > 0) append(" · 还有 $extraCount 条")
            if (idleScore >= 70) append(" · 现在应该有空")
        }

        val n = NotificationCompat.Builder(ctx, AppConfig.CH_SOFT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(memo.title.ifBlank { memo.contentRaw.take(24) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(memo.dueAt ?: SuiClock.now())
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "稍后 ${AppConfig.SNOOZE_MINUTES} 分钟", actionPi(memo.id, ReminderActionReceiver.ACTION_SNOOZE))
            .addAction(0, "标记完成", actionPi(memo.id, ReminderActionReceiver.ACTION_DONE))
            .build()

        return runCatching {
            NotificationManagerCompat.from(ctx).notify(AppConfig.NOTIFY_ID_SOFT, n)
            true
        }.getOrDefault(false)
    }

    /** 设置页「试一下提醒」，直接验证权限 + 渠道是否通路 */
    fun showTest(): Boolean {
        ensureChannels()
        if (!canPost()) return false
        val n = NotificationCompat.Builder(ctx, AppConfig.CH_SOFT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("提醒通路正常")
            .setContentText("这是一条测试提醒，看到就说明权限与渠道都没问题")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(ctx).notify(AppConfig.NOTIFY_ID_TEST, n)
            true
        }.getOrDefault(false)
    }

    fun cancelSoft() {
        runCatching { NotificationManagerCompat.from(ctx).cancel(AppConfig.NOTIFY_ID_SOFT) }
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun actionPi(memoId: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            (memoId + action).hashCode(),
            Intent(ctx, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra(ReminderActionReceiver.EXTRA_MEMO_ID, memoId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
