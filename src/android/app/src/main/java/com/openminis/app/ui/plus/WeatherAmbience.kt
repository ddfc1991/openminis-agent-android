package com.openminis.app.ui.plus

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle

/**
 * [Mines-38.8 v5d] Home weather ambience (user-approved 2026-10-16): a faint layer over the fog, behind every card,
 * from the CURRENT weather (weather_code + is_day that HomeGreeting already fetched; smoke override honoured):
 * clear day = warm sun glow drifting in the top corner · cloudy = a few slow translucent clouds · rain = thin faint
 * falling streaks · snow = slow small flakes · thunder = rain + a rare soft flash · fog / haze = an extra drifting veil ·
 * clear night = a few twinkling stars. ONE Canvas, ≤ 30 fps, only while the app is RESUMED (paused when not visible);
 * a static frame when animations are off or battery saver is on. No layout, no text.
 */
object WeatherAmbience {
    enum class Kind { NONE, SUN, STARS, CLOUDS, RAIN, SNOW, THUNDER, FOG }

    fun kindOf(code: Int?, day: Boolean): Kind = when (code) {
        null -> Kind.NONE
        0, 1 -> if (day) Kind.SUN else Kind.STARS
        2, 3 -> Kind.CLOUDS
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.THUNDER
        else -> Kind.NONE
    }

    /** smoke: what the home layer is drawing now */
    @Volatile var lastKind: Kind = Kind.NONE
}

@Composable
fun WeatherAmbienceLayer(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val m = mines()
    // [Mines-38.9.6 i14] the weather sky draws the weather itself
    val sTick by WeatherSky.tick.collectAsState()
    if (remember(sTick) { WeatherSky.enabled(ctx) }) return
    val gTick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    val kind = remember(gTick) {
        val o = com.openminis.app.plus.HomeGreeting.override
        val w = runCatching { com.openminis.app.plus.HomeGreeting.weatherNow(ctx) }.getOrNull()
        val hour = o?.optInt("hour", -1)?.takeIf { it >= 0 } ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val day = if (o != null && o.has("hour")) hour in 6..18 else com.openminis.app.plus.HomeGreeting.isDayNow(ctx) ?: (hour in 6..18)
        WeatherAmbience.kindOf(w?.code, day)
    }
    WeatherAmbience.lastKind = kind
    if (kind == WeatherAmbience.Kind.NONE) return
    val still = remember {
        AnimeWeather.still(ctx) || runCatching { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode }.getOrDefault(false)
    }
    val time = remember { mutableFloatStateOf(3.7f) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    if (!still) LaunchedEffect(owner, kind) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var last = 0L; var base = 0L
            while (true) { MotionGate.awaitRun(); androidx.compose.runtime.withFrameMillis { now ->
                if (base == 0L) base = now - (time.floatValue * 1000).toLong()
                if (now - last >= 33L) { last = now; time.floatValue = (now - base) / 1000f }
            } }
        }
    }
    val dark = m.dark
    Canvas(modifier) {
        val t = time.floatValue
        val w = size.width; val h = size.height; val sc = w / 1080f
        val twoPi = (2 * Math.PI).toFloat()
        fun rnd(i: Int, k: Int): Float { val x = kotlin.math.sin(i * 12.9898f + k * 78.233f) * 43758.547f; return x - kotlin.math.floor(x) }
        fun rain(n: Int, alpha: Float) {
            val col = if (dark) Color.White.copy(alpha = alpha * 0.8f) else Color(0xFF6E6290).copy(alpha = alpha)
            for (i in 0 until n) {
                val speed = h / (0.9f + 0.5f * rnd(i, 1))
                val len = (26f + 22f * rnd(i, 2)) * sc
                val x0 = rnd(i, 3) * (w + 120f * sc)
                val y = ((rnd(i, 4) * h + t * speed) % (h + len)) - len
                val x = x0 - (y / h) * 90f * sc
                drawLine(col, Offset(x, y), Offset(x - 9f * sc, y + len), strokeWidth = 1.4f * sc, cap = StrokeCap.Round)
            }
        }
        when (kind) {
            WeatherAmbience.Kind.SUN -> {
                val c = Offset(w * 0.86f + 34f * sc * kotlin.math.sin(twoPi * t / 22f), h * 0.07f + 26f * sc * kotlin.math.cos(twoPi * t / 27f))
                val r = w * 0.62f
                val a = (if (dark) 0.16f else 0.34f) * (0.88f + 0.12f * kotlin.math.sin(twoPi * t / 6f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC778).copy(alpha = a), Color(0xFFFFB27A).copy(alpha = a * 0.45f), Color.Transparent), center = c, radius = r), radius = r, center = c)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1C8).copy(alpha = a * 0.9f), Color.Transparent), center = c, radius = r * 0.28f), radius = r * 0.28f, center = c)
            }
            WeatherAmbience.Kind.STARS -> {
                val col = if (dark) Color.White else Color(0xFFC9963E)
                for (i in 0 until 26) {
                    val x = rnd(i, 1) * w; val y = rnd(i, 2) * h * 0.42f
                    val tw = 0.5f + 0.5f * kotlin.math.sin(twoPi * t / (2.4f + 2f * rnd(i, 3)) + i)
                    drawCircle(col.copy(alpha = (if (dark) 0.55f else 0.45f) * (0.25f + 0.75f * tw)), radius = (1.4f + 1.6f * rnd(i, 4)) * sc * 1.6f, center = Offset(x, y))
                }
            }
            WeatherAmbience.Kind.CLOUDS, WeatherAmbience.Kind.FOG -> {
                val col = if (dark) Color(0xFFD8D0E0) else Color.White
                val a = if (dark) 0.07f else 0.30f
                for (i in 0 until 4) {
                    val cw = (320f + 160f * rnd(i, 1)) * sc
                    val speed = (14f + 10f * rnd(i, 2)) * sc
                    val x = ((rnd(i, 3) * (w + cw) + t * speed) % (w + 2 * cw)) - cw
                    val y = h * (0.08f + 0.17f * i + 0.05f * rnd(i, 4))
                    for (k in 0 until 4) {
                        val px = x + cw * (k / 3f) ; val py = y + (if (k == 1 || k == 2) -0.16f else 0f) * cw
                        val rr = cw * (if (k == 1 || k == 2) 0.30f else 0.22f)
                        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.5f), Color.Transparent), center = Offset(px, py), radius = rr), radius = rr, center = Offset(px, py))
                    }
                }
                if (kind == WeatherAmbience.Kind.FOG) {
                    for (b in 0 until 3) {
                        val y = h * (0.25f + 0.25f * b) + 40f * sc * kotlin.math.sin(twoPi * t / (18f + 4f * b) + b)
                        val bh = h * 0.22f
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = if (dark) 0.08f else 0.26f), Color.Transparent), startY = y - bh / 2, endY = y + bh / 2),
                            topLeft = Offset(0f, y - bh / 2), size = androidx.compose.ui.geometry.Size(w, bh))
                    }
                }
            }
            WeatherAmbience.Kind.RAIN -> rain(70, 0.22f)
            WeatherAmbience.Kind.THUNDER -> {
                rain(90, 0.24f)
                val ph = t % 9f
                if (ph < 0.35f) {
                    val f = if (ph < 0.12f) ph / 0.12f else (0.35f - ph) / 0.23f
                    drawRect(Color.White.copy(alpha = (if (dark) 0.10f else 0.22f) * f.coerceIn(0f, 1f)))
                }
            }
            WeatherAmbience.Kind.SNOW -> {
                for (i in 0 until 46) {
                    val speed = h / (10f + 6f * rnd(i, 1))
                    val r = (2.2f + 2.4f * rnd(i, 2)) * sc * 1.4f
                    val y = ((rnd(i, 3) * h + t * speed) % (h + 20f)) - 10f
                    val x = rnd(i, 4) * w + 22f * sc * kotlin.math.sin(twoPi * t / (5f + 3f * rnd(i, 5)) + i)
                    if (!dark) drawCircle(Color(0xFF9C8CB8).copy(alpha = 0.22f), radius = r + 1.2f * sc, center = Offset(x, y))
                    drawCircle(Color.White.copy(alpha = if (dark) 0.55f else 0.92f), radius = r, center = Offset(x, y))
                }
            }
            WeatherAmbience.Kind.NONE -> {}
        }
    }
}
