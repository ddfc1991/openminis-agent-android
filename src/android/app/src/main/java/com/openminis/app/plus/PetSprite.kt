package com.openminis.app.plus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.openminis.app.R
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * [Mines-pet-32] Real frame animation for the desk pet (no GPU needed).
 *
 * One character = a base picture plus same-framing frames: blink (eyes
 * closed), mouth_a / mouth_o (visemes for lip-sync), think (eyes up). Built-in
 * characters ship them (generated from the line-up art, the changed face area
 * composited back onto the original so the body never jitters); for the
 * user's own picture 「生成动作」 makes them with Gemini ([PetFrameGen]).
 *
 * Drawing: the base is drawn through a grid mesh (drawBitmapMesh) whose
 * vertices move with a slow sine — breathing in the chest, a gentle sway that
 * grows towards the hair ends — then each active frame is crossfaded on top
 * with its own alpha (eased, 120–200 ms), so nothing ever hard-swaps.
 */
object PetFrames {
    const val BLINK = "blink"; const val MOUTH_A = "mouth_a"; const val MOUTH_O = "mouth_o"; const val THINK = "think"
    val names = listOf(BLINK, MOUTH_A, MOUTH_O, THINK)

    private val builtin: Map<String, Map<String, Int>> = mapOf(
        "claude" to mapOf(BLINK to R.drawable.pet_claude_blink, MOUTH_A to R.drawable.pet_claude_mouth_a, MOUTH_O to R.drawable.pet_claude_mouth_o, THINK to R.drawable.pet_claude_think),
        "gemini" to mapOf(BLINK to R.drawable.pet_gemini_blink, MOUTH_A to R.drawable.pet_gemini_mouth_a, MOUTH_O to R.drawable.pet_gemini_mouth_o, THINK to R.drawable.pet_gemini_think),
        "gpt" to mapOf(BLINK to R.drawable.pet_gpt_blink, MOUTH_A to R.drawable.pet_gpt_mouth_a, MOUTH_O to R.drawable.pet_gpt_mouth_o, THINK to R.drawable.pet_gpt_think),
        "grok" to mapOf(BLINK to R.drawable.pet_grok_blink, MOUTH_A to R.drawable.pet_grok_mouth_a, THINK to R.drawable.pet_grok_think),
        "kimi" to mapOf(BLINK to R.drawable.pet_kimi_blink, MOUTH_A to R.drawable.pet_kimi_mouth_a, MOUTH_O to R.drawable.pet_kimi_mouth_o),
        "wenxin" to mapOf(BLINK to R.drawable.pet_wenxin_blink, MOUTH_A to R.drawable.pet_wenxin_mouth_a, MOUTH_O to R.drawable.pet_wenxin_mouth_o, THINK to R.drawable.pet_wenxin_think),
        "whale" to mapOf(BLINK to R.drawable.pet_whale_blink, MOUTH_A to R.drawable.pet_whale_mouth_a, MOUTH_O to R.drawable.pet_whale_mouth_o, THINK to R.drawable.pet_whale_think),
    )

    fun customDir(c: Context) = File(c.filesDir, "pet_frames")
    fun customBase(c: Context) = File(customDir(c), "base.png")
    fun hasCustom(c: Context) = customBase(c).exists() && names.any { File(customDir(c), "$it.png").exists() }

    data class FrameSet(val key: String, val base: Bitmap, val frames: Map<String, Bitmap>)
    @Volatile private var cache: FrameSet? = null

    /** The frame set for [style] (built-in id or custom), or null when this character has none. */
    fun load(c: Context, style: String, version: Long): FrameSet? {
        val key = "$style/$version"
        cache?.takeIf { it.key == key }?.let { return it }
        val set = runCatching {
            if (style == Avatars.CUSTOM) {
                if (!hasCustom(c)) return@runCatching null
                val base = BitmapFactory.decodeFile(customBase(c).absolutePath) ?: return@runCatching null
                val fr = names.mapNotNull { n -> File(customDir(c), "$n.png").takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.absolutePath)?.let { n to it } } }.toMap()
                FrameSet(key, base, fr)
            } else {
                val b = Avatars.builtins.firstOrNull { it.id == style } ?: return@runCatching null
                val ids = builtin[style] ?: return@runCatching null
                val base = BitmapFactory.decodeResource(c.resources, b.neutral) ?: return@runCatching null
                FrameSet(key, base, ids.mapNotNull { (n, id) -> BitmapFactory.decodeResource(c.resources, id)?.let { n to it } }.toMap())
            }
        }.getOrNull()
        cache = set
        return set
    }

    fun invalidate() { cache = null }
}

/** Draws a [PetFrames.FrameSet] with mesh breathing + crossfaded frames. */
class PetSprite {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val COLS = 10; private val ROWS = 24   // [Mines-pet-34] finer mesh for head / torso articulation
    private val verts = FloatArray((COLS + 1) * (ROWS + 1) * 2)

    /** Current eased alpha per frame (0..1). */
    private val alpha = HashMap<String, Float>()

    /** Ease each frame's alpha towards its target; [dt] in seconds, [tau] ≈ crossfade time. */
    fun step(targets: Map<String, Float>, dt: Float, tau: Float = 0.08f) {
        val k = (dt / tau).coerceIn(0f, 1f)
        val keys = (alpha.keys + targets.keys).toSet()
        for (n in keys) {
            val cur = alpha[n] ?: 0f
            val tgt = targets[n] ?: 0f
            alpha[n] = cur + (tgt - cur) * k
        }
    }

    fun alphaOf(n: String) = alpha[n] ?: 0f

    /**
     * @param t seconds since start (breathing / sway phase)
     * @param breath 0..1 strength (0 while held or asleep-deep)
     * @param outline draw a high-contrast silhouette outline (busy wallpapers)
     */
    /**
     * [Mines-pet-34] Body pose on the mesh (articulated, not whole-sprite): the head turns about the
     * neck, the torso leans about the planted feet, the upper body "turns" (narrows + shifts) and nods.
     * Degrees / fractions; the service eases these every frame.
     */
    var headTilt = 0f      // deg, + = towards the right
    var nod = 0f           // 0..1, head dips forward
    var lean = 0f          // deg, torso about the feet
    var turn = 0f          // -1..1, fake 3/4 turn of the upper body
    var neckY = 0.22f      // neck height as a fraction of the frame (line-up art: chin ≈ 0.21)

    fun draw(canvas: Canvas, set: PetFrames.FrameSet, dst: RectF, t: Float, breath: Float, outline: Int?, density: Float) {
        val bmp = set.base
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        // aspect-fit, bottom anchored
        val ar = bw / bh
        val dw = if (dst.width() / dst.height() > ar) dst.height() * ar else dst.width()
        val dh = dw / ar
        val l = dst.left + (dst.width() - dw) / 2f
        val top = dst.bottom - dh
        val sx = dw / bw; val sy = dh / bh
        // mesh: breathing (chest rows 0.45..0.8 widen slightly), sway growing towards the bottom/sides (hair)
        var i = 0
        val br = sin(t * 2f * PI.toFloat() / 3.6f) * breath           // ~3.6 s breath
        val sw = sin(t * 2f * PI.toFloat() / 5.3f + 0.7f) * breath     // slower sway
        for (r in 0..ROWS) {
            val fy = r.toFloat() / ROWS
            for (c in 0..COLS) {
                val fx = c.toFloat() / COLS
                var x = fx * bw; var y = fy * bh
                val chest = (1f - kotlin.math.abs(fy - 0.6f) / 0.25f).coerceIn(0f, 1f)
                x += (fx - 0.5f) * bw * 0.018f * br * chest
                y -= bh * 0.006f * br * (1f - fy)                      // head rises a touch on the in-breath
                val side = kotlin.math.abs(fx - 0.5f) * 2f
                x += bw * 0.012f * sw * fy * fy * (0.4f + side)        // hair / skirt ends sway most
                // [Mines-pet-34] articulated pose
                if (turn != 0f) {
                    val up = (1f - fy).coerceIn(0f, 1f)
                    x = bw * 0.5f + (x - bw * 0.5f) * (1f - 0.10f * kotlin.math.abs(turn) * up) + turn * bw * 0.035f * up
                }
                if (lean != 0f) {
                    val w = (1f - fy).let { it * it * (3f - 2f * it) }        // feet stay planted
                    val a = Math.toRadians((lean * w).toDouble())
                    val px = bw * 0.5f; val py = bh
                    val dx = x - px; val dy = y - py
                    x = px + (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
                    y = py + (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
                }
                if (headTilt != 0f || nod != 0f) {
                    val wh = ((neckY + 0.06f - fy) / 0.12f).coerceIn(0f, 1f)   // 0 below the neck, 1 for the head
                    if (wh > 0f) {
                        val a = Math.toRadians((headTilt * wh).toDouble())
                        val px = bw * 0.5f; val py = bh * neckY
                        val dx = x - px; val dy = y - py
                        x = px + (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
                        y = py + (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
                        y += bh * 0.025f * nod * wh * ((neckY - fy) / neckY).coerceIn(0.3f, 1f)
                    }
                }
                verts[i++] = l + x * sx; verts[i++] = top + y * sy
            }
        }
        if (outline != null) {
            outlinePaint.colorFilter = PorterDuffColorFilter(outline, PorterDuff.Mode.SRC_IN)
            val o = 2.2f * density
            for ((ox, oy) in listOf(o to 0f, -o to 0f, 0f to o, 0f to -o)) {
                canvas.save(); canvas.translate(ox, oy)
                canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, outlinePaint)
                canvas.restore()
            }
        }
        paint.alpha = 255
        canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint)
        // frames share the base framing → same mesh, crossfaded on top
        for (n in PetFrames.names) {
            val a = alphaOf(n); if (a < 0.02f) continue
            val f = set.frames[n] ?: continue
            if (f.width != bmp.width || f.height != bmp.height) continue
            paint.alpha = (a * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmapMesh(f, COLS, ROWS, verts, 0, null, 0, paint)
        }
        paint.alpha = 255
    }

    companion object { val OUTLINE_LIGHT = Color.WHITE }
}
