package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Test

/** [38.9.15.1] finished replies without gratuitous emoji / stray Markdown; code untouched. */
class ReplyTidyTest {
    private fun t(s: String) = ReplyTidy.tidy(s)

    @Test fun dropsEmoji() {
        assertEquals("部署完成", t("🚀 部署完成 🎉"))
        assertEquals("用 `echo 🚀` 测试 一下", t("用 `echo 🚀` 测试 ✨ 一下"))
        assertEquals("好的", t("好的😂"))
    }

    @Test fun keepsStatusMarksAndLists() {
        assertEquals("- 已完成\n- ⚠ 注意\n- ✓ 好", t("- ✅ 已完成\n- ⚠ 注意\n- ✓ 好"))
        assertEquals("正常的 **粗体** 文本", t("正常的 **粗体** 文本"))
    }

    @Test fun dropsStrayBold() {
        assertEquals("**步骤：** 1. 用台账里的邮箱去注册", t("**步骤：** 1. **用台账里的邮箱去注册"))
        assertEquals("说明\n下一步", t("说明\n**\n下一步"))
        assertEquals("说明\n下一步", t("说明\n#\n下一步"))
    }

    @Test fun codeBlocksUntouched() {
        assertEquals("```sh\necho '🚀 **'\n```\n好的", t("```sh\necho '🚀 **'\n```\n好的😂"))
        val plain = "第一段\n\n```\n**\n```\n"
        assertEquals(plain, t(plain))
    }
}
