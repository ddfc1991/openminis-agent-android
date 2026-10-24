package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrZhCoreTest {
    @Test fun screenshotErrorsBecomeChinese() {
        assertEquals("Reddit（r/LocalLLaMA） 拒绝访问，已跳过", ErrZhCore.source("r/LocalLLaMA", "HTTP 403"))
        assertEquals("Hacker News 连接中断，重试也不行，已跳过", ErrZhCore.source("Hacker News", "java.io.EOFException", retried = true))
        assertEquals("Hacker News 连接中断，已重试成功", ErrZhCore.source("Hacker News", "EOFException", recovered = true))
        assertEquals("Telegram 连接中断，已跳过", ErrZhCore.source("Telegram", "stream was reset: CANCEL"))
        assertEquals("模型 mistralai/voxtral-mini-tts-3-0 不能用来聊天，已换下一个",
            ErrZhCore.model("mistralai/voxtral-mini-tts-3-0", "HTTP 400 text-to-speech model cannot be used with chat"))
        listOf(ErrZhCore.source("X", "weird"), ErrZhCore.model("m", "weird")).forEach { assertTrue(it.any { c -> c in '\u4e00'..'\u9fff' }) }
    }

    @Test fun retryOnlyForRefusedOrCut() {
        assertTrue(ErrZhCore.retryable("HTTP 403")); assertTrue(ErrZhCore.retryable("stream was reset: CANCEL")); assertTrue(ErrZhCore.retryable("EOFException"))
        assertFalse(ErrZhCore.retryable("HTTP 404")); assertFalse(ErrZhCore.retryable("JSON parse error"))
    }

    @Test fun nonChatModelsFiltered() {
        listOf("mistralai/voxtral-mini-tts-3-0", "tts-1", "gpt-4o-mini-tts", "whisper-1", "text-embedding-3-small", "BAAI/bge-m3", "jina-reranker-v2",
            "dall-e-3", "gpt-image-1", "black-forest-labs/FLUX.1-schnell", "imagen-3.0", "stabilityai/stable-diffusion-xl", "Kwai-Kolors/Kolors", "omni-moderation-latest", "veo-2")
            .forEach { assertFalse(it, ErrZhCore.chatCapable(it)) }
        listOf("deepseek-chat", "gpt-4o-mini", "mistralai/voxtral-small-24b", "gemini-2.5-flash", "qwen3-235b-a22b", "claude-haiku-4-5", "glm-4.5-air", "SenseChat-5", "kimi-k2")
            .forEach { assertTrue(it, ErrZhCore.chatCapable(it)) }
        assertTrue(ErrZhCore.notChat("This text-to-speech model cannot be used with chat"))
    }
    @Test fun oldStoredNotesTranslated() {
        assertEquals("Reddit（r/LocalLLaMA） 拒绝访问，已跳过", ErrZhCore.legacyNote("r/LocalLLaMA：HTTP 403"))
        assertEquals("没连上模型：按热度挑选", ErrZhCore.legacyNote("没连上模型：按热度挑选"))
    }
}
