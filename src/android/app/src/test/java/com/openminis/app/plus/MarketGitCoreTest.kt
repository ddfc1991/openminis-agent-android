package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.7] GitHub SSH → HTTPS for market installs (phone 21:22 「安装 rea 没成功 / 没有权限」). */
class MarketGitCoreTest {
    @Test fun specs() {
        assertEquals("git+https://github.com/morluto/rea.git", MarketGitCore.httpsSpec("github:morluto/rea"))
        assertEquals("git+https://github.com/morluto/rea.git", MarketGitCore.httpsSpec("git+ssh://git@github.com/morluto/rea.git"))
        assertEquals("git+https://github.com/a/b.git#v1", MarketGitCore.httpsSpec("git@github.com:a/b.git#v1"))
        assertEquals("dsh-whale-widget@latest", MarketGitCore.httpsSpec("dsh-whale-widget@latest"))
    }
    @Test fun env() {
        val e = MarketGitCore.GIT_ENV
        assertEquals("0", e["GIT_TERMINAL_PROMPT"]); assertEquals("2", e["GIT_CONFIG_COUNT"])
        assertEquals("ssh://git@github.com/", e["GIT_CONFIG_VALUE_0"]); assertEquals("git@github.com:", e["GIT_CONFIG_VALUE_1"])
        assertTrue(e["GIT_CONFIG_KEY_0"]!!.endsWith(".insteadOf"))
    }
    @Test fun reasons() {
        val log = "npm error command git --no-replace-objects ls-remote ssh://git@github.com/morluto/rea.git\nnpm error git@github.com: Permission denied (publickey)."
        assertTrue(MarketGitCore.isPublickey(log))
        assertTrue(MarketGitCore.reason(log)!!.contains("已改用 HTTPS 重试"))
        assertFalse(MarketGitCore.reason(log)!!.contains("没有权限"))
        assertNull(MarketGitCore.reason("npm error 404 Not Found"))
    }
    @Test fun kinds() {
        assertFalse(MarketGitCore.classify("dsh-desktop", "为 DeepSeek Harness (DSH) 插件生态打造的现代化桌面端", emptyList(), "client", "unsupported").canTry)
        assertEquals("资料库", MarketGitCore.classify("awesome-gpt-image-2", "GPT Image 2 提示词与案例库", emptyList(), "model-inference", "unsupported").pill)
        assertTrue(MarketGitCore.classify("rea", "Reverse engineer anything", listOf("dsh-plugin", "cordis"), "agent-workflow", "unsupported").canTry)
        assertEquals(null, MarketGitCore.classify("x", "", emptyList(), "", "verified").pill)
        assertFalse(MarketGitCore.classify("deepseek-harness", "DeepSeek Harness: Everything is a Plugin.", listOf("dsh-plugin"), "", "unsupported").canTry)
        assertFalse(MarketGitCore.classify("reactive-resume", "resume builder", emptyList(), "client", "unsupported").canTry)
    }

    // [Mines-38.9.8] dsh-pilot (SkillHub verified, Windows desktop control) → 只能电脑用, not installable
    @Test fun desktopOnly() {
        val pilot = "Desktop automation for DeepSeek Harness: hands and eyes on the whole Windows machine - screen_view returns a live PNG of the screen, desktop_control drives the real mouse, keyboard and windows at the same pixel coordinates"
        assertEquals("Windows", MarketGitCore.platformOnly("dsh-pilot", pilot, emptyList()))
        val k = MarketGitCore.classify("dsh-pilot", pilot, listOf("dsh-plugin"), "agent-workflow", "verified")
        assertFalse(k.canTry); assertTrue(k.desktopOnly); assertEquals("只能电脑用", k.pill)
        assertEquals("这个插件只能在 Windows 电脑上用，手机上装了也用不了", k.line)
        assertEquals("macOS", MarketGitCore.platformOnly("x", "Control apps via AppleScript", emptyList()))
        assertNull(MarketGitCore.platformOnly("rea", "Reverse engineer anything", listOf("dsh-plugin")))
        assertNull(MarketGitCore.platformOnly("dsh-git-panel", "Web GUI 里的 IDE 风格 Git 面板", emptyList()))
        assertTrue(MarketGitCore.classify("rea", "Reverse engineer anything", listOf("dsh-plugin"), "", "verified").canTry)
    }
    @Test fun convert() {
        assertTrue(MarketGitCore.convertible("awesome-prompts", "GPT 提示词与案例库", emptyList()))
        assertFalse(MarketGitCore.convertible("dsh-pilot", "Windows machine screen_view desktop_control prompt", emptyList()))
        assertFalse(MarketGitCore.convertible("dsh-desktop", "electron desktop app with prompts", emptyList()))
        val md = MarketGitCore.skillMd("o", "My Prompts", "Prompt pack", "https://github.com/o/My-Prompts", listOf("README.md" to "# Hi\n" + "x".repeat(200)))!!
        assertTrue(md.startsWith("---\nname: my-prompts\ndescription: Prompt pack\n"))
        assertTrue(md.contains("## README.md"))
        assertNull(MarketGitCore.skillMd("o", "n", "", "", listOf("README.md" to "short")))
        assertTrue(MarketGitCore.skillMd("o", "n", "", "", listOf("README.md" to "y".repeat(50_000)), maxChars = 5000)!!.length <= 5000)
    }
}
