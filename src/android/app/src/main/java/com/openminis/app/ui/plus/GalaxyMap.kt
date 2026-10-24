package com.openminis.app.ui.plus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.lifecycle.repeatOnLifecycle

/**
 * [Mines-38.8 v5] 知识库 「星空脑图」 (user approved mock3/知识库-脑图-星空v3 + 知识库-星空闪烁, 2026-10-16):
 * deep-space backdrop with ~400 twinkling stars (3 s sine loop per star, random phase, a few with a cross sparkle),
 * one colour nebula per topic (soft radial glow + thin neuron-like filaments) that breathes on the same 3 s loop.
 * Everything is drawn in ONE Canvas per layer (no per-star composables) and ticks at ≤ 30 fps from the frame clock,
 * so it keeps moving when battery saver sets the animator scale to 0 (realme) without costing much.
 */
object Galaxy {
    val Space = Color(0xFF080712)
    val Ink = Color(0xFFF6F2EE)
    val Ink2 = Color(0xFFBEB8C4)
    val Ink3 = Color(0xFFAAA4B4)
    val PillFill = Color(0xFF1C1828).copy(alpha = 0.76f)   // [Mines-38.9.4] a little denser frosted fill: labels read over the glow
    val Peach = Color(0xFFFFBE96)
    private val named = listOf(
        "开发项目" to Color(0xFFFF8A4C), "AI工具" to Color(0xFFC478FF), "安全研究" to Color(0xFF40E0D0),
        "Mines开发" to Color(0xFFFFCE54), "生活与其他" to Color(0xFFFF5CA8), "资料与链接" to Color(0xFF5AA0FF),
        "服务器" to Color(0xFF8CE66E), "AI会议" to Color(0xFFFF786E),
    )
    private val palette = named.map { it.second }
    private val extras = listOf(Color(0xFFFFB347), Color(0xFF7AD7F0), Color(0xFFB8F27A), Color(0xFFF07AE0), Color(0xFF9D8CFF), Color(0xFFFFE07A))

    /** The topic's own colour: the 8 named topics keep theirs; any other topic takes the next unused colour by rank. */
    fun colorsFor(topics: List<String>): Map<String, Color> {
        val out = LinkedHashMap<String, Color>()
        val key = { s: String -> s.replace(" ", "").lowercase() }
        topics.forEach { t -> named.firstOrNull { key(it.first) == key(t) }?.let { out[t] = it.second } }
        // [Mines-38.8 v5d] any other topic (e.g. 交易) gets a stable colour of its own: picked by the topic's name hash
        // from a bright extra palette (+ the unused named colours), so it stays the same as other topics come and go
        topics.forEach { t ->
            if (t !in out) {
                val pool = extras + palette
                val start = Math.floorMod(key(t).hashCode(), extras.size)
                val order = (pool.indices).map { pool[(start + it) % pool.size] }
                out[t] = order.firstOrNull { it !in out.values } ?: pool[start]
            }
        }
        return out
    }

    /** Stars as fractions of the screen: x, y, radius (dp), phase, speed (1 or 2 cycles per 3 s, so the loop is seamless). */
    val stars: FloatArray by lazy {
        val r = java.util.Random(7)
        val radii = floatArrayOf(0.35f, 0.45f, 0.55f, 0.75f, 1.05f)
        FloatArray(420 * 5).also { a ->
            for (i in 0 until 420) {
                a[i * 5] = r.nextFloat(); a[i * 5 + 1] = r.nextFloat(); a[i * 5 + 2] = radii[r.nextInt(radii.size)]
                a[i * 5 + 3] = (r.nextFloat() * 2 * Math.PI).toFloat(); a[i * 5 + 4] = if (r.nextInt(3) == 0) 2f else 1f
            }
        }
    }

    /** Neuron-like filaments around one topic (random walks, in px), built once per topic and size. */
    fun filaments(seed: Int, count: Int, density: Density): Path {
        val r = java.util.Random(1000L + seed)
        val px = density.density
        val p = Path()
        val n = 26 + 4 * count.coerceAtMost(8)
        repeat(n) {
            var x = 0f; var y = 0f
            var ang = (r.nextFloat() * 2 * Math.PI).toFloat()
            val len = (24f + r.nextFloat() * (50f + 4f * count.coerceAtMost(8))) * px
            p.moveTo(x, y)
            repeat(10) {
                ang += (r.nextFloat() - 0.5f) * 1.2f
                x += kotlin.math.cos(ang) * len / 10f; y += kotlin.math.sin(ang) * len / 10f
                p.lineTo(x, y)
            }
        }
        return p
    }
}

/**
 * [Mines-38.9] user 2026-10-16 「星空脑图 more animated」: the map's camera pan (px) for the star parallax, and whether
 * the page is drawn as one still frame (battery saver or 「移除动画」 / animator scale 0 → no ticking at all).
 */
object GalaxyMotion {
    var camX by androidx.compose.runtime.mutableFloatStateOf(0f)
    var camY by androidx.compose.runtime.mutableFloatStateOf(0f)
    fun still(c: android.content.Context): Boolean = AnimeWeather.still(c) ||
        runCatching { c.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
    /** smoke / self-test: frames ticked so far and the last time value */
    @Volatile var ticks: Long = 0
    @Volatile var stillSeen: Boolean = false
}

/** Galaxy time in seconds, ticking at ≤ 30 fps from the frame clock while the page is resumed (still → stays 0). */
@Composable
fun rememberGalaxyTime(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(owner) {
        if (GalaxyMotion.still(ctx)) { GalaxyMotion.stillSeen = true; return@LaunchedEffect }
        GalaxyMotion.stillSeen = false
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var start = -1L; var last = 0L
            while (true) androidx.compose.runtime.withFrameMillis { now ->
                if (start < 0) start = now
                if (now - last >= 33L) { last = now; GalaxyMotion.ticks++; t.floatValue = ((now - start) % 600_000L) / 1000f }
            }
        }
    }
    return t
}

/** Deep-space page backdrop with the twinkling star field (drawn in one Canvas). */
@Composable
fun GalaxyBackdrop(modifier: Modifier = Modifier) {
    val time = rememberGalaxyTime()
    Box(modifier.background(Galaxy.Space)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.value
            // faint warm haze in the middle of the page, like the mock's centre glow
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0x14FFAA78), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.52f), radius = size.width * 0.8f),
                radius = size.width * 0.8f, center = Offset(size.width / 2f, size.height * 0.52f))
            val s = Galaxy.stars
            val star = Color(0xFFFFFAF0)
            val dp = density
            // [Mines-38.9] three depth layers by star size: panning the map shifts the far stars less than the near ones
            val pcx = GalaxyMotion.camX; val pcy = GalaxyMotion.camY
            for (i in 0 until s.size / 5) {
                val depth = when { s[i * 5 + 2] >= 0.75f -> 0.10f; s[i * 5 + 2] >= 0.5f -> 0.05f; else -> 0.02f }
                val x = Math.floorMod((s[i * 5] * size.width + pcx * depth).toInt(), size.width.toInt().coerceAtLeast(1)).toFloat()
                val y = Math.floorMod((s[i * 5 + 1] * size.height + pcy * depth).toInt(), size.height.toInt().coerceAtLeast(1)).toFloat()
                val r = s[i * 5 + 2] * dp
                val a = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * s[i * 5 + 4] * t / 3f + s[i * 5 + 3]))
                val c = Offset(x, y)
                if (r >= 0.7f * dp) drawCircle(star.copy(alpha = a * 0.18f), radius = r * 2.6f, center = c)
                drawCircle(star.copy(alpha = a), radius = r, center = c)
                if (r >= 1.0f * dp && a > 0.85f) {
                    val l = r * 4f; val sc = star.copy(alpha = a * 0.5f)
                    drawLine(sc, Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.6f * dp)
                    drawLine(sc, Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.6f * dp)
                }
            }
            // [Mines-38.9] a shooting star every ~8–15 s (cycle 11.5 s, start jittered by 0–3.5 s), 0.9 s across
            val cyc = 11.5f; val k = kotlin.math.floor(t / cyc).toInt()
            val rnd = java.util.Random(31L * k + 7)
            val jit = rnd.nextFloat() * 3.5f; val local = t - k * cyc - jit
            if (t > 0f && local in 0f..0.9f) {
                val u = local / 0.9f
                val sx = (0.15f + 0.6f * rnd.nextFloat()) * size.width; val sy = (0.05f + 0.35f * rnd.nextFloat()) * size.height
                val ang = Math.toRadians((20 + rnd.nextInt(25)).toDouble()); val dist = size.width * 0.45f
                val dx = kotlin.math.cos(ang).toFloat() * dist; val dy = kotlin.math.sin(ang).toFloat() * dist
                val head = Offset(sx + dx * u, sy + dy * u); val tail = Offset(sx + dx * (u - 0.22f).coerceAtLeast(0f), sy + dy * (u - 0.22f).coerceAtLeast(0f))
                val fade = kotlin.math.sin(Math.PI.toFloat() * u)
                drawLine(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.Transparent, star.copy(alpha = 0.85f * fade)), tail, head), tail, head,
                    strokeWidth = 1.4f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(star.copy(alpha = 0.9f * fade), radius = 1.6f * dp, center = head)
                drawCircle(star.copy(alpha = 0.25f * fade), radius = 5f * dp, center = head)
            }
        }
    }
}
