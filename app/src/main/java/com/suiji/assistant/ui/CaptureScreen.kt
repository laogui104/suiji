package com.suiji.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suiji.assistant.core.CaptureSource
import com.suiji.assistant.vm.AppViewModel

/**
 * 新建记录（对应设计稿第 3 屏）。
 *
 * 语音转写完成后也会落到这里：先让用户看见文字、能改错字，再交给 AI 分拣。
 * 这一屏同时也是「分拣前」的最后一道人工闸门，比"语音一停就直接入库"稳。
 */
@Composable
fun CaptureScreen(vm: AppViewModel) {
    val extra = suijiExtra
    var text by remember(vm.captureSeed) { mutableStateOf(vm.captureSeed) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 带语音文本进来时不弹键盘：用户先看，想改再点
    LaunchedEffect(Unit) {
        if (vm.captureSeed.isBlank()) {
            runCatching { focus.requestFocus() }
        }
    }

    fun submit() {
        val t = text.trim()
        if (t.isBlank() || vm.busy) return
        keyboard?.hide()
        vm.submit(t, if (vm.captureSeed.isNotBlank()) CaptureSource.VOICE else CaptureSource.TEXT)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // 顶栏：返回 · 新建记录 · 完成
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pressable(onClick = { vm.closeCapture() }, modifier = Modifier.size(40.dp)) {
                    BackIcon(Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "新建记录",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "完成",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (text.isBlank() || vm.busy) extra.textSecondary
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = text.isNotBlank() && !vm.busy) { submit() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // 输入块：浅灰底、圆角、文字与提示同在一块里
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = extra.softBg.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePad)
            ) {
                Column(Modifier.padding(16.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 27.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .focusRequester(focus),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    "提醒我明天下午三点跟设计团队对一下首页改版方案",
                                    fontSize = 16.sp,
                                    lineHeight = 27.sp,
                                    color = extra.textSecondary
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "AI 将自动分拣为待办或灵感",
                        fontSize = 12.5.sp,
                        color = extra.textSecondary
                    )
                }
            }

            if (vm.busy) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(vm.busyLabel, fontSize = 13.sp, color = extra.textSecondary)
                }
            }

            Spacer(Modifier.weight(1f))

            // 底部：再次口述 + 完成
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePad, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = extra.softBg,
                    modifier = Modifier
                        .size(46.dp)
                        .clickable { vm.closeCapture() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MicIcon(Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (text.isBlank()) "想说就用下面的大按钮" else "${text.length} 字",
                    fontSize = 13.sp,
                    color = extra.textSecondary
                )
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    text = if (vm.busy) "正在分拣…" else "完成",
                    modifier = Modifier.widthIn(min = 108.dp),
                    enabled = text.isNotBlank() && !vm.busy,
                    onClick = { submit() }
                )
            }
        }
    }
}
