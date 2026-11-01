package com.suiji.assistant.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 设备时间的唯一入口。
 *
 * 改造前的问题：
 *  1. 各处直接调 `System.currentTimeMillis()`，但没人监听「用户改了系统时间 / 换了时区」，
 *     已经排好的闹钟和「今天/明天」的判断会一直用旧基准；
 *  2. 传给大模型的只有一句 `now = 2026-11-01T15:29`，却要求模型自己算 millis，
 *     模型很容易按 UTC 算出一个差 8 小时的毫秒值，而 8 小时 < 24 小时的校正容差，
 *     于是错误的提醒时间被原样存进了库。
 *
 * 这里把「设备当前时间」统一成一份可序列化的快照，提示词里给全，
 * 并要求模型只回本地时间字符串，毫秒一律由本机换算。
 */
object SuiClock {

    fun now(): Long = System.currentTimeMillis()

    fun zone(): ZoneId = runCatching { ZoneId.systemDefault() }.getOrDefault(ZoneId.of("Asia/Shanghai"))

    fun localDate(ms: Long = now()): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    fun localDateTime(ms: Long = now()): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(zone()).toLocalDateTime()

    fun isoLocal(ms: Long = now()): String = localDateTime(ms).format(ISO_MIN)

    fun isoSeconds(ms: Long = now()): String = localDateTime(ms).format(ISO_SEC)

    /** 给用户看的完整时间：「2026年11月1日 周日 15:29」 */
    fun human(ms: Long = now()): String {
        val zdt = Instant.ofEpochMilli(ms).atZone(zone())
        val week = zdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        return zdt.format(HUMAN) + " " + week
    }

    fun weekdayCn(ms: Long = now()): String =
        Instant.ofEpochMilli(ms).atZone(zone()).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)

    /** UTC 偏移，形如 +08:00 */
    fun utcOffset(ms: Long = now()): String =
        Instant.ofEpochMilli(ms).atZone(zone()).offset.id

    /** 毫秒 → 本地时刻（用于提示词与回显） */
    fun localIso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone()).format(ISO_MIN)

    /**
     * 本地时间字符串 → 毫秒。
     * 接受：2026-11-03T15:00 / 2026-11-03 15:00 / 2026-11-03T15:00:00 / 2026-11-03
     * 不做「已过顺延」，那是 TimeNormalizer 的职责。
     */
    fun fromLocalIso(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().replace(' ', 'T')
        if (s.endsWith("Z")) s = s.dropLast(1)
        if (s.length >= 16 && s[10] == 'T') s = s.substring(0, 16)
        if (s.length == 10) s = s + "T00:00"
        return runCatching {
            LocalDateTime.parse(s, ISO_MIN).atZone(zone()).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(s).atZone(zone()).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }

    /**
     * 交给大模型的时间基准块。
     * 明确给 epoch 毫秒是为了让模型不必自己算；同时要求它只回 dueLocal，
     * 避免时区换算这类它最容易出错的地方。
     */
    fun promptBlock(ms: Long = now()): String = buildString {
        append("now_epoch_ms = ").append(ms).append('\n')
        append("now_local = ").append(isoSeconds(ms)).append('\n')
        append("timezone = ").append(zone().id).append(" (UTC").append(utcOffset(ms)).append(")\n")
        append("weekday = ").append(weekdayCn(ms)).append('\n')
        append("today = ").append(localDate(ms).toString())
    }

    private val ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val ISO_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val HUMAN = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
}
