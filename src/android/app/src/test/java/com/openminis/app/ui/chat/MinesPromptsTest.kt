package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MinesPromptsTest {
    @Test
    fun `compact summary prompt stays byte-for-byte in its asset`() {
        val promptFile = File("src/main/assets/mines-prompts/compact-summary.txt")
        assertTrue("missing packaged prompt asset", promptFile.isFile)
        val prompt = MinesPrompts.read(promptFile.inputStream())
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // Captured from the prior Kotlin triple-quoted string after trimIndent().
        assertEquals("cfcc90979d9037f4f423c162ebe71515aebc110058637fa91ede2310ef525b52", sha256)

        val source = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        assertTrue(source.contains("MinesPrompts.compactSummary(context)"))
        assertFalse(source.contains("You are a context compaction engine."))
    }
}
