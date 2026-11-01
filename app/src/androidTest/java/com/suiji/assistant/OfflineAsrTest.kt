package com.suiji.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suiji.assistant.core.SuiResult
import com.suiji.assistant.voice.OfflineAsr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 离线识别的真机/模拟器级验证。
 *
 * assets/sample.wav 是用 zh-CN 语音合成的「明天下午三点和张总过报价单」，
 * 16kHz 单声道 16bit —— 和 VoiceRecorder 产出的格式完全一致。
 * 这个测试证明：不配任何 Key、不联网，也能把中文语音转成文字。
 */
@RunWith(AndroidJUnit4::class)
class OfflineAsrTest {

    @Test
    fun transcribesChineseSpeechOffline() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        val wav = File(ctx.cacheDir, "sample.wav")
        inst.context.assets.open("sample.wav").use { input ->
            wav.outputStream().use { out -> input.copyTo(out) }
        }
        assertTrue("样本音频缺失", wav.length() > 44)

        val result = OfflineAsr.transcribe(ctx, wav)
        println("ASR_RESULT = $result")

        assertTrue("离线识别应当返回文本，实际：$result", result is SuiResult.Ok)
        val text = (result as SuiResult.Ok).data
        assertFalse("识别结果为空", text.isBlank())
        println("ASR_TEXT = $text")

        // 允许识别有偏差，但关键要素必须命中
        assertTrue("没认出时间：$text", text.contains("三点") || text.contains("3点"))
        assertTrue("没认出人物：$text", text.contains("张总"))
    }
}
