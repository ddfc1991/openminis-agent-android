package com.openminis.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.15 伪人核心] VoiceFilter: a leak never ships, whatever the model emits; the user's own style is kept. */
class VoiceFilterCoreTest {

    @Test fun catchesChineseAndEnglishLeaks() {
        listOf(
            "作为一个人工智能，我没有个人观点。",
            "作为AI助手，我可以帮你安排。",
            "As an AI, I can't have feelings.",
            "I'm an assistant, but here you go.",
            "希望以上回答对您有帮助！",
            "当然可以！这就去办。",
            "Hope this helps!",
        ).forEach { assertTrue("missed: $it", VoiceFilterCore.hasLeak(it)) }
    }

    @Test fun ordinaryTextIsNotALeak() {
        listOf("明天我可以帮你搬家，几点？", "这个周末我有空。", "收到，晚点回你。", "I'll send it tonight.")
            .forEach { assertFalse("false positive: $it", VoiceFilterCore.hasLeak(it)) }
    }

    @Test fun strippingKeepsTheContentAndDropsTheDisclaimer() {
        val out = VoiceFilterCore.strip("作为一个人工智能，我没有个人感受。不过这家店我上周去过，味道不错。")
        assertFalse(VoiceFilterCore.hasLeak(out))
        assertTrue(out, out.contains("这家店我上周去过"))
        assertFalse(out, out.contains("人工智能"))
    }

    @Test fun aLeakInsideASentenceLosesOnlyThatClause() {
        val out = VoiceFilterCore.strip("作为AI，我觉得这个方案可以。")
        assertFalse(VoiceFilterCore.hasLeak(out))
        assertTrue(out, out.contains("这个方案可以"))
    }

    @Test fun strippingEverythingLeavesNothingToSend() {
        assertEquals(VoiceFilterCore.EMPTY, VoiceFilterCore.strip("作为一个人工智能，我没有感情。希望以上回答对您有帮助！"))
    }

    @Test fun finishFiltersTheModelsOwnRewriteToo() {
        val style = VoiceFilterCore.learn(listOf("收到，晚点弄。", "好，明天给你。", "这个我看看再说。"))
        val (out, leaked) = VoiceFilterCore.finish("作为AI助手，我可以帮你发邮件。", "作为一个AI，我帮你发了。", style)
        assertTrue("the original leaked", leaked)
        assertFalse("a leaking rewrite must not ship: $out", VoiceFilterCore.hasLeak(out))
    }

    @Test fun finishUsesACleanRewrite() {
        val (out, _) = VoiceFilterCore.finish("作为AI助手，我可以帮你发邮件。", "好，我这就发。", VoiceFilterCore.Style())
        assertEquals("好，我这就发。", out)
    }

    @Test fun learnsLengthEmojiAndExclaim() {
        val s = VoiceFilterCore.learn(listOf("收到。", "好的，晚点弄。", "明天给你。", "行。"))
        assertTrue(s.known)
        assertFalse(s.usesEmoji); assertFalse(s.usesExclaim)
        assertTrue("median ${s.sentenceLen}", s.sentenceLen in 2..8)
        val e = VoiceFilterCore.learn(listOf("太好了！😄", "没问题！👍", "来了！🎉"))
        assertTrue(e.usesEmoji); assertTrue(e.usesExclaim)
    }

    @Test fun fewSamplesIsNotAStyle() {
        assertFalse(VoiceFilterCore.learn(listOf("好的。")).known)
        assertFalse(VoiceFilterCore.learn(emptyList()).known)
    }

    @Test fun ruleRewriteDropsWhatTheUserDoesNotUse() {
        val style = VoiceFilterCore.learn(listOf("收到，晚点弄。", "好，明天给你。", "这个我看看再说。"))
        val out = VoiceFilterCore.ruleRewrite("作为AI助手，我可以帮你！😄 这就去办！", style)
        assertFalse(VoiceFilterCore.hasLeak(out))
        assertFalse("no emoji for this user: $out", VoiceFilterCore.hasEmoji(out))
        assertFalse("no exclamation for this user: $out", out.contains('！'))
    }

    @Test fun rewritePromptNeverAsksToSoundHuman() {
        val p = VoiceFilterCore.rewritePrompt(PersonaCore.Profile(displayName = "阿杨", traits = listOf("短句")), VoiceFilterCore.learn(listOf("收到。", "好。", "行，晚点说。")))
        assertTrue(p.contains("本人"))
        assertTrue(p.contains("短句"))
        assertFalse(p.contains("假装"))
    }
}
