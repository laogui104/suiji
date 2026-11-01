package com.suiji.assistant.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suiji.assistant.SuiJiApp
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.CaptureSource
import com.suiji.assistant.core.ClassifyDecision
import com.suiji.assistant.core.ClassifyOutcome
import com.suiji.assistant.core.DraftItem
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.ExpandState
import com.suiji.assistant.core.InsightParser
import com.suiji.assistant.core.InsightPhase
import com.suiji.assistant.core.InsightSkeleton
import com.suiji.assistant.core.InsightVersion
import com.suiji.assistant.core.InsightView
import com.suiji.assistant.core.LlmProviders
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.Prompts
import com.suiji.assistant.core.SecureStore
import com.suiji.assistant.core.SuiClock
import com.suiji.assistant.core.SuiResult
import com.suiji.assistant.core.TimeNormalizer
import com.suiji.assistant.core.TodoStatus
import com.suiji.assistant.reminder.AlarmScheduler
import com.suiji.assistant.voice.AsrRoute
import com.suiji.assistant.voice.OfflineAsr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MemoFilter(val label: String) {
    ALL("全部"), TODO("待办"), IDEA("灵感")
}

/** 底部三个导航位 */
enum class HomeTab(val label: String) {
    HOME("首页"), IDEA("灵感"), ME("我")
}

enum class StepPhase { PENDING, RUNNING, PASS, FAIL }

data class ConnStep(
    val name: String,
    val phase: StepPhase = StepPhase.PENDING,
    val detail: String = ""
)

/** 分拣完成后给用户过目的一条 */
data class ReviewItem(
    val id: String,
    val title: String,
    val type: MemoType,
    val timeLabel: String,
    val tags: List<String>
)

/**
 * 分拣回执。
 * 只要命中「低置信」或「一段话拆出多条」，就会生成它 ——
 * 这是 3.2 强化后在交互上最直观的变化：不再闷头入库。
 */
data class ReviewState(
    val items: List<ReviewItem>,
    val reason: String,
    val engine: EngineKind,
    val confidence: Float,
    val decision: ClassifyDecision,
    val segments: Int,
    val timeExpr: String?,
    val elapsedMs: Long
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val a = app as SuiJiApp
    private val settings get() = a.settings
    private val repo get() = a.repo

    // ---------------- 列表 ----------------
    var memos by mutableStateOf<List<Memo>>(emptyList())
        private set
    var filter by mutableStateOf(MemoFilter.ALL)
        private set
    var query by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var tab by mutableStateOf(HomeTab.HOME)
        private set

    /** 顶部「待确认」条所用 */
    var pending by mutableStateOf<List<Memo>>(emptyList())
        private set

    var todoCount by mutableStateOf(0)
        private set
    var ideaCount by mutableStateOf(0)
        private set

    // ---------------- 导航 ----------------
    var captureOpen by mutableStateOf(false)
        private set
    var settingsOpen by mutableStateOf(false)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var review by mutableStateOf<ReviewState?>(null)
        private set

    // ---------------- 录入 ----------------
    var busy by mutableStateOf(false)
        private set
    var busyLabel by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)

    // ---------------- 设置 / 连通测试 ----------------
    var connSteps by mutableStateOf<List<ConnStep>>(emptyList())
        private set
    var connRunning by mutableStateOf(false)
        private set
    var connSummary by mutableStateOf<String?>(null)
        private set

    // ---------------- 应用内顶部提醒 ----------------
    var inlineReminder by mutableStateOf<Memo?>(null)
        private set

    init {
        viewModelScope.launch { reload() }
    }

    // ================= 读取 =================

    fun reload() {
        viewModelScope.launch {
            val type = when {
                tab == HomeTab.IDEA -> MemoType.IDEA
                filter == MemoFilter.ALL -> null
                filter == MemoFilter.TODO -> MemoType.TODO
                else -> MemoType.IDEA
            }
            val kw = query.trim()
            val data = withContext(Dispatchers.IO) {
                if (kw.isBlank()) repo.list(type)
                else repo.search(kw).filter { type == null || it.type == type }
            }
            val pend = withContext(Dispatchers.IO) { repo.pendingConfirm() }
            val todoN = withContext(Dispatchers.IO) { repo.countByType(MemoType.TODO) }
            val ideaN = withContext(Dispatchers.IO) { repo.countByType(MemoType.IDEA) }
            memos = data
            pending = pend
            todoCount = todoN
            ideaCount = ideaN
            loading = false
            if (selectedId != null && data.none { it.id == selectedId } && pend.none { it.id == selectedId }) {
                selectedId = null
            }
        }
    }

    fun applyFilter(f: MemoFilter) {
        filter = f
        loading = true
        reload()
        checkInlineReminder()
    }

    fun applyQuery(q: String) {
        query = q
        reload()
        checkInlineReminder()
    }

    fun switchTab(t: HomeTab) {
        if (tab == t) {
            if (t == HomeTab.HOME) select(null)
            return
        }
        tab = t
        selectedId = null
        filter = MemoFilter.ALL
        loading = true
        reload()
        checkInlineReminder()
    }

    val selected: Memo?
        get() = memos.firstOrNull { it.id == selectedId }
            ?: pending.firstOrNull { it.id == selectedId }
            ?: selectedId?.let { id -> repo.get(id) }

    fun select(id: String?) {
        selectedId = id
        hideInlineReminder()
    }

    // ================= 录入 =================

    var captureSeed by mutableStateOf("")
        private set

    fun openCapture(seed: String = "") {
        captureSeed = seed
        captureOpen = true
        hideInlineReminder()
    }

    fun closeCapture() {
        captureOpen = false
        captureSeed = ""
    }

    fun openSettings() {
        tab = HomeTab.ME
        settingsOpen = true
        refreshConnSummary()
    }

    fun closeSettings() {
        settingsOpen = false
    }

    fun closeReview() {
        review = null
    }

    fun submit(text: String, source: CaptureSource = CaptureSource.TEXT) {
        if (text.isBlank()) return
        if (busy) return
        busy = true
        busyLabel = if (settings.hasUsableConfig()) "AI 正在分拣…" else "本地分拣中…"
        viewModelScope.launch {
            try {
                val outcome = a.classifier.classify(text)
                val now = SuiClock.now()
                val duplicate = withContext(Dispatchers.IO) {
                    repo.recentDuplicate(text.trim(), now - 24 * 3600_000L)
                }
                if (duplicate != null) {
                    message = "这条好像记过：${duplicate.title}"
                    captureOpen = false
                    return@launch
                }

                val created = mutableListOf<Memo>()
                withContext(Dispatchers.IO) {
                    created += materialize(outcome, text, source, now)
                }
                captureOpen = false
                rescheduleReminders()
                // 开了日历同步的，把带时间的待办直接写进系统日历
                if (settings.calendarSyncEnabled) {
                    withContext(Dispatchers.IO) {
                        created.filter { it.isTodo && it.dueAt != null }
                            .forEach { a.calendar.push(it) }
                    }
                }
                reload()

                val needReview = outcome.decision != ClassifyDecision.AUTO || created.size > 1
                if (needReview && created.isNotEmpty()) {
                    review = ReviewState(
                        items = created.map { m ->
                            ReviewItem(
                                id = m.id,
                                title = m.title,
                                type = m.type,
                                timeLabel = if (m.isTodo) {
                                    TimeNormalizer.format(m.dueAt, m.duePrecision)
                                } else "灵感",
                                tags = m.tags
                            )
                        },
                        reason = outcome.reason,
                        engine = outcome.engine,
                        confidence = outcome.confidence,
                        decision = outcome.decision,
                        segments = outcome.segments,
                        timeExpr = outcome.timeExpr,
                        elapsedMs = outcome.elapsedMs
                    )
                    message = null
                } else {
                    message = when {
                        outcome.engine == EngineKind.RULE && !settings.hasUsableConfig() ->
                            "已收好 · 本地规则分拣，配置 Key 后更准"
                        created.size > 1 -> "已拆成 ${created.size} 条收好"
                        else -> "已收好"
                    }
                }
            } catch (t: Throwable) {
                message = "保存失败：${t.message}"
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    /** 把一次分拣结果落库；返回真正写入的条目 */
    private fun materialize(
        outcome: ClassifyOutcome,
        text: String,
        source: CaptureSource,
        now: Long
    ): List<Memo> {
        val needConfirm = outcome.decision != ClassifyDecision.AUTO
        val list = mutableListOf<Memo>()

        if (outcome.items.isEmpty()) {
            val m = Memo(
                type = MemoType.UNCLASSIFIED,
                title = outcome.title.ifBlank { a.classifier.fallbackTitle(text) },
                contentRaw = text.trim(),
                createdAt = now,
                updatedAt = now,
                source = source,
                confidence = outcome.confidence,
                engine = outcome.engine,
                classifyReason = outcome.reason,
                needConfirm = false
            )
            repo.insert(m)
            return listOf(m)
        }

        outcome.items.forEach { item ->
            val m = when (item) {
                is DraftItem.Todo -> Memo(
                    type = MemoType.TODO,
                    title = item.draft.title,
                    contentRaw = text.trim(),
                    createdAt = now,
                    updatedAt = now,
                    source = source,
                    confidence = outcome.confidence,
                    engine = outcome.engine,
                    dueAt = item.draft.dueAt,
                    duePrecision = item.draft.duePrecision,
                    counterparty = item.draft.counterparty,
                    classifyReason = outcome.reason,
                    needConfirm = needConfirm,
                    tags = item.draft.tags,
                    segmentIndex = item.segment
                )

                is DraftItem.Idea -> Memo(
                    type = MemoType.IDEA,
                    title = item.draft.title,
                    contentRaw = item.draft.summary.ifBlank { text.trim() },
                    createdAt = now,
                    updatedAt = now,
                    source = source,
                    confidence = outcome.confidence,
                    engine = outcome.engine,
                    classifyReason = outcome.reason,
                    needConfirm = needConfirm,
                    tags = item.draft.tags,
                    segmentIndex = item.segment
                )
            }
            repo.insert(m)
            list += m
        }
        return list
    }

    // ================= 分拣结果的人工修正 =================

    /** 用户认可 AI 的归类：清标记，并把这一条记进偏好 */
    fun confirm(memo: Memo, asType: MemoType? = null) {
        val target = asType ?: memo.type
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.confirmType(memo.id, target)
                a.classifier.learnCorrection(memo.contentRaw, target)
            }
            rescheduleReminders()
            review = review?.copy(items = review!!.items.filterNot { it.id == memo.id })
                ?.takeIf { it.items.isNotEmpty() }
            reload()
            message = "记下了：以后类似的都按「${typeWord(target)}」收"
        }
    }

    /** 改判类型：同时喂给纠错记忆，下一次同类输入会更准 */
    fun reclassify(memo: Memo, type: MemoType) = reclassifyById(memo.id, memo.contentRaw, type)

    fun reclassifyById(id: String, text: String, type: MemoType) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.setType(id, type)
                a.classifier.learnCorrection(text, type)
            }
            rescheduleReminders()
            review = review?.let { r ->
                r.copy(items = r.items.map { if (it.id == id) it.copy(type = type) else it })
            }
            reload()
            message = "已改为${typeWord(type)}"
        }
    }

    /** 回执里点「都对了」：全部清掉待确认标记，并把归类偏好记下来 */
    fun confirmAll(r: ReviewState) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                r.items.forEach { repo.confirmType(it.id, it.type) }
            }
            rescheduleReminders()
            review = null
            reload()
            message = "都记下了，以后类似的按你的习惯归类"
        }
    }

    /** 回执里点「全部撤销」：整批移入回收站 */
    fun discardReview(r: ReviewState) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { r.items.forEach { repo.trash(it.id) } }
            rescheduleReminders()
            review = null
            reload()
            message = "已撤销"
        }
    }

    /** 让 AI 重新判一次（可能换了 Key / 上次走了本地规则） */
    fun reanalyze(memo: Memo) {
        if (busy) return
        busy = true
        busyLabel = "重新分拣…"
        viewModelScope.launch {
            try {
                if (!settings.hasUsableConfig()) {
                    message = "还没配置 API Key，用的是本地规则"
                    return@launch
                }
                val outcome = a.classifier.classify(memo.contentRaw, forceLlm = true)
                val first = outcome.items.firstOrNull()
                withContext(Dispatchers.IO) {
                    if (first != null) {
                        repo.setType(memo.id, first.type)
                        repo.setTitle(memo.id, first.title)
                        if (first is DraftItem.Todo) {
                            repo.insert(
                                memo.copy(
                                    title = first.draft.title,
                                    type = MemoType.TODO,
                                    dueAt = first.draft.dueAt,
                                    duePrecision = first.draft.duePrecision,
                                    tags = first.draft.tags,
                                    classifyReason = outcome.reason,
                                    confidence = outcome.confidence,
                                    engine = outcome.engine,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    repo.setNeedConfirm(memo.id, outcome.decision != ClassifyDecision.AUTO)
                }
                reload()
                message = if (outcome.engine == EngineKind.LLM) {
                    "重新分拣完成：${outcome.reason.take(24)}"
                } else {
                    "模型没给出可用结果，仍按本地规则"
                }
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun dismissConfirm(memo: Memo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setNeedConfirm(memo.id, false) }
            reload()
        }
    }

    // ================= 条目操作 =================

    fun toggleDone(memo: Memo) {
        viewModelScope.launch {
            val next = if (memo.todoStatus == TodoStatus.DONE) TodoStatus.PENDING else TodoStatus.DONE
            withContext(Dispatchers.IO) { repo.setTodoStatus(memo.id, next) }
            rescheduleReminders()
            reload()
        }
    }

    fun changeType(memo: Memo, type: MemoType) = reclassify(memo, type)

    fun delete(memo: Memo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.trash(memo.id) }
            rescheduleReminders()
            if (selectedId == memo.id) selectedId = null
            review = review?.let { r -> r.copy(items = r.items.filterNot { it.id == memo.id }) }
                ?.takeIf { it.items.isNotEmpty() }
            reload()
            message = "已移除：${memo.title}"
        }
    }

    fun renameMemo(memo: Memo, title: String) {
        val t = title.trim()
        if (t.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setTitle(memo.id, t.take(24)) }
            reload()
        }
    }

    // ================= 3.4 灵感深化 =================

    var insightMemo by mutableStateOf<Memo?>(null)
        private set
    var insightMode by mutableStateOf("梳理")
        private set
    var insightPhase by mutableStateOf(InsightPhase.IDLE)
        private set
    var insightView by mutableStateOf<InsightView?>(null)
        private set
    var insightError by mutableStateOf<String?>(null)
        private set
    var insightVersions by mutableStateOf<List<InsightVersion>>(emptyList())
        private set
    var insightLatency by mutableStateOf(0L)
        private set
    var insightDegraded by mutableStateOf(false)
        private set
    var insightCount by mutableStateOf(0)
        private set

    val insightOpen: Boolean get() = insightMemo != null

    fun openInsight(memo: Memo) {
        insightMemo = memo
        insightMode = "梳理"
        insightError = null
        insightDegraded = false
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { repo.insightsOf(memo.id) }
            insightVersions = versions
            val hit = versions.firstOrNull { it.mode == "梳理" } ?: versions.firstOrNull()
            if (hit != null) {
                insightMode = hit.mode
                insightView = hit.view
                insightLatency = hit.latencyMs
                insightPhase = InsightPhase.READY
            } else if (!memo.expanded.isNullOrBlank()) {
                insightView = InsightParser.parse("梳理", memo.expanded!!)
                insightPhase = InsightPhase.READY
            } else {
                insightView = null
                insightPhase = InsightPhase.IDLE
            }
            insightCount = withContext(Dispatchers.IO) { repo.countInsights() }
        }
    }

    fun closeInsight() {
        insightMemo = null
        insightView = null
        insightError = null
        insightPhase = InsightPhase.IDLE
        insightVersions = emptyList()
    }

    fun switchInsightMode(mode: String) {
        if (insightMode == mode) return
        insightMode = mode
        insightError = null
        val hit = insightVersions.firstOrNull { it.mode == mode }
        if (hit != null) {
            insightView = hit.view
            insightLatency = hit.latencyMs
            insightDegraded = hit.view.degraded
            insightPhase = InsightPhase.READY
        } else {
            insightView = null
            insightPhase = InsightPhase.IDLE
        }
    }

    fun selectVersion(v: InsightVersion) {
        insightMode = v.mode
        insightView = v.view
        insightLatency = v.latencyMs
        insightDegraded = v.view.degraded
        insightPhase = InsightPhase.READY
        insightError = null
    }

    fun runInsight(mode: String = insightMode) {
        val memo = insightMemo ?: return
        insightMode = mode
        insightPhase = InsightPhase.RUNNING
        insightError = null
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            val cfg = settings.llmConfig()
            if (cfg.apiKey.isNullOrBlank() || cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                // 没有 Key：给离线骨架，并明确告知不是 AI 生成
                insightView = InsightSkeleton.build(mode, memo.contentRaw)
                insightDegraded = true
                insightLatency = System.currentTimeMillis() - t0
                insightPhase = InsightPhase.READY
                insightError = AppError.KeyMissing.userMsg + " · 下面是离线骨架"
                return@launch
            }
            val res = a.llm.chat(
                cfg = cfg,
                system = Prompts.EXPAND_SYSTEM,
                user = Prompts.expandIdea(mode, memo.contentRaw.take(3000)),
                maxTokens = 1200,
                jsonMode = false,
                temperature = 0.4f
            )
            val latency = System.currentTimeMillis() - t0
            insightLatency = latency
            when (res) {
                is SuiResult.Ok -> {
                    val view = InsightParser.parse(mode, res.data)
                    insightView = view
                    insightDegraded = view.degraded
                    insightPhase = InsightPhase.READY
                    withContext(Dispatchers.IO) {
                        repo.insertInsight(
                            InsightVersion(
                                memoId = memo.id, mode = mode, content = res.data,
                                latencyMs = latency, ok = true
                            )
                        )
                        repo.setExpanded(memo.id, res.data, ExpandState.EXPANDED)
                        repo.pruneInsights()
                        insightVersions = repo.insightsOf(memo.id)
                        insightCount = repo.countInsights()
                    }
                    reload()
                }

                is SuiResult.Err -> {
                    // 失败也不留白：给骨架 + 明确原因 + 重试
                    insightView = InsightSkeleton.build(mode, memo.contentRaw)
                    insightDegraded = true
                    insightPhase = InsightPhase.FAILED
                    insightError = res.error.userMsg + " · " + res.error.hint
                    withContext(Dispatchers.IO) {
                        repo.setExpanded(memo.id, memo.expanded, ExpandState.FAILED)
                    }
                }
            }
        }
    }

    /** 把「我想做什么」变成标题 */
    fun applyInsightTitle() {
        val memo = insightMemo ?: return
        val what = insightView?.sections?.firstOrNull {
            it.heading.contains("做什么") || it.heading.contains("想法")
        }?.body?.lineSequence()?.firstOrNull()?.trim()
        if (what.isNullOrBlank()) {
            message = "这次结果里没有可用的标题"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setTitle(memo.id, what.take(24)) }
            reload()
            message = "标题已更新"
        }
    }

    /** 把「关键不确定点」落成待办 —— 深化结果不再是死文本 */
    fun promoteRisksToTodos() {
        val memo = insightMemo ?: return
        val view = insightView ?: return
        val risks = view.sections
            .filter { it.heading.contains("不确定") || it.heading.contains("风险") }
            .flatMap { it.body.lines() }
            .map { it.trim().trimStart('-', '•', '*', '1', '2', '3', '4', '5', '.', '、').trim() }
            .filter { it.length >= 4 }
            .take(3)
        if (risks.isEmpty()) {
            message = "这次结果里没有不确定点"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                risks.forEach { r ->
                    repo.insert(
                        Memo(
                            type = MemoType.TODO,
                            title = "验证：${r.take(18)}",
                            contentRaw = "来自灵感「${memo.title}」的深化结果\n$r",
                            createdAt = now,
                            updatedAt = now,
                            source = CaptureSource.TEXT,
                            engine = EngineKind.MANUAL,
                            confidence = 1f,
                            classifyReason = "由深化结果的关键不确定点转成",
                            tags = listOf("深化")
                        )
                    )
                }
            }
            closeInsight()
            switchTab(HomeTab.HOME)
            filter = MemoFilter.TODO
            reload()
            message = "已把 ${risks.size} 个不确定点变成待办"
        }
    }

    // ================= 设置 =================

    val providers = LlmProviders.ALL

    fun currentProviderId(): String = settings.providerId

    fun providerName(): String = settings.provider().name

    fun baseUrl(): String = settings.effectiveBaseUrl()

    fun baseUrlEditable(): Boolean = settings.providerId == LlmProviders.CUSTOM_ID

    fun model(): String = settings.effectiveModel()

    fun modelOptions(): List<String> = settings.provider().models

    fun apiKeyMasked(): String? = SecureStore.mask(settings.apiKey())

    fun hasApiKey(): Boolean = !settings.apiKey().isNullOrBlank()

    fun selectProvider(id: String) {
        settings.providerId = id
        val p = LlmProviders.byId(id)
        settings.customBaseUrl = if (id == LlmProviders.CUSTOM_ID) "" else p.baseUrl
        settings.customModel = p.defaultModel
        connSummary = null
        connSteps = emptyList()
    }

    fun setBaseUrl(url: String) {
        settings.customBaseUrl = url
        connSummary = null
    }

    fun setModel(m: String) {
        settings.customModel = m
        connSummary = null
    }

    fun setApiKey(key: String) {
        SecureStore.putApiKey(getApplication(), settings.providerId, key.trim())
        connSummary = null
        connSteps = emptyList()
    }

    fun clearApiKey() {
        SecureStore.removeApiKey(getApplication(), settings.providerId)
    }

    fun correctionCount(): Int = a.classifier.correctionCount()

    fun clearCorrections() {
        a.classifier.clearCorrections()
        message = "已清空纠错记忆"
    }

    fun insightTotal(): Int = insightCount

    // ---- 语音转写 ----

    var asrModeState by mutableStateOf(settings.asrMode)
        private set

    fun asrMode(): String = asrModeState

    fun setAsrMode(m: String) {
        settings.asrMode = m
        asrModeState = m
    }

    fun asrBaseUrl(): String = settings.effectiveAsrBaseUrl()

    fun setAsrBaseUrl(v: String) {
        settings.asrBaseUrl = v.trim()
    }

    fun asrModel(): String = settings.effectiveAsrModel()

    fun setAsrModel(v: String) {
        settings.asrModel = v.trim()
    }

    fun providerHasAsr(): Boolean = settings.provider().asrModel.isNotBlank()

    fun asrReady(): Boolean = settings.cloudAsrUsable()

    fun offlineAsrReady(): Boolean = OfflineAsr.isReady(getApplication())

    fun asrRoute(): AsrRoute =
        if (settings.cloudAsrUsable()) AsrRoute.CLOUD else AsrRoute.OFFLINE

    var reminderEnabled by mutableStateOf(settings.reminderEnabled)
        private set
    var idleThreshold by mutableStateOf(settings.idleThreshold)
        private set
    var calendarEnabled by mutableStateOf(settings.calendarSyncEnabled)
        private set

    fun updateReminderEnabled(v: Boolean) {
        reminderEnabled = v
        settings.reminderEnabled = v
        rescheduleReminders()
    }

    /** 通知权限是否已授予（设置页用来显示真实状态，而不是让用户猜） */
    fun notificationsAllowed(): Boolean = a.notifier.canPost()

    /** 试一下：直接在通知栏投一条，验证权限 + 渠道整条链路 */
    fun testNotification(): Boolean {
        val ok = a.notifier.showTest()
        message = if (ok) "已发送测试通知，下拉通知栏看看" else "发不出去：通知权限被关闭了"
        return ok
    }

    /** 重新对账所有待办的到点闹钟 */
    fun rescheduleReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AlarmScheduler.sync(a) }
        }
    }

    /** 通知上的「稍后」在 App 内也能触发 */
    fun snoozeReminder(memo: Memo) {
        viewModelScope.launch {
            val until = SuiClock.now() + AppConfig.SNOOZE_MINUTES * 60_000L
            withContext(Dispatchers.IO) {
                repo.setSnooze(memo.id, until)
                repo.logReminder(memo.id, SuiClock.now(), 0, "INLINE_BAR", "SNOOZE", "user")
            }
            a.notifier.cancelSoft()
            if (inlineReminder?.id == memo.id) inlineReminder = null
            rescheduleReminders()
            reload()
            message = "推迟到 ${TimeNormalizer.format(until, com.suiji.assistant.core.DuePrecision.EXACT_TIME)}"
        }
    }

    fun updateIdleThreshold(v: Int) {
        idleThreshold = v
        settings.idleThreshold = v
    }

    // ================= 系统日历 =================

    fun calendarPermissionOk(): Boolean = a.calendar.hasPermission()

    fun markNotifAsked() {
        settings.notifAsked = true
    }

    fun notifAskedBefore(): Boolean = settings.notifAsked

    fun calendarLeadMinutes(): Int = settings.calendarLeadMinutes

    fun cycleCalendarLead() {
        val next = when (settings.calendarLeadMinutes) {
            0 -> 5
            5 -> 15
            15 -> 30
            30 -> 60
            else -> 0
        }
        settings.calendarLeadMinutes = next
        message = "日历提醒提前 $next 分钟"
        calendarLeadTick++
    }

    fun calendarName(): String {
        if (!a.calendar.hasPermission()) return "未授权"
        val list = a.calendar.calendars()
        if (list.isEmpty()) return "没有可写日历"
        val target = a.calendar.targetCalendarId()
        return list.firstOrNull { it.id == target }?.name ?: list.first().name
    }

    var calendarLeadTick by mutableStateOf(0)
        private set

    fun calendarCalendars(): List<com.suiji.assistant.data.CalendarBridge.CalInfo> =
        a.calendar.calendars()

    fun calendarSummary(): String = when {
        !a.settings.calendarSyncEnabled -> "关闭"
        !a.calendar.hasPermission() -> "待授权"
        a.settings.calendarSyncedCount <= 0 -> "已开启 · 还没写过"
        else -> "已写入 ${a.settings.calendarSyncedCount} 条"
    }

    /** 打开同步：先要权限，拿到后才写历史待办 */
    fun enableCalendarSync(calendarId: Long? = null) {
        a.settings.calendarSyncEnabled = true
        if (calendarId != null && calendarId > 0) a.settings.calendarId = calendarId
        calendarEnabled = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 把已有的、还没写过的带时间待办补写一遍
                repo.list(MemoType.TODO).filter { it.dueAt != null && it.calendarEventId == null }
                    .take(30).forEach { a.calendar.push(it) }
            }
            message = if (a.calendar.hasPermission()) "已开启日历同步" else "还差日历权限，去系统设置里允许"
            reload()
        }
    }

    fun disableCalendarSync() {
        a.settings.calendarSyncEnabled = false
        calendarEnabled = false
        message = "已关闭日历同步（已写入的事件不会自动删除）"
    }

    /** 单条推送：详情页按钮用 */
    fun pushToCalendar(memo: Memo) {
        if (memo.dueAt == null) {
            message = "这条没有时间，先补一个时间再同步"
            return
        }
        if (!a.calendar.hasPermission()) {
            message = "缺少日历权限，去系统设置里允许后再试"
            return
        }
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { a.calendar.push(memo) }
            message = if (id != null) "已写入系统日历" else "写入失败，检查系统里是否还有可写日历"
            reload()
        }
    }


    fun quietRangeLabel(): String =
        "%02d:%02d - %02d:%02d".format(
            settings.quietStartMin / 60, settings.quietStartMin % 60,
            settings.quietEndMin / 60, settings.quietEndMin % 60
        )

    fun sensitivityLabel(): String = when {
        idleThreshold >= 80 -> "克制"
        idleThreshold >= 60 -> "标准"
        else -> "积极"
    }

    fun cycleSensitivity() {
        val next = when {
            idleThreshold >= 80 -> 55
            idleThreshold >= 60 -> 85
            else -> 70
        }
        updateIdleThreshold(next)
    }

    private fun refreshConnSummary() {
        if (settings.lastConnTestAt == 0L) {
            connSummary = null
            return
        }
        connSummary = if (settings.lastConnTestOk) {
            "✓ 正常 ${settings.lastConnLatency}ms"
        } else {
            "✗ ${settings.lastConnError ?: "上次测试失败"}"
        }
    }

    // ================= 连通测试 =================

    fun runConnectionTest() {
        if (connRunning) return
        connRunning = true
        connSummary = null
        val names = listOf("配置校验", "网络连通", "鉴权验证", "分拣能力", "结果保存")
        connSteps = names.map { ConnStep(it) }

        viewModelScope.launch {
            fun setStep(index: Int, phase: StepPhase, detail: String = "") {
                connSteps = connSteps.toMutableList().also {
                    it[index] = it[index].copy(phase = phase, detail = detail)
                }
            }

            val cfg = settings.llmConfig()

            setStep(0, StepPhase.RUNNING)
            val cfgError: AppError? = when {
                cfg.baseUrl.isBlank() -> AppError.ConfigIncomplete
                !cfg.baseUrl.startsWith("http") -> AppError.ConfigIncomplete
                cfg.model.isBlank() -> AppError.ConfigIncomplete
                cfg.apiKey.isNullOrBlank() -> AppError.KeyMissing
                cfg.apiKey.length < 8 -> AppError.KeyTooShort
                else -> null
            }
            if (cfgError != null) {
                setStep(0, StepPhase.FAIL, cfgError.userMsg + " · " + cfgError.hint)
                finishTest(false, AppError.ConfigIncomplete, 0)
                return@launch
            }
            setStep(0, StepPhase.PASS, "${cfg.providerName} · ${cfg.model}")

            setStep(1, StepPhase.RUNNING)
            when (val probe = a.llm.probeHost(cfg.baseUrl)) {
                is com.suiji.assistant.net.LlmClient.NetProbe.Fail -> {
                    setStep(1, StepPhase.FAIL, probe.reason)
                    finishTest(false, AppError.DnsError, 0)
                    return@launch
                }

                is com.suiji.assistant.net.LlmClient.NetProbe.Ok ->
                    setStep(1, StepPhase.PASS, "${probe.ms}ms")
            }

            setStep(2, StepPhase.RUNNING)
            val t0 = System.currentTimeMillis()
            val ping = a.llm.chat(
                cfg = cfg,
                system = "你是助手，回答极简。",
                user = Prompts.PING_USER,
                maxTokens = 16,
                jsonMode = false,
                temperature = 0f
            )
            val latency = System.currentTimeMillis() - t0
            if (ping is SuiResult.Err) {
                setStep(2, StepPhase.FAIL, "${ping.error.userMsg} · ${ping.error.hint}")
                finishTest(false, ping.error, latency)
                return@launch
            }
            val reply = (ping as SuiResult.Ok).data.take(20).replace("\n", " ")
            setStep(2, StepPhase.PASS, "${latency}ms · \"$reply\"")

            setStep(3, StepPhase.RUNNING)
            val outcome = a.classifier.classify(Prompts.TEST_CLASSIFY, forceLlm = true)
            val sample = outcome.items.firstOrNull { it is DraftItem.Todo } as? DraftItem.Todo
            if (outcome.engine != EngineKind.LLM) {
                setStep(3, StepPhase.FAIL, "模型未返回可用 JSON，已降级本地规则")
                finishTest(false, AppError.JsonBroken, latency)
                return@launch
            }
            if (sample == null) {
                setStep(3, StepPhase.FAIL, "未识别出待办条目，建议换 deepseek-chat 或 glm-4-flash")
                finishTest(false, AppError.JsonBroken, latency)
                return@launch
            }
            val timeText = TimeNormalizer.format(sample.draft.dueAt, sample.draft.duePrecision)
            setStep(3, StepPhase.PASS, "识别为待办「${sample.draft.title}」$timeText")

            setStep(4, StepPhase.RUNNING)
            settings.lastConnTestAt = System.currentTimeMillis()
            settings.lastConnTestOk = true
            settings.lastConnLatency = latency
            settings.lastConnError = null
            setStep(4, StepPhase.PASS, "已记录")
            connSummary = "✓ 正常 ${latency}ms"
            connRunning = false
        }
    }

    private fun finishTest(ok: Boolean, error: AppError, latency: Long) {
        settings.lastConnTestAt = System.currentTimeMillis()
        settings.lastConnTestOk = ok
        settings.lastConnLatency = latency
        settings.lastConnError = if (ok) null else error.userMsg
        connSummary = if (ok) "✓ 正常 ${latency}ms" else "✗ ${error.userMsg}"
        connRunning = false
    }

    // ================= 应用内柔性提醒条 =================

    fun checkInlineReminder() {
        if (inlineReminder != null) return
        val now = SuiClock.now()
        val candidate = memos.firstOrNull { m ->
            m.isTodo && m.todoStatus == TodoStatus.PENDING &&
                    m.dueAt != null && m.dueAt <= now &&
                    m.remindCount < AppConfig.MAX_FIRES_PER_MEMO &&
                    (m.snoozedUntil == null || m.snoozedUntil <= now)
        }
        if (candidate != null && settings.reminderEnabled) {
            inlineReminder = candidate
        }
    }

    fun hideInlineReminder() {
        inlineReminder = null
    }

    fun ackInlineReminder(memo: Memo) {
        hideInlineReminder()
        selectedId = memo.id
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                repo.markReminded(memo.id, memo.remindCount + 1, now)
                repo.logReminder(memo.id, now, 100, "INLINE_BAR", "ACK", "app_foreground")
            }
            a.notifier.cancelSoft()
            reload()
        }
    }

    private fun typeWord(t: MemoType) = when (t) {
        MemoType.TODO -> "待办"
        MemoType.IDEA -> "灵感"
        MemoType.UNCLASSIFIED -> "待整理"
    }
}
