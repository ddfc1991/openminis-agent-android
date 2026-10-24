package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.LLMModel
import java.io.File

/**
 * [Mines-38.9.9] Android side of [ModelScoreCore]: per-model observed outcomes (files/mines_model_score.json), the
 * 自动调度 switch (default on; hidden override under 设置 › Agent 工具 › 高级), and the single-flight guard for signed-in
 * web members. Key = "<provider>|<model id>" so the same model on two accounts is two keys.
 */
object ModelScore {
    private var map: MutableMap<String, ModelScoreCore.Stats>? = null
    private fun file(c: Context) = File(c.applicationContext.filesDir, "mines_model_score.json")
    @Volatile var webBusy: Boolean = false

    fun key(m: LLMModel): String = m.provider + "|" + m.id

    @Synchronized private fun load(c: Context): MutableMap<String, ModelScoreCore.Stats> = map ?: runCatching {
        ModelScoreCore.fromJson(org.json.JSONObject(file(c).readText())).toMutableMap()
    }.getOrDefault(mutableMapOf()).also { map = it }

    @Synchronized private fun save(c: Context) { runCatching { file(c).writeText(ModelScoreCore.toJson(load(c)).toString()) } }

    fun stats(c: Context, key: String): ModelScoreCore.Stats? = load(c)[key]

    @Synchronized fun ok(c: Context, m: LLMModel, ms: Long, done: Boolean = false) {
        val k = key(m); load(c)[k] = ModelScoreCore.onOk(load(c)[k], ms, System.currentTimeMillis(), done); save(c)
    }

    @Synchronized fun fail(c: Context, m: LLMModel, code: Int, timeout: Boolean) {
        val k = key(m); load(c)[k] = ModelScoreCore.onFail(load(c)[k], code, timeout, System.currentTimeMillis()); save(c)
    }

    /** 自动调度 on (default) — the hidden override in 高级 can turn it off */
    fun enabled(c: Context): Boolean = c.getSharedPreferences("mines_sched", 0).getBoolean("auto", true)
    fun setEnabled(c: Context, on: Boolean) { c.getSharedPreferences("mines_sched", 0).edit().putBoolean("auto", on).apply() }
}
