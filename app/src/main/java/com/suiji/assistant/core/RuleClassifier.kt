package com.suiji.assistant.core

/**
 * 本地规则分拣：无 Key / 断网 / 限流时的兜底，保证 App 100% 可用。
 *
 * v1.1 起它不再只判一个总类型，而是**逐段判**：
 * 先把输入交给 [Segmenter] 切成若干段，每段独立打分、独立产出条目，
 * 这样「无 Key」状态下也能把一段话拆成多条记录。
 *
 * 纯 Kotlin，无 Android 依赖，可单元测试。
 */
object RuleClassifier {

    private val TODO_STRONG = listOf(
        "提醒我", "记得", "别忘了", "别忘记", "要交", "需要", "截止", "前交", "之前",
        "开会", "联系", "对接", "回复", "提交", "续保", "续费", "约", "跟进", "报销",
        "签", "发一下", "发给", "打电话", "安排", "准备", "处理", "确认一下", "交一下",
        "过一下", "报给", "送到", "取", "还", "订", "买", "去一趟", "接", "送"
    )

    private val IDEA_MARK = listOf(
        "想法", "灵感", "思路", "构思", "如果", "可以做", "是不是可以", "点子", "设想",
        "也许能", "感觉可以", "要是能", "想到了", "有个主意", "能不能做", "值得做",
        "琢磨", "考虑做", "打算做", "想做个", "想做一"
    )

    private val TIME_HINT = Regex(
        "(今天|明天|后天|大后天|下周|本周|这周|周[一二三四五六日天]|月底|月初|" +
                "\\d+月\\d+[日号]|\\d+[点时]|尽快|马上|过两天|有空|回头|半小时|一刻钟)"
    )

    private val NOISE = listOf("测试", "嗯", "啊", "喂", "在吗", "你好", "test", "hello")

    /** 句尾问句偏灵感：「是不是可以做个…？」 */
    private val QUESTION_TAIL = Regex("[？?]\\s*$")

    fun isNoise(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length <= 2) return true
        return t.length <= 4 && NOISE.any { t == it }
    }

    fun clean(raw: String): String {
        var s = raw.trim().replace('\u3000', ' ')
        s = s.replace(Regex("[\uFF01-\uFF5E]")) { m ->
            String(Character.toChars(m.value[0].code - 0xFEE0))
        }
        s = s.replace(Regex("(嗯+|啊+|那个|就是说|然后呢)"), " ")
        s = s.replace(Regex("\\s{2,}"), " ")
        s = s.trim()
        return if (s.length > AppConfig.MAX_INPUT_CHARS) s.substring(0, AppConfig.MAX_INPUT_CHARS) else s
    }

    fun fallbackTitle(text: String): String = titleFor(text)

    /** 生成「动词+对象」式的短标题，去掉口头起始词与时间前缀 */
    fun titleFor(text: String): String {
        var t = text.trim()
        t = t.replace(Regex("^(嗯|啊|那个|就是|然后)+"), "")
        t = t.replace(Regex("^(提醒我|提醒一下我|记得帮我|记得|别忘了|别忘记|帮我)"), "")
        t = t.replace(Regex("^[我你]"), "")
        // 时间前缀不进标题：「明天下午三点跟设计团队对一下」→「跟设计团队对一下」
        t = t.replace(TIME_PREFIX, "")
        t = t.replace(Regex("^[，,。、\\s]+"), "")
        t = t.replace(Regex("[，。！？、,!?\\s]+$"), "")
        t = t.trim().removePrefix("的")
        if (t.isBlank()) t = text.trim()
        return if (t.length <= 16) t else t.substring(0, 16) + "…"
    }

    /** 句首的时间状语 */
    private val TIME_PREFIX = Regex(
        "^(今天|明天|明天早上|后天|大后天|下周|下个?周|这周|本周|周[一二三四五六日天]|月底|月初|" +
                "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]|尽快|马上|回头|过两天)" +
                "\\s*(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上)?" +
                "\\s*(\\d{1,2}|[零一二两三四五六七八九十]{1,3})?\\s*(点|时|:)?\\s*" +
                "(\\d{1,2}|半|一刻|三刻)?\\s*(分)?"
    )

    /** 从原文抽实体标签：人、金额、地点，抽不到就空 */
    fun tagsOf(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 单字姓 + 称谓（张总 / 李经理 / 王老师），避免把「找张总」整段吃进去
        Regex("([\\u4e00-\\u9fa5])(总|经理|老师|主任|总监|哥|姐)").findAll(text)
            .forEach { out += it.value }
        Regex("(\\d+(?:\\.\\d+)?\\s*(?:元|块|万|千))").findAll(text)
            .forEach { out += it.value.replace(" ", "") }
        Regex("(?:在|去)([\\u4e00-\\u9fa5]{2,6})(?:开会|见面|吃饭|办事|拿)").findAll(text)
            .forEach { out += it.groupValues[1] }
        return out.take(3).toList()
    }

    // ============================================================
    // 单段判定
    // ============================================================

    private data class Segment(
        val type: MemoType,
        val score: Int,
        val ideaHits: Int,
        val todoHits: Int,
        val hasTime: Boolean,
        val due: Long?,
        val precision: DuePrecision,
        val text: String
    )

    private fun scoreSegment(seg: String, now: Long, correction: MemoType?): Segment {
        val (due, precision) = TimeNormalizer.parse(seg, now)
        val hasTime = TIME_HINT.containsMatchIn(seg) || due != null
        val todoHits = TODO_STRONG.count { seg.contains(it) }
        val ideaHits = IDEA_MARK.count { seg.contains(it) }
        val question = QUESTION_TAIL.containsMatchIn(seg)

        var score = 40 * (if (hasTime) 1 else 0) +
                minOf(todoHits, 3) * 20 +
                minOf(ideaHits, 3) * 25
        score = score.coerceIn(0, 100)

        var type = when {
            // 问句形态的设想优先归灵感：「是不是可以做个…？」
            question && todoHits == 0 -> MemoType.IDEA
            ideaHits > 0 && todoHits == 0 -> MemoType.IDEA
            ideaHits >= 2 && todoHits == 0 -> MemoType.IDEA
            hasTime || todoHits > 0 -> MemoType.TODO
            score >= 30 -> MemoType.TODO
            else -> MemoType.UNCLASSIFIED
        }
        // 用户改判历史优先级最高，但仍然要求本段有实质内容
        if (correction != null && seg.length >= 3) type = correction

        return Segment(type, score, ideaHits, todoHits, hasTime, due, precision, seg)
    }

    // ============================================================
    // 对外入口
    // ============================================================

    fun classify(
        text: String,
        now: Long = System.currentTimeMillis(),
        correction: MemoType? = null
    ): ClassifyOutcome {
        val cleanText = clean(text)
        if (cleanText.isBlank()) {
            return ClassifyOutcome(MemoType.UNCLASSIFIED, 0f, "", emptyList(), "空输入", EngineKind.RULE)
        }
        if (isNoise(cleanText)) {
            return ClassifyOutcome(
                MemoType.UNCLASSIFIED, 0f, fallbackTitle(cleanText), emptyList(), "噪音输入", EngineKind.RULE
            )
        }

        val segments = Segmenter.split(cleanText)
        val results = segments.map { scoreSegment(it, now, correction) }

        val items = mutableListOf<DraftItem>()
        results.forEachIndexed { index, s ->
            when (s.type) {
                MemoType.TODO -> items += DraftItem.Todo(
                    TodoDraft(
                        title = titleFor(s.text),
                        dueAt = s.due,
                        duePrecision = s.precision,
                        rawTimeExpr = TimeNormalizer.parseExpr(s.text),
                        counterparty = tagsOf(s.text).firstOrNull(),
                        location = null,
                        tags = tagsOf(s.text)
                    ),
                    segment = index + 1
                )

                MemoType.IDEA -> items += DraftItem.Idea(
                    IdeaDraft(titleFor(s.text), s.text, tagsOf(s.text)),
                    segment = index + 1
                )

                MemoType.UNCLASSIFIED -> Unit
            }
        }

        val hasTodo = items.any { it is DraftItem.Todo }
        val hasIdea = items.any { it is DraftItem.Idea }
        val dominant = when {
            hasTodo && !hasIdea -> MemoType.TODO
            hasIdea && !hasTodo -> MemoType.IDEA
            hasTodo && hasIdea -> MemoType.TODO
            else -> MemoType.UNCLASSIFIED
        }

        val bestScore = results.maxOfOrNull { it.score } ?: 0
        val anyTime = results.any { it.hasTime }
        val confidence = Confidence.fuse(
            llmConfidence = null,
            ruleScore = bestScore,
            hasTime = anyTime,
            type = dominant,
            ruleAgrees = null,
            correctionHit = correction != null
        )

        val reason = buildString {
            append("本地规则：")
            append("切 ${segments.size} 段")
            append(" · 时间=").append(if (anyTime) "有" else "无")
            append(" · 事务词=").append(results.sumOf { it.todoHits })
            append(" · 灵感词=").append(results.sumOf { it.ideaHits })
            if (correction != null) append(" · 参考你的历史改判")
        }

        val title = items.firstOrNull()?.title ?: fallbackTitle(cleanText)
        return ClassifyOutcome(
            type = dominant,
            confidence = confidence,
            title = title.take(24),
            items = items,
            reason = reason,
            engine = EngineKind.RULE,
            segments = segments.size,
            alternate = when {
                hasTodo && hasIdea -> MemoType.IDEA
                dominant == MemoType.TODO -> MemoType.IDEA
                dominant == MemoType.IDEA -> MemoType.TODO
                else -> null
            },
            decision = Confidence.decide(dominant, confidence),
            timeExpr = results.firstOrNull { it.hasTime }?.text?.let { TimeNormalizer.parseExpr(it) }
        )
    }
}
