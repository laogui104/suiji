package com.suiji.assistant.net

import com.suiji.assistant.core.AppConfig
import com.suiji.assistant.core.AppError
import com.suiji.assistant.core.LlmConfig
import com.suiji.assistant.core.SuiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容 Chat Completions 客户端。只依赖 HttpURLConnection，无第三方网络库。
 */
class LlmClient {

    suspend fun chat(
        cfg: LlmConfig,
        system: String,
        user: String,
        maxTokens: Int = AppConfig.MAX_TOKENS,
        jsonMode: Boolean = true,
        temperature: Float = AppConfig.TEMPERATURE
    ): SuiResult<String> = withContext(Dispatchers.IO) {
        val key = cfg.apiKey
        if (key.isNullOrBlank()) return@withContext SuiResult.Err(AppError.Unauthorized, "未配置 API Key")
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            return@withContext SuiResult.Err(AppError.ConfigIncomplete, "base_url 或 model 为空")
        }

        val endpoint = endpointOf(cfg.baseUrl)
        var attempt = 0
        var last: SuiResult<String> = SuiResult.Err(AppError.Unknown)

        while (attempt <= AppConfig.LLM_MAX_RETRY) {
            last = once(endpoint, key, cfg, system, user, maxTokens, jsonMode, temperature)
            if (last is SuiResult.Ok) return@withContext last
            val err = (last as SuiResult.Err).error
            if (!err.retryable) return@withContext last
            attempt++
            if (attempt <= AppConfig.LLM_MAX_RETRY) {
                kotlinx.coroutines.delay(600L * attempt)
            }
        }
        last
    }

    private fun once(
        endpoint: String,
        key: String,
        cfg: LlmConfig,
        system: String,
        user: String,
        maxTokens: Int,
        jsonMode: Boolean,
        temperature: Float
    ): SuiResult<String> {
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
                put("temperature", temperature.toDouble())
                put("max_tokens", maxTokens)
                put("stream", false)
                if (jsonMode && cfg.jsonMode) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }

            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = AppConfig.LLM_CONNECT_TIMEOUT_MS
                readTimeout = AppConfig.LLM_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return SuiResult.Err(mapHttp(code, text), "HTTP $code: ${text.take(300)}")
            }

            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()

            if (content.isNullOrBlank()) {
                SuiResult.Err(AppError.JsonBroken, "choices 为空")
            } else {
                SuiResult.Ok(content)
            }
        } catch (e: UnknownHostException) {
            SuiResult.Err(AppError.DnsError, e.message ?: "")
        } catch (e: SocketTimeoutException) {
            SuiResult.Err(AppError.Timeout, e.message ?: "")
        } catch (e: SSLException) {
            SuiResult.Err(AppError.SslError, e.message ?: "")
        } catch (e: IOException) {
            SuiResult.Err(AppError.NoNetwork, e.message ?: "")
        } catch (e: Throwable) {
            SuiResult.Err(AppError.Unknown, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun endpointOf(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    private fun mapHttp(code: Int, body: String): AppError = when (code) {
        400, 422 -> AppError.BadRequest
        401, 403 -> AppError.Unauthorized
        402 -> AppError.QuotaExceeded
        404 -> AppError.ModelNotFound
        413 -> AppError.BadRequest
        429 -> AppError.RateLimited
        in 500..599 -> AppError.ServerError
        else -> if (body.contains("insufficient", true) || body.contains("余额", true)) {
            AppError.QuotaExceeded
        } else {
            AppError.Unknown
        }
    }

    // ---- 连通性探测 ----

    sealed interface NetProbe {
        data class Ok(val ms: Long) : NetProbe
        data class Fail(val reason: String) : NetProbe
    }

    suspend fun probeHost(baseUrl: String): NetProbe = withContext(Dispatchers.IO) {
        val url = runCatching { URL(baseUrl) }.getOrNull()
            ?: return@withContext NetProbe.Fail("地址格式非法，需以 http:// 或 https:// 开头")
        val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
        val t0 = System.currentTimeMillis()
        try {
            val addr = InetAddress.getByName(url.host)
            Socket().use { s -> s.connect(InetSocketAddress(addr, port), 4000) }
            NetProbe.Ok(System.currentTimeMillis() - t0)
        } catch (e: UnknownHostException) {
            NetProbe.Fail("域名解析失败：${url.host}")
        } catch (e: SocketTimeoutException) {
            NetProbe.Fail("连接超时，可能是网络或代理限制")
        } catch (e: SSLException) {
            NetProbe.Fail("HTTPS 证书异常")
        } catch (e: IOException) {
            NetProbe.Fail("网络不可达：${e.message}")
        }
    }
}
