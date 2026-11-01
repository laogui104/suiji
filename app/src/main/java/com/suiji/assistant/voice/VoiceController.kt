package com.suiji.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.Settings
import com.suiji.assistant.core.SuiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceState { IDLE, PREPARING, LISTENING, RECORDING, TRANSCRIBING }

enum class VoicePath { SYSTEM, LOCAL }

/** 本地录音之后用哪条通道转文字 */
enum class AsrRoute { CLOUD, OFFLINE }

/**
 * 语音采集与转写的编排器。两条通道：
 *
 *  A. SYSTEM —— 系统 SpeechRecognizer，边说边出字，零配置；但国产 ROM 常常没有该服务。
 *  B. LOCAL  —— AudioRecord 本地录音，停下后调 OpenAI 兼容 /audio/transcriptions 转写。
 *
 * 自动模式下优先 A、不可用则落 B，B 也不可用时仍然把音录下来并保留文件，
 * 让用户配好 ASR 后一键重试 —— 绝不出现「按了按钮什么都没发生」。
 */
class VoiceController(
    private val ctx: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val onState: (VoiceState) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onMillis: (Long) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (AppError) -> Unit
) {

    private val recorder = VoiceRecorder(ctx)
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var path: VoicePath = VoicePath.LOCAL
    private var lastPartial = ""
    private var stopTimeout: Job? = null

    /** 已录到但还没转写成功的声音，供「重试转写」使用 */
    var pendingAudio: File? = null
        private set

    init {
        recorder.onAmplitude = { onLevel(it) }
        recorder.onTick = { onMillis(it) }
        recorder.onAutoStop = { finishLocal() }
        recorder.onError = { e ->
            // 录音器报的错都要让用户看见，不能因为 active 已经置 false 就吞掉
            reset()
            onError(e)
        }
    }

    fun hasMicPermission(): Boolean = recorder.hasPermission()

    fun systemAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    fun resolvedPath(): VoicePath = when (settings.asrMode) {
        Settings.ASR_SYSTEM -> if (systemAvailable()) VoicePath.SYSTEM else VoicePath.LOCAL
        // auto / cloud / offline 一律先本地录音：
        // 系统识别在国产 ROM 上时有时无，一旦它「能创建但不出字」，用户就彻底没结果了。
        // 本地录音是唯一 100% 可控的一步，先保证「一定录到了」，再谈怎么转。
        else -> VoicePath.LOCAL
    }

    /** 转写将走哪条通道，供界面如实显示 */
    fun asrRoute(): AsrRoute =
        if (settings.cloudAsrUsable()) AsrRoute.CLOUD else AsrRoute.OFFLINE

    /** 离线模型是否已就绪（首次需要解压一次） */
    fun offlineReady(): Boolean = OfflineAsr.isReady(ctx)

    // ================= 开始 =================

    fun start() {
        if (active) return
        if (!recorder.hasPermission()) {
            onError(AppError.MicPermissionDenied)
            return
        }
        active = true
        lastPartial = ""
        when (resolvedPath()) {
            VoicePath.SYSTEM -> if (!startSystem()) startLocal()
            VoicePath.LOCAL -> startLocal()
        }
    }

    private fun startSystem(): Boolean {
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (t: Throwable) {
            return false
        }
        recognizer = r
        r.setRecognitionListener(listener)
        path = VoicePath.SYSTEM
        onState(VoiceState.LISTENING)
        return try {
            r.startListening(buildIntent())
            true
        } catch (t: Throwable) {
            destroyRecognizer()
            false
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // 让停顿更早结束，避免用户说完还在傻等
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }

    private fun startLocal() {
        path = VoicePath.LOCAL
        onMillis(0L)
        onLevel(0f)
        if (recorder.start()) {
            onState(VoiceState.RECORDING)
        } else {
            reset()
            onError(AppError.RecordFailed)
        }
    }

    // ================= 停止 =================

    fun stop() {
        if (!active) return
        when (path) {
            VoicePath.SYSTEM -> {
                active = false
                onState(VoiceState.TRANSCRIBING)
                runCatching { recognizer?.stopListening() }
                // 部分 ROM 停不下来，兜底 4 秒收尾
                stopTimeout?.cancel()
                stopTimeout = scope.launch {
                    delay(4000)
                    if (recognizer != null) finishWithPartial()
                }
            }

            VoicePath.LOCAL -> finishLocal()
        }
    }

    private fun finishLocal() {
        if (!active) return
        active = false
        val wav = recorder.stop()
        if (wav == null) {
            reset()
            return
        }
        onState(VoiceState.TRANSCRIBING)
        transcribe(wav)
    }

    /**
     * 转写路由：
     *  1. 云端 —— 仅当服务商真的提供 OpenAI 兼容 /audio/transcriptions（否则必然 404）
     *  2. 内置离线 —— 永远可用，不联网、不要 Key
     *  3. 云端失败时自动掉到离线，用户至多拿到「离线识别结果」，不会空手而归
     */
    fun transcribe(wav: File) {
        if (settings.cloudAsrUsable()) {
            val cfg = settings.asrConfig()
            onState(VoiceState.TRANSCRIBING)
            scope.launch {
                when (val r = AsrClient.transcribe(cfg, wav)) {
                    is SuiResult.Ok -> deliver(wav, r.data)
                    is SuiResult.Err -> runOffline(wav)
                }
            }
            return
        }
        runOffline(wav)
    }

    private fun runOffline(wav: File) {
        scope.launch {
            if (!OfflineAsr.isReady(ctx)) onState(VoiceState.PREPARING)
            when (val r = OfflineAsr.transcribe(ctx, wav)) {
                is SuiResult.Ok -> deliver(wav, r.data)
                is SuiResult.Err -> {
                    pendingAudio = wav
                    onState(VoiceState.IDLE)
                    onError(r.error)
                }
            }
        }
    }

    private fun deliver(wav: File, text: String) {
        runCatching { wav.delete() }
        pendingAudio = null
        onState(VoiceState.IDLE)
        onText(text)
    }

    /** 用户配好 ASR 后，重试上一段录音 */
    fun retryPending() {
        val f = pendingAudio ?: return
        if (!f.exists()) {
            pendingAudio = null
            onError(AppError.EmptySpeech)
            return
        }
        onState(VoiceState.TRANSCRIBING)
        transcribe(f)
    }

    // ================= 取消 / 清理 =================

    fun cancel() {
        active = false
        stopTimeout?.cancel()
        destroyRecognizer()
        recorder.cancel()
        onState(VoiceState.IDLE)
    }

    private fun reset() {
        active = false
        stopTimeout?.cancel()
        destroyRecognizer()
        onState(VoiceState.IDLE)
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.setRecognitionListener(null) }
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun finishWithPartial() {
        val text = lastPartial
        reset()
        if (text.isNotBlank()) onText(text) else onError(AppError.EmptySpeech)
    }

    // ================= 系统识别回调 =================

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB 通常落在 -2..10，映射成 0..1
            val l = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            onLevel(l)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (active) onState(VoiceState.TRANSCRIBING)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // 还在听就继续听，别因为用户停顿一下就把会话掐了
                    if (active) {
                        restartSystem()
                    } else {
                        finishWithPartial()
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    reset()
                    onError(AppError.MicPermissionDenied)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    reset()
                    onError(AppError.MicBusy)
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    reset()
                    onError(AppError.NoNetwork)
                }

                SpeechRecognizer.ERROR_SERVER -> {
                    reset()
                    onError(AppError.ServerError)
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    // 常见于连续 restart 过快；不再重启，直接按「本机识别不可用」处理并建议走云端
                    if (active) {
                        restartSystem()
                    } else {
                        finishWithPartial()
                    }
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    reset()
                    onError(AppError.SttUnavailable)
                }

                else -> {
                    reset()
                    onError(AppError.EmptySpeech)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (t.isNotBlank()) {
                lastPartial = t
                onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            val finalText = if (text.isNotBlank()) text else lastPartial
            reset()
            if (finalText.isNotBlank()) onText(finalText) else onError(AppError.EmptySpeech)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restartSystem() {
        destroyRecognizer()
        if (!active) return
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (t: Throwable) {
            reset()
            onError(AppError.SttUnavailable)
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        try {
            r.startListening(buildIntent())
        } catch (t: Throwable) {
            reset()
            onError(AppError.SttUnavailable)
        }
    }
}
