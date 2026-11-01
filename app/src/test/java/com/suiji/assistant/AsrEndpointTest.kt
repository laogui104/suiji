package com.suiji.assistant

import com.suiji.assistant.voice.AsrClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrEndpointTest {

    @Test
    fun appendsPathToBareBase() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            AsrClient.endpointOf("https://api.openai.com/v1")
        )
    }

    @Test
    fun keepsTrailingSlashOut() {
        assertEquals(
            "https://api.siliconflow.cn/v1/audio/transcriptions",
            AsrClient.endpointOf("https://api.siliconflow.cn/v1/")
        )
    }

    @Test
    fun doesNotDoubleAppend() {
        val full = "https://x.y/v1/audio/transcriptions"
        assertEquals(full, AsrClient.endpointOf(full))
    }

    @Test
    fun worksWithPlainHost() {
        assertEquals("http://10.0.2.2:8000/audio/transcriptions", AsrClient.endpointOf("http://10.0.2.2:8000"))
    }
}
