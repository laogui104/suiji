package com.suiji.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.AppError
import com.suiji.assistant.voice.VoiceState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 首页录音入口 + 全屏录音页。
 *
 * 两块分别对应设计稿第 1 屏底部的大圆按钮、第 2 屏的录音态：
 *  - [MicDock] 常驻首页，70dp 实心圆 + 98dp 柔光晕，点一下直接开录，不跳页；
 *  - [RecordingOverlay] 录音时才出现：波形 + 计时 + 实时字幕 + 「点击结束」。
 *
 * 上一版用户明确要求「录音按钮下面不读秒」，所以首页按钮周围只有一行路由提示，
 * 计时只出现在录音页的文字上方（设计稿也是这么放的）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MicDock(
    recording: Boolean,
    busy: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val extra = suijiExtra
    val core = 70.dp
    val halo = 98.dp
    val primary = MaterialTheme.colorScheme.primary
    val danger = extra.danger

    Box(modifier.size(halo), contentAlignment = Alignment.Center) {
        // 柔光晕：常驻一圈极淡的主色，录音时随音量呼吸
        Box(
            Modifier
                .size(halo)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            (if (recording) danger else primary).copy(alpha = if (recording) 0.16f else 0.10f),
                            (if (recording) danger else primary).copy(alpha = 0.02f)
                        )
                    ),
                    CircleShape
                )
        )
        if (recording) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension / 2f
                drawCircle(
                    color = danger.copy(alpha = 0.20f),
                    radius = base * (0.70f + 0.16f * level.coerceIn(0f, 1f))
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = if (recording) danger else primary,
            modifier = Modifier
                .size(core)
                .combinedClickable(
                    enabled = !busy,
                    onClick = onClick,
                    onLongClick = { onLongPress?.invoke() }
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(
                        Modifier.size(24.dp), strokeWidth = 2.2.dp, color = Color.White
                    )

                    recording -> StopIcon(Modifier.size(26.dp), Color.White)
                    else -> MicIcon(Modifier.size(30.dp), Color.White)
                }
            }
        }
    }
}

/** 全屏录音页（对应设计稿第 2 屏） */
@Composable
fun RecordingOverlay(
    state: VoiceState,
    level: Float,
    millis: Long,
    partial: String,
    err: AppError?,
    hint: String,
    onClose: () -> Unit,
    onUseKeyboard: () -> Unit
) {
    val extra = suijiExtra
    val transcribing = state == VoiceState.TRANSCRIBING || state == VoiceState.PREPARING
    val primary = MaterialTheme.colorScheme.primary

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Pressable(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    CloseIcon(Modifier.size(20.dp), extra.textSecondary)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (transcribing) {
                        CircularProgressIndicator(
                            Modifier.size(30.dp), strokeWidth = 2.4.dp, color = primary
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            if (state == VoiceState.PREPARING) "首次准备离线识别…" else "正在转成文字…",
                            fontSize = 15.sp,
                            color = extra.textSecondary
                        )
                    } else {
                        VoiceBars(level = level, active = state != VoiceState.IDLE)
                        Spacer(Modifier.height(22.dp))
                        Text(
                            formatMillis(millis),
                            fontSize = 15.sp,
                            letterSpacing = 0.4.sp,
                            color = extra.textSecondary
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = partial.ifBlank { hint },
                            fontSize = 17.sp,
                            lineHeight = 27.sp,
                            textAlign = TextAlign.Center,
                            color = if (partial.isBlank()) extra.textSecondary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 36.dp)
                        )
                    }

                    err?.let { e ->
                        Spacer(Modifier.height(18.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = extra.dangerSoft,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    e.userMsg,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = extra.danger
                                )
                                if (e.hint.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(e.hint, fontSize = 12.sp, color = extra.textSecondary)
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (transcribing) extra.softBg else primary,
                    modifier = Modifier
                        .size(72.dp)
                        .clickable(enabled = !transcribing) { onClose() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (transcribing) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp), strokeWidth = 2.2.dp, color = extra.textSecondary
                            )
                        } else {
                            StopIcon(Modifier.size(26.dp), Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (transcribing) "稍等一下" else "点击结束",
                    fontSize = 14.sp,
                    color = extra.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "改用文字输入",
                    fontSize = 13.sp,
                    color = extra.textSecondary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .clickable { onUseKeyboard() }
                        .padding(8.dp)
                )
            }
        }
    }
}

/**
 * 录音波形：设计稿是 9 根圆头竖条。
 * 高度由真实音量滚动驱动，静止时给一个缓慢起伏，避免"死"在屏幕上。
 */
@Composable
fun VoiceBars(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val history = remember { mutableStateListOf<Float>() }
    val idle = rememberInfiniteTransition(label = "idleBars")
    val breath by idle.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath"
    )

    androidx.compose.runtime.LaunchedEffect(level) {
        if (!active) return@LaunchedEffect
        history.add(level.coerceIn(0f, 1f))
        if (history.size > 24) history.removeAt(0)
    }
    androidx.compose.runtime.LaunchedEffect(active) {
        while (active) {
            delay(90)
            if (history.isEmpty()) history.add(level)
        }
    }

    val color = MaterialTheme.colorScheme.primary
    val n = 9
    Canvas(modifier.width(180.dp).height(76.dp)) {
        val gap = size.width / n
        val barW = gap * 0.30f
        val mid = size.height / 2f
        val snapshot = List(n) { i ->
            if (!active) breath * (0.6f + 0.4f * ((i % 3) / 2f))
            else {
                val idx = history.size - n + i
                if (idx < 0 || history.isEmpty()) 0.18f else history[idx].coerceAtLeast(0.12f)
            }
        }
        // 中间高两边低，像设计稿
        snapshot.forEachIndexed { i, raw ->
            val centerBias = 1f - (kotlin.math.abs(i - (n - 1) / 2f) / n) * 1.1f
            val v = (raw * (0.55f + 0.75f * centerBias)).coerceIn(0.08f, 1f)
            val h = size.height * v
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap + (gap - barW) / 2f, mid - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

/** 首页底部一行路由提示（麦克风下面那行小字） */
@Composable
fun RouteHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.5.sp,
        textAlign = TextAlign.Center,
        color = suijiExtra.textSecondary.copy(alpha = 0.9f),
        modifier = modifier
    )
}

@Composable
fun FadeInOut(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120))
    ) { content() }
}

private fun formatMillis(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
