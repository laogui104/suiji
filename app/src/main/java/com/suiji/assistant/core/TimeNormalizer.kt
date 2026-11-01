package com.suiji.assistant.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 中文时间表达式本地解析与校正。
 * 模型给出 dueAt 后必须再过一遍这里，避免"明天"被算错一天。
 */
object TimeNormalizer {

    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    private val WEEK_MAP = mapOf(
        "一" to DayOfWeek.MONDAY, "1" to DayOfWeek.MONDAY,
        "二" to DayOfWeek.TUESDAY, "2" to DayOfWeek.TUESDAY,
        "三" to DayOfWeek.WEDNESDAY, "3" to DayOfWeek.WEDNESDAY,
        "四" to DayOfWeek.THURSDAY, "4" to DayOfWeek.THURSDAY,
        "五" to DayOfWeek.FRIDAY, "5" to DayOfWeek.FRIDAY,
        "六" to DayOfWeek.SATURDAY, "6" to DayOfWeek.SATURDAY,
        "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY, "7" to DayOfWeek.SUNDAY
    )

    fun zone(): ZoneId = ZoneId.systemDefault()

    private fun dateOf(now: Long): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()

    /** 中文数字 1..99 */
    fun cnToInt(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty()) return null
        if (s == "十") return 10
        if (s.length == 1) return CN_DIGITS[s[0]]
        val idx = s.indexOf('十')
        if (idx >= 0) {
            val tens = if (idx == 0) 1 else CN_DIGITS[s[0]] ?: return null
            val ones = if (idx == s.length - 1) 0 else CN_DIGITS[s[idx + 1]] ?: return null
            return tens * 10 + ones
        }
        return null
    }

    private val DATE_RE = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
    private val REL_DAY_RE = Regex("(\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*天\\s*(?:之?后|以后)")
    private val REL_HOUR_RE = Regex("(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(?:个)?\\s*小时\\s*(?:之?后|以后)")
    private val WEEK_RE = Regex("(下下|下|这|本)?\\s*(?:个)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])")
    private val CLOCK_RE = Regex(
        "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\\s*(\\d{1,2}|[零一二两三四五六七八九十]{1,3})\\s*(?:点|时|:)\\s*(\\d{1,2}|[零一二两三四五六七八九十]{1,3})?\\s*(半|分)?"
    )

    /**
     * 解析中文时间表达式。
     * @return pair(毫秒时间戳, 精度)；无法解析返回 (null, NONE)
     */
    fun parse(expr: String?, now: Long): Pair<Long?, DuePrecision> {
        if (expr.isNullOrBlank()) return null to DuePrecision.NONE
        val text = expr.trim()
        val today = dateOf(now)

        // ---- 1. 基准日期 ----
        var date: LocalDate? = null
        var rangeMode = false

        when {
            text.contains("大后天") -> date = today.plusDays(3)
            text.contains("后天") -> date = today.plusDays(2)
            text.contains("明天") || text.contains("明日") || text.contains("明早") ||
                    text.contains("明晚") || text.contains("明儿") -> date = today.plusDays(1)
            text.contains("今天") || text.contains("今日") || text.contains("今晚") ||
                    text.contains("今早") -> date = today
            text.contains("月底") || text.contains("月末") -> {
                val last = today.withDayOfMonth(today.lengthOfMonth())
                date = last
                rangeMode = text.contains("前")
            }
            text.contains("月初") -> date = today.plusMonths(1).withDayOfMonth(1)
        }

        if (date == null) {
            DATE_RE.find(text)?.let { m ->
                val mo = m.groupValues[1].toIntOrNull()
                val da = m.groupValues[2].toIntOrNull()
                if (mo != null && da != null && mo in 1..12 && da in 1..31) {
                    var d = runCatching { LocalDate.of(today.year, mo, da) }.getOrNull()
                    // 已过则顺延到明年
                    if (d != null && d.isBefore(today)) {
                        d = runCatching { LocalDate.of(today.year + 1, mo, da) }.getOrNull()
                    }
                    date = d
                }
            }
        }

        if (date == null) {
            WEEK_RE.find(text)?.let { m ->
                val prefix = m.groupValues[1]
                val dow = WEEK_MAP[m.groupValues[2]]
                if (dow != null) {
                    val cur = today.dayOfWeek
                    // ISO 周：周一为 1、周日为 7
                    val delta = when (prefix) {
                        "下" -> (8 - cur.value) + (dow.value - 1)
                        "下下" -> (8 - cur.value) + (dow.value - 1) + 7
                        else -> (dow.value - cur.value + 7) % 7
                    }
                    date = today.plusDays(delta.toLong())
                }
            }
        }

        if (date == null) {
            REL_DAY_RE.find(text)?.let { m ->
                val n = cnToInt(m.groupValues[1])
                if (n != null) date = today.plusDays(n.toLong())
            }
        }

        if (date == null && (text.contains("尽快") || text.contains("马上") || text.contains("立刻") || text.contains("现在"))) {
            return (now + 2 * 3600_000L) to DuePrecision.EXACT_TIME
        }
        if (date == null && (text.contains("有空") || text.contains("回头") || text.contains("看情况") ||
                    text.contains("过两天") || text.contains("改天") || text.contains("找时间"))) {
            return (now + 3 * 86_400_000L) to DuePrecision.RANGE
        }

        // ---- 2. 时间点 ----
        var hour: Int? = null
        var minute = 0
        CLOCK_RE.find(text)?.let { m ->
            val period = m.groupValues[1]
            val h = cnToInt(m.groupValues[2])
            val miRaw = m.groupValues[3]
            val unit = m.groupValues[4]
            if (h != null && h in 0..24) {
                var hh = h
                when (period) {
                    "下午", "傍晚", "晚上", "夜里" -> if (hh in 1..11) hh += 12
                    "中午" -> if (hh in 1..10) hh += 12
                    "凌晨" -> if (hh == 12) hh = 0
                }
                hour = hh.coerceIn(0, 23)
                minute = when {
                    unit == "半" -> 30
                    !miRaw.isNullOrBlank() -> cnToInt(miRaw)?.coerceIn(0, 59) ?: 0
                    else -> 0
                }
            }
        }

        if (date == null && hour == null) {
            REL_HOUR_RE.find(text)?.let { m ->
                val n = cnToInt(m.groupValues[1])
                if (n != null) return (now + n * 3600_000L) to DuePrecision.EXACT_TIME
            }
        }

        // 只有时段词、没有具体钟点："中午"→12:00、"上午"→9:00
        if (hour == null) {
            hour = when {
                text.contains("中午") -> 12
                text.contains("明早") -> 8
                text.contains("早上") || text.contains("早晨") || text.contains("上午") -> 9
                text.contains("下午") -> 14
                text.contains("傍晚") -> 18
                text.contains("今晚") || text.contains("明晚") || text.contains("晚上") -> 20
                text.contains("凌晨") -> 1
                else -> null
            }
            if (hour != null) minute = 0
        }

        val hh0 = hour
        if (date == null && hh0 != null) {
            // 只有时间点：取今天，若已过则明天
            val candidate = LocalDateTime.of(today, LocalTime.of(hh0, minute))
            val todayAt = candidate.atZone(zone()).toInstant().toEpochMilli()
            date = if (todayAt < now - 60_000) today.plusDays(1) else today
        }

        val finalDate: LocalDate = date ?: return null to DuePrecision.NONE
        val finalHour = hour

        return if (finalHour != null) {
            val ldt = LocalDateTime.of(finalDate, LocalTime.of(finalHour, minute))
            ldt.atZone(zone()).toInstant().toEpochMilli() to DuePrecision.EXACT_TIME
        } else {
            val ldt = LocalDateTime.of(finalDate, LocalTime.MIDNIGHT)
            val ms = ldt.atZone(zone()).toInstant().toEpochMilli()
            if (rangeMode) ms to DuePrecision.RANGE else ms to DuePrecision.DATE_ONLY
        }
    }

    /** 强规则（命中优先级高的表达式）才允许覆盖模型结果 */
    private fun isStrongRule(expr: String?): Boolean {
        if (expr.isNullOrBlank()) return false
        return Regex("(今天|明天|后天|大后天|下周|本周|这周|周[一二三四五六日天]|\\d+月\\d+[日号]|月底|\\d+天后)")
            .containsMatchIn(expr)
    }

    /**
     * 校正模型给的时间。
     *
     * 旧实现容差是 24 小时 —— 太宽松了：模型最容易犯的错就是按 UTC 算，
     * 结果偏 8 小时，8h < 24h 于是被原样接受，提醒提前/推迟 8 小时。
     * 现在改成 2 小时：超过就认为模型算错了，用本地规则覆盖。
     */
    fun correct(
        rawExpr: String?,
        modelValue: Long?,
        modelPrecision: DuePrecision,
        now: Long
    ): Pair<Long?, DuePrecision> {
        val (localValue, localPrecision) = parse(rawExpr, now)
        if (localValue == null) return modelValue to modelPrecision
        if (modelValue == null) return localValue to localPrecision

        val diff = kotlin.math.abs(localValue - modelValue)
        // 时区错位的典型特征：偏差接近整小时（8h / 7h / 1h）
        val looksLikeTzShift = diff >= 55 * 60_000L && diff % 3_600_000L < 5 * 60_000L
        return when {
            isStrongRule(rawExpr) && (diff > 2 * 3600_000L || looksLikeTzShift) -> localValue to localPrecision
            diff > 12 * 3600_000L -> localValue to localPrecision
            else -> modelValue to modelPrecision
        }
    }

    // ---- 展示 ----

    private val DATE_EXPR = Regex(
        "(大后天|后天|明天|明日|明早|明晚|今天|今日|今晚|今早|" +
                "(?:下下|下|这|本)?(?:个)?(?:周|星期|礼拜)[一二三四五六日天1-7]|" +
                "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]|月底前?|月末|月初|" +
                "\\d{1,3}\\s*天(?:之?后|以后)|\\d{1,2}\\s*(?:个)?\\s*小时(?:之?后|以后)|" +
                "尽快|马上|过两天|有空时?|回头)"
    )

    private val CLOCK_EXPR = Regex(
        "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\\s*" +
                "(\\d{1,2}|[零一二两三四五六七八九十]{1,3})\\s*(?:点|时)" +
                "(?:\\s*(\\d{1,2}|[零一二两三四五六七八九十]{1,3})?\\s*分?|半)?"
    )

    /**
     * 从原文里抠出「时间词」原文，用于在「AI 判定」里给用户看：
     * 原文「下周三下午三点」→ 归一化「11月4日 15:00」。
     */
    fun parseExpr(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val dateM = DATE_EXPR.find(text)
        if (dateM == null) {
            val clock = CLOCK_EXPR.find(text) ?: return null
            return clock.value.trim().ifBlank { null }
        }
        val tail = text.substring(dateM.range.last + 1).take(10)
        val clock = CLOCK_EXPR.find(tail)?.value?.trim()
        val head = dateM.value.trim()
        return if (clock.isNullOrBlank()) head else head + clock
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MD = DateTimeFormatter.ofPattern("M月d日")
    private val MDHM = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun format(dueAt: Long?, precision: DuePrecision, now: Long = System.currentTimeMillis()): String {
        if (dueAt == null) return "无时间"
        val dt = Instant.ofEpochMilli(dueAt).atZone(zone())
        val today = dateOf(now)
        val d = dt.toLocalDate()
        val dayWord = when (d) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "后天"
            today.minusDays(1) -> "昨天"
            else -> null
        }
        val timePart = when (precision) {
            DuePrecision.EXACT_TIME -> " " + dt.format(HM)
            DuePrecision.DATE_ONLY -> ""
            DuePrecision.RANGE -> " 前"
            DuePrecision.NONE -> ""
        }
        return when {
            dayWord != null -> dayWord + timePart
            d.year == today.year -> d.format(MD) + timePart
            else -> d.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) + timePart
        }
    }

    fun formatFull(dueAt: Long?, precision: DuePrecision): String {
        if (dueAt == null) return "无时间"
        val dt = Instant.ofEpochMilli(dueAt).atZone(zone())
        return when (precision) {
            DuePrecision.EXACT_TIME -> dt.format(MDHM)
            DuePrecision.DATE_ONLY -> dt.format(MD)
            DuePrecision.RANGE -> dt.format(MD) + "前"
            DuePrecision.NONE -> "无时间"
        }
    }
}
