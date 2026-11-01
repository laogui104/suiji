package com.suiji.assistant

import com.suiji.assistant.core.DuePrecision
import com.suiji.assistant.core.SuiClock
import com.suiji.assistant.core.TimeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 提醒链路的「时间基准」回归测试。
 *
 * 这一组对应本轮排查出的最隐蔽的 bug：
 * 模型按 UTC 算出毫秒，与本机值差 8 小时，而旧的 correct() 容差是 24 小时，
 * 于是错误值被原样接受 —— 提醒提前或推迟 8 小时，用户看到的现象就是「没提醒」。
 */
class ReminderTimeTest {

    /** 设备当前毫秒 → 本地时间字符串 → 回读毫秒，必须一致 */
    @Test
    fun localIso_roundTrip() {
        val now = 1_762_000_000_000L
        val iso = SuiClock.localIso(now)
        val back = SuiClock.fromLocalIso(iso)
        assertNotNull(back)
        // 精度到分钟，允许秒级误差
        assertTrue(Math.abs(back!! - now) < 60_000)
    }

    @Test
    fun fromLocalIso_acceptsSpaceAndDateOnly() {
        val a = SuiClock.fromLocalIso("2026-11-02 15:00")
        val b = SuiClock.fromLocalIso("2026-11-02T15:00:00")
        assertNotNull(a)
        assertEquals(a, b)

        val d = SuiClock.fromLocalIso("2026-11-02")
        assertNotNull(d)
        val dt = SuiClock.localDateTime(d!!)
        assertEquals(0, dt.hour)
        assertEquals(2, dt.dayOfMonth)
    }

    @Test
    fun fromLocalIso_usesDeviceZone() {
        val ms = SuiClock.fromLocalIso("2026-06-15T09:30")!!
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), SuiClock.zone())
        assertEquals(9, dt.hour)
        assertEquals(30, dt.minute)
    }

    /** 模型按 UTC 算出的毫秒（比本地少 8 小时）必须被本地规则覆盖 */
    @Test
    fun correct_rejectsTimezoneShift() {
        val now = SuiClock.fromLocalIso("2026-11-01T10:00")!!
        // 原文「明天下午三点」→ 本地应为 11-02 15:00
        val modelUtc = SuiClock.fromLocalIso("2026-11-02T07:00")!! // 差 8 小时
        val (due, precision) = TimeNormalizer.correct(
            "明天下午三点", modelUtc, DuePrecision.EXACT_TIME, now
        )
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(due!!), SuiClock.zone())
        assertEquals(2, dt.dayOfMonth)
        assertEquals(15, dt.hour)
        assertEquals(DuePrecision.EXACT_TIME, precision)
    }

    /** 模型给的和本地一致时，保留模型值（不因毫秒级差异覆盖） */
    @Test
    fun correct_keepsConsistentModelValue() {
        val now = SuiClock.fromLocalIso("2026-11-01T10:00")!!
        val good = SuiClock.fromLocalIso("2026-11-02T15:00")!!
        val (due, _) = TimeNormalizer.correct("明天下午三点", good, DuePrecision.EXACT_TIME, now)
        assertEquals(good, due)
    }

    /** 提示词里的时间基准必须齐全，缺一项模型就可能自己猜 */
    @Test
    fun promptBlock_hasAllAnchors() {
        val block = SuiClock.promptBlock(1_762_000_000_000L)
        assertTrue(block.contains("now_epoch_ms"))
        assertTrue(block.contains("now_local"))
        assertTrue(block.contains("timezone"))
        assertTrue(block.contains("weekday"))
    }

    @Test
    fun parse_relativeTomorrowUsesDeviceDate() {
        val now = SuiClock.fromLocalIso("2026-11-01T10:00")!!
        val (due, precision) = TimeNormalizer.parse("明天下午三点提醒我开会", now)
        assertNotNull(due)
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(due!!), SuiClock.zone())
        assertEquals(2, dt.dayOfMonth)
        assertEquals(15, dt.hour)
        assertEquals(DuePrecision.EXACT_TIME, precision)
    }
}
