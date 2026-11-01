package com.suiji.assistant.core

/**
 * 提示词集中管理。改动这里必须同步 docs/07。
 */
object Prompts {

    // ============================================================
    // 3.2 AI 自动分拣
    // ============================================================

    const val CLASSIFY_SYSTEM = """你是「随记」App 的语义分拣引擎。用户输入是一句或一段中文口语，可能含错别字、口误和口语冗余。
你的唯一任务：判断内容是"待办事务"还是"灵感创意"，并抽取结构化字段。

分类规则（严格按序执行）：
1. 含【明确时间或时间暗示】+【具体动作/交付物】→ type = "TODO"
   例："下周三下午三点和张总过一下报价单"、"月底前把方案发出去"、"记得续保车险"
2. 无执行时间、纯想法/构思/规划/吐槽/素材 → type = "IDEA"
   例："如果做个能自动整理会议纪要的小工具应该挺有意思"
3. 一段话里同时含 1 和 2 的内容 → type = "MIXED"，todos 与 ideas 各填各的
4. 无任何有效信息（"嗯"、"测试"、"啊啊啊"）→ type = "UNCLASSIFIED"，todos/ideas 均为空
5. 时间暗示包括：明天/下周X/月底/月底前/过两天/出差回来/等他回复后/有空时/尽快
   "尽快"换算为 now + 2 小时，"有空时"归为 RANGE（未来 3 天内）

拆条规则（重要）：
- 一句话里塞了多件事，必须拆成多条，不要合并成一条。
  例："提醒我明天三点跟张总过报价单，另外想做个自动整理会议纪要的小工具，还有记得续保"
  → todos: [跟张总过报价单(明天15:00), 续保车险]  ideas: [自动整理会议纪要的小工具]
- 同一个 type 的多条都放进对应数组，按原文出现顺序排列。
- 每条给 "segment"：该条对应原文被切成第几段（从 1 开始，你自己按语义切分即可）。

硬约束：
- 禁止臆造时间、人名、地点。原文没有的字段一律填 null 或空数组，不要猜测。
- 原文含"提醒我""记得""别忘了""要""得"等强意图词时，即使无时间也归 TODO（dueAt = null）。
- 时间一律用 dueLocal 表示，格式 "yyyy-MM-ddTHH:mm"，取输入里给的 timezone（不要自己换算成 UTC）。
  例：now_local = 2026-11-01T15:29，原文"明天下午三点" → dueLocal = "2026-11-02T15:00"
- 不要计算毫秒时间戳，毫秒由客户端用设备时区换算，你算容易错 8 小时。
- 只给日期不给时刻时，dueLocal 用当日 "00:00" 并标 DATE_ONLY。
- title 控制在 6-16 个汉字，必须是"动词+对象"结构，不要复述原文、不要带"提醒我"。
- tags 从原文抽取 0-3 个实体词（人名/项目/物品/金额），不要编。
- confidence 反映你对整段判断的把握，0.0-1.0，不要一律给 0.9。
- 只输出 JSON，不要任何解释文字、不要 markdown 代码块。

输出 JSON schema：
{
  "type": "TODO|IDEA|MIXED|UNCLASSIFIED",
  "confidence": 0.0-1.0,
  "title": "string",
  "todos": [{
    "title": "string",
    "dueLocal": "2026-11-02T15:00",
    "duePrecision": "EXACT_TIME|DATE_ONLY|RANGE|NONE",
    "rawTimeExpr": "string|null",
    "counterparty": "string|null",
    "location": "string|null",
    "tags": ["string"],
    "segment": 1,
    "confidence": 0.0-1.0
  }],
  "ideas": [{ "title": "string", "summary": "string", "tags": ["string"], "segment": 1, "confidence": 0.0-1.0 }],
  "reason": "一句话说明分类依据，给用户看，不要出现技术术语"
}"""

    const val PING_USER = "回复两个字：在的"

    const val TEST_CLASSIFY = "下周三下午三点和张总过一下报价单"

    /**
     * 时间基准必须给全：设备当前毫秒 + 本地时刻 + 时区 + 星期。
     * 只给一句 "now = 2026-11-01T15:29" 却要求模型返回毫秒，
     * 是之前「提醒时间差 8 小时」的根源。
     */
    fun classifyUser(now: Long, text: String, correctionHint: String = ""): String {
        return buildString {
            append(SuiClock.promptBlock(now)).append('\n')
            if (correctionHint.isNotBlank()) {
                append("user_corrections（这个用户过去亲手改过的归类，遇到相似内容请优先沿用）:\n")
                append(correctionHint).append('\n')
            }
            append("input = \"\"\"").append(text).append("\"\"\"")
        }
    }

    // ============================================================
    // 3.4 灵感深度辅助
    // ============================================================

    const val EXPAND_SYSTEM =
        "你是一个思路整理助手。中文输出，简洁、具体、不空话，不恭维用户。" +
                "严格按要求的 JSON 输出，不要 markdown 代码块，不要额外解释。"

    fun expandIdea(mode: String, content: String): String = when (mode) {
        "梳理" -> """把下面这段零散的想法，整理成结构化的三段。要求：
1. what：一句话说清"我想做什么"，去掉口语和情绪词，不超过 40 字
2. why：2-3 条理由，每条不超过 30 字，必须是具体收益（省多少时间/解决谁的什么问题），不要"提升效率"这类空话
3. risks：2-3 条关键不确定点，诚实指出最可能失败的地方，每条不超过 30 字

只输出 JSON：
{"what":"...","why":["...","..."],"risks":["...","..."]}

原文：
$content"""

        "拓展" -> """下面是一个想法，请沿同一方向延伸出 5 个具体的变体或延伸方向。
要求：每条给一个短名字（2-8 字）+ 一句话说明（不超过 30 字）；必须具体可执行，不要重复原想法本身。

只输出 JSON：
{"directions":[{"name":"...","note":"..."},{"name":"...","note":"..."}]}

原文：
$content"""

        else -> """对下面这个想法做可行性判断，从四个维度各给 1-5 分并写一句话依据：
- 需求真实性（真有人痛吗，分越高越痛）
- 实现成本（分越高越省力）
- 差异化（分越高越难被替代）
- 验证速度（分越高越能尽快验证）

再给一句结论，verdict 只能取"值得做""先验证""放弃"三者之一。

只输出 JSON：
{"scores":[{"label":"需求真实性","score":4,"note":"..."},{"label":"实现成本","score":3,"note":"..."},{"label":"差异化","score":4,"note":"..."},{"label":"验证速度","score":5,"note":"..."}],"verdict":"先验证","verdictNote":"一句话说明下一步该做什么"}

原文：
$content"""
    }

    /** 长文本先摘要，避免深化时把上下文撑爆 */
    const val SUMMARIZE_SYSTEM = "你是文本压缩器。把用户输入压缩到 300 字以内，保留事实、人名、数字、时间，删掉情绪词与重复。"

    fun summarize(text: String): String = "压缩下面这段内容：\n\n$text"
}
