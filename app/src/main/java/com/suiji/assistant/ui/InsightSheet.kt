package com.suiji.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.InsightPhase
import com.suiji.assistant.core.InsightView
import com.suiji.assistant.vm.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 深化灵感（对应设计稿第 5 屏）。
 *
 * 三种模式共用一套外框，切换只换内容区：
 *  - 梳理：三段（我想做什么 / 为什么值得做 / 关键不确定点）
 *  - 拓展：五条方向，编号 + 一句话
 *  - 可行性：四维打分 + 结论徽章
 *
 * 与旧版的差别不只是"变好看"：现在是结构化渲染，而且
 * 结果能反哺记录本身（用作标题 / 不确定点转待办），不再是一坨死文本。
 */
@Composable
fun InsightSheet(vm: AppViewModel) {
    val memo = vm.insightMemo ?: return
    val extra = suijiExtra
    val mode = vm.insightMode

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable { vm.closeInsight() }
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            SheetSurface {
                Column(Modifier.fillMaxSize()) {
                    // 标题行
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = PagePad, end = 10.dp, top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "深化灵感",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Pressable(onClick = { vm.closeInsight() }, modifier = Modifier.size(36.dp)) {
                            CloseIcon(Modifier.size(18.dp), extra.textSecondary)
                        }
                    }

                    // 原想法
                    Text(
                        memo.title,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = extra.textSecondary,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = PagePad, vertical = 4.dp)
                    )

                    Spacer(Modifier.height(10.dp))

                    // 模式切换
                    Row(Modifier.padding(horizontal = PagePad)) {
                        PillSegmented(
                            options = listOf("梳理", "拓展", "可行性"),
                            selectedIndex = listOf("梳理", "拓展", "可行性").indexOf(mode),
                            onSelect = { vm.switchInsightMode(listOf("梳理", "拓展", "可行性")[it]) },
                            stretch = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 内容
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val view = vm.insightView
                        when {
                            vm.insightPhase == InsightPhase.RUNNING -> Generating(mode)
                            view == null -> IdleHint(mode) { vm.runInsight(mode) }
                            else -> InsightBody(view, vm)
                        }
                    }

                    // 错误 / 降级提示
                    AnimatedVisibility(
                        visible = vm.insightError != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PagePad)
                                .background(extra.dangerSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlertIcon(Modifier.size(14.dp), extra.danger)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                vm.insightError.orEmpty(),
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = extra.danger,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // 版本历史
                    if (vm.insightVersions.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        VersionRow(vm)
                    }

                    // 底部操作
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = PagePad, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 还没生成过时不该出现「重新生成」——中间已经有「开始梳理」，
                        // 这里只留一个「完成」，否则两个按钮做的是同一件事。
                        if (vm.insightPhase != InsightPhase.IDLE) {
                            SoftButton(
                                text = if (vm.insightPhase == InsightPhase.RUNNING) "生成中…" else "重新生成",
                                modifier = Modifier.weight(1f),
                                height = 46.dp,
                                enabled = vm.insightPhase != InsightPhase.RUNNING,
                                onClick = { vm.runInsight(mode) }
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        PrimaryButton(
                            text = "完成",
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                            onClick = { vm.closeInsight() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleHint(mode: String, onStart: () -> Unit) {
    val extra = suijiExtra
    Column(
        Modifier
            .fillMaxSize()
            .padding(PagePad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            SparkIcon(Modifier.size(30.dp), MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "还没${mode}过",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (mode) {
                "梳理" -> "把它拆成：我想做什么、为什么值得做、卡在哪"
                "拓展" -> "沿同一方向再想五个变体"
                else -> "四个维度打分，给一句能落地的结论"
            },
            fontSize = 13.sp,
            color = extra.textSecondary
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = "开始$mode",
            modifier = Modifier.width(160.dp),
            onClick = onStart
        )
    }
}

@Composable
private fun Generating(mode: String) {
    val extra = suijiExtra
    Column(
        Modifier
            .fillMaxSize()
            .padding(PagePad)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                Modifier.size(15.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text("正在$mode…", fontSize = 13.sp, color = extra.textSecondary)
        }
        Spacer(Modifier.height(18.dp))
        listOf(1f, 0.92f, 0.7f, 1f, 0.55f).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(13.dp)
                    .background(extra.softBg, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InsightBody(view: InsightView, vm: AppViewModel) {
    val extra = suijiExtra
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePad)
    ) {
        if (view.degraded) {
            Text(
                "离线骨架 · 非 AI 生成",
                fontSize = 11.5.sp,
                color = extra.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 梳理
        view.sections.forEach { s ->
            Text(
                s.heading,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(5.dp))
            // 多行正文（理由 / 不确定点）逐条加圆点，比一整块换行文字好扫
            val lines = s.body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size <= 1) {
                Text(
                    s.body,
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                lines.forEach { line ->
                    Row {
                        Text(
                            "·",
                            fontSize = 15.sp,
                            lineHeight = 25.sp,
                            color = extra.textSecondary
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            line,
                            fontSize = 15.sp,
                            lineHeight = 25.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 拓展
        view.directions.forEachIndexed { i, d ->
            Row(Modifier.padding(vertical = 7.dp)) {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${i + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (d.note.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            d.note,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = extra.textSecondary
                        )
                    }
                }
            }
        }

        // 可行性
        if (view.scores.isNotEmpty()) {
            view.scores.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (s.note.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(s.note, fontSize = 12.5.sp, lineHeight = 18.sp, color = extra.textSecondary)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    ScoreDots(s.score)
                }
            }
        }

        view.verdict?.let { v ->
            Spacer(Modifier.height(10.dp))
            val (fg, bg) = when {
                v.contains("值得") -> extra.success to extra.success.copy(alpha = 0.10f)
                v.contains("放弃") -> extra.danger to extra.dangerSoft
                else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
            }
            Surface(shape = RoundedCornerShape(12.dp), color = bg) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(
                        "结论 · $v",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = fg
                    )
                    if (!view.verdictNote.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            view.verdictNote!!,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 反哺操作：深化结果要能回写记录本身，而不是一坨死文本
        val hasWhat = view.sections.any { it.heading.contains("做什么") || it.heading.contains("想法") }
        val hasRisk = view.sections.any { it.heading.contains("不确定") || it.heading.contains("风险") }
        if (hasWhat || hasRisk) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasWhat) SmallGhost("用作标题") { vm.applyInsightTitle() }
                if (hasRisk) SmallGhost("不确定点转待办") { vm.promoteRisksToTodos() }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ScoreDots(score: Int) {
    val extra = suijiExtra
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = 14.dp, height = 5.dp)
                    .background(
                        if (i < score) MaterialTheme.colorScheme.primary else extra.line,
                        CircleShape
                    )
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (score <= 0) "—" else "$score",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (score <= 0) extra.textSecondary else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SmallGhost(text: String, onClick: () -> Unit) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = extra.softBg,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun VersionRow(vm: AppViewModel) {
    val extra = suijiExtra
    val fmt = androidx.compose.runtime.remember {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePad, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("历史", fontSize = 11.5.sp, color = extra.textSecondary)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            vm.insightVersions.forEach { v ->
                Surface(
                    shape = CircleShape,
                    color = extra.softBg,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clickable { vm.selectVersion(v) }
                ) {
                    Text(
                        "${v.mode} ${fmt.format(Date(v.createdAt))}",
                        fontSize = 11.sp,
                        color = extra.textSecondary,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
