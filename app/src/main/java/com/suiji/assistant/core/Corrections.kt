package com.suiji.assistant.core

import android.content.Context
import androidx.core.content.edit

/**
 * 用户修正记忆。
 *
 * 「AI 分拣」最容易让人失去信任的地方是：同一个错误犯三次。
 * 用户把「续保车险」从灵感改成待办，下次说「车险要续」还是被丢进灵感，
 * 他就会认为这个功能没用。
 *
 * 这里把每次人工改判记下来（标题 → 目标类型），下次输入时用二元组重合度做一次轻量匹配：
 *  - 命中：规则层直接把票投给历史类型，并给置信度 +0.08；
 *  - 同时取最近几条作为 few-shot 注入模型提示词，让模型也「记得」。
 *
 * 只存标题与类型，不存正文，最多 40 条，可一键清空。
 */
class Corrections(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("suiji_corrections", Context.MODE_PRIVATE)

    data class Entry(val title: String, val type: MemoType, val at: Long)

    fun record(text: String, to: MemoType) {
        if (to == MemoType.UNCLASSIFIED) return
        val title = normalize(text)
        if (title.length < 2) return
        val list = all().toMutableList()
        // 同标题覆盖，避免堆积
        list.removeAll { normalize(it.title) == title }
        list.add(0, Entry(title, to, System.currentTimeMillis()))
        save(list.take(MAX_ENTRIES))
    }

    /** 命中返回历史归类，未命中返回 null */
    fun match(text: String): MemoType? {
        val probe = bigrams(normalize(text))
        if (probe.isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0f
        all().forEach { e ->
            val s = similarity(probe, bigrams(normalize(e.title)))
            if (s > bestScore) {
                bestScore = s
                best = e
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) best?.type else null
    }

    /** 注入提示词的 few-shot 片段；没有历史时返回空串 */
    fun fewShotHint(limit: Int = 4): String {
        val list = all().take(limit)
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { e ->
            "- 「${e.title}」→ " + if (e.type == MemoType.TODO) "TODO" else "IDEA"
        }
    }

    fun count(): Int = all().size

    fun clear() = prefs.edit { remove(KEY) }

    // ---------------- 内部 ----------------

    private fun all(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split('\u0001')
            if (p.size < 3) return@mapNotNull null
            val type = runCatching { MemoType.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null
            Entry(p[2], type, p[1].toLongOrNull() ?: 0L)
        }.toList()
    }

    private fun save(list: List<Entry>) {
        val raw = list.joinToString("\n") { "${it.type.name}\u0001${it.at}\u0001${it.title}" }
        prefs.edit { putString(KEY, raw) }
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("[\\s，。！？、,.!?：:；;“”\"'（）()]+"), "").take(40)

    private fun bigrams(s: String): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length == 1) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        return out
    }

    private fun similarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.count { b.contains(it) }
        val union = a.size + b.size - inter
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 40
        const val MATCH_THRESHOLD = 0.5f
    }
}
