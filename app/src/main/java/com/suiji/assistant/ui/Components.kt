package com.suiji.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.Memo
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.core.TimeNormalizer
import com.suiji.assistant.core.TodoStatus
import com.suiji.assistant.vm.HomeTab

// ============================================================
// 圆角与间距常量（对齐设计稿，避免各页面各写一套）
// ============================================================

object SuiDimen {
    val CardRadius = 14.dp
    val ButtonRadius = 12.dp
    val SheetRadius = 22.dp
    val CardGap = 10.dp
    val CardPadH = 18.dp
    val CardPadV = 16.dp
}

/** 按压反馈：轻微缩放，不做水波纹（设计稿是干净的商务风） */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val extra = suijiExtra
    Box(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ============================================================
// 药丸分段控件（全部 / 待办 / 灵感）
// ============================================================

@Composable
fun PillSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    onSelect: (Int) -> Unit
) {
    val extra = suijiExtra
    Row(
        modifier
            .clip(CircleShape)
            .background(extra.softBg)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val fg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface else extra.textSecondary,
                tween(160), label = "segFg"
            )
            Box(
                Modifier
                    .then(if (stretch) Modifier.weight(1f) else Modifier)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier
                            .shadow(
                                2.dp, CircleShape,
                                ambientColor = Color(0x14000000),
                                spotColor = Color(0x1A000000)
                            )
                            .background(MaterialTheme.colorScheme.surface)
                        else Modifier
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = if (stretch) 0.dp else 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = fg,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ============================================================
// 顶栏
// ============================================================

@Composable
fun SuiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    centerTitle: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = PagePad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Pressable(onClick = onBack, modifier = Modifier.size(40.dp)) {
                BackIcon(Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        }
        if (centerTitle && onBack != null) Spacer(Modifier.weight(1f))
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** 大标题行（首页「随记」+ 右侧设置） */
@Composable
fun HomeTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    subtitle: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = PagePad, end = PagePad, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.5.sp, color = suijiExtra.textSecondary)
            }
        }
        if (onSettings != null) {
            Pressable(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                GearIcon(Modifier.size(22.dp), suijiExtra.textSecondary)
            }
        }
    }
}

// ============================================================
// 分组标题：今天 · 3
// ============================================================

@Composable
fun GroupHeader(label: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (count != null && count > 0) "$label · $count" else label,
            fontSize = 13.sp,
            color = suijiExtra.textSecondary
        )
    }
}

// ============================================================
// 卡片
// ============================================================

@Composable
fun MemoCard(
    memo: Memo,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleDone: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onConfirm: (() -> Unit)? = null
) {
    val extra = suijiExtra
    val done = memo.todoStatus == TodoStatus.DONE
    val overdue = memo.isOverdue

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(SuiDimen.CardRadius),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.4.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else extra.line
        )
    ) {
        Row(
            Modifier.padding(
                start = SuiDimen.CardPadH, end = 14.dp,
                top = SuiDimen.CardPadV, bottom = SuiDimen.CardPadV
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = memo.title.ifBlank { memo.contentRaw.take(20) },
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) extra.textSecondary else MaterialTheme.colorScheme.onSurface
                )
                val sub = subtitleOf(memo, done, overdue)
                if (sub != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (overdue) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(extra.danger, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            sub,
                            fontSize = 13.sp,
                            color = when {
                                overdue -> extra.danger
                                done -> extra.textSecondary
                                memo.needConfirm -> MaterialTheme.colorScheme.primary
                                else -> extra.textSecondary
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            when {
                onConfirm != null -> ConfirmAction(onConfirm)
                memo.isTodo && onToggleDone != null -> {
                    if (done) {
                        CheckIcon(Modifier.size(20.dp), extra.success)
                    } else {
                        TodoCheck(checked = false, onToggle = onToggleDone)
                    }
                }

                memo.isIdea && onExpand != null -> TextAction("深化", onExpand)
                else -> Unit
            }
        }
    }
}

private fun subtitleOf(memo: Memo, done: Boolean, overdue: Boolean): String? {
    val parts = mutableListOf<String>()
    when {
        memo.isTodo && done -> parts += "已完成"
        memo.isTodo && overdue -> parts += "逾期"
        memo.isTodo -> {
            if (memo.dueAt != null) parts += TimeNormalizer.format(memo.dueAt, memo.duePrecision)
        }

        memo.isIdea -> {
            parts += "灵感"
            if (memo.expanded.isNullOrBlank()) parts += "待深化" else parts += "已深化"
        }

        else -> parts += "待整理"
    }
    if (memo.needConfirm && !done) parts += "待确认"
    memo.tags.take(1).forEach { parts += it }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Composable
private fun ConfirmAction(onClick: () -> Unit) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = extra.todoSoft,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            "确认",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color? = null) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color ?: MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** 待办勾选：未完成是细线圈，完成后由卡片右侧换成绿勾 */
@Composable
fun TodoCheck(checked: Boolean, onToggle: () -> Unit) {
    val extra = suijiExtra
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "chk")
    Surface(
        modifier = Modifier
            .size(24.dp)
            .clickable { onToggle() },
        shape = CircleShape,
        color = if (checked) extra.success else Color.Transparent,
        border = BorderStroke(
            1.5.dp,
            if (checked) extra.success else extra.line
        )
    ) {
        if (progress > 0.5f) {
            Box(contentAlignment = Alignment.Center) {
                CheckIcon(Modifier.size(13.dp), Color.White)
            }
        }
    }
}

/** 类型标签：待办=蓝底蓝字，灵感=紫底紫字 */
@Composable
fun TypeTag(type: MemoType, modifier: Modifier = Modifier) {
    val extra = suijiExtra
    val (text, fg, bg) = when (type) {
        MemoType.TODO -> Triple("待办", extra.todo, extra.todoSoft)
        MemoType.IDEA -> Triple("灵感", extra.idea, extra.ideaSoft)
        MemoType.UNCLASSIFIED -> Triple("待整理", extra.textSecondary, extra.softBg)
    }
    Surface(shape = RoundedCornerShape(7.dp), color = bg, modifier = modifier) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    val extra = suijiExtra
    Surface(shape = CircleShape, color = extra.softBg, modifier = modifier) {
        Text(
            text,
            fontSize = 12.sp,
            color = extra.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ============================================================
// 按钮
// ============================================================

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onClick: () -> Unit
) {
    val extra = suijiExtra
    val bg = if (enabled) MaterialTheme.colorScheme.primary else extra.softBg
    Surface(
        shape = RoundedCornerShape(SuiDimen.ButtonRadius),
        color = bg,
        modifier = modifier
            .height(height)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else extra.textSecondary
            )
        }
    }
}

/** 次级按钮：浅灰底、无边框（设计稿「重新生成」） */
@Composable
fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(SuiDimen.ButtonRadius),
        color = extra.softBg,
        modifier = modifier
            .height(height)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.textSecondary
            )
        }
    }
}

/** 白底细边按钮（设计稿「稍后」） */
@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, suijiExtra.line),
        modifier = modifier
            .height(height)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

// ============================================================
// 搜索
// ============================================================

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索记录、人、时间"
) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = extra.softBg,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchIcon(Modifier.size(16.dp), extra.textSecondary)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 14.5.sp, color = extra.textSecondary)
                    }
                    inner()
                }
            )
            if (value.isNotEmpty()) {
                Pressable(onClick = { onValueChange("") }, modifier = Modifier.size(22.dp)) {
                    CloseIcon(Modifier.size(14.dp), extra.textSecondary)
                }
            }
        }
    }
}

// ============================================================
// 空状态（对应设计稿第 7 屏）
// ============================================================

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: Boolean = true
) {
    val extra = suijiExtra
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (art) {
            EmptyMicArt(120.dp)
            Spacer(Modifier.height(26.dp))
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = extra.textSecondary
        )
    }
}

// ============================================================
// 底部导航
// ============================================================

@Composable
fun BottomNav(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = suijiExtra
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(extra.line)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTab.entries.forEach { t ->
                val selected = t == current
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else extra.textSecondary,
                    label = "navTint"
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(t) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (t) {
                        HomeTab.HOME -> HomeNavIcon(Modifier.size(23.dp), tint, selected)
                        HomeTab.IDEA -> BulbIcon(Modifier.size(23.dp), tint, selected)
                        HomeTab.ME -> PersonIcon(Modifier.size(23.dp), tint, selected)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = tint
                    )
                }
            }
        }
    }
}

// ============================================================
// 面板（底部升起）
// ============================================================

@Composable
fun SheetSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = SuiDimen.SheetRadius, topEnd = SuiDimen.SheetRadius),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(suijiExtra.line, CircleShape)
                )
            }
            content()
        }
    }
}

/** 设置项：左标题 右值 + 箭头 */
@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    showChevron: Boolean = true,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val extra = suijiExtra
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = PagePad, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        } else {
            if (value != null) {
                Text(
                    value,
                    fontSize = 15.sp,
                    color = valueColor ?: extra.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f, fill = false)
                )
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                ChevronIcon(Modifier.size(14.dp), extra.textSecondary)
            }
        }
    }
}

@Composable
fun SettingGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (title != null) {
            Text(
                title,
                fontSize = 13.sp,
                color = suijiExtra.textSecondary,
                modifier = Modifier.padding(start = PagePad, top = 14.dp, bottom = 6.dp)
            )
        }
        content()
    }
}

@Composable
fun HairLine(startIndent: Dp = PagePad) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(suijiExtra.line.copy(alpha = 0.8f))
    )
}
