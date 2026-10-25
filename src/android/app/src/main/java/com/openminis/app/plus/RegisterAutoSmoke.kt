package com.openminis.app.plus

import android.content.Context
import org.json.JSONObject

/** [Mines-38.9.15 register_auto] adb-only smoke hooks ('ira …' through SelfTestProvider / GroupSelfTest). */
object RegisterAutoSmoke {
    fun handle(c: Context, rest: String): String {
        RegisterAuto.init(c)
        val sub = rest.substringBefore(' ').trim(); val arg = rest.substringAfter(' ', "").trim()
        return when (sub) {
            "base" -> { RegisterAuto.setMailBase(c, arg); JSONObject().put("base", RegisterAuto.mailBase).toString() }
            "state" -> RegisterAuto.stateJson(c)
            "custom" -> RegisterAutoCore.custom(arg, emptyList()).let { (p, why) -> JSONObject().put("url", p?.url ?: "").put("name", p?.name ?: "").put("why", why ?: "").toString() }
            "answer" -> { RegisterAuto.answer(arg != "skip"); JSONObject().put("ok", true).toString() }
            "start" -> {
                val list = arg.split(',').mapNotNull { RegisterAutoCore.normUrl(it) }.map { RegisterAutoCore.Platform(RegisterAutoCore.nameFor(it), it, true) }
                JSONObject().put("started", RegisterAuto.start(c, list)).toString()
            }
            "reset" -> {
                c.getSharedPreferences("mines_register_auto", Context.MODE_PRIVATE).edit().remove("custom").remove("run").apply()
                JSONObject().put("ok", true).toString()
            }
            // 临时邮箱台账 / 保活 / 整理
            "led" -> RegLedger.stateJson(c)
            "seedtidy" -> {   // <box> <boxPass>: one fillable temp row, one dead temp row without password, one gmail row
                val (box, pass) = arg.split(' ').let { it[0] to it.getOrElse(1) { "" } }
                RegLedger.init(c); RegLedger.setBoxPass(box, pass)
                SavedLogins.put(c, SavedLogins.Entry("10.0.2.2", "http://10.0.2.2:8096/", box, "Seed-Pw-12345678"))
                SavedLogins.put(c, SavedLogins.Entry("dead.example", "https://dead.example/", "gone-smoke@smoke-mail.test", ""))
                SavedLogins.put(c, SavedLogins.Entry("GitHub", "https://github.com/", "smoke.long@gmail.com", "Long-Pw-12345678"))
                c.getSharedPreferences("mines_reg_ledger", Context.MODE_PRIVATE).edit().putBoolean("tidy_v1", false).apply()
                RegLedger.lastTidy.value = null
                JSONObject().put("ok", true).put("all", SavedLogins.all(c).size).toString()
            }
            "tidy" -> kotlinx.coroutines.runBlocking { RegLedger.tidy(c).line }
            "karun" -> kotlinx.coroutines.runBlocking { org.json.JSONArray(RegLedger.runDue(c, force = true)).toString() }
            "saved" -> org.json.JSONArray().apply { SavedLogins.all(c).forEach { e -> put(JSONObject().put("host", e.host).put("user", e.user).put("pw", e.pass.isNotBlank())) } }.toString()
            else -> "ira base <url> | state | custom <text> | answer go|skip | start <url,url> | reset"
        }
    }
}
