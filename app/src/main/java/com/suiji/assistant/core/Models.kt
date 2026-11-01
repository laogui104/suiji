package com.suiji.assistant.core

import java.util.UUID

enum class MemoType { TODO, IDEA, UNCLASSIFIED }

enum class MemoStatus { ACTIVE, ARCHIVED, TRASHED }

enum class TodoStatus { PENDING, DONE, DROPPED }

enum class DuePrecision { EXACT_TIME, DATE_ONLY, RANGE, NONE }

enum class CaptureSource { VOICE, TEXT, SHARE, WIDGET, TILE }

enum class EngineKind { LLM, RULE, MANUAL }

enum class ExpandState { NONE, EXPANDING, EXPANDED, FAILED }

/** 分拣置信度落到哪个处置档位 */
enum class ClassifyDecision {
    /** 直接入库，不打扰 */
    AUTO,

    /** 入库但打「待确认」标，等用户一眼确认 */
    CONFIRM,

    /** 无法判断类型，归入待整理 */
    PARK
}

data class Memo(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoType = MemoType.UNCLASSIFIED,
    val title: String = "",
    val contentRaw: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: MemoStatus = MemoStatus.ACTIVE,
    val source: CaptureSource = CaptureSource.TEXT,
    val confidence: Float = 0f,
    val engine: EngineKind = EngineKind.MANUAL,
    val dueAt: Long? = null,
    val duePrecision: DuePrecision = DuePrecision.NONE,
    val counterparty: String? = null,
    val todoStatus: TodoStatus = TodoStatus.PENDING,
    val remindCount: Int = 0,
    val lastRemindAt: Long? = null,
    val expanded: String? = null,
    val expandState: ExpandState = ExpandState.NONE,
    val deletedAt: Long? = null,
    // ---- v1.1 新增：让「分拣」这件事可解释、可纠正 ----
    val tags: List<String> = emptyList(),
    /** 分拣依据，原样展示给用户：走的是哪条规则/模型说了什么 */
    val classifyReason: String = "",
    /** 需要用户确认一次归类 */
    val needConfirm: Boolean = false,
    /** 原文被拆成第几段（从 1 开始），0 表示整段 */
    val segmentIndex: Int = 0,
    // ---- v1.2 新增：提醒落地 ----
    /** 用户点了「稍后」，在此时间之前不再提 */
    val snoozedUntil: Long? = null,
    /** 已写入系统日历的事件 id，null 表示没写 */
    val calendarEventId: Long? = null
) {
    val isTodo: Boolean get() = type == MemoType.TODO
    val isIdea: Boolean get() = type == MemoType.IDEA
    val isDone: Boolean get() = todoStatus == TodoStatus.DONE
    val isOverdue: Boolean
        get() = isTodo && todoStatus == TodoStatus.PENDING &&
                dueAt != null && dueAt < System.currentTimeMillis()

    /** 灵感是否还没深化过 */
    val ideaUnTouched: Boolean get() = isIdea && expanded.isNullOrBlank()
}

data class TodoDraft(
    val title: String,
    val dueAt: Long?,
    val duePrecision: DuePrecision,
    val rawTimeExpr: String?,
    val counterparty: String?,
    val location: String?,
    val tags: List<String>
)

data class IdeaDraft(
    val title: String,
    val summary: String,
    val tags: List<String>
)

data class ClassifyResult(
    val type: MemoType,
    val confidence: Float,
    val title: String,
    val todos: List<TodoDraft> = emptyList(),
    val ideas: List<IdeaDraft> = emptyList(),
    val reason: String = "",
    val engine: EngineKind = EngineKind.RULE
) {
    val needsConfirm: Boolean
        get() = type == MemoType.UNCLASSIFIED || confidence < AppConfig.CONFIDENCE_THRESHOLD
}

/** 分拣后的统一产物：一句话可能拆成多条记录 */
data class ClassifyOutcome(
    val type: MemoType,
    val confidence: Float,
    val title: String,
    val items: List<DraftItem>,
    val reason: String,
    val engine: EngineKind,
    /** 原文被切成几段 */
    val segments: Int = 1,
    /** 另一种可能的归类，用于「一键改判」 */
    val alternate: MemoType? = null,
    /** 处置档位 */
    val decision: ClassifyDecision = ClassifyDecision.AUTO,
    /** 分拣耗时，用于「AI 判定」块展示 */
    val elapsedMs: Long = 0,
    /** 被规则/模型识别出的时间词原文 */
    val timeExpr: String? = null
) {
    val needConfirm: Boolean get() = decision != ClassifyDecision.AUTO
}

sealed interface DraftItem {
    data class Todo(val draft: TodoDraft, val segment: Int = 0) : DraftItem
    data class Idea(val draft: IdeaDraft, val segment: Int = 0) : DraftItem

    val title: String
        get() = when (this) {
            is Todo -> draft.title
            is Idea -> draft.title
        }

    val type: MemoType
        get() = if (this is Todo) MemoType.TODO else MemoType.IDEA
}

enum class IdleState { ACTIVE, SEMI_IDLE, IDLE, BLOCKED }

data class IdleAssessment(
    val state: IdleState,
    val score: Int,
    val reasons: List<String>
)

// ============================================================
// 灵感深化：结构化结果
// ============================================================

/** 「梳理」模式的三段 */
data class InsightSection(val heading: String, val body: String)

/** 「拓展」模式的一条方向 */
data class InsightDirection(val name: String, val note: String)

/** 「可行性」模式的一个维度打分 */
data class InsightScore(val label: String, val score: Int, val note: String)

/**
 * 一次深化的渲染模型。三种模式共用一套结构，
 * 由 [InsightParser] 从模型原始输出解析；解析不出来时退化为 [plain]。
 */
data class InsightView(
    val mode: String = "梳理",
    val sections: List<InsightSection> = emptyList(),
    val directions: List<InsightDirection> = emptyList(),
    val scores: List<InsightScore> = emptyList(),
    val verdict: String? = null,
    val verdictNote: String? = "",
    val plain: String = "",
    val degraded: Boolean = false
) {
    val isEmpty: Boolean
        get() = sections.isEmpty() && directions.isEmpty() && scores.isEmpty() && plain.isBlank()

    /** 可行性总分（0..100），无打分时返回 null */
    val totalScore: Int?
        get() = if (scores.isEmpty()) null else scores.sumOf { it.score } * 100 / (scores.size * 5)
}

/** 深化历史的一条版本 */
data class InsightVersion(
    val id: String = UUID.randomUUID().toString(),
    val memoId: String,
    val mode: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val latencyMs: Long = 0,
    val ok: Boolean = true
) {
    val view: InsightView get() = InsightParser.parse(mode, content)
}

/** 深化任务的四态，界面据此决定显示「生成中 / 结果 / 重试」 */
enum class InsightPhase { IDLE, RUNNING, READY, FAILED }
