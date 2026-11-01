package com.suiji.assistant.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 深化结果的解析器：把模型输出变成可渲染的结构。
 *
 * 老版本是「一整坨文本丢进滚动区」，用户得自己从里面找重点；
 * 现在三种模式分别有各自的骨架：
 *  - 梳理   → 三段（我想做什么 / 为什么值得做 / 关键不确定点）
 *  - 拓展   → 若干条方向，每条一句话
 *  - 可行性 → 四维打分 + 一句结论
 *
 * 解析必须是「永不失败」的：模型没按 JSON 输出时退化成纯文本解析，
 * 纯文本也读不出来时至少保留原文，界面上给「格式没按预期来」的灰字提示。
 */
object InsightParser {

    private val VERDICTS = listOf("值得做", "先验证", "放弃")

    fun parse(mode: String, raw: String): InsightView {
        val text = raw.trim()
        if (text.isEmpty()) return InsightView(mode = mode)
        val obj = LenientJson.parseObject(text)
        return when (mode) {
            "梳理" -> parseOrganize(text, obj)
            "拓展" -> parseExpand(text, obj)
            else -> parseFeasibility(text, obj)
        }
    }

    // ---------------- 梳理 ----------------

    private fun parseOrganize(text: String, obj: JSONObject?): InsightView {
        if (obj != null) {
            val what = firstString(obj, "what", "我想做什么", "goal", "summary")
            val why = stringList(obj, "why", "为什么值得做", "reasons")
            val risks = stringList(obj, "risks", "关键不确定点", "uncertainties")
            if (what.isNotBlank() || why.isNotEmpty() || risks.isNotEmpty()) {
                val sections = buildList {
                    if (what.isNotBlank()) add(InsightSection("我想做什么", what))
                    if (why.isNotEmpty()) add(InsightSection("为什么值得做", why.joinToString("\n")))
                    if (risks.isNotEmpty()) add(InsightSection("关键不确定点", risks.joinToString("\n")))
                }
                return InsightView(mode = "梳理", sections = sections, plain = text)
            }
        }
        return InsightView(
            mode = "梳理",
            sections = splitByHeading(text),
            plain = text,
            degraded = true
        )
    }

    /** 纯文本兜底：按「我想做什么 / 为什么… / 关键…」这类小标题切块，切不出来就按空行切 */
    private fun splitByHeading(text: String): List<InsightSection> {
        val headings = listOf("我想做什么", "为什么值得做", "关键不确定点", "不确定点", "风险")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<InsightSection>()
        var current: String? = null
        val buf = StringBuilder()
        fun flush() {
            val h = current
            if (h != null && buf.isNotBlank()) {
                out += InsightSection(h, cleanBody(buf.toString()))
            }
            buf.clear()
        }
        lines.forEach { line ->
            val hit = headings.firstOrNull { line.trimStart('#', '*', ' ').startsWith(it) }
            if (hit != null) {
                flush()
                current = hit
                val rest = line.trimStart('#', '*', ' ').removePrefix(hit).trimStart('：', ':', ' ')
                if (rest.isNotBlank()) buf.append(rest)
            } else {
                if (current == null) current = "梳理结果"
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            }
        }
        flush()
        if (out.isEmpty()) {
            val paragraphs = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            return if (paragraphs.size >= 2) {
                paragraphs.mapIndexed { i, p -> InsightSection(if (i == 0) "梳理结果" else "补充 ${i}", p.trim()) }
            } else {
                listOf(InsightSection("梳理结果", text))
            }
        }
        return out
    }

    private fun cleanBody(s: String): String = s.lines()
        .joinToString("\n") { it.trim().removePrefix("-").removePrefix("•").trim() }
        .trim()

    // ---------------- 拓展 ----------------

    private fun parseExpand(text: String, obj: JSONObject?): InsightView {
        val dirs = mutableListOf<InsightDirection>()
        if (obj != null) {
            val arr = obj.optJSONArray("directions") ?: obj.optJSONArray("items")
                ?: obj.optJSONArray("方向")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    when (val v = arr.opt(i)) {
                        is JSONObject -> {
                            val name = firstString(v, "name", "title", "方向", "direction")
                            val note = firstString(v, "note", "desc", "说明", "detail")
                            if (name.isNotBlank()) dirs += InsightDirection(name, note)
                        }

                        is String -> if (v.isNotBlank()) dirs += InsightDirection(v, "")
                    }
                }
            }
        }
        if (dirs.isEmpty()) {
            // 纯文本兜底：认「1. 名字 —— 说明」「- 名字：说明」两种写法
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val cleaned = line.replace(Regex("^([0-9]+[.、)）]|[-*•])\\s*"), "").trim()
                if (cleaned.isEmpty()) return@forEach
                val parts = cleaned.split(Regex("[——:：]+"), limit = 2)
                dirs += InsightDirection(
                    parts[0].trim().take(24),
                    parts.getOrNull(1)?.trim().orEmpty()
                )
            }
        }
        return InsightView(
            mode = "拓展",
            directions = dirs.take(8),
            plain = text,
            degraded = obj == null
        )
    }

    // ---------------- 可行性 ----------------

    private fun parseFeasibility(text: String, obj: JSONObject?): InsightView {
        val scores = mutableListOf<InsightScore>()
        var verdict: String? = null
        var verdictNote = ""

        if (obj != null) {
            val arr = obj.optJSONArray("scores") ?: obj.optJSONArray("dimensions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val label = firstString(o, "label", "name", "维度")
                    val score = o.optInt("score", o.optString("score", "").toIntOrNull() ?: 0)
                    val note = firstString(o, "note", "comment", "说明")
                    if (label.isNotBlank()) {
                        scores += InsightScore(label, score.coerceIn(0, 5), note)
                    }
                }
            }
            verdict = firstString(obj, "verdict", "结论").ifBlank { null }
            verdictNote = firstString(obj, "verdictNote", "conclusion", "结论说明")
        }

        if (scores.isEmpty() || verdict == null) {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val v = VERDICTS.firstOrNull { line.contains(it) }
                if (v != null && verdict == null && !line.contains(Regex("[0-9]分"))) {
                    verdict = v
                    verdictNote = line.replace(v, "").trim(' ', '：', ':', '，', ',', '。')
                    return@forEach
                }
                val num = Regex("([0-9])\\s*分").find(line)
                if (num != null) {
                    val score = num.groupValues[1].toIntOrNull() ?: return@forEach
                    val label = line.substringBefore(num.value)
                        .trim(' ', '-', '*', '•', '：', ':', '1', '2', '3', '4', '5', '6', '.', '、')
                    if (label.isNotBlank() && scores.none { it.label == label }) {
                        scores += InsightScore(
                            label.take(12),
                            score.coerceIn(0, 5),
                            line.substringAfter(num.value).trim(' ', '：', ':', '，', ',')
                        )
                    }
                }
            }
        }

        return InsightView(
            mode = "可行性",
            scores = scores.take(6),
            verdict = verdict,
            verdictNote = verdictNote,
            plain = text,
            degraded = obj == null || scores.isEmpty()
        )
    }

    // ---------------- JSON 取值小工具 ----------------

    private fun firstString(o: JSONObject, vararg keys: String): String {
        keys.forEach { k ->
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                if (v is String && v.isNotBlank()) return v.trim()
                if (v is JSONArray) {
                    val joined = (0 until v.length()).mapNotNull { v.optString(it, "").ifBlank { null } }
                    if (joined.isNotEmpty()) return joined.joinToString("；")
                }
            }
        }
        return ""
    }

    private fun stringList(o: JSONObject, vararg keys: String): List<String> {
        keys.forEach { k ->
            if (o.has(k) && !o.isNull(k)) {
                when (val v = o.opt(k)) {
                    is JSONArray -> return (0 until v.length()).mapNotNull {
                        val s = when (val item = v.opt(it)) {
                            is String -> item
                            is JSONObject -> firstString(item, "text", "note", "body", "title")
                            else -> item?.toString().orEmpty()
                        }
                        s.trim().ifBlank { null }
                    }

                    is String -> return v.split(Regex("[\\n；;]+"))
                        .map { it.trim().trimStart('-', '•', '*').trim() }
                        .filter { it.isNotBlank() }
                }
            }
        }
        return emptyList()
    }
}
