package com.openminis.app.sandbox.offload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [Mines-38.8 v5d] Tier 3: on-device OCR with the ML Kit text recognizer already in the APK
 * (com.google.mlkit:text-recognition 16.0.1, Latin script model; Chinese characters are not read by this model).
 * Screenshot from the accessibility service (Android 11+) or Shizuku `screencap`, recognised in-process; returns
 * one element per text line with its screen bounds, so `tap N` / `tap "文本"` work on games and canvas screens.
 */
internal object ScreenOcr {
    private const val TAG = "ScreenOcr"

    data class Result(val items: List<ScreenTiers.Item>, val error: String? = null, val via: String = "", val ms: Long = 0, val tier: String = ScreenTiers.TIER_OCR)

    fun screenshot(): Pair<Bitmap?, String> {
        val svc = MinisAccessibilityService.getInstance()
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val shot = runCatching { svc.captureScreenshot() }.getOrNull()
            if (shot?.bitmap != null) return shot.bitmap to "无障碍截图"
        }
        if (FastUi.shizukuReady()) runCatching {
            val r = ShizukuManager.runProcess(arrayOf("sh", "-c", "screencap -p | base64"), timeoutMs = 10_000L)
            val bytes = android.util.Base64.decode(r.stdout.replace("\n", "").replace("\r", ""), android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it to "Shizuku 截图" }
        }.onFailure { AppLogger.warning(TAG, "shizuku screencap: ${it.message}") }
        return null to "截不了屏（需要 Android 11+ 的无障碍服务，或 Shizuku）"
    }

    fun read(context: Context): Result {
        val (bmp, via) = screenshot()
        if (bmp == null) return Result(emptyList(), via)
        return try { latin(bmp, via) } finally { runCatching { bmp.recycle() } }
    }

    /**
     * [Mines-38.9] Tier 3 with the cloud step: ML Kit (Latin, on the phone) first; when it reads little, the screen is
     * Chinese or the text looks garbled ([ScreenTiers.wantsCloudOcr]), the same screenshot goes to the user's own
     * image-capable cloud model (Vision Group, else any configured vision model) which returns lines + boxes.
     */
    fun readBest(context: Context, tree: List<ScreenTiers.Item>): Result {
        val (bmp, via) = screenshot()
        if (bmp == null) return Result(emptyList(), via)
        try {
            val lat = latin(bmp, via)
            val zh = runCatching { context.resources.configuration.locales[0].language == "zh" }.getOrDefault(false)
            if (!ScreenTiers.wantsCloudOcr(tree, lat.items, zh)) return lat
            val cl = cloud(context, bmp, via)
            if (cl.items.isNotEmpty()) return cl.copy(ms = cl.ms + lat.ms)
            return lat.copy(error = listOfNotNull(lat.error, cl.error).joinToString("；").ifBlank { null })
        } finally { runCatching { bmp.recycle() } }
    }

    private const val CLOUD_PROMPT = "OCR this phone screenshot (%dx%d px). Return ONLY a JSON array, one object per visible text line in reading order: " +
        "{\"t\":\"exact text\",\"b\":[left,top,right,bottom]} with integer pixel coordinates in THIS image. Keep Chinese and English exactly as shown. " +
        "Include buttons, tabs and labels. No commentary, no markdown."

    /** [Mines-38.9] cloud OCR on [bmp] (downscaled to ≤ 1080 px wide, JPEG 80); boxes scaled back to screen pixels. */
    fun cloud(context: Context, bmp: Bitmap, via: String = ""): Result {
        val t0 = System.currentTimeMillis()
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return Result(emptyList(), "云端 OCR 不可用", via)
        val f = if (bmp.width > 1080) 1080f / bmp.width else 1f
        val small = if (f < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true) else bmp
        val bytes = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val prompt = String.format(java.util.Locale.US, CLOUD_PROMPT, small.width, small.height)
        if (small !== bmp) runCatching { small.recycle() }
        val r = runCatching {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                com.openminis.app.tools.VisionGroupResolver.ocrOnce(app.providerRepository, context, bytes, "image/jpeg", prompt) } }
        }.onFailure { AppLogger.warning(TAG, "cloud ocr: ${it.message}") }.getOrNull()
            ?: return Result(emptyList(), "云端 OCR 没有可用的识图模型（在模型组里设一个识图组，或加一个能看图的模型）", via, System.currentTimeMillis() - t0, ScreenTiers.TIER_OCR_CLOUD)
        val items = ScreenTiers.parseCloudOcr(r.first, 1f / f)
        return Result(items, if (items.isEmpty()) "云端 OCR 没读到字" else null, via, System.currentTimeMillis() - t0, ScreenTiers.TIER_OCR_CLOUD + " " + r.second)
    }

    private fun latin(bmp: Bitmap, via: String): Result {
        val t0 = System.currentTimeMillis()
        return try {
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            val text = com.google.android.gms.tasks.Tasks.await(rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)), 8, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { rec.close() }
            val out = ArrayList<ScreenTiers.Item>()
            for (block in text.textBlocks) for (line in block.lines) {
                val b = line.boundingBox ?: continue
                val s = line.text.trim(); if (s.isEmpty()) continue
                out += ScreenTiers.Item(s.take(60), l = b.left, t = b.top, r = b.right, b = b.bottom, clickable = false, source = "ocr", role = "文字")
            }
            Result(out, null, via, System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ocr failed: ${t.javaClass.simpleName} ${t.message}")
            Result(emptyList(), "OCR 失败：${t.javaClass.simpleName}", via, System.currentTimeMillis() - t0)
        }
    }
}
