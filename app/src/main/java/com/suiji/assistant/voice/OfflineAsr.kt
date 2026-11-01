package com.suiji.assistant.voice

import android.content.Context
import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.SuiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 内置离线语音识别（Vosk + 中文小模型）。
 *
 * 存在的唯一理由：**「转不了文字」这件事不能依赖用户配置或厂商 ROM**。
 * 云端 ASR 要额外的服务商与 Key，系统 SpeechRecognizer 在国产 ROM 上大概率不存在，
 * 所以这里把 40MB 的中文模型直接打进 APK —— 装完就能转，不联网、不要 Key、不看机型。
 *
 * 模型只解压一次，落在 filesDir/asr-model 下；进程内 Model 单例，避免每次识别重新加载 40MB。
 */
object OfflineAsr {

    /** assets 里的模型包 */
    private const val ASSET_ZIP = "model-cn.zip"
    /** 解压目标目录 */
    private const val DIR = "asr-model"
    /** zip 内的顶层目录名 */
    private const val TOP = "vosk-model-small-cn-0.22"
    /** 全部完成才会创建，避免解压一半被当成可用 */
    private const val MARKER = ".ready"

    private const val SAMPLE_RATE = 16_000f
    private const val WAV_HEADER = 44
    /** 每次喂 0.25 秒，兼顾内存与延迟 */
    private const val CHUNK = 8000

    @Volatile
    private var model: Model? = null
    private val modelLock = Any()
    private val runLock = Any()

    fun isReady(ctx: Context): Boolean = File(File(ctx.filesDir, DIR), MARKER).exists()

    /** 模型解压并加载完成所需的大致字节数，用于「首次准备」提示 */
    fun modelSizeHint(): String = "约 10 秒"

    /**
     * 首次调用会解压模型（约 40MB / 5~10 秒），之后走缓存。
     */
    private fun loadModel(ctx: Context): Model? {
        model?.let { return it }
        synchronized(modelLock) {
            model?.let { return it }
            val dir = unpackIfNeeded(ctx) ?: return null
            val m = runCatching { Model(dir.absolutePath) }.getOrNull() ?: return null
            model = m
            return m
        }
    }

    private fun unpackIfNeeded(ctx: Context): File? {
        val root = File(ctx.filesDir, DIR)
        val target = File(root, TOP)
        if (File(root, MARKER).exists() && target.isDirectory) return target

        return runCatching {
            root.deleteRecursively()
            root.mkdirs()
            val rootPath = root.canonicalPath + File.separator
            ctx.assets.open(ASSET_ZIP).use { raw ->
                ZipInputStream(BufferedInputStream(raw, 128 * 1024)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val out = File(root, entry.name)
                        // 防 zip 路径穿越
                        if (!out.canonicalPath.startsWith(rootPath)) {
                            zis.closeEntry()
                            continue
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos ->
                                zis.copyTo(fos, 128 * 1024)
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }
            if (!target.isDirectory) return null
            File(root, MARKER).createNewFile()
            target
        }.getOrNull()
    }

    /**
     * 识别一段 16k / 单声道 / 16bit 的 WAV。
     */
    suspend fun transcribe(ctx: Context, wav: File): SuiResult<String> =
        withContext(Dispatchers.Default) {
            if (!wav.exists() || wav.length() <= WAV_HEADER) {
                return@withContext SuiResult.Err(AppError.EmptySpeech, "音频为空")
            }
            val m = loadModel(ctx)
                ?: return@withContext SuiResult.Err(
                    AppError.AsrEngineFailed, "离线模型加载失败"
                )

            val result = runCatching {
                synchronized(runLock) {
                    val all = wav.readBytes()
                    if (all.size <= WAV_HEADER) return@runCatching ""
                    val pcm = all.copyOfRange(WAV_HEADER, all.size)
                    if (pcm.size < SAMPLE_RATE.toInt() / 4) {
                        return@runCatching ""
                    }
                    val rec = Recognizer(m, SAMPLE_RATE)
                    try {
                        val sb = StringBuilder()
                        var off = 0
                        while (off < pcm.size) {
                            val n = minOf(CHUNK, pcm.size - off)
                            val buffer = ByteArray(n)
                            System.arraycopy(pcm, off, buffer, 0, n)
                            if (rec.acceptWaveForm(buffer, n)) {
                                val piece = JSONObject(rec.result).optString("text")
                                if (piece.isNotBlank()) sb.append(piece).append(' ')
                            }
                            off += n
                        }
                        val tail = JSONObject(rec.finalResult).optString("text")
                        if (tail.isNotBlank()) sb.append(tail)
                        sb.toString()
                    } finally {
                        runCatching { rec.close() }
                    }
                }
            }.getOrElse { "" }

            if (result.isBlank()) {
                SuiResult.Err(AppError.EmptySpeech, "离线识别无结果")
            } else {
                SuiResult.Ok(clean(result))
            }
        }

    /**
     * Vosk 中文输出是「明天 下午 三点 和 张总」这种词间带空格的形态，
     * 直接去掉中文之间的空格；英文/数字之间保留一个空格，避免把 "PPT 方案" 粘成 "PPT方案" 之外的怪串。
     */
    private fun clean(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val sb = StringBuilder(trimmed.length)
        val chars = trimmed.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (c != ' ') {
                sb.append(c)
                continue
            }
            val prev = if (i > 0) chars[i - 1] else ' '
            val next = if (i + 1 < chars.size) chars[i + 1] else ' '
            // 两侧都是 ASCII 字母数字时才保留空格
            val keep = prev.isAsciiWord() && next.isAsciiWord()
            if (keep) sb.append(' ')
        }
        return sb.toString().trim()
    }

    private fun Char.isAsciiWord(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** 释放 native 内存（进程退出前无需调用，留给低内存场景） */
    fun release() {
        synchronized(modelLock) {
            runCatching { model?.close() }
            model = null
        }
    }
}
