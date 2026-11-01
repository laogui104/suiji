package com.suiji.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.InsightParser
import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.TimeNormalizer
import com.suiji.assistant.core.TodoStatus
import com.suiji.assistant.vm.AppViewModel
import com.suiji.assistant.vm.HomeTab
import com.suiji.assistant.vm.MemoFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页列表（对应设计稿第 1 / 8 屏）。
 * 结构：标题行 + 可选搜索 + 待确认条 + 分组卡片。
 */
@Composable
fun ListPane(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    showTitleRow: Boolean = true
) {
    var searchOpen by remember { mutableStateOf(false) }
    val isIdea = vm.tab == HomeTab.IDEA

    Column(modifier.fillMaxSize()) {
        if (showTitleRow) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = PagePad, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isIdea) "灵感" else "随记",
                    fontSize = 27.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(14.dp))
                if (!isIdea) {
                    PillSegmented(
                        options = MemoFilter.entries.map { it.label },
                        selectedIndex = MemoFilter.entries.indexOf(vm.filter),
                        onSelect = { vm.applyFilter(MemoFilter.entries[it]) }
                    )
                }
                Spacer(Modifier.weight(1f))
                Pressable(onClick = { searchOpen = !searchOpen }, modifier = Modifier.size(40.dp)) {
                    SearchIcon(Modifier.size(20.dp), suijiExtra.textSecondary)
                }
                Pressable(onClick = { vm.openSettings() }, modifier = Modifier.size(40.dp)) {
                    GearIcon(Modifier.size(21.dp), suijiExtra.textSecondary)
                }
            }

            FadeInOut(searchOpen) {
                SearchField(
                    value = vm.query,
                    onValueChange = { vm.applyQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PagePad, vertical = 8.dp)
                )
            }
        }

        val memos = vm.memos
        when {
            memos.isEmpty() && vm.loading -> Unit

            memos.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = when {
                        vm.query.isNotBlank() -> "没找到匹配的内容"
                        isIdea -> "还没有灵感"
                        else -> "还没有记录"
                    },
                    subtitle = when {
                        vm.query.isNotBlank() -> "换个关键词试试"
                        isIdea -> "随口冒出来的想法，都会落到这里"
                        else -> "轻触下方的按钮，记录你的第一段记忆"
                    }
                )
            }

            else -> {
                val grouped = groupByDay(memos)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PagePad, end = PagePad, top = 6.dp, bottom = 24.dp
                    )
                ) {
                    // 柔性提醒是列表的一部分（设计稿第 8 屏就放在列表最上方），
                    // 不做浮层 —— 浮层会压住标题和分段控件。
                    vm.inlineReminder?.let { memo ->
                        item(key = "reminder_${memo.id}") {
                            // 不再 9 秒自动消失：提醒自动消失等于用户错过。
                            // 留到用户点「现在处理」或「稍后」为止。
                            InlineReminderCard(
                                memo = memo,
                                onClick = { vm.ackInlineReminder(memo) },
                                onSnooze = { vm.snoozeReminder(memo) },
                                onDismiss = { vm.hideInlineReminder() }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (vm.pending.isNotEmpty()) {
                        item(key = "pending") {
                            PendingBanner(
                                count = vm.pending.size,
                                onClick = { vm.select(vm.pending.first().id) }
                            )
                        }
                    }
                    grouped.forEach { (label, list) ->
                        item(key = "h_$label") { GroupHeader(label, list.size) }
                        items(list, key = { it.id }) { memo ->
                            MemoCard(
                                memo = memo,
                                selected = vm.selectedId == memo.id,
                                onClick = { vm.select(memo.id) },
                                onToggleDone = if (memo.isTodo) {
                                    { vm.toggleDone(memo) }
                                } else null,
                                onExpand = if (memo.isIdea) {
                                    { vm.openInsight(memo) }
                                } else null,
                                onConfirm = if (memo.needConfirm) {
                                    { vm.confirm(memo) }
                                } else null
                            )
                            Spacer(Modifier.height(SuiDimen.CardGap))
                        }
                    }
                }
            }
        }
    }
}

/** 顶部「N 条待你确认」提示条 */
@Composable
private fun PendingBanner(count: Int, onClick: () -> Unit) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(SuiDimen.CardRadius),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BellIcon(Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$count 条待你确认",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "AI 拿不太准，点一下就能定",
                    fontSize = 12.sp,
                    color = extra.textSecondary
                )
            }
            Text(
                "去看看",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 柔性提醒卡（对应设计稿第 8 屏） */
@Composable
fun InlineReminderCard(
    memo: Memo,
    onClick: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(SuiDimen.CardRadius),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BellIcon(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "柔性提醒",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Pressable(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    CloseIcon(Modifier.size(13.dp), extra.textSecondary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "你之前提到要${memo.title}，现在方便处理吗？",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlineButton("稍后 ${AppConfig.SNOOZE_MINUTES} 分钟", height = 36.dp, onClick = onSnooze)
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .height(36.dp)
                        .clickable { onClick() }
                ) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "现在处理",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 详情（对应设计稿第 4 屏）
// ============================================================

@Composable
fun DetailPane(
    vm: AppViewModel,
    memo: Memo,
    modifier: Modifier = Modifier,
    showBack: Boolean = true
) {
    val extra = suijiExtra
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                Pressable(onClick = { vm.select(null) }, modifier = Modifier.size(40.dp)) {
                    BackIcon(Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "详情",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PagePad)
        ) {
            Spacer(Modifier.height(8.dp))
            TypeTag(memo.type)
            Spacer(Modifier.height(14.dp))
            Text(
                memo.title.ifBlank { memo.contentRaw.take(24) },
                fontSize = 25.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClockIcon(
                    Modifier.size(13.dp),
                    if (memo.isOverdue) extra.danger else extra.textSecondary
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    metaLine(memo),
                    fontSize = 13.sp,
                    color = if (memo.isOverdue) extra.danger else extra.textSecondary
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                memo.contentRaw,
                fontSize = 15.5.sp,
                lineHeight = 16.sp * 1.7f,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (memo.tags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    memo.tags.take(4).forEach { TagChip(it) }
                }
            }

            Spacer(Modifier.height(20.dp))
            JudgmentCard(vm = vm, memo = memo)

            if (memo.isIdea) {
                Spacer(Modifier.height(14.dp))
                InsightPreviewCard(vm = vm, memo = memo)
            }

            Spacer(Modifier.height(28.dp))
        }

        // 底部操作条
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(extra.line)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PagePad, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "删除",
                    fontSize = 16.sp,
                    color = extra.textSecondary,
                    modifier = Modifier
                        .clickable { vm.delete(memo) }
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                if (memo.isTodo) {
                    PrimaryButton(
                        text = if (memo.todoStatus == TodoStatus.DONE) "标记未完成" else "标记完成",
                        height = 46.dp,
                        modifier = Modifier.widthIn(min = 132.dp),
                        onClick = { vm.toggleDone(memo) }
                    )
                } else {
                    PrimaryButton(
                        text = "深化这个灵感",
                        height = 46.dp,
                        modifier = Modifier.widthIn(min = 132.dp),
                        onClick = { vm.openInsight(memo) }
                    )
                }
            }
        }
    }
}

private fun metaLine(memo: Memo): String {
    val time = when {
        memo.isTodo -> TimeNormalizer.format(memo.dueAt, memo.duePrecision)
        else -> "记录于 " + TimeNormalizer.format(memo.createdAt, com.suiji.assistant.core.DuePrecision.EXACT_TIME)
    }
    val state = when {
        memo.isTodo && memo.todoStatus == TodoStatus.DONE -> "已完成"
        memo.isOverdue -> "已逾期"
        memo.isTodo -> if (memo.dueAt != null) "待提醒" else "未设时间"
        else -> if (memo.expanded.isNullOrBlank()) "待深化" else "已深化"
    }
    return "$time · $state"
}

/**
 * 「AI 判定」块 —— 3.2 强化的可视化部分。
 * 用户能直接看到：谁判的、几分把握、依据是什么、时间词被换算成了什么。
 */
@Composable
private fun JudgmentCard(vm: AppViewModel, memo: Memo) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(SuiDimen.CardRadius),
        color = extra.softBg.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SparkIcon(Modifier.size(13.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    "AI 判定",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    engineLabel(memo),
                    fontSize = 12.sp,
                    color = extra.textSecondary
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConfidenceBar(memo.confidence)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${(memo.confidence * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (memo.needConfirm) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "待确认",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (memo.classifyReason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    memo.classifyReason,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = extra.textSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (memo.needConfirm) {
                    MiniPill("确认无误", primary = true) { vm.confirm(memo) }
                }
                MiniPill(
                    if (memo.type == MemoType.TODO) "其实是灵感" else "其实是待办"
                ) {
                    vm.reclassify(
                        memo,
                        if (memo.type == MemoType.TODO) MemoType.IDEA else MemoType.TODO
                    )
                }
                MiniPill("让 AI 重判") { vm.reanalyze(memo) }
            }
        }
    }
}

@Composable
private fun ConfidenceBar(value: Float) {
    val extra = suijiExtra
    val v = value.coerceIn(0f, 1f)
    val color = when {
        v >= 0.62f -> MaterialTheme.colorScheme.primary
        v >= 0.4f -> Color(0xFFE0A11B)
        else -> extra.danger
    }
    Box(
        Modifier
            .width(72.dp)
            .height(5.dp)
            .background(extra.line, CircleShape)
    ) {
        Box(
            Modifier
                .width(72.dp * v)
                .height(5.dp)
                .background(color, CircleShape)
        )
    }
}

private fun engineLabel(memo: Memo): String = when (memo.engine) {
    EngineKind.LLM -> "大模型判定"
    EngineKind.RULE -> "本地规则判定"
    EngineKind.MANUAL -> "手动指定"
}

@Composable
private fun MiniPill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

/** 灵感卡片上的深化结果预览 */
@Composable
private fun InsightPreviewCard(vm: AppViewModel, memo: Memo) {
    val extra = suijiExtra
    val expanded = memo.expanded
    Surface(
        shape = RoundedCornerShape(SuiDimen.CardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, extra.line),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openInsight(memo) }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SparkIcon(Modifier.size(13.dp), extra.idea)
                Spacer(Modifier.width(6.dp))
                Text(
                    "深化灵感",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                ChevronIcon(Modifier.size(13.dp), extra.textSecondary)
            }
            Spacer(Modifier.height(8.dp))
            if (expanded.isNullOrBlank()) {
                Text(
                    "梳理成三段、延伸五个方向、或者打个可行性分",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = extra.textSecondary
                )
            } else {
                val view = remember(expanded) { InsightParser.parse("梳理", expanded) }
                val preview = view.sections.firstOrNull()?.body
                    ?: view.plain.take(80)
                Text(
                    preview,
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ============================================================
// 分组
// ============================================================

fun groupByDay(memos: List<Memo>): List<Pair<String, List<Memo>>> {
    val today = LocalDate.now()
    val buckets = LinkedHashMap<String, MutableList<Memo>>()
    memos.forEach { m ->
        val d = Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val key = when {
            d == today -> "今天"
            d == today.minusDays(1) -> "昨天"
            d.isAfter(today.minusDays(7)) -> "本周"
            d.isAfter(today.minusDays(30)) -> "本月"
            else -> "更早"
        }
        buckets.getOrPut(key) { mutableListOf() }.add(m)
    }
    return buckets.map { it.key to it.value }
}
