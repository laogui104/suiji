package com.suiji.assistant.voice

import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.AsrConfig
import com.suiji.assistant.core.SuiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容的语音转写客户端：POST {base}/audio/transcriptions，multipart/form-data。
 * 走的就是用户已填的那把 Key，不额外引入服务商。
 */
object AsrClient {

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 90_000

    fun endpointOf(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/audio/transcriptions")) return b
        return "$b/audio/transcriptions"
    }

    suspend fun transcribe(cfg: AsrConfig, wav: File): SuiResult<String> =
        withContext(Dispatchers.IO) {
            val key = cfg.apiKey
            if (key.isNullOrBlank()) return@withContext SuiResult.Err(AppError.AsrNotConfigured, "未配置 API Key")
            if (cfg.baseUrl.isBlank()) return@withContext SuiResult.Err(AppError.ConfigIncomplete, "base_url 为空")
            if (!wav.exists() || wav.length() <= 44) {
                return@withContext SuiResult.Err(AppError.EmptySpeech, "音频为空")
            }

            val boundary = "----suiji${System.currentTimeMillis()}"
            val body = buildBody(boundary, cfg, wav)
            val conn = (URL(endpointOf(cfg.baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }

            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    return@withContext SuiResult.Err(mapHttp(code, text), "HTTP $code · ${text.take(180)}")
                }
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@withContext SuiResult.Err(AppError.JsonBroken, text.take(180))
                val out = obj.optString("text").trim()
                if (out.isBlank()) SuiResult.Err(AppError.EmptySpeech, "返回文本为空")
                else SuiResult.Ok(out)
            } catch (e: SocketTimeoutException) {
                SuiResult.Err(AppError.Timeout, "转写超时")
            } catch (e: UnknownHostException) {
                SuiResult.Err(AppError.DnsError, "域名解析失败")
            } catch (e: SSLException) {
                SuiResult.Err(AppError.SslError, e.message ?: "SSL")
            } catch (e: Exception) {
                SuiResult.Err(AppError.NoNetwork, e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    private fun buildBody(boundary: String, cfg: AsrConfig, wav: File): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fun field(name: String, value: String) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write("$value\r\n")
        }

        field("model", cfg.model)
        field("language", cfg.language)
        field("response_format", "json")
        field("temperature", "0")

        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n")
        write("Content-Type: audio/wav\r\n\r\n")
        out.write(wav.readBytes())
        write("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun mapHttp(code: Int, body: String): AppError = when (code) {
        400 -> if (body.contains("model", true)) AppError.ModelNotFound else AppError.BadRequest
        401, 403 -> AppError.Unauthorized
        402 -> AppError.QuotaExceeded
        404 -> AppError.ModelNotFound
        413 -> AppError.BadRequest
        429 -> if (body.contains("quota", true) || body.contains("balance", true))
            AppError.QuotaExceeded else AppError.RateLimited
        in 500..599 -> AppError.ServerError
        else -> AppError.Unknown
    }
}
