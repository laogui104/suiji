package com.suiji.assistant.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.DuePrecision
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.Settings
import com.suiji.assistant.core.SuiClock
import java.time.ZoneOffset

/**
 * 系统日历桥。
 *
 * 为什么选「直接写 CalendarContract」而不是别的方案：
 *  - 系统日历的提醒由系统自己守着，不受国产 ROM 杀后台 / 自启动限制影响，
 *    这对「提醒必须准时」是决定性的；
 *  - 写进去之后用户在日历里能看到、能改、能删，符合「这是你的数据」的直觉；
 *  - 代价是要 READ/WRITE_CALENDAR 两个运行时权限，所以做成默认关闭的开关。
 *
 * 降级路径：没有权限时用 ACTION_INSERT 打开系统新建事件界面，由用户点一下确认。
 */
class CalendarBridge(
    private val ctx: Context,
    private val settings: Settings,
    private val repo: MemoRepo
) {

    data class CalInfo(val id: Long, val name: String, val account: String)

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 可写日历（能插事件的），按「本地日历优先」排序 */
    fun calendars(): List<CalInfo> {
        if (!hasPermission()) return emptyList()
        val out = ArrayList<CalInfo>()
        runCatching {
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
            )
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "日历",
                        account = c.getString(3) ?: ""
                    )
                }
            }
        }
        return out
    }

    fun targetCalendarId(): Long? {
        val list = calendars()
        if (list.isEmpty()) {
            // 有些机器（新装系统、没登录账号）一个可写日历都没有。
            // 这时建一个「随记」本地日历，否则同步功能等于摆设。
            return ensureLocalCalendar()
        }
        val wanted = settings.calendarId
        if (wanted > 0 && list.any { it.id == wanted }) return wanted
        // 优先本地日历（ACCOUNT_TYPE == LOCAL），兼容性最好
        return list.firstOrNull { it.account.equals("LOCAL", true) }?.id ?: list.first().id
    }

    /** 建一个只存在于本机的日历，账户类型必须是 LOCAL 才不需要 sync adapter */
    private fun ensureLocalCalendar(): Long? = runCatching {
        val base = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val cv = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "随记")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2562EB.toInt())
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        ctx.contentResolver.insert(base, cv)?.let {
            val id = ContentUris.parseId(it)
            settings.calendarId = id
            id
        }
    }.getOrNull()

    /**
     * 写入或更新一条待办。
     * @return 事件 id；失败返回 null
     */
    fun push(memo: Memo): Long? {
        if (!settings.calendarSyncEnabled) return null
        if (!hasPermission()) return null
        val due = memo.dueAt ?: return null
        val calId = targetCalendarId() ?: return null

        val allDay = memo.duePrecision == DuePrecision.DATE_ONLY
        val start: Long
        val end: Long
        if (allDay) {
            // 全天事件：CalendarContract 要求按 UTC 零点换算
            val d = SuiClock.localDate(due)
            start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            end = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            start = due
            end = due + 30 * 60_000L
        }

        val cv = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, memo.title.ifBlank { "随记待办" })
            put(CalendarContract.Events.DESCRIPTION, memo.contentRaw)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, SuiClock.zone().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        val existing = memo.calendarEventId
        val eventId: Long? = runCatching {
            if (existing != null && existing > 0) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing)
                val n = ctx.contentResolver.update(uri, cv, null, null)
                if (n > 0) existing else insert(cv)
            } else {
                insert(cv)
            }
        }.getOrNull()

        if (eventId != null && eventId > 0) {
            runCatching { ensureReminder(eventId) }
            runCatching { repo.setCalendarEvent(memo.id, eventId) }
            settings.calendarSyncedCount = settings.calendarSyncedCount + if (existing == null) 1 else 0
            return eventId
        }
        return null
    }

    fun remove(memo: Memo): Boolean {
        val id = memo.calendarEventId ?: return false
        if (!hasPermission()) return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            ctx.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false).also {
            if (it) runCatching { repo.setCalendarEvent(memo.id, null) }
        }
    }

    /** 没有写入权限时的降级：拉起系统「新建事件」界面，用户点保存 */
    fun insertIntent(memo: Memo): Intent? {
        val due = memo.dueAt ?: return null
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, memo.title)
            putExtra(CalendarContract.Events.DESCRIPTION, memo.contentRaw)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, due)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, due + 30 * 60_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun insert(cv: ContentValues): Long {
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) ?: return -1
        return ContentUris.parseId(uri)
    }

    private fun ensureReminder(eventId: Long) {
        val minutes = settings.calendarLeadMinutes.coerceAtLeast(0)
        val cv = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, cv)
    }

    companion object {
        const val DEFAULT_LEAD = AppConfig.CAL_LEAD_MINUTES
        private const val ACCOUNT = "SuiJi"
    }
}
