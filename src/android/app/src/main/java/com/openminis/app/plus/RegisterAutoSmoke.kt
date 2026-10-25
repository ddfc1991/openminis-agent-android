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
            else -> "ira base <url> | state | custom <text> | answer go|skip | start <url,url> | reset"
        }
    }
}
