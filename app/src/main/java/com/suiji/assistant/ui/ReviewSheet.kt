package com.suiji.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.ClassifyDecision
import com.suiji.assistant.core.EngineKind
import com.suiji.assistant.core.MemoType
import com.suiji.assistant.vm.AppViewModel
import com.suiji.assistant.vm.ReviewState

/**
 * 分拣回执（3.2 新增交互）。
 *
 * 触发条件：置信度不够，或一段话拆出了多条。
 * 它替代了旧版「Toast 一闪而过、用户根本不知道 AI 猜了什么」的做法：
 * 每条都能就地改成待办/灵感，改的同时会被记进纠错记忆，下次同类输入更准。
 */
@Composable
fun ReviewSheet(vm: AppViewModel) {
    val r = vm.review ?: return
    val extra = suijiExtra

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                .clickable { vm.closeReview() }
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SheetSurface {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = PagePad, end = 10.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (r.items.size > 1) "拆成了 ${r.items.size} 条" else "AI 这样归类",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(3.dp))
                            // 拆成两行：第一行是「怎么判的」，第二行是「为什么拿不准」。
                            // 合成一行时中文会断在奇怪的位置（引擎 · 50% · 拿不太准 · 时间词…78ms）
                            Text(
                                reasonMeta(r),
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = extra.textSecondary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                reasonWhy(r),
                                fontSize = 12.5.sp,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = extra.textSecondary
                            )
                        }
                        Pressable(onClick = { vm.closeReview() }, modifier = Modifier.size(36.dp)) {
                            CloseIcon(Modifier.size(18.dp), extra.textSecondary)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        r.items.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = extra.softBg.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PagePad, vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Text(
                                        item.title,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TypeToggle("待办", item.type == MemoType.TODO) {
                                            vm.reclassifyById(item.id, item.title, MemoType.TODO)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TypeToggle("灵感", item.type == MemoType.IDEA) {
                                            vm.reclassifyById(item.id, item.title, MemoType.IDEA)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            item.timeLabel,
                                            fontSize = 12.5.sp,
                                            color = extra.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = PagePad, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "全部撤销",
                            fontSize = 14.sp,
                            color = extra.textSecondary,
                            modifier = Modifier
                                .clickable { vm.discardReview(r) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        PrimaryButton(
                            text = "都对了",
                            modifier = Modifier.width(150.dp),
                            height = 46.dp,
                            onClick = { vm.confirmAll(r) }
                        )
                    }
                }
            }
        }
    }
}

private fun reasonMeta(r: ReviewState): String {
    val engine = when (r.engine) {
        EngineKind.LLM -> "大模型判定"
        EngineKind.RULE -> "本地规则判定"
        EngineKind.MANUAL -> "手动输入"
    }
    val pct = (r.confidence * 100).toInt()
    return buildString {
        append(engine).append(" · 把握 ").append(pct).append("%")
        if (r.elapsedMs > 0) append(" · ").append(r.elapsedMs).append("ms")
    }
}

private fun reasonWhy(r: ReviewState): String {
    val tail = when (r.decision) {
        ClassifyDecision.AUTO -> "已归档"
        ClassifyDecision.CONFIRM -> "拿不太准，请确认"
        ClassifyDecision.PARK -> "先归到待整理"
    }
    return buildString {
        append(tail)
        if (!r.timeExpr.isNullOrBlank()) append(" · 时间词「").append(r.timeExpr).append("」")
    }
}

@Composable
private fun TypeToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val extra = suijiExtra
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else extra.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
