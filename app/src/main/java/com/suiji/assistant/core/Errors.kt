package com.suiji.assistant.core

/**
 * 统一错误枚举。UI 只读 userMsg，不读技术细节。
 */
enum class AppError(
    val code: Int,
    val userMsg: String,
    val retryable: Boolean,
    val hint: String = ""
) {
    NoNetwork(1001, "网络不可用，已先存草稿", true, "检查 Wi-Fi / 移动数据"),
    Timeout(1002, "AI 响应超时，已先存草稿", true, "网络较慢或服务商拥堵"),
    Unauthorized(1003, "API Key 无效或已失效", false, "到设置页重新填写 Key"),
    QuotaExceeded(1004, "账户余额不足或被限流", false, "到服务商控制台充值"),
    ModelNotFound(1005, "接口地址或模型名不对", false, "核对 base_url 与 model"),
    RateLimited(1006, "请求过于频繁，稍后再试", true, "并发过高，稍后自动重试"),
    BadRequest(1007, "请求参数被服务商拒绝", false, "多因模型不支持 JSON 模式"),
    ServerError(1008, "模型服务商异常", true, "非本地问题，稍后重试"),
    JsonBroken(1009, "AI 返回格式异常", false, "已自动降级为本地规则"),
    SslError(1010, "HTTPS 证书异常", false, "可能被代理劫持"),
    DnsError(1011, "域名解析失败", true, "检查网络或代理设置"),
    Unknown(1099, "未知错误", true, ""),

    MicPermissionDenied(2001, "未授权麦克风，可用文字录入", false, "点这里重新授权"),
    MicBusy(2002, "麦克风被占用", true, "关掉通话或其他录音应用再试"),
    EmptySpeech(2003, "没听清，再说一次", true, "靠近一点，说完整一句"),
    NoiseDetected(2004, "没听到声音", false, "检查麦克风是否被占用或被挡住，再点一次"),
    SttUnavailable(2005, "本机没有语音识别服务", false, "去设置里开启「云端识别」，或直接打字"),
    ConfigIncomplete(2006, "配置不完整", false, "检查服务商、模型与 Key"),

    KeyMissing(2007, "还没有填写 API Key", false, "在输入框粘贴 Key 后点「保存 Key」"),
    KeyTooShort(2008, "API Key 看起来不完整", false, "重新复制，注意不要漏掉末尾字符"),

    RecordFailed(2009, "录音启动失败", true, "检查是否被其他应用占用麦克风"),
    AsrNotConfigured(2010, "录到了，但还没有转写通道", false, "去设置里填 Key 并开启「云端识别」"),

    AsrEngineFailed(2011, "内置识别没启动起来", true, "重启应用再试，或直接打字"),
    AsrPreparing(2012, "首次使用，正在准备离线识别…", true, "大约 10 秒，只需一次")
}

sealed interface SuiResult<out T> {
    data class Ok<T>(val data: T) : SuiResult<T>
    data class Err(val error: AppError, val detail: String = "") : SuiResult<Nothing>
}

inline fun <T> runCatchingErr(block: () -> T): SuiResult<T> =
    try {
        SuiResult.Ok(block())
    } catch (e: Throwable) {
        SuiResult.Err(AppError.Unknown, e.message ?: e.javaClass.simpleName)
    }
