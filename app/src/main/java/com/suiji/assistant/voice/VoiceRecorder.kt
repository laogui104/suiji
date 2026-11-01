package com.suiji.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.suiji.assistant.core.AppError
import java.io.File
import java.io.FileOutputStream

/**
 * 本地录音器：AudioRecord 采集 16kHz / 单声道 / 16bit PCM，落盘为 WAV。
 *
 * 为什么自研而不是直接用 SpeechRecognizer：
 * 国产 ROM 上 `android.speech.RecognitionService` 经常不存在或未暴露，
 * 一旦依赖它，「能不能录音」这件事就变成了厂商行为，用户按下按钮毫无反应。
 * AudioRecord 只依赖 RECORD_AUDIO 权限，是唯一 100% 可控的采集通道。
 */
class VoiceRecorder(private val ctx: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 单条录音上限，防止缓存目录被撑爆 */
        const val MAX_MS = 120_000L
        /** 低于这个时长的录音视为误触 */
        private const val MIN_MS = 600L
        private const val READ_SHORTS = 1600 // 100ms
    }

    var onAmplitude: ((Float) -> Unit)? = null
    var onTick: ((Long) -> Unit)? = null
    var onError: ((AppError) -> Unit)? = null
    var onAutoStop: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var pcmFile: File? = null
    /** 本段录音出现过的最大音量，用来区分「没说话」和「说了但没识别出来」 */
    @Volatile private var peak = 0f

    val isRecording: Boolean get() = running

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            onError?.invoke(AppError.MicPermissionDenied)
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError?.invoke(AppError.RecordFailed)
            return false
        }
        val bufBytes = maxOf(minBuf * 2, READ_SHORTS * 2 * 4)

        // VOICE_RECOGNITION 源不做 AGC/降噪，更适合喂给 ASR；失败再退回 MIC
        val r = newRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufBytes)
            ?: newRecord(MediaRecorder.AudioSource.MIC, bufBytes)
        if (r == null) {
            onError?.invoke(AppError.RecordFailed)
            return false
        }

        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        rec = r
        pcmFile = f
        peak = 0f
        running = true
        try {
            r.startRecording()
        } catch (t: Throwable) {
            running = false
            runCatching { r.release() }
            rec = null
            onError?.invoke(AppError.RecordFailed)
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            runCatching { r.release() }
            rec = null
            onError?.invoke(AppError.RecordFailed)
            return false
        }

        worker = Thread({ loop(r, f) }, "suiji-voice").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    private fun newRecord(source: Int, bufBytes: Int): AudioRecord? {
        val r = try {
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
        } catch (t: Throwable) {
            null
        }
        return if (r != null && r.state == AudioRecord.STATE_INITIALIZED) r
        else {
            r?.release()
            null
        }
    }

    private fun loop(r: AudioRecord, f: File) {
        val buf = ShortArray(READ_SHORTS)
        val bytes = ByteArray(READ_SHORTS * 2)
        var total = 0L
        var out: FileOutputStream? = null
        try {
            out = FileOutputStream(f)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) break else continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sum += s.toDouble() * s
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                out.write(bytes, 0, n * 2)
                total += n

                // 转成 0..1 的相对音量，供 UI 画波形
                val rms = Math.sqrt(sum / n) / 32768.0
                val db = 20.0 * Math.log10(if (rms < 1e-7) 1e-7 else rms)
                val level = ((db + 55.0) / 55.0).coerceIn(0.0, 1.0).toFloat()
                if (level > peak) peak = level
                val ms = total * 1000L / SAMPLE_RATE
                main.post {
                    onAmplitude?.invoke(level)
                    onTick?.invoke(ms)
                }
                if (ms >= MAX_MS) {
                    main.post { onAutoStop?.invoke() }
                    break
                }
            }
        } catch (t: Throwable) {
            main.post { onError?.invoke(AppError.RecordFailed) }
        } finally {
            runCatching { out?.flush() }
            runCatching { out?.close() }
        }
    }

    /** 停止并返回 WAV；时长过短或未启动时返回 null */
    fun stop(): File? {
        if (!running) return null
        running = false
        runCatching { rec?.stop() }
        worker?.let { w -> runCatching { w.join(1500) } }
        runCatching { rec?.release() }
        rec = null
        worker = null

        val pcm = pcmFile ?: return null
        pcmFile = null
        if (!pcm.exists()) return null

        val ms = pcm.length() * 1000L / (SAMPLE_RATE * 2)
        if (ms < MIN_MS) {
            pcm.delete()
            onError?.invoke(AppError.EmptySpeech)
            return null
        }
        // 全程近乎静音：不是「没听清」，而是根本没收到声音（没授权成功 / 被占用 / 被遮挡）
        if (peak < 0.015f) {
            pcm.delete()
            onError?.invoke(AppError.NoiseDetected)
            return null
        }
        return runCatching { toWav(pcm) }.getOrElse {
            pcm.delete()
            onError?.invoke(AppError.RecordFailed)
            null
        }
    }

    fun cancel() {
        running = false
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        worker = null
        pcmFile?.delete()
        pcmFile = null
    }

    private fun toWav(pcm: File): File {
        val data = pcm.readBytes()
        val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
        FileOutputStream(wav).use { out ->
            val totalLen = 36 + data.size
            val header = ByteArray(44)
            fun putStr(off: Int, s: String) = s.forEachIndexed { i, c -> header[off + i] = c.code.toByte() }
            fun putInt(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
                header[off + 2] = ((v shr 16) and 0xFF).toByte()
                header[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
            }
            putStr(0, "RIFF")
            putInt(4, totalLen)
            putStr(8, "WAVE")
            putStr(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)                       // PCM
            putShort(22, 1)                       // mono
            putInt(24, SAMPLE_RATE)
            putInt(28, SAMPLE_RATE * 2)           // byte rate
            putShort(32, 2)                       // block align
            putShort(34, 16)                      // bits
            putStr(36, "data")
            putInt(40, data.size)
            out.write(header)
            out.write(data)
        }
        pcm.delete()
        return wav
    }
}
