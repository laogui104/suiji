package com.suiji.assistant.net

import com.suiji.assistant.core.ClassifyDecision
import com.suiji.assistant.core.ClassifyOutcome
import com.suiji.assistant.core.Confidence
import com.suiji.assistant.core.Corrections
import com.suiji.assistant.core.DraftItem
import com.suiji.assistant.core.DuePrecision
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.IdeaDraft
import com.suiji.assistant.core.LenientJson
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.Prompts
import com.suiji.assistant.core.RuleClassifier
import com.suiji.assistant.core.Segmenter
import com.suiji.assistant.core.Settings
import com.suiji.assistant.core.SuiClock
import com.suiji.assistant.core.SuiResult
import com.suiji.assistant.core.TimeNormalizer
import com.suiji.assistant.core.TodoDraft
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分拣流水线（对应功能 3.2）。
 *
 * 顺序是固定的五步，任何一步失败都不会让用户看到错误弹窗：
 *
 *   ① 清洗        → 去口语词、全角转半角、截断
 *   ② 分段        → [Segmenter] 把一段话切成 ≤4 段，逐段可成条
 *   ③ 本地预判    → [RuleClassifier] 先跑一遍（很便宜）：给出规则类型、打分、条目
 *   ④ 模型判定    → 有 Key 才跑；提示词里带上用户历史改判作为 few-shot
 *   ⑤ 融合决策    → [Confidence] 揉合模型置信度 / 规则打分 / 时间命中 / 历史改判
 *
 * 第 ⑤ 步的产物是 [ClassifyDecision]：AUTO 静默入库，CONFIRM 打「待确认」标。
 * 这是本次强化最核心的变化——以前不置信也硬存，用户根本不知道 AI 猜错了。
 */
class Classifier(
    private val llm: LlmClient,
    private val settings: Settings,
    private val corrections: Corrections? = null
) {

    suspend fun classify(text: String, forceLlm: Boolean = false): ClassifyOutcome {
        val t0 = System.currentTimeMillis()
        val clean = RuleClassifier.clean(text)
        if (clean.isBlank()) {
            return ClassifyOutcome(MemoType.UNCLASSIFIED, 0f, "", emptyList(), "空输入", EngineKind.RULE)
        }
        if (RuleClassifier.isNoise(clean)) {
            return ClassifyOutcome(
                MemoType.UNCLASSIFIED, 0f, RuleClassifier.fallbackTitle(clean),
                emptyList(), "只听到语气词，没有实质内容", EngineKind.RULE,
                decision = ClassifyDecision.PARK
            )
        }

        val now = System.currentTimeMillis()
        val correction = runCatching { corrections?.match(clean) }.getOrNull()

        // ③ 本地预判先跑：便宜，且无论如何都要用（模型失败时它就是答案）
        val ruleOutcome = RuleClassifier.classify(clean, now, correction)

        val cfg = settings.llmConfig()
        val canLlm = (forceLlm || !cfg.apiKey.isNullOrBlank()) &&
                cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank()

        if (!canLlm) {
            return ruleOutcome.copy(elapsedMs = System.currentTimeMillis() - t0)
        }

        val hint = runCatching { corrections?.fewShotHint().orEmpty() }.getOrDefault("")
        var res = llm.chat(
            cfg = cfg,
            system = Prompts.CLASSIFY_SYSTEM,
            user = Prompts.classifyUser(now, clean, hint)
        )
        // 模型返回非 JSON 时再问一次（只追问一次，避免反复烧额度）
        if (res is SuiResult.Ok && LenientJson.parseObject(res.data) == null) {
            res = llm.chat(
                cfg = cfg,
                system = Prompts.CLASSIFY_SYSTEM,
                user = Prompts.classifyUser(now, clean, hint) +
                        "\n\n注意：上次输出无法解析。只输出一个 JSON 对象，不要任何其他文字。"
            )
        }

        val parsed = (res as? SuiResult.Ok)?.let { parseLlm(it.data, clean, now, ruleOutcome, correction) }
            ?: return ruleOutcome.copy(elapsedMs = System.currentTimeMillis() - t0)

        return parsed.copy(elapsedMs = System.currentTimeMillis() - t0)
    }

    /**
     * 用户手动改判后，把「这条原文 → 新类型」交给后续分词参考。
     * 由 ViewModel 在改类型时调用。
     */
    fun learnCorrection(text: String, to: MemoType) {
        runCatching { corrections?.record(text, to) }
    }

    fun correctionCount(): Int = runCatching { corrections?.count() ?: 0 }.getOrDefault(0)

    fun clearCorrections() = runCatching { corrections?.clear() }

    // ============================================================
    // 解析
    // ============================================================

    private fun parseLlm(
        raw: String,
        clean: String,
        now: Long,
        rule: ClassifyOutcome,
        correction: MemoType?
    ): ClassifyOutcome? {
        val obj = LenientJson.parseObject(raw) ?: return null
        val typeStr = obj.optString("type", "UNCLASSIFIED").uppercase()
        val llmConf = obj.optDouble("confidence", 0.6).toFloat().coerceIn(0f, 1f)

        val todos = mutableListOf<TodoDraft>()
        obj.optJSONArray("todos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val title = t.optString("title", "").trim()
                if (title.isBlank()) continue
                val rawTime = t.optString("rawTimeExpr", "").ifBlank { null }
                val modelPrecision = parsePrecision(t.optString("duePrecision", "NONE"))
                // 优先用模型给的「本地时间字符串」，由本机按设备时区换算成毫秒；
                // 只有老格式才回落到 dueAt（毫秒），避免模型自己算毫秒算错 8 小时。
                val modelDue = SuiClock.fromLocalIso(t.optString("dueLocal", "").ifBlank { null })
                    ?: if (t.has("dueAt") && !t.isNull("dueAt")) t.optLong("dueAt") else null
                val (due, precision) = TimeNormalizer.correct(rawTime, modelDue, modelPrecision, now)
                val tags = t.optJSONArray("tags").toStringList()
                todos += TodoDraft(
                    title = title.take(20),
                    dueAt = due,
                    duePrecision = precision,
                    rawTimeExpr = rawTime,
                    counterparty = t.optString("counterparty", "").ifBlank { tags.firstOrNull() },
                    location = t.optString("location", "").ifBlank { null },
                    tags = tags
                )
            }
        }

        val ideas = mutableListOf<IdeaDraft>()
        obj.optJSONArray("ideas")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "").trim()
                if (title.isBlank()) continue
                ideas += IdeaDraft(
                    title = title.take(20),
                    summary = o.optString("summary", "").ifBlank { clean },
                    tags = o.optJSONArray("tags").toStringList()
                )
            }
        }

        val type = when (typeStr) {
            "TODO" -> MemoType.TODO
            "IDEA" -> MemoType.IDEA
            "MIXED" -> MemoType.TODO
            else -> MemoType.UNCLASSIFIED
        }

        val items = buildList<DraftItem> {
            todos.forEachIndexed { i, d -> add(DraftItem.Todo(d, i + 1)) }
            ideas.forEachIndexed { i, d -> add(DraftItem.Idea(d, todos.size + i + 1)) }
        }

        // 模型说得出类型但没给条目 → 用本地分段结果补全，别丢内容
        if (items.isEmpty() && type != MemoType.UNCLASSIFIED) {
            if (rule.items.isNotEmpty()) {
                val relabel = rule.items.map { item ->
                    when (item) {
                        is DraftItem.Todo -> DraftItem.Todo(item.draft, item.segment)
                        is DraftItem.Idea -> if (type == MemoType.TODO) {
                            DraftItem.Todo(
                                TodoDraft(
                                    item.draft.title, null, DuePrecision.NONE,
                                    null, null, null, item.draft.tags
                                ), item.segment
                            )
                        } else item
                    }
                }
                return finish(
                    type = type,
                    llmConf = llmConf,
                    title = obj.optString("title", "").ifBlank { relabel.first().title },
                    items = relabel,
                    reason = obj.optString("reason").ifBlank { rule.reason },
                    rule = rule,
                    correction = correction,
                    clean = clean
                )
            }
            return finish(
                type = type,
                llmConf = llmConf,
                title = obj.optString("title", "").ifBlank { RuleClassifier.fallbackTitle(clean) },
                items = emptyList(),
                reason = obj.optString("reason").ifBlank { "模型只给了类型" },
                rule = rule,
                correction = correction,
                clean = clean
            )
        }

        return finish(
            type = type,
            llmConf = llmConf,
            title = obj.optString("title", "").ifBlank { items.firstOrNull()?.title.orEmpty() },
            items = items,
            reason = obj.optString("reason").ifBlank { "模型判定" },
            rule = rule,
            correction = correction,
            clean = clean
        )
    }

    private fun finish(
        type: MemoType,
        llmConf: Float,
        title: String,
        items: List<DraftItem>,
        reason: String,
        rule: ClassifyOutcome,
        correction: MemoType?,
        clean: String
    ): ClassifyOutcome {
        val agree: Boolean? = when {
            rule.type == MemoType.UNCLASSIFIED -> null
            rule.type == MemoType.TODO && type == MemoType.IDEA -> false
            rule.type == MemoType.IDEA && type == MemoType.TODO -> false
            else -> true
        }
        val hasTime = items.any {
            it is DraftItem.Todo && (it.draft.dueAt != null || !it.draft.rawTimeExpr.isNullOrBlank())
        }
        val conf = Confidence.fuse(
            llmConfidence = llmConf,
            ruleScore = ruleScoreOf(rule),
            hasTime = hasTime,
            type = type,
            ruleAgrees = agree,
            correctionHit = correction != null && correction == type
        )
        val hasTodo = items.any { it is DraftItem.Todo }
        val hasIdea = items.any { it is DraftItem.Idea }
        val segments = Segmenter.split(clean).size
        return ClassifyOutcome(
            type = type,
            confidence = conf,
            title = (title.ifBlank { items.firstOrNull()?.title ?: RuleClassifier.fallbackTitle(clean) }).take(24),
            items = items,
            reason = reason,
            engine = EngineKind.LLM,
            segments = segments,
            alternate = when {
                hasTodo && hasIdea -> MemoType.IDEA
                type == MemoType.TODO -> MemoType.IDEA
                type == MemoType.IDEA -> MemoType.TODO
                else -> null
            },
            decision = Confidence.decide(type, conf),
            timeExpr = items.filterIsInstance<DraftItem.Todo>()
                .firstOrNull { !it.draft.rawTimeExpr.isNullOrBlank() }
                ?.draft?.rawTimeExpr
        )
    }

    /** 从规则给的 reason 里把打分读回来，读不到给个中性值 */
    private fun ruleScoreOf(rule: ClassifyOutcome): Int {
        val c = rule.confidence
        return (c / 0.72f * 100f).toInt().coerceIn(0, 100)
    }

    fun clean(raw: String): String = RuleClassifier.clean(raw)

    fun fallbackTitle(text: String): String = RuleClassifier.fallbackTitle(text)

    private fun parsePrecision(s: String): DuePrecision = when (s.uppercase()) {
        "EXACT_TIME" -> DuePrecision.EXACT_TIME
        "DATE_ONLY" -> DuePrecision.DATE_ONLY
        "RANGE" -> DuePrecision.RANGE
        else -> DuePrecision.NONE
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            val v = optString(i, "").trim()
            if (v.isNotBlank()) out += v.take(8)
        }
        return out.take(5)
    }
}
