package com.suiji.assistant

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.suiji.assistant.core.Corrections
import com.suiji.assistant.core.Settings
import com.suiji.assistant.data.CalendarBridge
import com.suiji.assistant.data.MemoRepo
import com.suiji.assistant.net.Classifier
import com.suiji.assistant.net.LlmClient
import com.suiji.assistant.reminder.AlarmScheduler
import com.suiji.assistant.reminder.Notifier
import com.suiji.assistant.reminder.ScreenStateReceiver

class SuiJiApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var repo: MemoRepo
        private set
    lateinit var llm: LlmClient
        private set
    lateinit var corrections: Corrections
        private set
    lateinit var classifier: Classifier
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var calendar: CalendarBridge
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repo = MemoRepo(this)
        llm = LlmClient()
        corrections = Corrections(this)
        classifier = Classifier(llm, settings, corrections)
        notifier = Notifier(this)
        notifier.ensureChannels()
        calendar = CalendarBridge(this, settings, repo)

        // 屏幕状态只能动态注册，用于计算"亮屏多久 / 多久没碰手机"
        runCatching {
            registerReceiver(
                ScreenStateReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            )
        }

        // 启动即对账：重建每条待办的精确闹钟 + 兜底巡检。
        // 旧实现这里调的是 setInexactRepeating，每次冷启动都会把周期闹钟重新计时，
        // 频繁打开 App 就等于永远不触发。放后台线程，别拖慢冷启动。
        kotlin.concurrent.thread(name = "suiji-alarm") {
            runCatching { AlarmScheduler.sync(this) }
        }

        // 顺带清理 30 天前的回收站（放后台线程，避免拖慢冷启动）
        kotlin.concurrent.thread(name = "suiji-purge") {
            runCatching {
                repo.purge(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            }
        }
    }

    companion object {
        @Volatile
        lateinit var instance: SuiJiApp
            private set
    }
}
