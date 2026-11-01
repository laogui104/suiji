package com.suiji.assistant

import com.suiji.assistant.core.DuePrecision
import com.suiji.assistant.core.TimeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimeNormalizerTest {

    private val zone: ZoneId = TimeNormalizer.zone()

    /** 2026-11-01 是周日，用来验证"下周"的 ISO 周边界 */
    private val now: Long =
        LocalDateTime.of(2026, 11, 1, 7, 44).atZone(zone).toInstant().toEpochMilli()

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun assertDate(expr: String, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) {
        val (v, _) = TimeNormalizer.parse(expr, now)
        assertNotNull("未解析: $expr", v)
        assertEquals("解析错误: $expr", at(y, m, d, h, min), v)
    }

    @Test
    fun `今天 明天 后天`() {
        assertDate("今天下午三点开会", 2026, 11, 1, 15, 0)
        assertDate("明天上午十点交周报", 2026, 11, 2, 10, 0)
        assertDate("后天中午跟客户吃饭", 2026, 11, 3, 12, 0)
        assertDate("大后天", 2026, 11, 4, 0, 0)
    }

    @Test
    fun `下周三是 11 月 4 日而不是 11 日`() {
        val (v, p) = TimeNormalizer.parse("下周三下午三点和张总过一下报价单", now)
        assertEquals(at(2026, 11, 4, 15, 0), v)
        assertEquals(DuePrecision.EXACT_TIME, p)
    }

    @Test
    fun `周内无前缀时取最近一次`() {
        // 周日说"周三" → 未来最近的周三，即 11-04
        val (v, _) = TimeNormalizer.parse("周三", now)
        assertEquals(at(2026, 11, 4), v)
    }

    @Test
    fun `N 天后 与 N 小时后`() {
        val (d3, p3) = TimeNormalizer.parse("3天后", now)
        assertEquals(at(2026, 11, 4), d3)
        assertEquals(DuePrecision.DATE_ONLY, p3)

        val (h2, p2) = TimeNormalizer.parse("2小时后发给他", now)
        assertEquals(now + 2 * 3600_000L, h2)
        assertEquals(DuePrecision.EXACT_TIME, p2)
    }

    @Test
    fun `月底与月底前`() {
        val (v1, p1) = TimeNormalizer.parse("月底", now)
        assertEquals(at(2026, 11, 30), v1)
        assertEquals(DuePrecision.DATE_ONLY, p1)

        val (v2, p2) = TimeNormalizer.parse("月底前把方案发出去", now)
        assertEquals(at(2026, 11, 30), v2)
        assertEquals(DuePrecision.RANGE, p2)
    }

    @Test
    fun `显式日期与跨年顺延`() {
        val (v, _) = TimeNormalizer.parse("12月5号", now)
        assertEquals(at(2026, 12, 5), v)

        // 已过的日期顺延到明年
        val (v2, _) = TimeNormalizer.parse("3月8日", now)
        assertEquals(at(2027, 3, 8), v2)
    }

    @Test
    fun `模糊时间`() {
        val (v1, p1) = TimeNormalizer.parse("尽快回复一下", now)
        assertEquals(now + 2 * 3600_000L, v1)
        assertEquals(DuePrecision.EXACT_TIME, p1)

        val (v2, p2) = TimeNormalizer.parse("有空时看一下", now)
        assertTrue((v2 ?: 0L) > now)
        assertEquals(DuePrecision.RANGE, p2)
    }

    @Test
    fun `无时间返回 NONE`() {
        val (v, p) = TimeNormalizer.parse("这个想法挺有意思的", now)
        assertEquals(null, v)
        assertEquals(DuePrecision.NONE, p)
    }

    @Test
    fun `强规则可覆盖模型错误时间`() {
        // 模型把"明天"算成了 3 天后，本地应纠正
        val wrong = now + 3 * 86_400_000L
        val (v, _) = TimeNormalizer.correct("明天上午十点", wrong, DuePrecision.EXACT_TIME, now)
        assertEquals(at(2026, 11, 2, 10, 0), v)
    }

    @Test
    fun `模型值在 24 小时内偏差时信任模型`() {
        val model = at(2026, 11, 2, 10, 30)
        val (v, _) = TimeNormalizer.correct("明天上午十点", model, DuePrecision.EXACT_TIME, now)
        assertEquals(model, v)
    }

    @Test
    fun `展示格式`() {
        val todayStr = TimeNormalizer.format(at(2026, 11, 1, 15, 0), DuePrecision.EXACT_TIME, now)
        assertEquals("今天 15:00", todayStr)
        val tomorrow = TimeNormalizer.format(at(2026, 11, 2, 0, 0), DuePrecision.DATE_ONLY, now)
        assertEquals("明天", tomorrow)
    }

    @Test
    fun `中文数字转换`() {
        assertEquals(3, TimeNormalizer.cnToInt("三"))
        assertEquals(10, TimeNormalizer.cnToInt("十"))
        assertEquals(12, TimeNormalizer.cnToInt("十二"))
        assertEquals(30, TimeNormalizer.cnToInt("三十"))
    }

    @Test
    fun `日期不会早于今天`() {
        val (v, _) = TimeNormalizer.parse("今天", now)
        val d = java.time.Instant.ofEpochMilli(v!!).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 1), d)
    }
}
