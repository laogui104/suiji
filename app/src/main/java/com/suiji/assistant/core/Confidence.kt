package com.suiji.assistant.core

/**
 * 置信度融合与处置决策。
 *
 * 为什么要融合：模型自报的 confidence 普遍虚高（它说 0.9 的时候其实经常错），
 * 本地规则又没有概率概念。把「时间是否解析出来」「规则与模型是否互相印证」
 * 「这条是否命中过用户的修正历史」三者揉进去，比单看模型数字靠谱得多。
 *
 * 纯函数，可单元测试。
 */
object Confidence {

    /** 到这个分就静默入库 */
    const val AUTO_ACCEPT = 0.62f

    /** 低于这个分就算判出了类型也不敢直接信 */
    const val MIN_ACCEPT = 0.40f

    /**
     * @param llmConfidence 模型自报置信度，未走模型时传 null
     * @param ruleScore 本地规则打分 0..100
     * @param hasTime 是否解析出了时间（含模糊时间）
     * @param type 最终归类
     * @param ruleAgrees 规则与模型的类型判断是否一致；规则未命中传 null
     * @param correctionHit 是否命中用户过去的修正偏好
     */
    fun fuse(
        llmConfidence: Float?,
        ruleScore: Int,
        hasTime: Boolean,
        type: MemoType,
        ruleAgrees: Boolean?,
        correctionHit: Boolean
    ): Float {
        if (type == MemoType.UNCLASSIFIED) {
            return (0.12f + ruleScore / 1000f).coerceAtMost(0.30f)
        }

        var score: Float
        if (llmConfidence != null) {
            score = llmConfidence.coerceIn(0f, 1f)
            when (ruleAgrees) {
                true -> score += 0.06f
                false -> score -= 0.18f
                null -> Unit
            }
        } else {
            // 纯规则：本身没有概率语义，上限压到 0.72，避免"规则一命中就万事大吉"
            score = (ruleScore / 100f) * 0.72f
        }

        if (hasTime && type == MemoType.TODO) score += 0.07f
        if (correctionHit) score += 0.08f

        return score.coerceIn(0.05f, 0.97f)
    }

    fun decide(type: MemoType, confidence: Float): ClassifyDecision = when {
        type == MemoType.UNCLASSIFIED -> ClassifyDecision.PARK
        confidence >= AUTO_ACCEPT -> ClassifyDecision.AUTO
        else -> ClassifyDecision.CONFIRM
    }

    /** 给用户看的档位文案 */
    fun label(decision: ClassifyDecision): String = when (decision) {
        ClassifyDecision.AUTO -> "已自动归档"
        ClassifyDecision.CONFIRM -> "待你确认"
        ClassifyDecision.PARK -> "待整理"
    }

    /** 一句话解释为什么需要确认 */
    fun explain(type: MemoType, confidence: Float): String = when {
        type == MemoType.UNCLASSIFIED -> "没看到明确的时间或动作，先放进待整理"
        confidence < MIN_ACCEPT -> "线索太少，两种归类都有可能"
        else -> {
            val other = if (type == MemoType.TODO) "灵感" else "待办"
            "按「${if (type == MemoType.TODO) "待办" else "灵感"}」收下了，也像$other，点一下就能改"
        }
    }
}
