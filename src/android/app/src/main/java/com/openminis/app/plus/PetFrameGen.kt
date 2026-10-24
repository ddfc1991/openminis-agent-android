package com.openminis.app.plus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [Mines-pet-32] 「生成动作」: makes the desk pet's frames (blink, mouth A/O, think)
 * from the user's own picture with Gemini's image-editing model (Nano Banana;
 * ai.google.dev/gemini-api/docs/image-generation, checked 2026-10-13: the
 * Interactions API with gemini-nano-banana-2.1 / gemini-3.1-flash-image, the
 * older generateContent path with gemini-2.5-flash-image as fallback). The
 * original goes along as the reference every time; only the face area of each
 * result is blended back onto the original (same framing → no jitter).
 * Cost: 4 image edits on the user's Gemini key (free tier when available).
 */
object PetFrameGen {
    val progress = MutableStateFlow<String?>(null)

    private val prompts = linkedMapOf(
        PetFrames.BLINK to "Change ONLY the eyes: both eyes gently closed in a natural blink. Mouth unchanged.",
        PetFrames.MOUTH_A to "Change ONLY the mouth: open as if saying \"ah\" while talking. Eyes unchanged.",
        PetFrames.MOUTH_O to "Change ONLY the mouth: a small round \"oh\" shape while talking. Eyes unchanged.",
        PetFrames.THINK to "Change ONLY the eyes: glancing upward to one side, thoughtful. Mouth unchanged.",
    )
    private val models = listOf("gemini-nano-banana-2.1", "gemini-3.1-flash-image", "gemini-2.5-flash-image")

    /** Returns a one-line result for the UI. */
    suspend fun generate(context: Context): String = withContext(Dispatchers.IO) {
        PlusVault.init(context)
        val key = GeminiRoute.apiKey() ?: return@withContext "要先在「应用」里连上 Gemini（填 API Key），才能生成动作"
        val src = listOf(Avatars.noBgFile(context), Avatars.sourceFile(context)).firstOrNull { it.exists() }
            ?: return@withContext "先在上面选一张你自己的图片作为形象"
        val base0 = BitmapFactory.decodeFile(src.absolutePath) ?: return@withContext "这张图片读不出来，换一张试试"
        // keep it modest: long side 820 px like the built-in frames
        val sc = 820f / maxOf(base0.width, base0.height)
        val base = if (sc < 1f) Bitmap.createScaledBitmap(base0, (base0.width * sc).toInt(), (base0.height * sc).toInt(), true) else base0
        val png = ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val dir = PetFrames.customDir(context).apply { mkdirs() }
        File(dir, "base.png").outputStream().use { base.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val route = runCatching { GeminiRoute.routes(context, false).firstOrNull() }.getOrNull()
        var ok = 0; var lastErr = ""
        prompts.entries.forEachIndexed { i, (name, p) ->
            progress.value = "正在生成第 ${i + 1}/${prompts.size} 个动作…"
            val prompt = "Edit the reference image: the exact same character, identical pose, framing, crop, size, position, clothing, hair, colours and line art. $p Everything else identical. Same background. No text, no watermark."
            val out = runCatching { edit(route, key, prompt, png) }.onFailure { lastErr = it.message ?: "" }.getOrNull()
            if (out != null) {
                val merged = blendFace(base, out)
                File(dir, "$name.png").outputStream().use { merged.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ok++
            }
        }
        progress.value = null
        PetFrames.invalidate()
        PlusPrefs.update(context) { it.copy(avatarVersion = it.avatarVersion + 1) }
        if (ok == 0) "没生成出来：${lastErr.take(80)}" else "已生成 $ok 个动作，桌宠会眨眼、说话时动嘴了"
    }

    private fun edit(route: GeminiRoute.Route?, key: String, prompt: String, png: ByteArray): Bitmap {
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
        val client = if (route != null) GeminiRoute.client(route, 120_000) else okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
        var err = ""
        for (m in models) {
            val (url, body) = if (m == "gemini-2.5-flash-image")
                "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent" to JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", b64))))))
            else "https://generativelanguage.googleapis.com/v1beta/interactions" to JSONObject().put("model", m).put("input", JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt)).put(JSONObject().put("type", "image").put("mime_type", "image/png").put("data", b64)))
            val r = runCatching {
                client.newCall(Request.Builder().url(url).header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.code to it.body?.string().orEmpty() }
            }.getOrElse { e -> err = e.message ?: ""; null } ?: continue
            if (r.first !in 200..299) { err = "HTTP ${r.first} " + r.second.take(120); continue }
            val data = findImage(JSONObject(r.second)) ?: run { err = "回复里没有图片"; null } ?: continue
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        throw IllegalStateException(err)
    }

    /** Finds the first base64 image in either API's answer. */
    private fun findImage(o: Any?): String? {
        when (o) {
            is JSONObject -> {
                if (o.optString("type") == "image" && o.has("data")) return o.optString("data")
                (o.optJSONObject("inlineData") ?: o.optJSONObject("inline_data"))?.let { d -> if (d.optString("mimeType", d.optString("mime_type")).startsWith("image")) return d.optString("data") }
                for (k in o.keys()) findImage(o.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) findImage(o.opt(i))?.let { return it }
        }
        return null
    }

    /** Blend only the changed face area of [gen] onto [base] (top half, feathered). */
    private fun blendFace(base: Bitmap, gen0: Bitmap): Bitmap {
        val w = base.width; val h = base.height
        val gen = Bitmap.createScaledBitmap(gen0, w, h, true)
        val a = IntArray(w * h); val g = IntArray(w * h)
        base.getPixels(a, 0, w, 0, 0, w, h); gen.getPixels(g, 0, w, 0, 0, w, h)
        fun lum(c: Int) = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
        val lim = (h * 0.5f).toInt()
        // diff mask on a 4× coarser grid, dilated, then feathered by distance
        val cw = (w + 3) / 4; val ch = (lim + 3) / 4
        val m = FloatArray(cw * ch)
        for (y in 0 until lim) for (x in 0 until w) {
            val d = kotlin.math.abs(lum(a[y * w + x]) - lum(g[y * w + x]))
            if (d > 40) m[(y / 4) * cw + x / 4] += 1f / 16f
        }
        val dense = FloatArray(cw * ch)
        val r = 3
        for (y in 0 until ch) for (x in 0 until cw) {
            var s = 0f; var n = 0
            for (yy in maxOf(0, y - r)..minOf(ch - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(cw - 1, x + r)) { s += m[yy * cw + xx]; n++ }
            dense[y * cw + x] = if (s / n > 0.2f) 1f else 0f
        }
        val out = a.copyOf()
        for (y in 0 until lim) for (x in 0 until w) {
            // soft edge: average dense over a 3×3 coarse neighbourhood
            val cy = y / 4; val cx = x / 4
            var s = 0f; var n = 0
            for (yy in maxOf(0, cy - 2)..minOf(ch - 1, cy + 2)) for (xx in maxOf(0, cx - 2)..minOf(cw - 1, cx + 2)) { s += dense[yy * cw + xx]; n++ }
            val k = s / n
            if (k <= 0f) continue
            val i = y * w + x; val pa = a[i]; val pg = g[i]
            fun mix(sh: Int) = (((pa shr sh) and 255) * (1 - k) + ((pg shr sh) and 255) * k).toInt()
            out[i] = (pa and (0xff shl 24)) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
