package com.openminis.app.ui.plus

import com.openminis.app.plus.RainSkyCore
import com.openminis.app.plus.WeatherSkyCore

/**
 * [Mines-38.9.11 c] loads 雨 v5 (assets/skybg/rain.agsl, [RainSkyCore]) over the bundled sky segments. The two rain shaders are
 * compiled once here (on the sky's preparation thread); if either fails, the bundled rain is kept and the reason is recorded,
 * so a rain problem never sends every other scene to the fallback stills.
 */
object RainSky {
    @Volatile var lastError: String? = null
    @Volatile var active: Boolean = false
    fun label(): String = if (active) RainSkyCore.VERSION else "雨 v2" + (lastError?.let { "（$it）" } ?: "")

    @androidx.annotation.RequiresApi(33)
    fun segments(c: android.content.Context, base: Map<String, String>): Map<String, String> {
        val src = runCatching { c.assets.open(RainSkyCore.ASSET).bufferedReader().use { it.readText() } }
            .onFailure { lastError = "no asset" }.getOrNull() ?: return base
        val merged = RainSkyCore.merge(base, src)
        if (merged === base) { lastError = "bad segments"; active = false; return base }
        return try {
            android.graphics.RuntimeShader(WeatherSkyCore.skySource(merged, WeatherSkyCore.Scene.RAIN))
            WeatherSkyCore.pass2Source(merged, WeatherSkyCore.Scene.RAIN)?.let { android.graphics.RuntimeShader(it) }
            active = true; lastError = null; merged
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(200)
            android.util.Log.w("MinesSky", "rain v5 shader failed, keeping the bundled rain: $lastError")
            active = false; base
        }
    }
}
