package com.suiji.assistant.core

/**
 * 口语分段器。
 *
 * 一句话里塞三件事，是语音输入最典型的样子：
 * 「提醒我明天三点跟张总过报价单，另外想做个自动整理会议纪要的小工具，还有记得续保」
 * 老版本只按整段判断一个类型，结果要么全归待办、要么全归灵感，用户得手动拆。
 *
 * 这里做的是「切段」而不是「分句」：按句末标点、显式连接词、以及带独立意图的逗号子句切开，
 * 每段再各自走一遍分拣，这样无 Key 的本地规则也能产出多条记录。
 *
 * 纯 Kotlin，无 Android 依赖，可单元测试。
 */
object Segmenter {

    /** 句末与分号、换行：天然的段边界 */
    private val SENTENCE_END = Regex("[。！？!?；;\\n\\r]+")

    /** 显式连接词：出现即说明"接下来是另一件事"（长词在前，避免被短词抢先匹配） */
    private val CONNECTOR = Regex(
        "(还有一件事|还有一个|还有就是|还有一点|另外就是|另外|顺便说一下|顺便|对了|再就是|然后是|再者|以及|" +
                // 光秃秃的「还有」只在后面确实接新意图时才算连接词，避免「还有三天就到期」被切坏
                "还有(?=记得|要|需要|帮我|一个|一件|就是|得|想|明天|后天|下周|月底))"
    )

    /** 逗号：只在后半句自成意图时才切 */
    private val COMMA = Regex("[，,]")

    /** 以时间词开头的子句，通常已经是另一件事：「明天三点开会，后天交报告」 */
    private val TIME_HEAD = Regex(
        "^(今天|明天|后天|大后天|下周|下个?周|这周|本周|周[一二三四五六日天]|月底|月初|" +
                "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]|\\d{1,2}\\s*[点时])"
    )

    /** 逗号后以这些词开头，说明是一个能独立成条的动作 */
    private val SELF_CONTAINED = listOf(
        "提醒", "记得", "别忘了", "别忘", "要", "得", "需要", "帮我", "安排",
        "联系", "回复", "提交", "发给", "发一下", "准备", "确认", "跟进", "约",
        "开个", "开个会", "开会", "过一下", "对接", "报销", "续", "交", "签",
        "想", "打算", "考虑", "琢磨", "研究", "试试", "搞个", "做个", "写个",
        "看看", "能不能", "是不是", "如果", "有个想法", "有个点子", "灵感"
    )

    private const val MIN_SEGMENT = 3
    private const val MAX_SEGMENT_CHARS = 220

    fun split(raw: String, max: Int = 4): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()

        // 1) 先把连接词换成硬边界，但要保留连接词之后的正文
        val marked = CONNECTOR.replace(text) { m -> "\u0001" + m.value + "\u0002" }

        val rough = mutableListOf<String>()
        marked.split('\u0001').forEach { block ->
            // 去掉自己带的连接词前缀
            val body = block.substringAfter('\u0002', block).trim()
            if (body.isNotEmpty()) rough += splitSentences(body)
        }

        // 2) 句内再按"独立意图逗号"细切
        val fine = mutableListOf<String>()
        rough.forEach { s -> fine += splitClauses(s) }

        // 3) 清洗、合并过短碎片
        val cleaned = mutableListOf<String>()
        fine.forEach { piece ->
            val p = tidy(piece)
            if (p.length < MIN_SEGMENT) {
                // 太短：并回上一段，避免「带报价表」被切成一条
                if (cleaned.isNotEmpty()) {
                    cleaned[cleaned.lastIndex] = (cleaned.last() + "，" + p).take(MAX_SEGMENT_CHARS)
                }
                return@forEach
            }
            if (cleaned.isNotEmpty() && cleaned.last().contains(p) ) return@forEach
            cleaned += p.take(MAX_SEGMENT_CHARS)
        }

        return if (cleaned.isEmpty()) listOf(text.take(MAX_SEGMENT_CHARS))
        else cleaned.take(max.coerceAtLeast(1))
    }

    private fun splitSentences(block: String): List<String> =
        block.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitClauses(sentence: String): List<String> {
        val clauses = COMMA.split(sentence).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size <= 1) return listOf(sentence)

        val units = mutableListOf<String>()
        var current = StringBuilder(clauses.first())
        clauses.drop(1).forEach { c ->
            if (startsNewUnit(c)) {
                units += current.toString()
                current = StringBuilder(c)
            } else {
                if (current.isNotEmpty()) current.append('，')
                current.append(c)
            }
        }
        if (current.isNotEmpty()) units += current.toString()
        return units
    }

    private fun startsNewUnit(clause: String): Boolean {
        val c = clause.trimStart()
        if (c.length < 4) return false
        // 以时间词开头的子句：多半是另一件事（「后天交报告」）
        if (c.length >= 5 && TIME_HEAD.containsMatchIn(c)) return true
        return SELF_CONTAINED.any { c.startsWith(it) }
    }

    private fun tidy(s: String): String {
        var t = s.trim()
        // 去掉残留的连接词与标点
        t = CONNECTOR.replace(t, "")
        t = t.trim(' ', '，', ',', '。', '、', '：', ':')
        return t
    }
}
