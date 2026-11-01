package com.suiji.assistant.core

/**
 * 离线骨架：没有 Key、断网、或模型连续失败时，深化不能「什么都不给」。
 *
 * 这里不编造答案——编造比空白更糟。它只做两件事：
 *  1. 把原话整理成可读的结构（保留原句，去掉口癖）；
 *  2. 给出三个「值得自己回答的问题」，让用户顺着想下去。
 *
 * 界面上会明确标注「离线骨架 · 非 AI 生成」，不含糊。
 */
object InsightSkeleton {

    private val FILLER = listOf("嗯", "啊", "那个", "就是", "然后呢", "感觉", "其实")

    fun build(mode: String, text: String): InsightView {
        val clean = text.lines().joinToString(" ") { it.trim() }
            .let { s -> FILLER.fold(s) { acc, f -> acc.replace(f, "") } }
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return when (mode) {
            "拓展" -> InsightView(
                mode = "拓展",
                directions = listOf(
                    InsightDirection("最小可用版本", "只保留一个动作，先让它跑通一次"),
                    InsightDirection("换个使用者", "想想还有谁面临同样的问题"),
                    InsightDirection("换个场景", "同样的能力放进另一个时间/地点是否更值钱"),
                    InsightDirection("拆成两步", "把最贵的一步挪到后面，先验证前面的假设"),
                    InsightDirection("反着做", "如果只能做减法，先砍掉哪一块")
                ),
                plain = clean,
                degraded = true
            )

            "可行性" -> InsightView(
                mode = "可行性",
                scores = listOf(
                    InsightScore("需求真实性", 0, "待你自评：最近一周有人为这事抱怨过吗"),
                    InsightScore("实现成本", 0, "待你自评：一个人两周能不能做出能用的版本"),
                    InsightScore("差异化", 0, "待你自评：现有做法差在哪一步"),
                    InsightScore("验证速度", 0, "待你自评：多久能拿到第一个真实反馈")
                ),
                verdict = "先验证",
                verdictNote = "离线状态下不打分：先回答下面四个问题，再接上大模型让它判断",
                plain = clean,
                degraded = true
            )

            else -> InsightView(
                mode = "梳理",
                sections = listOf(
                    InsightSection("原始想法", clean.ifBlank { "（没有正文）" }),
                    InsightSection(
                        "值得先回答的三个问题",
                        "1. 谁会第一个用它？他现在怎么凑合解决的\n" +
                                "2. 做成之后，哪一件事会明显变快或变省\n" +
                                "3. 最小的验证动作是什么，一周内能拿到什么反馈"
                    )
                ),
                plain = clean,
                degraded = true
            )
        }
    }
}
