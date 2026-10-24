package com.openminis.app.ui.plus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * [Mines-38.5] 知识库 脑图 (user 「知识库能不能做成那种脑图」, XMind-style: tap a branch → it opens outward and the
 * camera glides to it; tap a leaf → a card with the full title, summary and 「打开对话」). Centre 「我」, first ring =
 * topics clustered from the organised chats' titles / summaries, second ring = the chats. Only one branch is open
 * at a time, so branches never overlap. Pinch to zoom, drag to pan. Plain Compose Canvas, no extra library.
 */
object KnowledgeClusters {
    private val rules = listOf(
        "开发项目" to Regex("(?i)代码|插件|开发|apk|build|github|kotlin|python|部署|服务器|vps|脚本|编译|bug|接口|网站|前端|app"),
        "交易" to Regex("(?i)交易|币|btc|eth|okx|binance|行情|量化|合约|现货|k线|仓位"),
        "AI 工具" to Regex("(?i)\\bai\\b|模型|claude|gpt|gemini|deepseek|提示词|agent|智能体|kimi|grok|大模型|语音"),
        "安全研究" to Regex("(?i)漏洞|挖洞|安全|src|渗透|bounty|xss|sql|逆向|抓包|靶场"),
        "资料与链接" to Regex("(?i)链接|文章|分享|公众号|微信|x平台|推特|视频"),
    )

    /** [Mines-38.7] the conversation's own topic (model or local rules) first; the fixed rules only when it has none. */
    fun topicOf(e: com.openminis.app.plus.ChatKnowledge.Entry): String {
        e.topic.trim().takeIf { it.isNotBlank() && it != "其他" && it.length <= 8 }?.let { return it }
        val t = e.title + " " + e.topic + " " + e.summary
        return rules.firstOrNull { (_, rx) -> rx.containsMatchIn(t) }?.first ?: "生活与其他"
    }

    /** [Mines-38.7] the 要点 of one conversation for the map: 结论 first, then 待办, then facts (kind 结论/待办/要点). */
    fun points(e: com.openminis.app.plus.ChatKnowledge.Entry): List<Pair<String, String>> =
        (e.decisions.map { "结论" to it } + e.todos.map { "待办" to it } + e.facts.map { "要点" to it })
            .ifEmpty { listOf("要点" to e.summary.ifBlank { e.title }) }

    fun cluster(list: List<com.openminis.app.plus.ChatKnowledge.Entry>) =
        list.groupBy { topicOf(it) }.toList().sortedByDescending { it.second.size }.take(8)
}

private const val OPEN_Z = 0.9f

/** [Mines-38.9.5 i] user 2026-10-17 「不要大字」: the map's type is one step smaller (topics 13, chips / counts 12). */
object MapType { val Topic = 13.sp; val Chip = 12.sp
    /** neon accents of the sci-fi map: terracotta + cyan (user 22:04 「脑图更科幻」) */
    val Terracotta = Color(0xFFE07A4F); val Cyan = Color(0xFF5CE1E6) }

/** [Mines-38.8 v5d] geometry for the open branch's sub-chips (world px at zoom 1; sizes match the Canvas drawing). Pure, unit-tested. */
object KnowledgeLayout {
    fun chipSize(measurer: androidx.compose.ui.text.TextMeasurer, label: String, d: androidx.compose.ui.unit.Density): Size {
        val tl = measurer.measure(label, TextStyle(fontSize = MapType.Chip), maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(d) { 170.dp.roundToPx() }), density = d)
        val w = with(d) { (10.dp * 2 + 3.dp * 2 + 6.dp).toPx() } + tl.size.width
        return Size(w, with(d) { 26.dp.toPx() })
    }
    fun pillRect(measurer: androidx.compose.ui.text.TextMeasurer, topic: String, count: Int, p: Offset, d: androidx.compose.ui.unit.Density): Rect {
        val tl = measurer.measure(topic, TextStyle(fontSize = MapType.Topic, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(d) { 110.dp.roundToPx() }), density = d)
        val w = with(d) { (11.dp + 4.dp * 2 + 7.dp * 2 + 22.dp + 5.dp).toPx() } + tl.size.width + (if (count >= 10) with(d) { 8.dp.toPx() } else 0f)
        val h = with(d) { 32.dp.toPx() }
        return Rect(Offset(p.x - w / 2f, p.y - h / 2f), Size(w, h))
    }
    /** the face ring and the 「我」 pill under it */
    fun faceRects(d: androidx.compose.ui.unit.Density): List<Rect> = with(d) {
        val r = (36.dp + 10.dp).toPx()
        listOf(Rect(Offset(-r, -r), Size(r * 2, r * 2)), Rect(Offset(-30.dp.toPx(), (36.dp + 4.dp).toPx()), Size(60.dp.toPx(), 26.dp.toPx())))
    }

    /**
     * Places [sizes] chips around [parent] (a topic pill centred at [bp], outward unit direction [dir]): candidates on
     * rings of growing radius, angles alternating either side of the outward direction (never more than 100° off it),
     * each clamped to [-half, half] minus [margin]; the first candidate overlapping no obstacle / placed chip (all inflated
     * by [gap]) wins. When nothing is free the least-overlapping candidate is used. Returns chip centres.
     */
    fun placeChips(bp: Offset, dir: Offset, parent: Rect, sizes: List<Size>, obstacles: List<Rect>, half: Size, gap: Float, margin: Float): List<Offset> {
        val base = kotlin.math.atan2(dir.y, dir.x)
        val taken = ArrayList<Rect>(); taken += obstacles; taken += parent
        val out = ArrayList<Offset>()
        val angles = ArrayList<Float>().apply {
            add(0f); for (s in 1..5) { add(s * 20f); add(-s * 20f) }
        }.map { Math.toRadians(it.toDouble()).toFloat() }
        for (sz in sizes) {
            var best: Offset? = null; var bestCost = Float.MAX_VALUE
            val r0 = kotlin.math.hypot(parent.width / 2f, parent.height / 2f) * 0.6f + kotlin.math.hypot(sz.width / 2f, sz.height / 2f) * 0.6f + gap
            search@ for (ring in 0 until 14) {
                val rr = r0 + ring * sz.height * 0.9f
                for (a in angles) {
                    val c0 = Offset(bp.x + kotlin.math.cos(base + a) * rr, bp.y + kotlin.math.sin(base + a) * rr)
                    val c = clampCentre(c0, sz, half, margin)
                    val rect = Rect(Offset(c.x - sz.width / 2f, c.y - sz.height / 2f), sz)
                    val cost = taken.sumOf { overlap(it.inflate(gap), rect).toDouble() }.toFloat()
                    if (cost == 0f) { best = c; bestCost = 0f; break@search }
                    if (cost < bestCost) { bestCost = cost; best = c }
                }
            }
            val c = best ?: bp
            out += c; taken += Rect(Offset(c.x - sz.width / 2f, c.y - sz.height / 2f), sz)
        }
        return out
    }
    fun clampCentre(c: Offset, sz: Size, half: Size, margin: Float): Offset {
        val hx = (half.width - margin - sz.width / 2f).coerceAtLeast(0f); val hy = (half.height - margin - sz.height / 2f).coerceAtLeast(0f)
        return Offset(c.x.coerceIn(-hx, hx), c.y.coerceIn(-hy, hy))
    }
    fun overlap(a: Rect, b: Rect): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left); val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }
}

private class MapNode(val id: String, val label: String, val pos: Offset, val level: Int, val entry: com.openminis.app.plus.ChatKnowledge.Entry? = null, var rect: Rect = Rect.Zero,
                      val point: Pair<String, String>? = null)

@Composable
fun KnowledgeMindMap(entries: List<com.openminis.app.plus.ChatKnowledge.Entry>, height: Dp = 560.dp,
                     onOpenChat: (String) -> Unit) {
    val m = mines()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val clusters = remember(entries) { KnowledgeClusters.cluster(entries) }
    var openBranch by remember { mutableStateOf<String?>(null) }
    // [Mines-38.9] sub-chips grow out of their topic pill (scale + fade, 380 ms) when a branch opens
    val expand = remember { Animatable(1f) }
    val stillCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(openBranch) {
        if (openBranch != null && !GalaxyMotion.still(stillCtx)) { expand.snapTo(0f); expand.animateTo(1f, tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        else expand.snapTo(1f)
    }
    var leaf by remember { mutableStateOf<Pair<com.openminis.app.plus.ChatKnowledge.Entry, Pair<String, String>>?>(null) }
    val camX = remember { Animatable(0f) }; val camY = remember { Animatable(0f) }; val zoom = remember { Animatable(0.88f) }
    // [Mines-38.9.5 i] pan inertia: the last drag velocity (px/s, smoothed) glides out with exponential decay on release
    val vel = remember { floatArrayOf(0f, 0f, 0f) }   // vx, vy, last time (ms)
    val px = { d: Float -> with(density) { d.dp.toPx() } }

    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // layout in world coordinates (px), 「我」 at the origin
    val nodes = remember(clusters, openBranch, density, boxSize) {
        val out = ArrayList<MapNode>()
        out += MapNode("me", "我", Offset.Zero, 0)
        val k = clusters.size.coerceAtLeast(1)
        clusters.forEachIndexed { i, (topic, list) ->
            val a = Math.toRadians(-90.0 + 360.0 * i / k)
            val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
            // [Mines-38.8] balanced ellipse filling the tall card (mock3 知识库-脑图: ±118 dp wide, up to ±210 dp tall)
            val bp = Offset(dir.x * px(118f), dir.y * px((height.value / 2f - 72f).coerceIn(130f, 210f)))
            out += MapNode("b:$topic", "$topic · ${list.size}", bp, 1)
            if (openBranch == topic) {
                // [Mines-38.7] leaves are the 要点 (结论 / 待办 / 要点) of the topic's conversations, up to 2 per conversation, 10 per topic
                val kids = list.flatMap { e -> KnowledgeClusters.points(e).take(2).map { e to it } }.take(6)
                // [Mines-38.8 v5d] sub-chips go radially OUTWARD from the topic pill: each one takes the first free spot on
                // widening rings around the outward direction that overlaps no topic pill, the face / 「我」 pill or an
                // earlier chip, and is clamped inside the map (the camera stays put while a branch is open, zoom OPEN_Z)
                val sizes = kids.map { (_, pt) ->
                    val tag = when (pt.first) { "结论" -> "✓ "; "待办" -> "☐ "; else -> "" }
                    tag + pt.second to KnowledgeLayout.chipSize(measurer, tag + pt.second, density)
                }
                val obstacles = ArrayList<Rect>()
                obstacles += KnowledgeLayout.faceRects(density)
                clusters.forEachIndexed { i2, (t2, l2) ->
                    val a2 = Math.toRadians(-90.0 + 360.0 * i2 / k)
                    val p2 = Offset(kotlin.math.cos(a2).toFloat() * px(118f), kotlin.math.sin(a2).toFloat() * px((height.value / 2f - 72f).coerceIn(130f, 210f)))
                    obstacles += KnowledgeLayout.pillRect(measurer, t2, l2.size, p2, density)
                }
                val half = if (boxSize.width > 0) Size(boxSize.width / 2f / OPEN_Z, (boxSize.height / 2f - px(26f)) / OPEN_Z)
                           else Size(px(180f) / OPEN_Z, px(height.value / 2f) / OPEN_Z)
                val placed = KnowledgeLayout.placeChips(bp, dir, KnowledgeLayout.pillRect(measurer, topic, list.size, bp, density),
                    sizes.map { it.second }, obstacles, half, px(10f), px(8f))
                kids.forEachIndexed { j, (e, pt) ->
                    out += MapNode("l:${e.sessionId}:$j", sizes[j].first, placed[j], 2, e, point = pt)
                }
            }
        }
        out
    }

    fun focus(p: Offset, z: Float) = scope.launch {
        launch { camX.animateTo(-p.x * z, tween(420)) }; launch { camY.animateTo(-p.y * z, tween(420)) }; launch { zoom.animateTo(z, tween(420)) }
    }

    fun onNode(hit: MapNode) {
        when (hit.level) {
            0 -> { openBranch = null; focus(Offset.Zero, 0.9f) }
            1 -> {
                val t = hit.id.removePrefix("b:")
                openBranch = if (openBranch == t) null else t
                // glide to the branch, a bit further out so its children are in view
                // [Mines-38.8 v5d] the camera stays centred (chips are laid out to fit the screen at OPEN_Z, no topic slides off)
                if (openBranch != null) focus(Offset.Zero, OPEN_Z) else focus(Offset.Zero, 0.9f)
            }
            else -> hit.entry?.let { e -> leaf = e to (hit.point ?: ("要点" to e.summary)) }
        }
    }
    // [Mines-38.8 v5] 「星空脑图」 (user-approved mock3/知识库-脑图-星空v3 + 知识库-星空闪烁): the map floats on the deep-space
    // page (GalaxyBackdrop, drawn by KnowledgeScreen). Each topic owns a colour nebula (radial glow + neuron filaments)
    // that breathes on the 3 s loop, glowing curved branches from the centre face, dark-glass pills with a colour dot
    // and a count badge, expanded sub-chips in the topic's colour. One Canvas, no per-node composables for drawing.
    val gcol = remember(clusters) { Galaxy.colorsFor(clusters.map { it.first }) }
    val filaments = remember(clusters, density) { clusters.mapIndexed { i, (t, l) -> t to Galaxy.filaments(i, l.size, density) }.toMap() }
    val gtime = rememberGalaxyTime()
    // unified type scale: node labels = body 15 sp Medium, counts / sub-chips / 「我」 = caption 13 sp
    val bStyle = TextStyle(fontSize = MapType.Topic, fontWeight = FontWeight.Medium, color = Galaxy.Ink)
    val cStyle = TextStyle(fontSize = MapType.Chip, fontWeight = FontWeight.Medium, color = Color.White)
    val lStyle = TextStyle(fontSize = MapType.Chip, color = Galaxy.Ink)
    val appear = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }

    Box(Modifier.fillMaxWidth().height(height).semantics { contentDescription = "知识脑图：双指缩放，拖动查看，点主题展开" }) {
        Canvas(Modifier.fillMaxWidth().height(height).onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    val now = System.nanoTime() / 1_000_000f
                    val dt = (now - vel[2]).coerceIn(4f, 80f); vel[2] = now
                    vel[0] = vel[0] * 0.6f + (pan.x / dt * 1000f) * 0.4f; vel[1] = vel[1] * 0.6f + (pan.y / dt * 1000f) * 0.4f
                    scope.launch {
                        camX.stop(); camY.stop()
                        zoom.snapTo((zoom.value * z).coerceIn(0.4f, 3f)); camX.snapTo(camX.value + pan.x); camY.snapTo(camY.value + pan.y)
                    }
                }
            }
            .pointerInput(Unit) {
                // release → fling (skipped with reduce-motion)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false); vel[0] = 0f; vel[1] = 0f
                    do { val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final) } while (ev.changes.any { it.pressed })
                    val vx = vel[0]; val vy = vel[1]
                    val fresh = System.nanoTime() / 1_000_000f - vel[2] < 90f
                    if (fresh && !GalaxyMotion.still(stillCtx) && kotlin.math.hypot(vx, vy) > 250f) scope.launch {
                        val decay = androidx.compose.animation.core.exponentialDecay<Float>(frictionMultiplier = 1.6f)
                        launch { camX.animateDecay(vx.coerceIn(-6000f, 6000f), decay) }
                        launch { camY.animateDecay(vy.coerceIn(-6000f, 6000f), decay) }
                    }
                }
            }
            .pointerInput(nodes) {
                detectTapGestures { tap ->
                    val hit = nodes.lastOrNull { it.rect.contains(tap) } ?: return@detectTapGestures
                    onNode(hit)
                }
            }) {
            val c = Offset(size.width / 2f + camX.value, size.height / 2f + camY.value)
            val z = zoom.value
            val ap = appear.value
            val t = gtime.value
            val twoPi = (2 * Math.PI).toFloat()
            fun scr(p: Offset) = c + p * (z * (0.55f + 0.45f * ap))
            fun colOf(n: MapNode): Color {
                val tp = if (n.level == 1) n.id.removePrefix("b:") else (openBranch ?: "")
                return gcol[tp] ?: Galaxy.Peach
            }
            GalaxyMotion.camX = camX.value; GalaxyMotion.camY = camY.value   // [Mines-38.9] star parallax follows the pan
            val ex = expand.value
            val pos = HashMap<String, Offset>()
            // [Mines-38.9] topic pills float gently (±2.5 dp vertical, ±1.5 dp sideways, 5–7 s loops, own phase)
            nodes.forEachIndexed { i, n ->
                val base = scr(n.pos)
                pos[n.id] = if (n.level == 1) base + Offset(kotlin.math.cos(twoPi * t / 7f + i * 0.9f) * 1.5.dp.toPx(), kotlin.math.sin(twoPi * t / 5f + i * 1.3f) * 2.5.dp.toPx())
                    else base
            }
            // sub-chips start at their topic pill and glide out while the branch opens
            nodes.filter { it.level == 2 }.forEach { n -> val from = pos["b:$openBranch"]; val to = pos[n.id]
                if (from != null && to != null) pos[n.id] = from + (to - from) * ex }
            val me = pos["me"] ?: c
            // 0) [Mines-38.9.5 i] faint cyan grid riding the camera at half speed (depth), fading toward the edges
            run {
                val step = 44.dp.toPx() * z.coerceIn(0.6f, 1.6f)
                val ox = ((camX.value * 0.5f) % step + step) % step; val oy = ((camY.value * 0.5f) % step + step) % step
                val gc = MapType.Cyan.copy(alpha = 0.045f * ap)
                var gx = ox; while (gx < size.width) { drawLine(gc, Offset(gx, 0f), Offset(gx, size.height), 0.6.dp.toPx()); gx += step }
                var gy = oy; while (gy < size.height) { drawLine(gc, Offset(0f, gy), Offset(size.width, gy), 0.6.dp.toPx()); gy += step }
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xFF0B0A14).copy(alpha = 0.55f)), center = Offset(size.width / 2f, size.height / 2f),
                    radius = maxOf(size.width, size.height) * 0.7f))
            }
            // 1) nebula regions: soft radial glow + core + neuron filaments, breathing on the 3 s loop
            nodes.filter { it.level == 1 }.forEachIndexed { i, n ->
                val p = pos[n.id] ?: return@forEachIndexed
                val topic = n.id.removePrefix("b:")
                val col = gcol[topic] ?: Galaxy.Peach
                val cnt = clusters.firstOrNull { it.first == topic }?.second?.size ?: 1
                // [Mines-38.9] the nebula breathes (±7 % size, 8 s) and drifts a little around its topic (20 / 26 s loops)
                val drift = Offset(kotlin.math.sin(twoPi * t / 20f + i) * 7.dp.toPx(), kotlin.math.cos(twoPi * t / 26f + i * 1.7f) * 6.dp.toPx()) * z
                val np = p + drift
                val big = (58f + 5f * cnt.coerceAtMost(8)).dp.toPx() * z * 1.6f * (1f + 0.07f * kotlin.math.sin(twoPi * t / 8f + i * 2.1f))
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.30f * ap), col.copy(alpha = 0.10f * ap), Color.Transparent), center = np, radius = big), radius = big, center = np)
                val core = 46.dp.toPx() * z
                val breathe = 0.5f + 0.5f * kotlin.math.sin(twoPi * t / 3f + i)
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = (0.24f + 0.20f * breathe) * ap), Color.Transparent), center = p, radius = core), radius = core, center = p)
                filaments[topic]?.let { fp ->
                    translate(p.x, p.y) {
                        scale(z, z, pivot = Offset.Zero) {
                            drawPath(fp, col.copy(alpha = 0.10f * ap), style = Stroke(width = 2.4.dp.toPx()))
                            drawPath(fp, col.copy(alpha = 0.32f * ap), style = Stroke(width = 0.5.dp.toPx()))
                        }
                    }
                }
            }
            // warm glow behind the centre face
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFAA78).copy(alpha = 0.20f), Color.Transparent), center = me, radius = 84.dp.toPx() * z),
                radius = 84.dp.toPx() * z, center = me)
            // 2) glowing curved branches: centre → topic (quadratic, bowed sideways) and topic → its sub-chips
            nodes.filter { it.level > 0 }.forEach { n ->
                val a = (if (n.level == 1) me else pos["b:$openBranch"]) ?: return@forEach
                val b = pos[n.id] ?: return@forEach
                val col = colOf(n)
                val d = b - a
                val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1f)
                val bow = if (n.level == 1) 19.dp.toPx() * z else 0f
                val ctl = Offset((a.x + b.x) / 2f - d.y / len * bow, (a.y + b.y) / 2f + d.x / len * bow)
                val path = Path().apply { moveTo(a.x, a.y); quadraticTo(ctl.x, ctl.y, b.x, b.y) }
                val w = (if (n.level == 1) 1.5f else 0.9f).dp.toPx() * z.coerceIn(0.7f, 1.3f)
                drawPath(path, col.copy(alpha = 0.22f * ap), style = Stroke(width = w * 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawPath(path, col.copy(alpha = (if (n.level == 1) 0.90f else 0.66f) * ap * (if (n.level == 2) ex else 1f)), style = Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // [Mines-38.9] a light pulse flows out along every centre → topic branch (2.4 s, staggered per topic)
                if (n.level == 1 || (n.level == 2 && ex >= 0.99f)) {
                    val idx = nodes.indexOf(n)
                    val u = ((t / (if (n.level == 1) 2.4f else 1.8f) + idx * 0.37f) % 1.4f)
                    if (u <= 1f) {
                        val om = 1f - u
                        val q = Offset(om * om * a.x + 2f * om * u * ctl.x + u * u * b.x, om * om * a.y + 2f * om * u * ctl.y + u * u * b.y)
                        val fade = kotlin.math.sin(Math.PI.toFloat() * u) * ap
                        drawCircle((if (idx % 2 == 0) col else MapType.Cyan).copy(alpha = 0.30f * fade), radius = 6.dp.toPx() * z, center = q)
                        drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = 1.8.dp.toPx() * z, center = q)
                    }
                }
            }
            // 3) nodes: centre ring + 「我」 pill, topic pills, sub-chips (all dark glass with a colour dot)
            // [Mines-38.9.5 i] neon edge: soft outer glow in the topic colour, rim gradient topic colour → cyan
            fun pill(r: Rect, col: Color, rimAlpha: Float) {
                val g = 3.dp.toPx()
                drawRoundRect(col.copy(alpha = 0.16f * rimAlpha), r.topLeft - Offset(g, g), Size(r.width + 2 * g, r.height + 2 * g), CornerRadius(r.height / 2f + g), style = Stroke(2.5f * g))
                drawRoundRect(Galaxy.PillFill, r.topLeft, r.size, CornerRadius(r.height / 2f))
                drawRoundRect(Brush.linearGradient(listOf(col.copy(alpha = rimAlpha), MapType.Cyan.copy(alpha = rimAlpha * 0.85f)), start = r.topLeft, end = r.bottomRight),
                    r.topLeft, r.size, CornerRadius(r.height / 2f), style = Stroke(1.2.dp.toPx()))
            }
            // [Mines-38.9.5 i] topic pills scale in one after another as the map appears
            fun nodeIn(i: Int): Float = 0.6f + 0.4f * (ap * 1.8f - i * 0.1f).coerceIn(0f, 1f)
            nodes.forEach { n ->
                val p = pos[n.id] ?: return@forEach
                val col = colOf(n)
                when (n.level) {
                    0 -> {
                        val rr = 36.dp.toPx() * z * (0.7f + 0.3f * ap)
                        n.rect = Rect(p - Offset(rr, rr), Size(rr * 2, rr * 2))
                        drawCircle(Galaxy.Peach.copy(alpha = 0.18f), radius = rr + 9.dp.toPx() * z, center = p, style = Stroke(5.dp.toPx() * z))
                        drawCircle(Galaxy.Peach.copy(alpha = 0.47f), radius = rr + 9.dp.toPx() * z, center = p, style = Stroke(1.2.dp.toPx()))
                        drawCircle(Color(0xFFFFE6D2), radius = rr + 1.5.dp.toPx() * z, center = p)
                        val tl = measurer.measure("我", lStyle.copy(fontSize = MapType.Chip * z))
                        val dot = 3.dp.toPx() * z; val padH = 10.dp.toPx() * z
                        val pw = padH * 2 + dot * 2 + 6.dp.toPx() * z + tl.size.width; val ph = 24.dp.toPx() * z
                        val pr = Rect(Offset(p.x - pw / 2f, p.y + rr + 16.dp.toPx() * z - ph / 2f), Size(pw, ph))
                        pill(pr, Galaxy.Peach, 0.75f)
                        drawCircle(Galaxy.Peach, radius = dot, center = Offset(pr.left + padH + dot, pr.center.y))
                        drawText(tl, topLeft = Offset(pr.left + padH + dot * 2 + 6.dp.toPx() * z, pr.center.y - tl.size.height / 2f))
                    }
                    1 -> scale(nodeIn(nodes.indexOf(n)), nodeIn(nodes.indexOf(n)), pivot = p) {
                        val topic = n.id.removePrefix("b:")
                        val count = n.label.substringAfterLast("· ", "").trim()
                        val tl = measurer.measure(topic, bStyle.copy(fontSize = MapType.Topic * z), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = (110.dp.toPx() * z).toInt().coerceAtLeast(1)))
                        val ct = measurer.measure(count, cStyle.copy(fontSize = MapType.Chip * z))
                        val h = 32.dp.toPx() * z; val dot = 4.dp.toPx() * z
                        val padL = 11.dp.toPx() * z; val gap = 7.dp.toPx() * z; val padR = 5.dp.toPx() * z
                        val badge = maxOf(22.dp.toPx() * z, ct.size.width + 10.dp.toPx() * z)
                        val w = padL + dot * 2 + gap + tl.size.width + gap + (if (count.isNotEmpty()) badge else 0f) + padR
                        val r = Rect(Offset(p.x - w / 2f, p.y - h / 2f), Size(w, h)); n.rect = r
                        val open = openBranch == topic
                        pill(r, col, if (open) 1f else 0.78f)
                        if (open) drawRoundRect(col.copy(alpha = 0.22f), r.topLeft - Offset(3f, 3f), Size(r.width + 6f, r.height + 6f), CornerRadius(h / 2f + 3f), style = Stroke(3.dp.toPx()))
                        drawCircle(col, radius = dot, center = Offset(r.left + padL + dot, p.y))
                        drawText(tl, topLeft = Offset(r.left + padL + dot * 2 + gap, p.y - tl.size.height / 2f))
                        if (count.isNotEmpty()) {
                            val bc = Offset(r.right - padR - badge / 2f, p.y)
                            drawRoundRect(col.copy(alpha = 0.85f), Offset(bc.x - badge / 2f, p.y - 11.dp.toPx() * z), Size(badge, 22.dp.toPx() * z), CornerRadius(11.dp.toPx() * z))
                            drawText(ct, topLeft = Offset(bc.x - ct.size.width / 2f, p.y - ct.size.height / 2f))
                        }
                    }
                    else -> {
                        val tl = measurer.measure(n.label, lStyle.copy(fontSize = MapType.Chip * z), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = (170.dp.toPx() * z).toInt().coerceAtLeast(1)))
                        val dot = 3.dp.toPx() * z; val padH = 10.dp.toPx() * z; val h = 26.dp.toPx() * z
                        val w = padH * 2 + dot * 2 + 6.dp.toPx() * z + tl.size.width
                        val r = Rect(Offset(p.x - w / 2f, p.y - h / 2f), Size(w, h)); n.rect = r
                        // [Mines-38.9] grows from 60 % with the expand animation, fading in
                        scale(0.6f + 0.4f * ex, 0.6f + 0.4f * ex, pivot = p) {
                            drawRoundRect(Galaxy.PillFill.copy(alpha = Galaxy.PillFill.alpha * ex), r.topLeft, r.size, CornerRadius(r.height / 2f))
                            drawRoundRect(col.copy(alpha = 0.72f * ex), r.topLeft, r.size, CornerRadius(r.height / 2f), style = Stroke(1.dp.toPx()))
                            drawCircle(col.copy(alpha = ex), radius = dot, center = Offset(r.left + padH + dot, p.y))
                            drawText(tl, topLeft = Offset(r.left + padH + dot * 2 + 6.dp.toPx() * z, p.y - tl.size.height / 2f), alpha = ex)
                        }
                    }
                }
            }
        }
        // [Mines-38.8] the centre is the character's face (mock3), riding the camera like the drawn rings
        run {
            val zc = zoom.value * (0.55f + 0.45f * appear.value)
            val faceDp = 72.dp
            val fpx = with(density) { faceDp.toPx() }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((boxSize.width / 2f + camX.value - fpx / 2f).toInt(), (boxSize.height / 2f + camY.value - fpx / 2f).toInt()) }
                .size(faceDp).graphicsLayer { scaleX = zc; scaleY = zc }.clip(androidx.compose.foundation.shape.CircleShape)) { PetAvatar(size = faceDp) }
        }
        if (entries.isNotEmpty()) Text("${clusters.size} 个主题 · ${entries.size} 条 · 刚刚整理完", fontSize = MT.Caption, color = Galaxy.Ink3,
            // [Mines-38.9.4] frosted pill so the status reads over the glow
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 10.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color(0xFF16121A).copy(alpha = 0.55f))
                .border(1.dp, Color(0x29FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
        // [Mines-38.7] every node is also a real (invisible) button: TalkBack reads it (the user is partially sighted) and
        // uiautomator can tap it (smoke). Same action as tapping the drawn node.
        nodes.forEach { n ->
            val z = zoom.value
            val cx = boxSize.width / 2f + camX.value + n.pos.x * z
            val cy = boxSize.height / 2f + camY.value + n.pos.y * z
            val wDp = if (n.level == 0) 64.dp else 96.dp; val hDp = 40.dp
            val wPx = with(density) { wDp.toPx() }; val hPx = with(density) { hDp.toPx() }
            if (cx > -wPx && cy > -hPx && cx < boxSize.width + wPx && cy < boxSize.height + hPx)
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((cx - wPx / 2f).toInt(), (cy - hPx / 2f).toInt()) }
                    .size(wDp, hDp)
                    .semantics { contentDescription = "脑图节点：" + n.label }
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onNode(n) })
        }
        // [Mines-38.5] empty state centred under 「我」 with padding (was flush top-left, orphan 「图」)
        if (entries.isEmpty()) Text("还没有整理好的对话\n多聊几次，这里会长出脑图", fontSize = MT.Body, lineHeight = MT.BodyLH, color = Galaxy.Ink2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                .offset(y = 84.dp).padding(horizontal = 24.dp))
    }
    leaf?.let { (e, pt) ->
        // [Mines-38.7] node detail: the point in full, the conversation's summary / 结论 / 待办, where it came from, 「回到对话」
        MinesDialog(pt.first, onDismiss = { leaf = null }, confirm = "回到对话", onConfirm = { leaf = null; onOpenChat(e.route) }, dismiss = "关闭") {
            Text(pt.second, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink)
            Text(e.from + (if (e.topic.isNotBlank()) " · " + e.topic else ""), fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.primaryText)
            if (e.summary.isNotBlank()) Text(e.summary, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
            e.decisions.filter { it != pt.second }.take(3).forEach { Text("✓ $it", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2) }
            e.todos.filter { it != pt.second }.take(3).forEach { Text("☐ $it", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2) }
        }
    }
}
