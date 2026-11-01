package com.suiji.assistant.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.suiji.assistant.SuiJiApp
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.IdleState
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.MemoStatus
import com.suiji.assistant.core.SuiClock
import com.suiji.assistant.core.TodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 提醒派发。
 *
 * 改造前的致命问题：全应用只有一条 `setInexactRepeating(15min)` 的周期巡旚，
 * 而且是在 Application.onCreate 里注册的——每冷启动一次就把这条闹钟「重新计时」，
 * 频繁打开 App 就等于永远不触发；再叠加 Doze 对非精确周期闹钟的压制、
 * 以及 IdleDetector 的「息屏直接 BLOCKED」，结果就是「一个通知都收不到」。
 *
 * 改造后是三层：
 *  1. 每条待办一个 setExactAndAllowWhileIdle 精确闹钟（Doze 下也能唤醒）；
 *  2. 15 分钟兜底巡检（自续期，用 set 而不是 setInexactRepeating，不会被打断重置）；
 *  3. 亮屏 / 开机 / 改时间 / 换时区 时重新对账，把错过的补上。
 */
object ReminderEngine {

    /**
     * 派发闸门。
     * 到点闹钟、兜底巡检、亮屏对账可能在同一秒内都触发同一条待办，
     * 读-改-写不是原子的，会连发两三条一模一样的通知。
     */
    private val gate = Any()

    /** 同一条待办的最小重发间隔，压在闸门内判断 */
    private const val SAME_MEMO_QUIET_MS = AppConfig.REMIND_REPEAT_MS

    sealed interface Outcome {
        data class Fired(val memo: Memo, val score: Int, val extra: Int) : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    /** 单条派发：精确闹钟触发时走这里 */
    fun dispatchOne(ctx: Context, memoId: String): Outcome = synchronized(gate) {
        val app = ctx.applicationContext as SuiJiApp
        if (!app.settings.reminderEnabled) return Outcome.Skipped("提醒已关闭")

        val memo = app.repo.get(memoId) ?: return Outcome.Skipped("记录不存在")
        if (!memo.isTodo || memo.todoStatus != TodoStatus.PENDING ||
            memo.status != MemoStatus.ACTIVE
        ) {
            return Outcome.Skipped("已完成或已删除")
        }
        if (memo.dueAt == null) return Outcome.Skipped("无时间")
        if (memo.remindCount >= AppConfig.MAX_FIRES_PER_MEMO) {
            return Outcome.Skipped("已达提醒次数上限")
        }
        val now = SuiClock.now()
        if (memo.snoozedUntil != null && memo.snoozedUntil > now) {
            return Outcome.Skipped("稍后窗口内")
        }
        if (memo.lastRemindAt != null && now - memo.lastRemindAt < SAME_MEMO_QUIET_MS) {
            return Outcome.Skipped("刚提过")
        }

        val detector = IdleDetector(ctx, app.settings)
        val assessment = detector.assess(now)
        if (assessment.state == IdleState.BLOCKED) {
            // 拦截不是「丢掉」：记一条 DEFERRED，10 分钟后再来
            app.repo.logReminder(
                memo.id, now, assessment.score, "SYSTEM_NOTIFICATION", "DEFERRED",
                assessment.reasons.joinToString("|")
            )
            return Outcome.Skipped("静默: ${assessment.reasons.joinToString()}")
        }

        val extra = app.repo.overdueUnreminded(now).count { it.id != memo.id }
        val posted = app.notifier.showSoftReminder(memo, extraCount = extra, idleScore = assessment.score)
        app.repo.logReminder(
            memo.id, now, assessment.score, "SYSTEM_NOTIFICATION",
            if (posted) "SHOWN" else "FAILED_NO_PERMISSION",
            assessment.reasons.joinToString("|")
        )
        if (!posted) return Outcome.Skipped("通知被系统拒绝（权限或渠道）")

        app.repo.markReminded(memo.id, memo.remindCount + 1, now)
        app.settings.lastFiredAt = now
        return Outcome.Fired(memo, assessment.score, extra)
    }

    /** 兜底巡检：补发所有错过的（每次只补一条，避免一次性轰炸） */
    fun run(ctx: Context): Outcome {
        val app = ctx.applicationContext as SuiJiApp
        if (!app.settings.reminderEnabled) return Outcome.Skipped("提醒已关闭")

        val now = SuiClock.now()
        if (now - app.settings.lastFiredAt < AppConfig.MIN_GAP_BETWEEN_ANY_MS) {
            return Outcome.Skipped("全局冷却中")
        }

        val candidate = app.repo.overdueUnreminded(now).firstOrNull()
            ?: return Outcome.Skipped("无到点待办")

        val detector = IdleDetector(ctx, app.settings)
        if (detector.assess(now).state == IdleState.BLOCKED) {
            return Outcome.Skipped("当前不适合打扰")
        }
        return dispatchOne(ctx, candidate.id)
    }
}

/** 把「哪条待办、几点响」翻译成系统闹钟。 */
object AlarmScheduler {

    private const val ACTION_DUE = "com.suiji.assistant.DUE"
    private const val ACTION_ASSESS = "com.suiji.assistant.ASSESS"

    /** 需要排的最多条数，防止极端数据把闹钟表塞满 */
    private const val MAX_ALARMS = 12

    fun sync(ctx: Context) {
        val app = ctx.applicationContext as? SuiJiApp ?: return
        val now = SuiClock.now()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val overdue = app.repo.overdueUnreminded(now)
        val upcoming = app.repo.schedulable(now)

        // 已错过：5 秒后立刻补一条（而不是等下一次巡检）。
        // 但必须跳过「刚刚提过的」——否则每次冷启动都会重发一遍，
        // 用户会看到同一条待办反复弹通知。
        val catchUp = overdue.firstOrNull { m ->
            m.lastRemindAt == null || now - m.lastRemindAt > AppConfig.REMIND_REPEAT_MS
        }
        if (catchUp != null) {
            setAlarm(ctx, am, now + 5_000, dueIntent(ctx, catchUp.id), requestCode(catchUp.id))
        }

        upcoming.take(MAX_ALARMS).forEach { m ->
            val due = m.dueAt ?: return@forEach
            if (due <= now) return@forEach
            setAlarm(ctx, am, due, dueIntent(ctx, m.id), requestCode(m.id))
        }

        // 「稍后」到期也要准点回来，否则 10 分钟的承诺会变成 15-25 分钟
        app.repo.nextSnoozeWake(now)?.let { at ->
            setAlarm(ctx, am, at, assessIntent(ctx, 2), 2)
        }

        // 兜底巡检（自续期）。用 set 而非 setInexactRepeating：
        // 周期闹钟会在每次冷启动时被重新计时，等于永远不触发。
        setAlarm(
            ctx, am, now + AppConfig.ASSESS_INTERVAL_MS,
            assessIntent(ctx), 1, exact = false
        )

        app.settings.lastScheduleAt = now
    }

    fun cancelFor(ctx: Context, memoId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(dueIntent(ctx, memoId)) }
    }

    private fun setAlarm(
        ctx: Context,
        am: AlarmManager,
        at: Long,
        pi: PendingIntent,
        req: Int,
        exact: Boolean = true
    ) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true

        runCatching {
            when {
                exact && canExact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                // 没有精确闹钟权限时退一步：Doze 下仍会被唤醒，只是误差几分钟
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** requestCode 用 id 的哈希低 15 位，稳定且不会超过系统上限 */
    private fun requestCode(memoId: String): Int = AppConfig.ALARM_REQ_BASE + (memoId.hashCode() and 0x7FFF)

    private fun dueIntent(ctx: Context, memoId: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode(memoId),
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = ACTION_DUE
                putExtra(EXTRA_MEMO_ID, memoId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun assessIntent(ctx: Context, req: Int = 1): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_ASSESS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    const val EXTRA_MEMO_ID = "memo_id"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val action = intent.action
        val memoId = intent.getStringExtra(AlarmScheduler.EXTRA_MEMO_ID)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == "com.suiji.assistant.DUE" && !memoId.isNullOrBlank()) {
                    ReminderEngine.dispatchOne(app, memoId)
                } else {
                    ReminderEngine.run(app)
                }
                AlarmScheduler.sync(app)
            } catch (_: Throwable) {
                // 闹钟线程不能崩，崩了就再也不会有下一次
            } finally {
                pending.finish()
            }
        }
    }
}

/** 通知上的「稍后 / 完成」按钮 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SuiJiApp ?: return
        val memoId = intent.getStringExtra(EXTRA_MEMO_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = SuiClock.now()
                when (intent.action) {
                    ACTION_SNOOZE -> {
                        app.repo.setSnooze(memoId, now + AppConfig.SNOOZE_MINUTES * 60_000L)
                        app.repo.logReminder(memoId, now, 0, "NOTIFICATION_ACTION", "SNOOZE", "user")
                        app.notifier.cancelSoft()
                        AlarmScheduler.cancelFor(app, memoId)
                        AlarmScheduler.sync(app)
                    }

                    ACTION_DONE -> {
                        app.repo.setTodoStatus(memoId, TodoStatus.DONE)
                        app.repo.logReminder(memoId, now, 0, "NOTIFICATION_ACTION", "DONE", "user")
                        app.notifier.cancelSoft()
                        AlarmScheduler.cancelFor(app, memoId)
                        AlarmScheduler.sync(app)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.suiji.assistant.SNOOZE"
        const val ACTION_DONE = "com.suiji.assistant.DONE"
        const val EXTRA_MEMO_ID = "memo_id"
    }
}

/** 开机 / 应用更新：把闹钟全部按最新数据重建 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> AlarmScheduler.sync(context)
        }
    }
}

/**
 * 用户改了系统时间、换了时区、跨了一天 —— 必须重新对账。
 * 改造前没人监听这三个广播，导致「改了时间之后所有提醒都按旧基准触发」。
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                val app = context.applicationContext as? SuiJiApp ?: return
                // 时间大幅跳变后，旧的「亮屏时刻」已不可比，直接作废
                app.settings.lastScreenOnAt = 0L
                AlarmScheduler.sync(context)
            }
        }
    }
}

/**
 * 屏幕亮起 / 解锁时间戳记录。顺带做一次对账：
 * 用户解锁时如果有错过的提醒，这时补发最自然。
 */
class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SuiJiApp ?: return
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                app.settings.lastScreenOnAt = SuiClock.now()
                AlarmScheduler.sync(context)
            }

            Intent.ACTION_USER_PRESENT -> {
                app.settings.lastInteractionAt = SuiClock.now()
                AlarmScheduler.sync(context)
            }
        }
    }
}
