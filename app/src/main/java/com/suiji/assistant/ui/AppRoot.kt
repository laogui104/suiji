package com.suiji.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.Settings
import com.suiji.assistant.vm.AppViewModel
import com.suiji.assistant.voice.VoiceController
import com.suiji.assistant.voice.VoicePath
import com.suiji.assistant.voice.VoiceState
import com.suiji.assistant.vm.HomeTab
import kotlinx.coroutines.delay

/**
 * 应用外壳：三套布局（单栏 / 双栏 / 悬停）+ 所有覆盖层。
 *
 * 页面与设计稿的对应关系：
 *   首页/灵感/我 → 底部三 Tab，录音大按钮常驻首页底部
 *   全屏录音     → RecordingOverlay（第 2 屏）
 *   新建记录     → CaptureScreen（第 3 屏）
 *   详情         → DetailPane（第 4 屏）
 *   深化灵感     → InsightSheet（第 5 屏）
 *   分拣回执     → ReviewSheet（本次新增，对应 3.2 强化）
 */
@Composable
fun AppRoot(vm: AppViewModel, hasMic: Boolean, onRequestMic: () -> Unit) {
    val spec = currentLayoutSpec()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var level by remember { mutableStateOf(0f) }
    var millis by remember { mutableLongStateOf(0L) }
    var partial by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<AppError?>(null) }
    var overlayOpen by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(false) }

    val controller = remember {
        VoiceController(
            ctx = ctx,
            settings = Settings(ctx),
            scope = scope,
            onState = { state = it },
            onLevel = { l -> level = level * 0.5f + l * 0.5f },
            onMillis = { millis = it },
            onPartial = { partial = it },
            onText = { t ->
                state = VoiceState.IDLE
                overlayOpen = false
                partial = ""
                if (t.isNotBlank()) vm.openCapture(t)
            },
            onError = { e ->
                err = e
                state = VoiceState.IDLE
            }
        )
    }

    // 授权回调后自动开录，省掉「再点一次」
    LaunchedEffect(hasMic) {
        if (hasMic && autoStart) {
            autoStart = false
            err = null
            overlayOpen = true
            controller.start()
        }
    }

    DisposableEffect(Unit) { onDispose { controller.cancel() } }

    val recording = state == VoiceState.RECORDING || state == VoiceState.LISTENING
    val transcribing = state == VoiceState.TRANSCRIBING || state == VoiceState.PREPARING
    val busyVoice = recording || transcribing

    fun startVoice() {
        err = null
        level = 0f
        millis = 0L
        partial = ""
        overlayOpen = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.start()
    }

    fun onMicTap() {
        when {
            transcribing -> Unit
            recording -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                controller.stop()
            }

            !hasMic -> {
                autoStart = true
                onRequestMic()
            }

            else -> startVoice()
        }
    }

    fun closeOverlay() {
        controller.cancel()
        overlayOpen = false
        err = null
        partial = ""
    }

    BackHandler(
        enabled = overlayOpen || vm.review != null || vm.insightOpen ||
                vm.captureOpen || (vm.settingsOpen && vm.tab == HomeTab.ME) || vm.selectedId != null
    ) {
        when {
            overlayOpen -> closeOverlay()
            vm.review != null -> vm.closeReview()
            vm.insightOpen -> vm.closeInsight()
            vm.captureOpen -> vm.closeCapture()
            vm.settingsOpen -> vm.closeSettings()
            else -> vm.select(null)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        when (spec.mode) {
            LayoutMode.SINGLE_PANE -> SinglePane(
                vm = vm,
                recording = recording,
                busy = transcribing,
                level = level,
                hint = routeHint(controller),
                onMicTap = { onMicTap() },
                onMicLongPress = { vm.openCapture() }
            )

            LayoutMode.TWO_PANE -> TwoPane(
                vm = vm,
                spec = spec,
                recording = recording,
                busy = transcribing,
                level = level,
                hint = routeHint(controller),
                onMicTap = { onMicTap() },
                onMicLongPress = { vm.openCapture() }
            )

            LayoutMode.TABLETOP -> Tabletop(
                vm = vm,
                recording = recording,
                busy = transcribing,
                level = level,
                hint = routeHint(controller),
                onMicTap = { onMicTap() },
                onMicLongPress = { vm.openCapture() }
            )
        }

        // 轻提示
        vm.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(2400)
                vm.message = null
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
                ) {
                    Text(
                        msg,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // 新建记录
        AnimatedVisibility(
            visible = vm.captureOpen,
            enter = slideInVertically(tween(240)) { it / 8 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(200)) { it / 8 } + fadeOut(tween(150))
        ) {
            CaptureScreen(vm)
        }

        // 独立设置页（从首页齿轮进入）
        AnimatedVisibility(
            visible = vm.settingsOpen && vm.tab != HomeTab.ME,
            enter = slideInVertically(tween(240)) { it / 10 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(200)) { it / 10 } + fadeOut(tween(150))
        ) {
            SettingsScreen(vm)
        }

        if (vm.insightOpen) InsightSheet(vm)
        if (vm.review != null) ReviewSheet(vm)

        // 全屏录音页
        AnimatedVisibility(
            visible = overlayOpen,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140))
        ) {
            RecordingOverlay(
                state = state,
                level = level,
                millis = millis,
                partial = partial,
                err = err,
                // 有实时字幕时字幕顶替提示位（设计稿第 2 屏就是这层结构：
                // 波形 → 计时 → 这句话 → 按钮），没有字幕才退回提示文案
                hint = if (state == VoiceState.TRANSCRIBING) "正在转成文字…"
                else "正在听你说话",
                onClose = {
                    if (recording) controller.stop() else closeOverlay()
                },
                onUseKeyboard = {
                    controller.cancel()
                    overlayOpen = false
                    vm.openCapture()
                }
            )
        }
    }
}

// ============================================================
// 单栏
// ============================================================

@Composable
private fun SinglePane(
    vm: AppViewModel,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    hint: String,
    onMicTap: () -> Unit,
    onMicLongPress: () -> Unit = {}
) {
    val sel = vm.selected
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when {
                sel != null -> DetailPane(vm, sel, showBack = true)
                vm.tab == HomeTab.ME -> SettingsScreen(vm, showBack = false)
                else -> ListPane(vm)
            }
        }
        if (sel == null) {
            if (vm.tab != HomeTab.ME) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MicDock(
                        recording = recording,
                        busy = busy,
                        level = level,
                        onLongPress = onMicLongPress,
                        onClick = onMicTap
                    )
                    RouteHint(hint)
                    Spacer(Modifier.height(6.dp))
                }
            }
            BottomNav(current = vm.tab, onSelect = { vm.switchTab(it) })
        }
    }
}

// ============================================================
// 双栏（折叠屏展开 / 大屏）
// ============================================================

@Composable
private fun TwoPane(
    vm: AppViewModel,
    spec: LayoutSpec,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    hint: String,
    onMicTap: () -> Unit,
    onMicLongPress: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Column(Modifier.width(spec.listWidth).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (vm.tab == HomeTab.ME) SettingsScreen(vm, showBack = false)
                else ListPane(vm)
            }
            if (vm.tab != HomeTab.ME) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MicDock(recording, busy, level, onLongPress = onMicLongPress, onClick = onMicTap)
                    RouteHint(hint)
                    Spacer(Modifier.height(6.dp))
                }
            }
            BottomNav(current = vm.tab, onSelect = { vm.switchTab(it) })
        }

        Box(
            Modifier
                .width(1.dp)
                .fillMaxSize()
                .background(suijiExtra.line)
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(start = 8.dp)
        ) {
            val sel = vm.selected
            if (sel != null) {
                DetailPane(vm, sel, showBack = false)
            } else {
                EmptyState(
                    title = "选一条看看",
                    subtitle = "左边点一条，这里显示正文与 AI 判定",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 悬停态：上半屏看内容，下半屏操作 */
@Composable
private fun Tabletop(
    vm: AppViewModel,
    recording: Boolean,
    busy: Boolean,
    level: Float,
    hint: String,
    onMicTap: () -> Unit,
    onMicLongPress: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val sel = vm.selected
            if (sel != null) {
                DetailPane(vm, sel, showBack = false)
            } else {
                EmptyState(
                    title = "把手机折成 L 形",
                    subtitle = "上半屏看内容，下半屏说一句",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(suijiExtra.line)
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ListPane(vm, showTitleRow = false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    MicDock(recording, busy, level, onLongPress = onMicLongPress, onClick = onMicTap)
                    Spacer(Modifier.width(18.dp))
                    RouteHint(hint)
                }
            }
        }
    }
}

private fun routeHint(controller: VoiceController): String {
    if (!controller.hasMicPermission()) return "先允许麦克风权限，才能听你说话"
    return when {
        controller.resolvedPath() == VoicePath.SYSTEM -> "系统识别 · 边说边出字 · 长按可打字"
        controller.asrRoute() == com.suiji.assistant.voice.AsrRoute.CLOUD -> "云端转写 · 长按可打字"
        else -> "本机离线识别 · 不联网 · 长按可打字"
    }
}
