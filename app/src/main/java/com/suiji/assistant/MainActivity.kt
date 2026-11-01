package com.suiji.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.suiji.assistant.core.CaptureSource
import com.suiji.assistant.ui.AppRoot
import com.suiji.assistant.ui.SuiJiTheme
import com.suiji.assistant.ui.hasNotificationPermission
import com.suiji.assistant.vm.AppViewModel

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var pendingCapture = intent?.getBooleanExtra("start_capture", false) == true
        var pendingMemoId = intent?.getStringExtra("open_memo_id")
        // 从别的应用「分享到随记」：直接把这段文字收下并分拣（CaptureSource.SHARE）
        var pendingShare = sharedTextOf(intent)

        setContent {
            SuiJiTheme {
                var hasMic by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMic = granted
                }

                val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { vm.markNotifAsked() }

                // 只在第一次启动时申请；之后没权限就靠设置页的红色提示引导，
                // 避免每次冷启动都弹一个系统对话框（那种体验更糟，用户只会一直点拒绝）。
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasNotificationPermission(this@MainActivity) &&
                        !vm.notifAskedBefore()
                    ) {
                        vm.markNotifAsked()
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(pendingCapture) {
                    if (pendingCapture) {
                        vm.openCapture()
                        pendingCapture = false
                    }
                }

                LaunchedEffect(pendingShare) {
                    val t = pendingShare
                    if (!t.isNullOrBlank()) {
                        pendingShare = null
                        vm.submit(t, CaptureSource.SHARE)
                    }
                }

                LaunchedEffect(pendingMemoId, vm.memos.size) {
                    val id = pendingMemoId
                    if (id != null && vm.memos.any { it.id == id }) {
                        vm.select(id)
                        pendingMemoId = null
                    }
                }

                AppRoot(
                    vm = vm,
                    hasMic = hasMic,
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("start_capture", false)) vm.openCapture()
        sharedTextOf(intent)?.let { vm.submit(it, CaptureSource.SHARE) }
        intent.getStringExtra("open_memo_id")?.let { id ->
            if (vm.memos.any { it.id == id }) vm.select(id)
        }
    }

    /** 分享进来的正文：优先 EXTRA_TEXT，其次 EXTRA_STREAM 只取文本型 */
    private fun sharedTextOf(intent: Intent?): String? {
        if (intent == null) return null
        val t = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return t?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
        vm.checkInlineReminder()
    }
}
