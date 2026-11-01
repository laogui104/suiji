package com.suiji.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.BuildConfig
import com.suiji.assistant.core.Settings
import com.suiji.assistant.vm.AppViewModel
import com.suiji.assistant.vm.StepPhase

/**
 * 设置 / 我的（对应设计稿第 6 屏）。
 * 分组列表 + 右侧值 + 箭头，靠分组标题与 1px 分隔线组织，不套卡片。
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    showBack: Boolean = true
) {
    val extra = suijiExtra
    val ctx = LocalContext.current
    var sheet by remember { mutableStateOf<SettingSheet?>(null) }
    var notifTick by remember { mutableStateOf(0) }

    // 通知权限：拒绝过就引导去系统设置，不再死循环弹窗
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifTick++ }

    // 日历权限是成对的，一起要
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        notifTick++
        if (result.values.all { it }) vm.enableCalendarSync() else vm.disableCalendarSync()
    }

    // notifTick 只用来触发重组，实际状态每次都从系统读
    @Suppress("UNUSED_EXPRESSION")
    notifTick

    Column(modifier.fillMaxSize()) {
        if (showBack) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pressable(onClick = { vm.closeSettings() }, modifier = Modifier.size(40.dp)) {
                    BackIcon(Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = PagePad, end = PagePad, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${vm.todoCount} 待办 · ${vm.ideaCount} 灵感",
                    fontSize = 12.5.sp,
                    color = extra.textSecondary
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("AI 模型") {
                SettingRow("服务商", vm.providerName()) { sheet = SettingSheet.Provider }
                HairLine()
                SettingRow("模型", vm.model().ifBlank { "未设置" }) { sheet = SettingSheet.Model }
                HairLine()
                SettingRow(
                    "API Key",
                    value = vm.apiKeyMasked() ?: "未填写",
                    valueColor = if (vm.hasApiKey()) extra.textSecondary else extra.danger
                ) { sheet = SettingSheet.Key }
                HairLine()
                SettingRow(
                    "连通测试",
                    value = vm.connSummary,
                    showChevron = false,
                    trailing = {
                        if (vm.connRunning) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "开始测试",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) { if (!vm.connRunning) vm.runConnectionTest() }

                if (vm.connSteps.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = PagePad, vertical = 6.dp)) {
                        vm.connSteps.forEach { s ->
                            Row(
                                Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(stepColor(s.phase), CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    s.name,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (s.detail.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        s.detail,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = extra.textSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingGroup("语音识别") {
                SettingRow(
                    "识别方式",
                    value = when (vm.asrMode()) {
                        Settings.ASR_SYSTEM -> "系统识别"
                        Settings.ASR_CLOUD -> "云端转写"
                        Settings.ASR_OFFLINE -> "本机离线"
                        else -> "自动（优先云端）"
                    }
                ) { sheet = SettingSheet.AsrMode }
                HairLine()
                SettingRow(
                    "离线模型",
                    value = if (vm.offlineAsrReady()) "已就绪" else "首次使用时准备",
                    showChevron = false
                )
            }

            SettingGroup("提醒与通知") {
                val allowed = vm.notificationsAllowed()
                SettingRow(
                    "通知权限",
                    value = if (allowed) "已授权" else "未授权 · 点这里开启",
                    valueColor = if (allowed) null else Color(0xFFE5484D),
                    showChevron = !allowed
                ) {
                    if (allowed) {
                        openNotificationSettings(ctx)
                    } else {
                        vm.markNotifAsked()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(ctx)
                        }
                    }
                }
                HairLine()
                SettingRow("到点提醒", value = "试一下") {
                    if (!vm.testNotification()) openNotificationSettings(ctx)
                }
                HairLine()
                SettingRow(
                    "提醒开关",
                    showChevron = false,
                    trailing = { SuiSwitch(vm.reminderEnabled) { vm.updateReminderEnabled(it) } }
                )
                HairLine()
                SettingRow("静默时段", vm.quietRangeLabel(), showChevron = false)
                HairLine()
                SettingRow("检测灵敏度", vm.sensitivityLabel()) { vm.cycleSensitivity() }
            }

            SettingGroup("系统日历") {
                SettingRow(
                    "自动写入日历",
                    showChevron = false,
                    trailing = {
                        SuiSwitch(vm.calendarEnabled) { want ->
                            if (!want) {
                                vm.disableCalendarSync()
                            } else if (vm.calendarPermissionOk()) {
                                vm.enableCalendarSync()
                            } else {
                                calendarLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR
                                    )
                                )
                            }
                        }
                    }
                )
                HairLine()
                SettingRow("同步状态", vm.calendarSummary(), showChevron = false)
                HairLine()
                SettingRow(
                    "提前提醒",
                    value = "${vm.calendarLeadMinutes()} 分钟",
                    showChevron = false,
                    trailing = {
                        Text(
                            "调整",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { vm.cycleCalendarLead() }
                                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                )
                HairLine()
                SettingRow(
                    "选择日历",
                    value = vm.calendarName(),
                    showChevron = false
                )
            }

            SettingGroup("分拣与深化") {
                SettingRow(
                    "纠错记忆",
                    value = "${vm.correctionCount()} 条",
                    showChevron = false,
                    trailing = {
                        if (vm.correctionCount() > 0) {
                            Text(
                                "清空",
                                fontSize = 14.sp,
                                color = extra.textSecondary,
                                modifier = Modifier
                                    .clickable { vm.clearCorrections() }
                                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                )
                HairLine()
                SettingRow("深化次数", "${vm.insightTotal()} 次", showChevron = false)
            }

            SettingGroup("数据") {
                SettingRow("待办 / 灵感", "${vm.todoCount} / ${vm.ideaCount}", showChevron = false)
                HairLine()
                SettingRow("本地存储", "SQLite · 仅本机", showChevron = false)
            }

            SettingGroup("关于") {
                SettingRow("版本号", BuildConfig.VERSION_NAME, showChevron = false)
                HairLine()
                SettingRow("设计语言", "商务简约 · 单一主色") { }
                HairLine()
                SettingRow("开源协议", "见 docs 目录") { }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "随记 · 轻量 AI 私人记忆助理",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = extra.textSecondary.copy(alpha = 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }

    when (sheet) {
        SettingSheet.Provider -> PickerSheet(
            title = "选择服务商",
            options = vm.providers.map {
                it.id to (it.name + if (it.note.isNotBlank()) "  ·  ${it.note}" else "")
            },
            selectedKey = vm.currentProviderId(),
            onPick = { vm.selectProvider(it); sheet = null },
            onDismiss = { sheet = null }
        )

        SettingSheet.Model -> PickerSheet(
            title = "选择模型",
            options = vm.modelOptions().map { it to it },
            selectedKey = vm.model(),
            onPick = { vm.setModel(it); sheet = null },
            onDismiss = { sheet = null },
            allowCustom = true
        )

        SettingSheet.Key -> KeySheet(
            current = vm.apiKeyMasked(),
            onSave = { vm.setApiKey(it); sheet = null },
            onClear = { vm.clearApiKey(); sheet = null },
            onDismiss = { sheet = null }
        )

        SettingSheet.AsrMode -> PickerSheet(
            title = "语音识别方式",
            options = listOf(
                Settings.ASR_AUTO to "自动 · 有 Key 走云端，否则本机离线",
                Settings.ASR_OFFLINE to "本机离线 · 不联网，不要 Key",
                Settings.ASR_CLOUD to "云端转写 · 需支持 audio/transcriptions",
                Settings.ASR_SYSTEM to "系统识别 · 视机型而定，多数国产 ROM 不可用"
            ),
            selectedKey = vm.asrMode(),
            onPick = { vm.setAsrMode(it); sheet = null },
            onDismiss = { sheet = null }
        )

        null -> Unit
    }
}

private fun stepColor(phase: StepPhase): Color = when (phase) {
    StepPhase.PASS -> Color(0xFF12A150)
    StepPhase.FAIL -> Color(0xFFE5484D)
    StepPhase.RUNNING -> Color(0xFF2562EB)
    StepPhase.PENDING -> Color(0xFFD8DCE2)
}

private enum class SettingSheet { Provider, Model, Key, AsrMode }

@Composable
private fun SuiSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val extra = suijiExtra
    val track = if (checked) MaterialTheme.colorScheme.primary else extra.line
    // 点击区放大到 56x40dp：原来只有 48x28dp，实测很难点中，
    // 用户会以为是「开关失效」而不是「没点着」。
    Box(
        Modifier
            .size(width = 56.dp, height = 40.dp)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 28.dp)
                .background(track, CircleShape)
        ) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(start = if (checked) 0.dp else 2.dp, end = if (checked) 2.dp else 0.dp)
                    .size(24.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun PickerSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    allowCustom: Boolean = false
) {
    val extra = suijiExtra
    var custom by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                .clickable { onDismiss() }
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SheetSurface {
                Column(Modifier.navigationBarsPadding()) {
                    Text(
                        title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = PagePad, top = 8.dp, bottom = 10.dp)
                    )
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        options.forEach { (key, label) ->
                            val selected = key == selectedKey
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(key) }
                                    .padding(horizontal = PagePad, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    CheckIcon(
                                        Modifier.size(16.dp),
                                        MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            HairLine()
                        }
                    }

                    if (allowCustom) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PagePad, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = extra.softBg,
                                modifier = Modifier.weight(1f)
                            ) {
                                BasicTextField(
                                    value = custom,
                                    onValueChange = { custom = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                    decorationBox = { inner ->
                                        if (custom.isEmpty()) {
                                            Text(
                                                "或手填模型名",
                                                fontSize = 14.sp,
                                                color = extra.textSecondary
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            PrimaryButton(
                                text = "确定",
                                modifier = Modifier.width(76.dp),
                                height = 42.dp,
                                enabled = custom.isNotBlank(),
                                onClick = { onPick(custom.trim()) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun KeySheet(
    current: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val extra = suijiExtra
    var text by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                .clickable { onDismiss() }
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SheetSurface {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = PagePad)
                ) {
                    Text(
                        "API Key",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    Text(
                        current?.let { "当前：$it" } ?: "还没有填写，AI 分拣会用本地规则",
                        fontSize = 12.5.sp,
                        color = extra.textSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = extra.softBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            decorationBox = { inner ->
                                if (text.isEmpty()) {
                                    Text(
                                        "粘贴 sk-...",
                                        fontSize = 14.5.sp,
                                        color = extra.textSecondary
                                    )
                                }
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current != null) {
                            Text(
                                "清除",
                                fontSize = 14.sp,
                                color = extra.danger,
                                modifier = Modifier
                                    .clickable { onClear() }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        PrimaryButton(
                            text = "保存",
                            modifier = Modifier.width(120.dp),
                            height = 46.dp,
                            enabled = text.trim().length >= 8,
                            onClick = { onSave(text.trim()) }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** 跳到本应用的通知设置页：用户手动关掉通知时，只能在这里改回来 */
internal fun openNotificationSettings(ctx: android.content.Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            }
        } else {
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
