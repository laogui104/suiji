package com.suiji.assistant

import com.suiji.assistant.core.ClassifyDecision
import com.suiji.assistant.core.Confidence
import com.suiji.assistant.core.DraftItem
import com.suiji.assistant.core.InsightParser
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.RuleClassifier
import com.suiji.assistant.core.Segmenter
import com.suiji.assistant.core.TimeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 3.2 分拣强化的三条新逻辑：分段、置信度融合、结构化深化结果。
 */
class SegmentsTest {

    private val now: Long =
        LocalDateTime.of(2026, 11, 1, 7, 44)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---------------- 分段 ----------------

    @Test
    fun `连接词把一句话拆成多段`() {
        val text = "提醒我明天三点跟张总过报价单，另外想做个自动整理会议纪要的小工具，还有记得续保"
        val segs = Segmenter.split(text)
        assertEquals(3, segs.size)
        assertTrue(segs.any { it.contains("续保") })
        assertTrue(segs.any { it.contains("会议纪要") })
    }

    @Test
    fun `同一件事的补充说明不会被拆开`() {
        val segs = Segmenter.split("提醒我明天带上上次的报价表")
        assertEquals(1, segs.size)
    }

    @Test
    fun `空输入不产出段`() {
        assertTrue(Segmenter.split("   ").isEmpty())
    }

    @Test
    fun `超长逗号句最多切四段`() {
        val text = "明天开会，后天交报告，下周见客户，月底对账，有空再聊"
        assertTrue(Segmenter.split(text).size <= 4)
    }

    // ---------------- 规则层多条目 ----------------

    @Test
    fun `无 Key 也能把一段话拆成多条记录`() {
        val r = RuleClassifier.classify(
            "提醒我明天三点跟张总过报价单，另外想做个自动整理会议纪要的小工具，还有记得续保",
            now
        )
        assertEquals(3, r.items.size)
        assertEquals(2, r.items.count { it is DraftItem.Todo })
        assertEquals(1, r.items.count { it is DraftItem.Idea })
    }

    @Test
    fun `拆条时每条都带自己的时间`() {
        val r = RuleClassifier.classify("明天三点开会，后天交报告", now)
        val dues = r.items.filterIsInstance<DraftItem.Todo>().map { it.draft.dueAt }
        assertEquals(2, dues.size)
        assertTrue(dues.all { it != null })
        assertTrue(dues[0] != dues[1])
    }

    @Test
    fun `用户历史改判能覆盖规则判断`() {
        val text = "如果做个自动整理会议纪要的小工具"
        assertEquals(MemoType.IDEA, RuleClassifier.classify(text, now).type)
        // 用户曾把它改成待办 → 下次直接按待办
        assertEquals(MemoType.TODO, RuleClassifier.classify(text, now, MemoType.TODO).type)
    }

    @Test
    fun `标签能抽出人和钱`() {
        val tags = RuleClassifier.tagsOf("明天找张总报销 3500 元")
        assertTrue(tags.contains("张总"))
        assertTrue(tags.any { it.contains("3500") })
    }

    // ---------------- 置信度融合 ----------------

    @Test
    fun `模型与规则一致时置信度更高`() {
        val agree = Confidence.fuse(0.7f, 60, true, MemoType.TODO, true, false)
        val disagree = Confidence.fuse(0.7f, 60, true, MemoType.TODO, false, false)
        assertTrue(agree > disagree)
        assertTrue(disagree < Confidence.AUTO_ACCEPT)
    }

    @Test
    fun `纯规则置信度有上限`() {
        val only = Confidence.fuse(null, 100, true, MemoType.TODO, null, false)
        assertTrue(only <= 0.79f)
        assertTrue(only > 0.5f)
    }

    @Test
    fun `未分类永远不进自动档`() {
        val c = Confidence.fuse(null, 50, false, MemoType.UNCLASSIFIED, null, false)
        assertTrue(c <= 0.30f)
        assertEquals(ClassifyDecision.PARK, Confidence.decide(MemoType.UNCLASSIFIED, c))
    }

    @Test
    fun `低置信度落进待确认`() {
        assertEquals(ClassifyDecision.CONFIRM, Confidence.decide(MemoType.IDEA, 0.5f))
        assertEquals(ClassifyDecision.AUTO, Confidence.decide(MemoType.IDEA, 0.63f))
    }

    // ---------------- 深化结果解析 ----------------

    @Test
    fun `梳理模式解析为三段`() {
        val raw = "{\"what\":\"做一个自动整理会议纪要的小工具\",\"why\":[\"团队会议多\",\"人工整理耗时\"],\"risks\":[\"转写准确率\",\"隐私边界\"]}"
        val v = InsightParser.parse("梳理", raw)
        assertEquals(3, v.sections.size)
        assertEquals("我想做什么", v.sections[0].heading)
        assertTrue(v.sections[1].body.contains("人工整理耗时"))
        assertTrue(!v.degraded)
    }

    @Test
    fun `拓展模式解析为多条方向`() {
        val raw = """{"directions":[{"name":"最小版本","note":"只做转写"},{"name":"团队版","note":"多人协作"}]}"""
        val v = InsightParser.parse("拓展", raw)
        assertEquals(2, v.directions.size)
        assertEquals("最小版本", v.directions[0].name)
    }

    @Test
    fun `可行性模式解析打分与结论`() {
        val raw = "{\"scores\":[{\"label\":\"需求真实性\",\"score\":4,\"note\":\"痛点明确\"}," +
                "{\"label\":\"实现成本\",\"score\":3,\"note\":\"两周可做\"}]," +
                "\"verdict\":\"先验证\",\"verdictNote\":\"先做一周的转写测试\"}"
        val v = InsightParser.parse("可行性", raw)
        assertEquals(2, v.scores.size)
        assertEquals(4, v.scores[0].score)
        assertEquals("先验证", v.verdict)
        assertNotNull(v.totalScore)
    }

    @Test
    fun `模型没给 JSON 时退化成纯文本分段`() {
        val raw = "我想做什么\n做个纪要工具\n\n为什么值得做\n开会太多\n\n关键不确定点\n隐私怎么办"
        val v = InsightParser.parse("梳理", raw)
        assertTrue(v.sections.isNotEmpty())
        assertTrue(v.degraded)
    }

    @Test
    fun `空结果不崩`() {
        assertTrue(InsightParser.parse("梳理", "").isEmpty)
        assertTrue(InsightParser.parse("可行性", "   ").isEmpty)
    }

    // ---------------- 时间词回显 ----------------

    @Test
    fun `能抠出原文时间词`() {
        val expr = TimeNormalizer.parseExpr("下周三下午三点和张总过一下报价单")
        assertNotNull(expr)
        assertTrue(expr!!.contains("下周三"))
    }
}
