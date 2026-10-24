package com.openminis.app.plus

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [Mines-38.9.12] 知识库 「NEXUS」 holographic HUD — the pure geometry (unit-tested, NexusCoreTest), ported from the
 * approved motion mock (mock-v3/kb_anim.py): a 900-point Fibonacci sphere with latitude / meridian rings rotating
 * slowly, 8 topic nodes on a shell at radius 1.32, sub-entry specks around each node, a tilted outer orbit, and the
 * camera push-in toward a focused node. No avatar in the centre — the centre is the energy core (drawn by the UI).
 *
 * Everything works on flat FloatArrays (x, y, z triples) so the frame loop allocates nothing.
 */
object NexusCore {
    const val POINTS = 900
    const val SHELL = 1.32f
    const val PERSPECTIVE = 3.2f
    /** rotation speed around the vertical axis (rad/s) — the mock's 「ORBIT 0.12 rad/s」 */
    const val ORBIT_RAD_S = 0.12f

    /** Fibonacci sphere, unit radius: [n] points as x,y,z triples. */
    fun sphere(n: Int = POINTS): FloatArray {
        val out = FloatArray(n * 3)
        val golden = PI * (1 + sqrt(5.0))
        for (i in 0 until n) {
            val k = i + 0.5
            val phi = acos(1 - 2 * k / n)
            val th = golden * k
            out[i * 3] = (sin(phi) * cos(th)).toFloat()
            out[i * 3 + 1] = cos(phi).toFloat()
            out[i * 3 + 2] = (sin(phi) * sin(th)).toFloat()
        }
        return out
    }

    /** latitude rings (−60…60 every 30°) then meridians (0…150 every 30°), [seg] points each, as one array per ring */
    fun rings(seg: Int = 96): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (lat in intArrayOf(-60, -30, 0, 30, 60)) {
            val r = cos(Math.toRadians(lat.toDouble())); val y = sin(Math.toRadians(lat.toDouble()))
            out += FloatArray(seg * 3).also { a -> for (j in 0 until seg) { val t = 2 * PI * j / (seg - 1)
                a[j * 3] = (r * cos(t)).toFloat(); a[j * 3 + 1] = y.toFloat(); a[j * 3 + 2] = (r * sin(t)).toFloat() } }
        }
        for (lo in 0 until 180 step 30) {
            val l = Math.toRadians(lo.toDouble())
            out += FloatArray(seg * 3).also { a -> for (j in 0 until seg) { val t = 2 * PI * j / (seg - 1)
                a[j * 3] = (cos(t) * cos(l)).toFloat(); a[j * 3 + 1] = sin(t).toFloat(); a[j * 3 + 2] = (cos(t) * sin(l)).toFloat() } }
        }
        return out
    }

    /** the tilted outer orbit (radius 1.75, slight wobble) */
    fun orbit(seg: Int = 140): FloatArray = FloatArray(seg * 3).also { a ->
        for (j in 0 until seg) { val t = 2 * PI * j / (seg - 1)
            a[j * 3] = (1.75 * cos(t)).toFloat(); a[j * 3 + 1] = (0.08 * sin(t * 2)).toFloat(); a[j * 3 + 2] = (1.75 * sin(t)).toFloat() }
    }

    private val LATS = intArrayOf(22, -14, 30, -28, 12, -6, -30, 26)

    /** unit directions of the topic nodes (up to 8, evenly round the vertical axis, alternating latitude) */
    fun nodeDirs(n: Int): FloatArray {
        val k = n.coerceIn(0, 8)
        val out = FloatArray(k * 3)
        for (i in 0 until k) {
            val lo = i * 2 * PI / maxOf(k, 1); val la = Math.toRadians(LATS[i].toDouble())
            out[i * 3] = (cos(la) * cos(lo)).toFloat(); out[i * 3 + 1] = sin(la).toFloat(); out[i * 3 + 2] = (cos(la) * sin(lo)).toFloat()
        }
        return out
    }

    /** deterministic specks around each node (≤ 10 per node, by entry count), as x,y,z triples; [owner] = node index per speck */
    fun subs(dirs: FloatArray, counts: IntArray, seed: Long = 7L): Pair<FloatArray, IntArray> {
        val rnd = java.util.Random(seed)
        val n = dirs.size / 3
        val per = IntArray(n) { counts.getOrElse(it) { 0 }.coerceIn(0, 10) }
        val total = per.sum()
        val xyz = FloatArray(total * 3); val owner = IntArray(total)
        var w = 0
        for (i in 0 until n) for (j in 0 until per[i]) {
            xyz[w * 3] = dirs[i * 3] * SHELL + (rnd.nextGaussian() * 0.13).toFloat()
            xyz[w * 3 + 1] = dirs[i * 3 + 1] * SHELL + (rnd.nextGaussian() * 0.13).toFloat()
            xyz[w * 3 + 2] = dirs[i * 3 + 2] * SHELL + (rnd.nextGaussian() * 0.13).toFloat()
            owner[w] = i; w++
        }
        return xyz to owner
    }

    /** rotation about Y by [ay] then about X by [ax] (the mock's rot()), [src] → [dst] (same size), in place-safe */
    fun rotate(src: FloatArray, dst: FloatArray, ay: Float, ax: Float) {
        val cy = cos(ay); val sy = sin(ay); val cx = cos(ax); val sx = sin(ax)
        var i = 0
        while (i + 2 < src.size) {
            val x = src[i]; val y = src[i + 1]; val z = src[i + 2]
            // v @ Ry^T : Ry = [[cy,0,sy],[0,1,0],[-sy,0,cy]]
            val x1 = x * cy + z * sy; val y1 = y; val z1 = -x * sy + z * cy
            // @ Rx^T : Rx = [[1,0,0],[0,cx,-sx],[0,sx,cx]]
            dst[i] = x1; dst[i + 1] = y1 * cx - z1 * sx; dst[i + 2] = y1 * sx + z1 * cx
            i += 3
        }
    }

    /**
     * Perspective projection of rotated triples into screen x,y (+ depth z kept, + perspective factor f) — out is
     * 4 floats per point: sx, sy, z, f. [cx],[cy] = screen centre, [scale] = px per unit, [fx],[fy] = camera offset.
     */
    fun project(rot: FloatArray, out: FloatArray, cx: Float, cy: Float, scale: Float, fx: Float, fy: Float) {
        var i = 0; var o = 0
        while (i + 2 < rot.size) {
            val z = rot[i + 2]; val f = PERSPECTIVE / (PERSPECTIVE + z)
            out[o] = cx + fx + rot[i] * scale * f
            out[o + 1] = cy + fy - rot[i + 1] * scale * f
            out[o + 2] = z; out[o + 3] = f
            i += 3; o += 4
        }
    }

    /** smoothstep 0..1 */
    fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    /** camera pan toward a focused node (rotated, shell radius applied): (fx, fy) in px for focus amount [z] */
    fun focusOffset(nodeRotX: Float, nodeRotY: Float, scale0: Float, z: Float): Pair<Float, Float> =
        (-nodeRotX * scale0 * 1.2f * z) to (nodeRotY * scale0 * 1.2f * z - 0.7f * scale0 * z)

    /** quadratic Bézier point between core (x0,y0) and node (x1,y1), bowed upward by [bow] px, at t */
    fun arc(x0: Float, y0: Float, x1: Float, y1: Float, bow: Float, t: Float): Pair<Float, Float> {
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f - bow
        val u = 1 - t
        return (u * u * x0 + 2 * u * t * mx + t * t * x1) to (u * u * y0 + 2 * u * t * my + t * t * y1)
    }

    /** index of the node nearest to (x, y) within [radius] px among projected nodes (4 floats each), or -1 */
    fun hit(proj: FloatArray, n: Int, x: Float, y: Float, radius: Float): Int {
        var best = -1; var bd = radius * radius
        for (i in 0 until n) {
            val dx = proj[i * 4] - x; val dy = proj[i * 4 + 1] - y; val d = dx * dx + dy * dy
            if (d <= bd) { bd = d; best = i }
        }
        return best
    }

    /** node marker size (px) from its entry count, like the mock: 3.5 + min(c,40)/40·4.5 dp, times perspective */
    fun nodeSize(count: Int, dp: Float, f: Float): Float = (3.5f + minOf(count, 40) / 40f * 4.5f) * dp * f

    /** the HUD readouts: entries as 3 digits, clusters as 2 digits */
    fun pad3(n: Int) = n.coerceIn(0, 999).toString().padStart(3, '0')
    fun pad2(n: Int) = n.coerceIn(0, 99).toString().padStart(2, '0')

    /** 「INDEXED」: share of entries that the model organised (byModel), one decimal; 0 entries → "—" */
    fun indexed(total: Int, byModel: Int): String =
        if (total <= 0) "—" else String.format(java.util.Locale.US, "%.1f%%", 100.0 * byModel.coerceIn(0, total) / total)

    /** [Mines-38.9.12.1] topic nodes sit out on the orbit (radius in sphere units), not on the sphere shell */
    const val NODE_R = 1.75f   // = the drawn orbit's radius: the nodes ride on it
    /** [Mines-38.9.12.1] node latitudes for the orbit layout: small, so the 8 nodes spread round the orbit ellipse */
    private val ORBIT_LATS = intArrayOf(8, -6, 10, -9, 5, -4, -10, 7)

    /** unit directions for up to 8 nodes spread evenly round the orbit (alternating small latitudes) */
    fun orbitDirs(n: Int): FloatArray {
        val k = n.coerceIn(0, 8)
        val out = FloatArray(k * 3)
        for (i in 0 until k) {
            val lo = i * 2 * PI / maxOf(k, 1) + 0.3; val la = Math.toRadians(ORBIT_LATS[i].toDouble())
            out[i * 3] = (cos(la) * cos(lo)).toFloat(); out[i * 3 + 1] = sin(la).toFloat(); out[i * 3 + 2] = (cos(la) * sin(lo)).toFloat()
        }
        return out
    }

    /**
     * [Mines-38.9.12.1] the viz box between the search field ([top]) and the bottom panel ([bottom]) on a [w]-wide page:
     * centre y and sphere scale (px per unit) so sphere + orbit fill it like the design (phone 2026-10-23: sphere sat high
     * and small with an empty band below). Returns (cy, scale, floorY).
     */
    fun layout(w: Float, top: Float, bottom: Float): Triple<Float, Float, Float> {
        val hgt = (bottom - top).coerceAtLeast(1f)
        val cy = top + hgt * 0.47f
        val scale = minOf(w * 0.235f, hgt * 0.26f)
        val floor = minOf(bottom - 4f, cy + scale * 1.18f)
        return Triple(cy, scale, floor)
    }

    /** frame interval cap: battery saver 15 fps, otherwise 60 fps (the loop skips frames that come sooner) */
    fun frameGapMs(powerSave: Boolean): Long = if (powerSave) 66L else 16L
}
