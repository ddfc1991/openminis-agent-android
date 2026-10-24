package com.openminis.app.ui.plus

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.ChatKnowledge
import com.openminis.app.plus.NexusCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * [Mines-38.9.12] 知识库 as the approved cyan-white holographic HUD (mock-v3 kb_v3b / kb-motion2): rotating 900-point
 * sphere with latitude / meridian lines, the 「NEXUS CORE」 energy core (pulsing, 3 tilted rotating orbit rings with
 * orbiting points — no avatar), up to 8 topic nodes with labels / counts, data particles along curved links, radar sweep,
 * perspective grid floor, scan beam. Tap a topic: camera pushes in, the others dim, the detail panel slides up with its
 * entries one by one, 「在对话中引用」 / 「生成主题摘要」.
 *
 * Battery: the frame loop only runs while the page is RESUMED and no glass overlay is open (MotionGate); 60 fps cap,
 * 15 fps in battery saver. All geometry lives in reusable FloatArrays (NexusCore) — the frame allocates nothing.
 */
object NexusHudState {
    /** smoke: frames drawn so far, last selected topic */
    @Volatile var frames: Long = 0
    @Volatile var selected: String = ""
    /** smoke: ask the page to focus topic i (-1 = none); -2 = no request */
    val request = kotlinx.coroutines.flow.MutableStateFlow(-2)
}

private val HudCyan = Color(0xFF7AE8FF)
private val HudInk = Color(0xFFE6F5FF)
private val HudMuted = Color(0xFF6EB4D7)
private val HudBg = Color(0xFF060A14)
private val TopicColors = intArrayOf(0xFF96FFC8.toInt(), 0xFFC8AAFF.toInt(), 0xFFFFD678.toInt(), 0xFFFFA0C8.toInt(),
    0xFFFFB482.toInt(), 0xFFB4AAFF.toInt(), 0xFF8CC8FF.toInt(), 0xFF78F0E6.toInt())

private fun withA(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

private class HudPaints {
    val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
    val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
    val path = android.graphics.Path()
    val rect = RectF()
    var beam: android.graphics.Shader? = null
    var beamH = 0f
}

private class HudGeo(n: Int, counts: IntArray) {
    val sphere = NexusCore.sphere()
    val sRot = FloatArray(sphere.size)
    val sProj = FloatArray(sphere.size / 3 * 4)
    val front = FloatArray(sphere.size / 3 * 2)
    val back = FloatArray(sphere.size / 3 * 2)
    val rings = NexusCore.rings(72)
    val rRot = FloatArray(72 * 3)
    val rProj = FloatArray(72 * 4)
    val segF = FloatArray(rings.size * 71 * 4)
    val segB = FloatArray(rings.size * 71 * 4)
    val orbit = NexusCore.orbit(120)
    val oRot = FloatArray(orbit.size)
    val oProj = FloatArray(120 * 4)
    val dirs = NexusCore.orbitDirs(n).also { d -> for (i in d.indices) d[i] *= NexusCore.NODE_R }   // [38.9.12.1] out on the orbit
    val nRot = FloatArray(dirs.size)
    val nProj = FloatArray(dirs.size / 3 * 4)
    val subs = NexusCore.subs(NexusCore.orbitDirs(n), counts).also { (xyz, _) -> for (i in xyz.indices) xyz[i] *= NexusCore.NODE_R / NexusCore.SHELL }
    val subRot = FloatArray(subs.first.size)
    val subProj = FloatArray(subs.first.size / 3 * 4)
    val subPts = FloatArray(subs.first.size / 3 * 2)
    val order = IntArray(n)
    val stars = java.util.Random(11L).let { r -> FloatArray(220 * 4) { i -> when (i % 4) { 0, 1 -> r.nextFloat(); 2 -> 0.4f + r.nextFloat(); else -> r.nextFloat() * 6.28f } } }
}

/** One topic cluster on the HUD. */
data class NexusTopic(val name: String, val entries: List<ChatKnowledge.Entry>)

@Composable
fun NexusKnowledgePage(entries: List<ChatKnowledge.Entry>, onBack: () -> Unit, onList: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val topics = remember(entries) { KnowledgeClusters.cluster(entries).map { NexusTopic(it.first, it.second.sortedByDescending { e -> e.at }) } }
    val n = topics.size.coerceAtMost(8)
    val geo = remember(topics) { HudGeo(n, IntArray(n) { topics[it].entries.size }) }
    var query by remember { mutableStateOf("") }
    var sel by remember { mutableIntStateOf(-1) }
    val focus = remember { Animatable(0f) }
    val time = remember { mutableFloatStateOf(0f) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val saver by MotionGate.powerSave(ctx)
    LaunchedEffect(owner, saver) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L; var prev = -1L
            val gap = NexusCore.frameGapMs(saver)
            while (true) {
                if (MotionGate.awaitRun() > 0L) prev = -1L
                androidx.compose.runtime.withFrameMillis { now ->
                    if (prev < 0) prev = now
                    if (now - last >= gap) {
                        val dt = ((now - prev).coerceIn(0L, 100L)) / 1000f
                        prev = now; last = now
                        time.floatValue = (time.floatValue + dt) % 3600f
                        NexusHudState.frames++
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { NexusHudState.request.collect { r -> if (r >= -1) { sel = if (r < n) r else -1; NexusHudState.request.value = -2 } } }
    LaunchedEffect(sel) {
        NexusHudState.selected = topics.getOrNull(sel)?.name.orEmpty()
        focus.animateTo(if (sel >= 0) 1f else 0f, tween(if (sel >= 0) 1400 else 700, easing = FastOutSlowInEasing))
    }
    val q = query.trim().lowercase()
    val matches = remember(q, topics) {
        if (q.isEmpty()) null else topics.indices.filter { i -> topics[i].name.lowercase().contains(q) ||
            topics[i].entries.any { e -> (e.title + e.summary + e.facts.joinToString() + e.decisions.joinToString()).lowercase().contains(q) } }.toSet()
    }
    val paints = remember { HudPaints() }
    val dp = density.density
    // [38.9.12.1] the viz fills the space between the search field and the bottom panel (measured)
    var vizTop by remember { mutableFloatStateOf(0f) }
    var vizBot by remember { mutableFloatStateOf(0f) }
    val totalEntries = entries.size
    val byModel = entries.count { it.byModel }
    val lastAt = entries.maxOfOrNull { it.at } ?: 0L

    var boxTop by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(HudBg).onGloballyPositioned { boxTop = it.boundsInRoot().top }) {
        // ── the HUD canvas (full page; the header and panels sit on top) ──
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "知识库星图" }
            .pointerInput(geo) {
                detectTapGestures { p ->
                    val i = NexusCore.hit(geo.nProj, n, p.x, p.y, 34 * dp)
                    sel = if (i >= 0 && i != sel) i else -1
                }
            }) {
            val t = time.floatValue
            val z = NexusCore.ease(focus.value)
            val w = size.width; val h = size.height
            val top0 = if (vizTop > 0f) vizTop - boxTop else h * 0.20f
            val bot0 = if (vizBot - boxTop > top0 + 100f) vizBot - boxTop else h * 0.84f
            val (cy, scaleL, floorY) = NexusCore.layout(w, top0, bot0)
            val cx = w / 2f
            drawIntoCanvas { cv ->
                val c = cv.nativeCanvas
                val P = paints
                // background glow
                drawCircle(Brush.radialGradient(listOf(Color(0xFF12243F), Color(0xFF0A1528), HudBg), center = Offset(cx, cy), radius = w * 0.95f),
                    radius = w * 1.2f, center = Offset(cx, cy))
                // stars
                val st = geo.stars
                for (k in 0 until 220) {
                    val a = 0.5f * (0.6f + 0.4f * sin(t * 1.3f + st[k * 4 + 3]))
                    P.fill.color = withA(0xFFC8E1FF.toInt(), a * (0.3f + st[k * 4 + 2] * 0.4f))
                    c.drawCircle(st[k * 4] * w, st[k * 4 + 1] * h, st[k * 4 + 2] * 0.7f * dp, P.fill)
                }
                // perspective floor grid, slowly moving toward the viewer
                val hor = floorY   // [38.9.12.1] grid floor directly under the sphere
                P.line.strokeWidth = 1f
                for (k in 0 until 14) {
                    val yy = Math.pow(((k + (t * 0.4f) % 1f) / 14f).toDouble(), 2.2).toFloat(); val y = hor + yy * (h - hor)
                    P.line.color = withA(0xFF5ABEFF.toInt(), 0.05f + 0.16f * yy); c.drawLine(0f, y, w, y, P.line)
                }
                P.line.color = withA(0xFF5ABEFF.toInt(), 0.10f)
                for (k in -12..12) c.drawLine(cx + k * 14 * dp, hor, cx + k * 110 * dp, h, P.line)

                // camera
                val ay = 0.4f + NexusCore.ORBIT_RAD_S * t; val ax = -0.32f + 0.04f * sin(t * 0.5f)
                val scale0 = scaleL
                NexusCore.rotate(geo.dirs, geo.nRot, ay * 0.5f, ax - 0.15f)   // [38.9.12.1] same frame as the orbit: nodes ride on it
                var fx = 0f; var fy = 0f
                if (sel in 0 until n && z > 0f) {
                    val o = NexusCore.focusOffset(geo.nRot[sel * 3], geo.nRot[sel * 3 + 1], scale0, z); fx = o.first; fy = o.second
                }
                val scale = scale0 * (1f + 0.45f * z)
                val dim = 1f - 0.75f * z
                val qdim = if (matches != null) 0.55f else 1f

                // sphere points
                NexusCore.rotate(geo.sphere, geo.sRot, ay, ax)
                NexusCore.project(geo.sRot, geo.sProj, cx, cy, scale, fx, fy)
                var nf = 0; var nb = 0
                for (i in 0 until geo.sphere.size / 3) {
                    val x = geo.sProj[i * 4]; val y = geo.sProj[i * 4 + 1]
                    if (geo.sProj[i * 4 + 2] < 0f) { geo.front[nf++] = x; geo.front[nf++] = y } else { geo.back[nb++] = x; geo.back[nb++] = y }
                }
                P.pt.strokeWidth = 1.4f * dp; P.pt.color = withA(0xFF96DCFF.toInt(), 0.18f * dim * qdim); c.drawPoints(geo.back, 0, nb, P.pt)
                P.pt.strokeWidth = 2.1f * dp; P.pt.color = withA(0xFF96DCFF.toInt(), 0.60f * dim * qdim); c.drawPoints(geo.front, 0, nf, P.pt)

                // latitude / meridian lines
                var sf = 0; var sb = 0
                for (ring in geo.rings) {
                    NexusCore.rotate(ring, geo.rRot, ay, ax); NexusCore.project(geo.rRot, geo.rProj, cx, cy, scale, fx, fy)
                    for (j in 0 until 71) {
                        val arr = if (geo.rProj[j * 4 + 2] < 0f) geo.segF else geo.segB
                        val o = if (arr === geo.segF) sf else sb
                        arr[o] = geo.rProj[j * 4]; arr[o + 1] = geo.rProj[j * 4 + 1]; arr[o + 2] = geo.rProj[(j + 1) * 4]; arr[o + 3] = geo.rProj[(j + 1) * 4 + 1]
                        if (arr === geo.segF) sf += 4 else sb += 4
                    }
                }
                P.line.strokeWidth = 1f
                P.line.color = withA(HudCyan.toArgb(), 0.07f * dim); c.drawLines(geo.segB, 0, sb, P.line)
                P.line.color = withA(HudCyan.toArgb(), 0.22f * dim); c.drawLines(geo.segF, 0, sf, P.line)

                // radar sweep (expanding flat ring every 3.5 s)
                val rt = (t % 3.5f) / 3.5f; val rr = scale * (0.9f + 1.1f * rt)
                P.line.strokeWidth = 1.6f * dp; P.line.color = withA(HudCyan.toArgb(), 0.6f * (1 - rt) * dim)
                P.rect.set(cx + fx - rr, cy + fy - rr * 0.36f, cx + fx + rr, cy + fy + rr * 0.36f); c.drawOval(P.rect, P.line)
                // [38.9.12.1] visible radar sweep: a rotating wedge with a fading trail on the orbit plane
                val sr = scale * 1.85f
                P.rect.set(cx + fx - sr, cy + fy - sr * 0.36f, cx + fx + sr, cy + fy + sr * 0.36f)
                val sa = Math.toDegrees((t * 0.9).toDouble()).toFloat() % 360f
                for (k in 0 until 8) { P.fill.color = withA(HudCyan.toArgb(), 0.10f * (1f - k / 8f) * dim); c.drawArc(P.rect, sa - k * 5f, 5f, true, P.fill) }
                P.line.strokeWidth = 1.4f * dp; P.line.color = withA(0xFFBFF6FF.toInt(), 0.7f * dim)
                val sRad = Math.toRadians(sa.toDouble() + 5.0)
                c.drawLine(cx + fx, cy + fy, cx + fx + sr * cos(sRad).toFloat(), cy + fy + sr * 0.36f * sin(sRad).toFloat(), P.line)

                // tilted outer orbit
                NexusCore.rotate(geo.orbit, geo.oRot, ay * 0.5f, ax - 0.15f); NexusCore.project(geo.oRot, geo.oProj, cx, cy, scale, fx, fy)
                P.line.strokeWidth = 1f
                for (j in 0 until 119) {
                    P.line.color = withA(HudCyan.toArgb(), (if (geo.oProj[j * 4 + 2] < 0f) 0.27f else 0.09f) * dim)
                    c.drawLine(geo.oProj[j * 4], geo.oProj[j * 4 + 1], geo.oProj[(j + 1) * 4], geo.oProj[(j + 1) * 4 + 1], P.line)
                }

                // nodes (back to front)
                NexusCore.project(geo.nRot, geo.nProj, cx, cy, scale, fx, fy)
                val (subXyz, subOwner) = geo.subs
                NexusCore.rotate(subXyz, geo.subRot, ay * 0.5f, ax - 0.15f); NexusCore.project(geo.subRot, geo.subProj, cx, cy, scale, fx, fy)
                for (i in 0 until n) geo.order[i] = i
                // insertion sort by depth (far first) — n ≤ 8
                for (i in 1 until n) { var j = i; while (j > 0 && geo.nProj[geo.order[j - 1] * 4 + 2] < geo.nProj[geo.order[j] * 4 + 2]) { val tmp = geo.order[j]; geo.order[j] = geo.order[j - 1]; geo.order[j - 1] = tmp; j-- } }
                val coreX = cx + fx; val coreY = cy + fy
                for (oi in 0 until n) {
                    val k = geo.order[oi]
                    val col = TopicColors[k % TopicColors.size]
                    val nx = geo.nProj[k * 4]; val ny = geo.nProj[k * 4 + 1]; val nz = geo.nProj[k * 4 + 2]; val nfk = geo.nProj[k * 4 + 3]
                    val isSel = k == sel
                    val dk = if (isSel) 1f else dim
                    val qk = if (matches == null || k in matches) 1f else 0.3f
                    val al = (if (nz < 0.2f) 1f else 0.62f) * dk * qk
                    // curved link with brighter end
                    P.line.strokeWidth = 1.2f * dp
                    var px0 = coreX; var py0 = coreY
                    for (s in 1..16) {
                        val tt = s / 16f; val (bx, by) = NexusCore.arc(coreX, coreY, nx, ny, 30 * dp, tt)
                        P.line.color = withA(col, 0.40f * al * (0.3f + 0.7f * tt)); c.drawLine(px0, py0, bx, by, P.line); px0 = bx; py0 = by
                    }
                    // data particles
                    for (qn in 0 until 3) {
                        val tt = (t * 0.45f + k * 0.13f + qn / 3f) % 1f
                        val (qx, qy) = NexusCore.arc(coreX, coreY, nx, ny, 30 * dp, tt)
                        P.fill.color = withA(col, 0.30f * al); c.drawCircle(qx, qy, 5f * dp, P.fill)
                        P.fill.color = withA(col, 0.65f * al); c.drawCircle(qx, qy, 2.6f * dp, P.fill)
                        P.fill.color = withA(0xFFFFFFFF.toInt(), al); c.drawCircle(qx, qy, 1.4f * dp, P.fill)
                    }
                    // sub-entry specks
                    var cnt = 0
                    for (s in subOwner.indices) if (subOwner[s] == k) { geo.subPts[cnt++] = geo.subProj[s * 4]; geo.subPts[cnt++] = geo.subProj[s * 4 + 1] }
                    P.pt.strokeWidth = (if (isSel && z > 0f) 3.4f else 2.6f) * dp; P.pt.color = withA(col, 0.78f * al)
                    c.drawPoints(geo.subPts, 0, cnt, P.pt)
                    if (isSel && z > 0.3f) {
                        P.line.strokeWidth = 1f; P.line.color = withA(col, 0.24f * z)
                        var s2 = 0; while (s2 < cnt) { c.drawLine(geo.subPts[s2], geo.subPts[s2 + 1], nx, ny, P.line); s2 += 2 }
                    }
                    // marker: glow, diamond, ring
                    val sz = NexusCore.nodeSize(topics[k].entries.size, dp, nfk) * (1f + 0.25f * z * (if (isSel) 1f else 0f))
                    val pulse = 1f + 0.12f * sin(t * 2.4f + k)
                    P.fill.color = withA(col, 0.14f * al); c.drawCircle(nx, ny, sz * 2.6f, P.fill)
                    P.fill.color = withA(col, 0.26f * al); c.drawCircle(nx, ny, sz * 1.6f, P.fill)
                    P.path.reset(); P.path.moveTo(nx, ny - sz); P.path.lineTo(nx + sz, ny); P.path.lineTo(nx, ny + sz); P.path.lineTo(nx - sz, ny); P.path.close()
                    P.fill.color = withA(0xFFF0FAFF.toInt(), al); c.drawPath(P.path, P.fill)
                    P.line.strokeWidth = 1.2f * dp; P.line.color = withA(col, 0.67f * al); c.drawCircle(nx, ny, sz * 1.9f * pulse, P.line)
                    if (isSel && z > 0f) { P.line.color = withA(col, 0.86f * z); c.drawCircle(nx, ny, sz * (2.6f + 0.6f * sin(t * 3f)), P.line) }
                    // label tag
                    if (al >= 0.3f) {   // [38.9.12.1] every topic labelled (front and back), not just the front 4
                        val right = nx >= coreX
                        P.text.textSize = 12.5f * density.fontScale * dp; P.mono.textSize = 9f * density.fontScale * dp
                        val name = topics[k].name
                        val tw = P.text.measureText(name)
                        var lx = nx + (if (right) 26 else -26) * dp; val ly = ny - 18 * dp
                        lx = lx.coerceIn(10 * dp + (if (right) 0f else tw), w - 10 * dp - (if (right) tw else 0f))
                        P.line.strokeWidth = 1f; P.line.color = withA(col, 0.6f * al)
                        c.drawLine(nx + (if (right) sz else -sz) * 1.4f, ny - sz, lx, ly, P.line)
                        c.drawLine(lx, ly, lx + (if (right) min(tw, 60 * dp) else -min(tw, 60 * dp)), ly, P.line)
                        P.text.textAlign = if (right) Paint.Align.LEFT else Paint.Align.RIGHT
                        P.mono.textAlign = P.text.textAlign
                        val tx = lx + (if (right) 2 else -2) * dp
                        P.text.color = withA(0xFFEBF5FF.toInt(), al); c.drawText(name, tx, ly - 4 * dp, P.text)
                        P.mono.color = withA(col, 0.9f * al); c.drawText(NexusCore.pad3(topics[k].entries.size), tx, ly + 12 * dp, P.mono)
                    }
                }

                // NEXUS CORE (fades as the camera pushes in)
                if (z < 0.98f) {
                    val cdim = dim
                    val cs = 78 * dp * (1f - 0.35f * z)
                    val R = cs / 2f + 6 * dp
                    P.line.strokeWidth = 1.2f * dp; P.line.color = withA(HudCyan.toArgb(), 0.86f * cdim); c.drawCircle(coreX, coreY, R, P.line)
                    P.line.strokeWidth = 1f; P.line.color = withA(HudCyan.toArgb(), 0.58f * cdim)
                    for (kk in 0 until 60) {
                        val aa = kk * Math.PI.toFloat() / 30f + t * 0.4f
                        val r1 = R + 4 * dp; val r2 = R + (if (kk % 10 == 0) 9 else 6) * dp
                        c.drawLine(coreX + r1 * cos(aa), coreY + r1 * sin(aa), coreX + r2 * cos(aa), coreY + r2 * sin(aa), P.line)
                    }
                    P.line.strokeWidth = 1.4f * dp; P.line.color = withA(HudCyan.toArgb(), 0.78f * cdim)
                    P.rect.set(coreX - R - 14 * dp, coreY - R - 14 * dp, coreX + R + 14 * dp, coreY + R + 14 * dp)
                    c.drawArc(P.rect, Math.toDegrees((-t * 0.9f).toDouble()).toFloat(), 70f, false, P.line)
                    val pr = cs * 0.30f * (1f + 0.06f * sin(t * 2.2f))
                    for (k in 6 downTo 1) { P.fill.color = withA(HudCyan.toArgb(), 0.05f * cdim); c.drawCircle(coreX, coreY, pr * (1 + k * 0.35f), P.fill) }
                    P.fill.color = withA(0xFFC8F5FF.toInt(), 0.92f * cdim); c.drawCircle(coreX, coreY, pr, P.fill)
                    P.fill.color = withA(0xFFFFFFFF.toInt(), cdim); c.drawCircle(coreX, coreY, pr * 0.55f, P.fill)
                    val tilts = floatArrayOf(0.35f, 1.2f, 2.3f); val spds = floatArrayOf(1.1f, -0.8f, 0.6f)
                    val ro = cs * 0.42f
                    for (k in 0 until 3) {
                        val ang = tilts[k] + t * 0.15f * spds[k]
                        c.save(); c.rotate(Math.toDegrees(ang.toDouble()).toFloat(), coreX, coreY)
                        P.line.strokeWidth = 1.2f * dp; P.line.color = withA(HudCyan.toArgb(), 0.67f * cdim)
                        P.rect.set(coreX - ro, coreY - ro * 0.32f, coreX + ro, coreY + ro * 0.32f); c.drawOval(P.rect, P.line)
                        val aa = t * spds[k] * 1.6f + k
                        P.fill.color = withA(0xFFFFFFFF.toInt(), cdim); c.drawCircle(coreX + cos(aa) * ro, coreY + sin(aa) * ro * 0.32f, 2.4f * dp, P.fill)
                        c.restore()
                    }
                    P.mono.textAlign = Paint.Align.CENTER; P.mono.textSize = 8f * dp; P.mono.color = withA(0xFF6EB4D7.toInt(), 0.8f * cdim)
                    c.drawText("NEXUS CORE", coreX, coreY + cs * 0.5f + 16 * dp, P.mono)
                }

                // scan beam sweeping down the page
                if (P.beam == null || P.beamH != 40 * dp) {
                    P.beamH = 40 * dp
                    P.beam = android.graphics.LinearGradient(0f, 0f, 0f, P.beamH, withA(HudCyan.toArgb(), 0f), withA(HudCyan.toArgb(), 0.13f), android.graphics.Shader.TileMode.CLAMP)
                }
                val by = ((t * 0.22f) % 1f) * h
                c.save(); c.translate(0f, by - P.beamH)
                P.fill.shader = P.beam; c.drawRect(0f, 0f, w, P.beamH, P.fill); P.fill.shader = null
                c.restore()
                // HUD corner brackets around the viz
                P.line.strokeWidth = 1f; P.line.color = withA(HudCyan.toArgb(), 0.47f)
                val top = top0 + 8 * dp; val bot = bot0 - 8 * dp; val l = 14 * dp; val r = w - 14 * dp; val L = 16 * dp
                c.drawLine(l, top, l + L, top, P.line); c.drawLine(l, top, l, top + L, P.line)
                c.drawLine(r, top, r - L, top, P.line); c.drawLine(r, top, r, top + L, P.line)
                c.drawLine(l, bot, l + L, bot, P.line); c.drawLine(l, bot, l, bot - L, P.line)
                c.drawLine(r, bot, r - L, bot, P.line); c.drawLine(r, bot, r, bot - L, P.line)
                P.mono.textAlign = Paint.Align.LEFT; P.mono.textSize = 7.5f * dp; P.mono.color = withA(0xFF5A96BE.toInt(), 0.8f)
                c.drawText("ORBIT  0.12 rad/s   ·   FOCUS  " + (if (sel >= 0) "LOCK" else "AUTO"), l + 8 * dp, bot - 6 * dp, P.mono)
            }
        }

        // ── header, readouts, search ──
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)
            .onGloballyPositioned { vizTop = it.boundsInRoot().bottom }) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HudSquareButton("‹", "返回") { if (sel >= 0) sel = -1 else onBack() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("知识库", color = HudInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("KNOWLEDGE  ·  NEXUS", color = HudMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                }
                HudSquareButton("≡", "列表") { onList() }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                val sync = if (lastAt > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(lastAt)) else "--:--"
                listOf(NexusCore.pad3(totalEntries) to "ENTRIES", NexusCore.pad2(topics.size) to "CLUSTERS", sync to "LAST SYNC",
                    NexusCore.indexed(totalEntries, byModel) to "INDEXED").forEach { (a, b) ->
                    Row(Modifier.weight(1f).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {   // [38.9.12.1] captions were clipped at 30 dp
                        Box(Modifier.width(1.dp).fillMaxHeight().background(HudCyan.copy(alpha = 0.8f)))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(a, color = HudInk, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            Text(b, color = HudMuted, fontSize = 8.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(36.dp).clip(RoundedCornerShape(9.dp))
                .background(HudCyan.copy(alpha = 0.04f)).border(1.dp, HudCyan.copy(alpha = 0.24f), RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌕", color = HudMuted, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("检索主题、关键词或文件", color = HudMuted.copy(alpha = 0.9f), fontSize = 12.5.sp)
                        BasicTextField(query, { query = it.take(40) }, singleLine = true, cursorBrush = SolidColor(HudCyan),
                            textStyle = TextStyle(color = HudInk, fontSize = 12.5.sp), modifier = Modifier.fillMaxWidth())
                    }
                    if (matches != null) Text("${matches.size} 个主题", color = HudCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (topics.isEmpty()) {
            Text("还没有收录内容。和 Mines 聊过的事会自动整理到这里。", color = HudMuted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 260.dp, start = 32.dp, end = 32.dp))
        }

        // ── bottom: RECENT INGEST (no focus) / topic detail (focused) ──
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)
            .onGloballyPositioned { if (sel < 0) vizBot = it.boundsInRoot().top }) {
            AnimatedVisibility(sel < 0 && entries.isNotEmpty(), enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
                HudPanel {
                    Text("RECENT INGEST", color = HudMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
                    entries.sortedByDescending { it.at }.take(2).forEachIndexed { i, e ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { PlusNav.go(e.route) }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(4.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(Color(TopicColors[topics.indexOfFirst { tp -> tp.entries.contains(e) }.coerceAtLeast(0) % 8])))
                            Text(e.title.ifBlank { e.topic }, color = HudInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text(fmt.format(java.util.Date(e.at)), color = HudMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            AnimatedVisibility(sel in topics.indices, enter = slideInVertically(tween(700, delayMillis = 500)) { it } + fadeIn(tween(500, delayMillis = 500)),
                exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(250))) {
                val tp = topics.getOrNull(sel)
                if (tp != null) NexusDetail(sel, tp, onQuote = { NexusActions.quote(ctx, tp, false) }, onSummary = { NexusActions.quote(ctx, tp, true) })
            }
        }
    }
}

@Composable
private fun HudSquareButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HudCyan.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(glyph, color = HudCyan, fontSize = 20.sp)
    }
}

@Composable
private fun HudPanel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xEB081226))
        .border(1.dp, HudCyan.copy(alpha = 0.27f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 14.dp)) { content() }
}

@Composable
private fun NexusDetail(index: Int, tp: NexusTopic, onQuote: () -> Unit, onSummary: () -> Unit) {
    val col = Color(TopicColors[index % TopicColors.size])
    val fmt = remember { java.text.SimpleDateFormat("M 月 d 日 HH:mm", java.util.Locale.CHINA) }
    HudPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CLUSTER " + NexusCore.pad2(index + 1), color = HudMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(tp.name, color = HudInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Text(NexusCore.pad3(tp.entries.size) + " ENTRIES", color = col, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        tp.entries.take(4).forEachIndexed { k, e ->
            var shown by remember(tp.name) { mutableStateOf(false) }
            LaunchedEffect(tp.name) { delay(900L + k * 120L); shown = true }
            val a by animateFloatAsState(if (shown) 1f else 0f, tween(300), label = "nexus-row")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).alpha(a).clickable { PlusNav.go(e.route) }) {
                Box(Modifier.width(2.dp).height(34.dp).background(col))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(e.title.ifBlank { e.summary.take(30) }, color = HudInk, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.summary.take(40).ifBlank { e.from } + " · " + fmt.format(java.util.Date(e.at)), color = HudMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        var btn by remember(tp.name) { mutableStateOf(false) }
        LaunchedEffect(tp.name) { delay(1500L); btn = true }
        val ba by animateFloatAsState(if (btn) 1f else 0f, tween(300), label = "nexus-btn")
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).alpha(ba), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, HudCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .clickable(onClick = onQuote), contentAlignment = Alignment.Center) { Text("在对话中引用", color = HudCyan, fontSize = 13.sp) }
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(HudCyan)
                .clickable(onClick = onSummary), contentAlignment = Alignment.Center) { Text("生成主题摘要", color = Color(0xFF04121F), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** The two detail-panel actions: a fresh draft chat with the topic quoted in the input (the user sends it). */
object NexusActions {
    fun text(tp: NexusTopic, summary: Boolean): String = buildString {
        append(if (summary) "请把知识库主题「${tp.name}」整理成一份摘要（要点、结论、待办）：\n" else "引用知识库主题「${tp.name}」：\n")
        tp.entries.take(8).forEach { e ->
            append("- ").append(e.title.ifBlank { e.topic })
            if (e.summary.isNotBlank()) append("：").append(e.summary.take(160))
            append('\n')
        }
    }.trim()

    fun quote(ctx: android.content.Context, tp: NexusTopic, summary: Boolean) {
        runCatching {
            com.openminis.app.share.SharedShareStore.savePendingShare(ctx, com.openminis.app.share.PendingShare(
                listOf(com.openminis.app.share.PendingShare.Item(com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT, text(tp, summary))),
                System.currentTimeMillis()))
            com.openminis.app.share.ShareCoordinator.processPendingShare(ctx)
        }
        PlusNav.go(com.openminis.app.ui.navigation.Routes.chat("__new__" + java.util.UUID.randomUUID()))
    }
}
