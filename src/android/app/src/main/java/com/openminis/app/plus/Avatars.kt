package com.openminis.app.plus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import com.openminis.app.R
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * [Mines-avatars-24] One built-in character: full-body art ([full]), a round
 * head crop on its own pastel backdrop ([face]), and three expressions the
 * desk pet switches between (neutral idle, happy, laughing).
 * [Mines-avatars-27] Faces and expressions redrawn in the user's reference
 * line-up style: normal adult proportions (no chibi / Q-version). The
 * expressions are waist-up cut-outs that fade out at the bottom edge.
 */
data class BuiltinAvatar(
    val id: String, val name: String, val full: Int, val face: Int,
    val neutral: Int, val happy: Int, val laugh: Int,
) {
    fun chibi(expression: String): Int = when (expression) {
        Avatars.HAPPY -> happy
        Avatars.LAUGH -> laugh
        else -> neutral
    }
}

/**
 * The avatar shown on the Today card, the voice screen and the desk pet:
 * six built-in anime maid characters, or the user's own picture / GIF /
 * animated WebP (circle-cropped, optional plain-background removal).
 */
object Avatars {
    const val CUSTOM = "custom"
    const val NEUTRAL = "neutral"
    const val HAPPY = "happy"
    const val LAUGH = "laugh"

    // [Mines-avatars-24] Seven characters redrawn from the user's reference
    // line-up (maid-style girls) and the DeepSeek-chan chibi sheet.
    // [Mines-design-34] No 「娘」 suffix (user, 2026-10-13); Claude (orange hair) first = the default.
    val builtins = listOf(
        BuiltinAvatar("claude", "Claude", R.drawable.avatar_claude, R.drawable.avatar_claude_face,
            R.drawable.avatar_claude_neutral, R.drawable.avatar_claude_happy, R.drawable.avatar_claude_laugh),
        BuiltinAvatar("whale", "小鲸", R.drawable.avatar_whale, R.drawable.avatar_whale_face,
            R.drawable.avatar_whale_neutral, R.drawable.avatar_whale_happy, R.drawable.avatar_whale_laugh),
        // [Mines-avatars-25] Names fixed per the user: the black-haired maid is
        // Grok's character, the white-haired horned one is GPT's; no Tongyi.
        BuiltinAvatar("grok", "Grok", R.drawable.avatar_grok, R.drawable.avatar_grok_face,
            R.drawable.avatar_grok_neutral, R.drawable.avatar_grok_happy, R.drawable.avatar_grok_laugh),
        BuiltinAvatar("gpt", "GPT", R.drawable.avatar_gpt, R.drawable.avatar_gpt_face,
            R.drawable.avatar_gpt_neutral, R.drawable.avatar_gpt_happy, R.drawable.avatar_gpt_laugh),
        BuiltinAvatar("gemini", "Gemini", R.drawable.avatar_gemini, R.drawable.avatar_gemini_face,
            R.drawable.avatar_gemini_neutral, R.drawable.avatar_gemini_happy, R.drawable.avatar_gemini_laugh),
        BuiltinAvatar("kimi", "Kimi", R.drawable.avatar_kimi, R.drawable.avatar_kimi_face,
            R.drawable.avatar_kimi_neutral, R.drawable.avatar_kimi_happy, R.drawable.avatar_kimi_laugh),
        BuiltinAvatar("wenxin", "文心", R.drawable.avatar_wenxin, R.drawable.avatar_wenxin_face,
            R.drawable.avatar_wenxin_neutral, R.drawable.avatar_wenxin_happy, R.drawable.avatar_wenxin_laugh),
    )

    /** Ring / accent colours (terracotta first, the Mines brand colour). */
    val colors = listOf(
        0xFFD97757.toInt(), 0xFFF4F0E8.toInt(), 0xFF5B8DEF.toInt(), 0xFF34C759.toInt(),
        0xFFAF52DE.toInt(), 0xFFFF6F91.toInt(), 0xFF8E8E93.toInt(),
    )

    /** Old styles (penguin / cat / ghost) map to the default character. */
    /**
     * [Mines-avatars-25] build-24 ids → build-25 ids, applied once per install:
     * "gpt" (black-haired art) became "grok", "qwen" (white-haired art) became "gpt".
     * Same pictures, so a user keeps the character they picked.
     */
    fun migrate24(style: String): String = when (style) {
        "gpt" -> "grok"
        "qwen" -> "gpt"
        else -> style
    }

    fun normalize(style: String): String =
        if (style == CUSTOM || builtins.any { it.id == style }) style else builtins.first().id

    fun name(style: String): String = builtins.firstOrNull { it.id == style }?.name ?: "我的形象"

    fun sourceFile(context: Context) = File(context.filesDir, "plus_avatar_src")
    fun noBgFile(context: Context) = File(context.filesDir, "plus_avatar_nobg.png")
    fun pickFile(context: Context) = File(context.filesDir, "plus_avatar_pick")

    fun hasCustom(context: Context) = sourceFile(context).exists()

    /** True for GIF / animated WebP / animated HEIF content. */
    fun isAnimated(file: File): Boolean {
        if (!file.exists()) return false
        val head = ByteArray(64)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 12) return false
        val s = String(head, 0, n, Charsets.ISO_8859_1)
        return s.startsWith("GIF8") || (s.startsWith("RIFF") && s.substring(8, 12) == "WEBP" && s.contains("ANIM")) ||
            (s.startsWith("RIFF") && s.substring(8, 12) == "WEBP" && s.contains("VP8X") && (head[20].toInt() and 0x02) != 0)
    }

    /** Decodes [file] as a (possibly animated) drawable; animation needs Android 9+. */
    fun decode(context: Context, file: File): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 768) {
                    val f = 768f / longest
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= 768) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { BitmapDrawable(context.resources, it) }
        }
    }.getOrNull()

    /**
     * Removes a plain background: flood-fills from the image border through pixels
     * close to the border colours, then softens the edge. Works best on photos /
     * pictures with a fairly even background. Writes [noBgFile].
     */
    fun removeBackground(context: Context): Boolean = runCatching {
        val src = sourceFile(context)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 640) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return false
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Reference colours: samples along the border.
        val refs = ArrayList<Int>()
        for (i in 0 until 16) {
            refs += px[(i * (w - 1) / 15)]                       // top
            refs += px[(h - 1) * w + (i * (w - 1) / 15)]         // bottom
            refs += px[(i * (h - 1) / 15) * w]                   // left
            refs += px[(i * (h - 1) / 15) * w + (w - 1)]         // right
        }
        fun near(c: Int, tol: Int): Boolean = refs.any { r ->
            abs(Color.red(c) - Color.red(r)) + abs(Color.green(c) - Color.green(r)) + abs(Color.blue(c) - Color.blue(r)) < tol
        }
        val bg = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(i: Int) { if (!bg[i] && Color.alpha(px[i]) > 0 && near(px[i], 60)) { bg[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) seed(i - 1); if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w); if (y < h - 1) seed(i + w)
        }
        var removed = 0
        for (i in px.indices) {
            if (bg[i]) { px[i] = 0; removed++ }
            else {
                // Soft edge: half-transparent where a neighbour is background.
                val x = i % w; val y = i / w
                val edge = (x > 0 && bg[i - 1]) || (x < w - 1 && bg[i + 1]) || (y > 0 && bg[i - w]) || (y < h - 1 && bg[i + w])
                if (edge) px[i] = (px[i] and 0x00FFFFFF) or (0xB0 shl 24)
            }
        }
        if (removed < w * h / 50) return false // nothing that looks like a plain background
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        noBgFile(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)
}

/** Crop of the user's own picture inside the circle. */
data class AvatarCrop(val scale: Float = 1f, val offX: Float = 0f, val offY: Float = 0f)

/**
 * Draws the current avatar (built-in or custom) on a canvas. Shared by the
 * Compose [AvatarView] and the desk-pet overlay so both always match.
 */
class AvatarRenderer(private val context: Context) {
    private var key: String? = null
    private var builtinFull: Bitmap? = null
    private var builtinFace: Bitmap? = null
    private var builtin: BuiltinAvatar? = null
    private val chibi = HashMap<String, Bitmap>()
    private var custom: Drawable? = null
    var callback: Drawable.Callback? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()

    private fun load(style: String, version: Long, noBg: Boolean) {
        val k = "$style/$version/$noBg"
        if (k == key) return
        key = k
        (custom as? Animatable)?.stop()
        custom = null; builtinFull = null; builtinFace = null; builtin = null; chibi.clear()
        if (style == Avatars.CUSTOM) {
            val f = if (noBg && Avatars.noBgFile(context).exists()) Avatars.noBgFile(context) else Avatars.sourceFile(context)
            custom = Avatars.decode(context, f)?.also { d ->
                d.callback = callback
                (d as? Animatable)?.start()
            }
            if (custom != null) return
        }
        val b = Avatars.builtins.firstOrNull { it.id == style } ?: Avatars.builtins.first()
        builtin = b
        // [Mines-avatars-27] Mipmaps keep the 512 px face crisp when it is drawn
        // at 40–56 dp (bilinear-only downscaling by 3× looks grainy).
        builtinFace = BitmapFactory.decodeResource(context.resources, b.face)?.also { it.setHasMipMap(true) }
    }

    fun stop() { (custom as? Animatable)?.stop(); key = null }

    /**
     * @param face true = round head crop (Today card, lists); false = full body (desk pet, large previews)
     * @param bob vertical breathing offset as a fraction of height
     */
    fun draw(
        canvas: Canvas, w: Float, h: Float,
        style: String, face: Boolean, ringColor: Int?, bob: Float = 0f,
        // [Mines-pet-motion] Lateral sway and a vertical squash, so the desk
        // pet can breathe, drift and dip instead of only translating. Both
        // default to "no motion" so every existing caller (the Today card, the
        // voice screen, the avatar picker) keeps its current still rendering.
        sway: Float = 0f,
        squash: Float = 1f,
        crop: AvatarCrop = AvatarCrop(), version: Long = 0L, noBg: Boolean = false,
        // [Mines-avatars-24] Non-face rendering: the chibi in this expression
        // (desk pet, small previews), or the full-body art when [fullBody].
        expression: String = Avatars.NEUTRAL,
        fullBody: Boolean = false,
    ) {
        load(Avatars.normalize(style), version, noBg)
        val size = minOf(w, h)
        val left = (w - size) / 2f + sway * size
        val top = (h - size) / 2f + bob * size
        val c = custom
        if (c != null) {
            // Custom picture: always a circle, user crop applied.
            canvas.save()
            clip.reset(); clip.addCircle(left + size / 2, top + size / 2, size / 2, Path.Direction.CW)
            canvas.clipPath(clip)
            val dw = c.intrinsicWidth.coerceAtLeast(1).toFloat(); val dh = c.intrinsicHeight.coerceAtLeast(1).toFloat()
            val base = max(size / dw, size / dh) * crop.scale.coerceIn(1f, 5f)
            val sw = dw * base; val sh = dh * base
            val cx = left + size / 2 + crop.offX * size; val cy = top + size / 2 + crop.offY * size
            c.setBounds((cx - sw / 2).toInt(), (cy - sh / 2).toInt(), (cx + sw / 2).toInt(), (cy + sh / 2).toInt())
            c.draw(canvas)
            canvas.restore()
            drawRing(canvas, left, top, size, ringColor)
            return
        }
        if (face) {
            val bmp = builtinFace ?: return
            // Squash about the bottom edge, the way a head tucks rather than
            // shrinking in place — scaling about the centre reads as a zoom.
            val drawH = size * squash
            val rect = RectF(left, top + (size - drawH), left + size, top + size)
            canvas.save()
            clip.reset(); clip.addCircle(left + size / 2, top + size / 2, size / 2, Path.Direction.CW)
            canvas.clipPath(clip)
            // [Mines-avatars-24] No tinted wash behind the head any more: the
            // face art carries its own opaque pastel backdrop, so it renders
            // crisp instead of looking dimmed through a 20% ring-colour fill.
            canvas.drawBitmap(bmp, null, rect, paint)
            canvas.restore()
            drawRing(canvas, left, top, size, ringColor)
        } else {
            val b = builtin ?: return
            val bmp = if (fullBody) {
                builtinFull ?: BitmapFactory.decodeResource(context.resources, b.full)?.also { it.setHasMipMap(true); builtinFull = it }
            } else {
                chibi[expression] ?: BitmapFactory.decodeResource(context.resources, b.chibi(expression))
                    ?.also { it.setHasMipMap(true); chibi[expression] = it }
            } ?: return
            // Aspect-fit, anchored to the bottom so the feet stay planted while
            // the character breathes (squash about the bottom edge).
            val ar = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val dw = if (ar <= 1f) size * ar else size
            val dh = (if (ar <= 1f) size else size / ar) * squash
            val l = left + (size - dw) / 2f
            val t = top + (size - dh)
            canvas.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), paint)
        }
    }

    private fun drawRing(canvas: Canvas, left: Float, top: Float, size: Float, ringColor: Int?) {
        val rc = ringColor ?: return
        ring.color = rc
        ring.strokeWidth = (size * 0.022f).coerceAtLeast(1.5f)
        canvas.drawCircle(left + size / 2, top + size / 2, size / 2 - ring.strokeWidth / 2, ring)
    }
}

/** A plain View around [AvatarRenderer]; used from Compose via AndroidView. */
class AvatarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val renderer = AvatarRenderer(context).also { r ->
        r.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { postDelayed(what, `when` - android.os.SystemClock.uptimeMillis()) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { removeCallbacks(what) }
        }
    }
    var style: String? = null
    var face: Boolean = true
    var bob: Boolean = false
    var showRing: Boolean = true
    var crop: AvatarCrop? = null
    var noBg: Boolean? = null
    var version: Long? = null
    var fullBody: Boolean = false
    var expression: String = Avatars.NEUTRAL
    private val start = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = PlusPrefs.state.value
        val t = (System.currentTimeMillis() - start) / 1000f
        // [Mines-pet-motion] The old motion was a single 0.02-amplitude sine at
        // 2.2 rad/s — 2% of the height, which reads as nothing at all, so the
        // desk pet looked like a static picture. Three composable motions
        // instead, each on its own frequency so they never lock into one
        // visible cycle:
        //   • breathing — a slow vertical bob, 6% of the height
        //   • sway      — a lateral drift, 2.5% of the width, out of phase
        //   • blink     — the built-in *_face drawables have no separate eye
        //                  frames, so "blink" is a brief scale dip on Y,
        //                  which reads as the character tucking its chin
        // The custom (GIF / animated WebP) path animates itself through
        // Animatable and is left alone.
        val breathing = sin(t * 1.6f) * 0.06f
        val sway = sin(t * 0.9f + 1.1f) * 0.025f
        val blink = if (blinkPhase(t)) 0.94f else 1f
        renderer.draw(
            canvas, width.toFloat(), height.toFloat(),
            style ?: st.petStyle, face, if (showRing) st.petColor else null,
            bob = if (bob) breathing else 0f,
            sway = if (bob) sway else 0f,
            squash = if (bob) blink else 1f,
            crop = crop ?: AvatarCrop(st.avatarScale, st.avatarOffX, st.avatarOffY),
            version = version ?: st.avatarVersion,
            noBg = noBg ?: st.avatarRemoveBg,
            expression = expression, fullBody = fullBody,
        )
        if (bob) postInvalidateDelayed(33)
    }

    /** True during the ~120 ms dip of a ~4 s blink cycle. */
    private fun blinkPhase(t: Float): Boolean {
        val phase = (t * 1000f) % 4000f
        return phase < 120f
    }

    override fun onDetachedFromWindow() {
        renderer.stop()
        super.onDetachedFromWindow()
    }
}
