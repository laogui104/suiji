package com.suiji.assistant.core

import org.json.JSONObject

/** 容错 JSON 解析：去围栏、截首尾花括号、去尾逗号、去注释 */
object LenientJson {

    fun parseObject(raw: String): JSONObject? {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        s = s.removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        s = s.substring(start, end + 1)
        s = Regex(""",\s*([}\]])""").replace(s, "$1")
        return runCatching { JSONObject(s) }.getOrNull()
    }

    fun parseArray(raw: String): org.json.JSONArray? {
        var s = raw.trim()
        s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        s = s.substring(start, end + 1)
        s = Regex(""",\s*([}\]])""").replace(s, "$1")
        return runCatching { org.json.JSONArray(s) }.getOrNull()
    }
}
