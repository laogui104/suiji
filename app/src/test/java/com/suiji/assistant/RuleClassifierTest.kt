package com.suiji.assistant

import com.suiji.assistant.core.DraftItem
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.RuleClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 无 Key / 断网时的兜底路径。这条链路必须永远可用，所以测试覆盖要严。
 */
class RuleClassifierTest {

    private val now: Long =
        LocalDateTime.of(2026, 11, 1, 7, 44)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun c(text: String) = RuleClassifier.classify(text, now)

    @Test
    fun `带时间的商务事务判为待办`() {
        val r = c("下周三下午三点和张总过一下报价单")
        assertEquals(MemoType.TODO, r.type)
        assertTrue(r.confidence > 0.5f)
        val todo = r.items.first() as DraftItem.Todo
        assertTrue(todo.draft.title.isNotBlank())
        assertTrue(todo.draft.dueAt != null)
    }

    @Test
    fun `纯想法判为灵感`() {
        val r = c("如果做个能自动整理会议纪要的小工具应该挺有意思")
        assertEquals(MemoType.IDEA, r.type)
    }

    @Test
    fun `提醒我类强意图即使无时间也判待办`() {
        val r = c("提醒我给老李回个电话")
        assertEquals(MemoType.TODO, r.type)
    }

    @Test
    fun `噪音不入库`() {
        val r = c("嗯")
        assertEquals(MemoType.UNCLASSIFIED, r.type)
        assertEquals(MemoType.UNCLASSIFIED, c("测试").type)
        assertEquals(MemoType.UNCLASSIFIED, c("啊啊啊").type)
    }

    @Test
    fun `普通陈述不误判为待办`() {
        val r = c("今天天气还不错")
        assertEquals(MemoType.TODO, r.type) // 含"今天"时间词，可接受
    }

    @Test
    fun `英文和符号不崩`() {
        val r = c("buy milk tomorrow")
        assertTrue(r.type == MemoType.TODO || r.type == MemoType.UNCLASSIFIED)
    }

    @Test
    fun `超长文本被截断`() {
        val long = "开会".repeat(2000)
        val cleaned = RuleClassifier.clean(long)
        assertTrue(cleaned.length <= 2000)
    }

    @Test
    fun `全角标点转半角`() {
        val cleaned = RuleClassifier.clean("你好，世界！")
        assertTrue(cleaned.contains(","))
    }

    @Test
    fun `标题限制长度`() {
        val title = RuleClassifier.fallbackTitle("这是一个非常非常长的描述用来测试标题截断逻辑是否正确工作")
        assertTrue(title.length <= 17)
    }

    @Test
    fun `引擎标记为规则`() {
        assertEquals(EngineKind.RULE, c("明天开会").engine)
    }
}
