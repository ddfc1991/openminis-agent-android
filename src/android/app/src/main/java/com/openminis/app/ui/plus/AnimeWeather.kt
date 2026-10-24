package com.openminis.app.ui.plus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shapes follow the approved board /workspace/work/design387/board2.jpg (mk2.py SVGs, 46×46 units).
 * [Mines-38.7] user 2026-10-15 「那个天气能不能再二次元一点？动画动态一点」: a small hand-drawn, anime-style weather sticker
 * for the home weather chip — sun with a round face and blush whose rays turn slowly, soft clouds drifting, rain drops
 * falling, snowflakes, a blinking crescent moon with twinkling stars, a cloud with a little flash now and then.
 * Pastel fills + rounded outline, matched to the warm porcelain theme. Light: one Canvas, redrawn per frame only while
 * the app is RESUMED; still when the system turns animations off (ANIMATOR_DURATION_SCALE = 0 / 减少动态).
 * [icon] is HomeGreeting.describe()'s line-icon name (sun, moon, cloud-sun, cloud, cloud-fog, cloud-drizzle, cloud-rain,
 * cloud-snow, cloud-lightning). No semantics of its own: the chip reads 「天气：晴，26 度」.
 */
object AnimeWeather {
    val Outline = Color(0xFF8A6A5C)
    val Sun = Color(0xFFFFD27A)
    val SunRay = Color(0xFFFFB86B)
    val Blush = Color(0xFFFF9EAA)
    val CloudFill = Color(0xFFFFFFFF)
    val CloudShade = Color(0xFFE6ECF7)
    val Drop = Color(0xFF8CC4FF)
    val Snow = Color(0xFFBFDFFF)
    val Moon = Color(0xFFFFE8A3)
    val Star = Color(0xFFFFD36E)
    val Bolt = Color(0xFFFFD24D)

    /** true when the system says animations are off */
    fun still(c: android.content.Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** smoke / self-test: the time the last frame was drawn at (seconds since start) */
    @Volatile var lastT: Float = 0f
}

@Composable
fun AnimeWeatherIcon(icon: String, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val still = remember { AnimeWeather.still(ctx) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var t by remember { mutableFloatStateOf(0.35f) }
    LaunchedEffect(owner, still) {
        if (still) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val base = withFrameMillis { it } - (t * 1000).toLong()
            while (true) { MotionGate.awaitRun(); withFrameMillis { now -> t = (now - base) / 1000f; AnimeWeather.lastT = t } }
        }
    }
    Canvas(modifier.size(size)) {
        val time = t
        val u = this.size.minDimension / 46f
        // everything below is drawn in the board's 46×46 SVG units
        scale(u, u, pivot = Offset.Zero) {
            when (icon) {
                "sun" -> wxSun(time)
                "moon" -> wxMoon(time)
                "cloud-sun" -> { translate(-6f, -6f) { scale(0.72f, 0.72f, pivot = Offset(23f, 23f)) { wxSun(time) } }; translate(5f, 6f) { scale(0.78f, 0.78f, pivot = Offset(23f, 23f)) { wxCloud(time, drops = 0) } } }
                "cloud-drizzle", "cloud-rain" -> wxCloud(time, drops = 3)
                "cloud-snow" -> wxCloud(time, drops = 0, snow = true)
                "cloud-lightning" -> wxCloud(time, drops = 0, bolt = true)
                else -> wxCloud(time, drops = 0, drift = true)
            }
        }
    }
}

private val sunBrown = Color(0xFF6B3A1E)
private val rayC = Color(0xFFF2A93B)
private val sunFill = Color(0xFFFFD36A)
private val blush = Color(0xFFFF9A8B)
private val cloudFill = Color(0xFFEAF2FB)
private val cloudLine = Color(0xFF7FA6D6)
private val cloudInk = Color(0xFF3B4A63)
private val dropC = Color(0xFF6FA8E8)
private val moonFill = Color(0xFFFFE59A)
private val moonLine = Color(0xFFE8B64A)
private val moonInk = Color(0xFF6B4A1E)
private val starC = Color(0xFFFFF3C4)

private val cloudD = PathParser().parsePathString("M12 26 a8 8 0 0 1 3-15 a10 10 0 0 1 18 2 a7 7 0 0 1 1 13z").toPath()
private val moonD = PathParser().parsePathString("M28 6 a16 16 0 1 0 12 26 a13 13 0 0 1 -12 -26z").toPath()
private val dropD = PathParser().parsePathString("M0 0 q2 4 0 6 q-2 -2 0 -6z").toPath()
private val sparkD = PathParser().parsePathString("M38 6 l1.2 2.6 2.6 1.2 -2.6 1.2 -1.2 2.6 -1.2 -2.6 -2.6 -1.2 2.6 -1.2z").toPath()

private fun DrawScope.arcLine(x: Float, y: Float, dx: Float, dy: Float, c: Color, w: Float) {
    // SVG "M x y q dx/2 dy dx 0"
    val p = Path().apply { moveTo(x, y); quadraticTo(x + dx / 2f, y + dy, x + dx, y) }
    drawPath(p, c, style = Stroke(width = w, cap = StrokeCap.Round))
}

/** 腮红笑脸太阳: rays turn slowly (one turn / 15 s) and breathe a little */
private fun DrawScope.wxSun(t: Float) {
    val c = Offset(23f, 23f)
    rotate((t * 24f) % 360f, c) {
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat()
            val r1 = 16f - 0.6f * sin(t * 3f + i); val r2 = 21f
            drawLine(rayC, Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
    drawCircle(sunFill, 12f, c)
    drawCircle(rayC, 12f, c, style = Stroke(2f))
    arcLine(18f, 22f, 3f, -2f, sunBrown, 1.8f); arcLine(25f, 22f, 3f, -2f, sunBrown, 1.8f)
    drawOval(blush.copy(alpha = 0.8f), Offset(14.1f, 24.5f), Size(4.8f, 3f))
    drawOval(blush.copy(alpha = 0.8f), Offset(27.1f, 24.5f), Size(4.8f, 3f))
    arcLine(21f, 27f, 4f, 2f, sunBrown, 1.6f)
}

/** 圆脸小雨云 + 雨滴 (drops fall and fade, looping); also the plain / snow / thunder cloud */
private fun DrawScope.wxCloud(t: Float, drops: Int, snow: Boolean = false, bolt: Boolean = false, drift: Boolean = false) {
    if (bolt) {
        val ph = t % 2.6f
        val a = if (ph < 0.22f) 1f else 0.3f
        val p = Path().apply { moveTo(24f, 27f); lineTo(19f, 36f); lineTo(23f, 36f); lineTo(20f, 44f); lineTo(28f, 33f); lineTo(24f, 33f); lineTo(27f, 27f); close() }
        drawPath(p, Color(0xFFFFD24D).copy(alpha = a))
    }
    if (drops > 0) {
        val xs = floatArrayOf(15f, 23f, 31f); val y0 = floatArrayOf(28f, 30f, 28f)
        for (i in 0 until drops) {
            val ph = (t * 1.1f + i * 0.33f) % 1f
            translate(xs[i], y0[i] + ph * 11f) { drawPath(dropD, dropC.copy(alpha = (1.15f - ph).coerceIn(0f, 1f))) }
        }
    }
    if (snow) {
        for (i in 0 until 3) {
            val ph = (t * 0.45f + i * 0.33f) % 1f
            val x = 15f + i * 8f + sin(t * 2f + i) * 1.5f; val y = 29f + ph * 13f
            rotate(t * 40f + i * 30f, Offset(x, y)) {
                for (k in 0 until 3) { val a = (k * PI / 3).toFloat()
                    drawLine(dropC.copy(alpha = 1f - ph * 0.7f), Offset(x - cos(a) * 2.6f, y - sin(a) * 2.6f), Offset(x + cos(a) * 2.6f, y + sin(a) * 2.6f), 1.4f, cap = StrokeCap.Round) }
            }
        }
    }
    val dx = if (drift) sin(t * 1.2f) * 2.5f else sin(t * 1.2f) * 0.8f
    translate(dx, if (drops > 0 || snow || bolt) 0f else 5f) {
        drawPath(cloudD, cloudFill)
        drawPath(cloudD, cloudLine, style = Stroke(2f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(cloudInk, 1.4f, Offset(19f, 19f)); drawCircle(cloudInk, 1.4f, Offset(27f, 19f))
        arcLine(21f, 22f, 4f, 1.6f, cloudInk, 1.4f)
        drawOval(Color(0xFFFFAFA3).copy(alpha = 0.8f), Offset(14f, 20.8f), Size(4f, 2.4f))
        drawOval(Color(0xFFFFAFA3).copy(alpha = 0.8f), Offset(28f, 20.8f), Size(4f, 2.4f))
    }
}

/** 眨眼弯月 + 星星 (stars twinkle; the open eye blinks every ~3.2 s) */
private fun DrawScope.wxMoon(t: Float) {
    drawPath(moonD, moonFill)
    drawPath(moonD, moonLine, style = Stroke(2f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    arcLine(17f, 24f, 3f, 1.6f, moonInk, 1.7f)            // closed, smiling eye (wink)
    val blink = (t % 3.2f) < 0.18f
    if (blink) drawLine(moonInk, Offset(24f, 23.6f), Offset(27f, 23.6f), 1.7f, cap = StrokeCap.Round)
    else drawLine(moonInk, Offset(24f, 24f), Offset(27f, 22.8f), 1.7f, cap = StrokeCap.Round)
    drawOval(blush.copy(alpha = 0.8f), Offset(13.8f, 26.7f), Size(4.4f, 2.6f))
    val k1 = 0.55f + 0.45f * sin(t * 3.1f)
    val k2 = 0.55f + 0.45f * sin(t * 2.3f + 1.7f)
    val k3 = 0.55f + 0.45f * sin(t * 3.7f + 3.1f)
    scale(0.8f + 0.25f * k1, 0.8f + 0.25f * k1, pivot = Offset(38f, 9.8f)) { drawPath(sparkD, starC.copy(alpha = k1.coerceIn(0.35f, 1f))) }
    drawCircle(starC.copy(alpha = k2.coerceIn(0.3f, 1f)), 1.3f, Offset(42f, 20f))
    drawCircle(starC.copy(alpha = k3.coerceIn(0.3f, 1f)), 1f, Offset(34f, 16f))
}
