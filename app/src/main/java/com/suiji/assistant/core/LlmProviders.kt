package com.suiji.assistant.core

/**
 * 内置主流大模型服务商预设。
 * 用户只需选一个 + 填 Key，地址与模型名自动带出；任何预设的字段都允许被覆盖。
 */
data class LlmProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>,
    val jsonMode: Boolean = true,
    val note: String = "",
    /** 该服务商 OpenAI 兼容 /audio/transcriptions 的默认模型；空串表示不提供 */
    val asrModel: String = ""
)

object LlmProviders {

    const val CUSTOM_ID = "custom"

    val ALL: List<LlmProvider> = listOf(
        LlmProvider(
            "deepseek", "DeepSeek 深度求索",
            "https://api.deepseek.com/v1", "deepseek-chat",
            listOf("deepseek-chat", "deepseek-reasoner"),
            note = "中文分拣效果好，价格低"
        ),
        LlmProvider(
            "zhipu", "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
            listOf("glm-4-flash", "glm-4-air", "glm-4-plus"),
            note = "glm-4-flash 免费"
        ),
        LlmProvider(
            "dashscope", "通义千问（阿里云百炼）",
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            note = "新用户有免费额度"
        ),
        LlmProvider(
            "moonshot", "月之暗面 Kimi",
            "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            listOf("moonshot-v1-8k", "moonshot-v1-32k")
        ),
        LlmProvider(
            "ark", "火山方舟 豆包",
            "https://ark.cn-beijing.volces.com/api/v3", "",
            emptyList(),
            note = "model 需填接入点 ID，形如 ep-2024xxxx"
        ),
        LlmProvider(
            "qianfan", "百度千帆 文心",
            "https://qianfan.baidubce.com/v2", "ernie-4.0-turbo-8k",
            listOf("ernie-4.0-turbo-8k", "ernie-speed-128k")
        ),
        LlmProvider(
            "minimax", "MiniMax",
            "https://api.minimax.chat/v1", "abab6.5s-chat",
            listOf("abab6.5s-chat", "MiniMax-Text-01"),
            jsonMode = false,
            note = "部分模型不支持 JSON 模式，已用提示词约束"
        ),
        LlmProvider(
            "siliconflow", "硅基流动 SiliconFlow",
            "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct",
            listOf("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3"),
            note = "同一把 Key 还能做语音转写",
            asrModel = "FunAudioLLM/SenseVoiceSmall"
        ),
        LlmProvider(
            "openai", "OpenAI",
            "https://api.openai.com/v1", "gpt-4o-mini",
            listOf("gpt-4o-mini", "gpt-4o"),
            asrModel = "whisper-1"
        ),
        LlmProvider(
            CUSTOM_ID, "自定义（OpenAI 兼容）",
            "", "",
            emptyList(),
            note = "手填完整 base_url，如 https://your.host/v1",
            asrModel = "whisper-1"
        )
    )

    fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
