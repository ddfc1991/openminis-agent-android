package com.openminis.app.plus.group

import android.content.Context
import android.content.Intent
import com.openminis.app.sandbox.offload.FastUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Mines-group-37] 「代打字」 for AI apps without an API (Genspark, Cue, Operit,
 * Meta AI, Muse official app …): Mines opens the app, types the message into
 * its last input box, presses send, waits until the screen stops changing and
 * takes the new text as the reply, then comes back to the group. Uses the
 * same accessibility / Shizuku FastUi path as the agent's phone control.
 * Slow (20–90 s) and fragile when the app changes its layout — default off,
 * one app at a time. Never used for WeChat / QQ (risk control, account bans).
 */
object PhoneTyping {
    @Volatile var appContext: Context? = null
    private val lock = Mutex()
    private val blocked = setOf("com.tencent.mm", "com.tencent.mobileqq")
    /** Route to come back to after typing (the open group). */
    @Volatile var returnRoute: String? = null

    suspend fun ask(p: Partner, text: String): AiClient.Reply = lock.withLock {
        val ctx = appContext ?: throw MemberError("代打字没准备好")
        val pkg = p.base
        if (pkg in blocked) throw MemberError("微信 / QQ 不做代打字：会触发风控封号")
        withContext(Dispatchers.IO) {
            val ui = FastUi(ctx)
            if (!ui.ready()) throw MemberError("要先开 Mines 的无障碍（或 Shizuku）才能代打字")
            try {
                val opened = ui.voiceOpenApp(pkg)
                delay(2500)
                var screen = ui.voiceScreen()
                var input = lastInput(screen)
                if (input == null) { delay(2500); screen = ui.voiceScreen(); input = lastInput(screen) }
                if (input == null) throw MemberError(if (opened.contains("✗") || opened.contains("失败")) "打不开 ${p.spec.name}" else "${p.spec.name} 里找不到输入框（可能要先登录或换到对话页）")
                val before = ui.allText().lines().map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
                val clean = text.replace(Regex("[\\r\\n;；\"]+"), "，").take(1500)
                ui.voiceDo("type $input \"$clean\"", true)
                delay(400)
                val sent = ui.tapText("发送") || ui.tapText("Send") || ui.tapText("send") ||
                    ui.voiceDo("key enter", true).contains("✓")
                if (!sent) throw MemberError("${p.spec.name} 里没找到发送按钮")
                val t0 = System.currentTimeMillis()
                var last = ""; var stable = 0; var reply = ""
                while (System.currentTimeMillis() - t0 < 120_000) {
                    delay(2000)
                    val now = ui.allText()
                    val fresh = now.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in before && !clean.contains(it) && it.length > 1 }
                    val cand = fresh.joinToString("\n")
                    if (cand.isNotEmpty() && cand == last) { stable++; if (stable >= 3) { reply = cand; break } } else stable = 0
                    last = cand
                }
                if (reply.isBlank()) reply = last
                if (reply.isBlank()) throw MemberError("等了 2 分钟没读到 ${p.spec.name} 的回复（代打字可能断了）")
                AiClient.Reply(reply.take(4000), model = "App 代打字")
            } finally {
                comeBack(ctx)
            }
        }
    }

    private fun lastInput(list: String): Int? =
        list.lines().mapNotNull { l -> Regex("""^(\d+) \[输入框]""").find(l.trim())?.groupValues?.get(1)?.toIntOrNull() }.lastOrNull()

    private fun comeBack(ctx: Context) {
        runCatching {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            returnRoute?.let { i.putExtra("mines_route", it) }
            ctx.startActivity(i)
        }
    }
}
