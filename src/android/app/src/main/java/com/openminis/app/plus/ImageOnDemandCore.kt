package com.openminis.app.plus

/**
 * [Mines-38.9.6 i17] Images sent to the agent work like Hark (user 2026-10-18): the picture is saved as a file in the
 * sandbox (original + a model copy, long edge ≤ 1568 px, JPEG q85) and the message carries only a short marker with the
 * path, type and size. A vision model looks at it with read_image when it needs to; a text-only model is told plainly
 * where the image is. No base64 rides along in every later turn. Pure (JVM-testable).
 */
object ImageOnDemandCore {
    const val MAX_EDGE = 1568
    const val QUALITY = 85

    /** target size for the model copy, or null when the original already fits */
    fun fit(w: Int, h: Int, max: Int = MAX_EDGE): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val long = maxOf(w, h)
        if (long <= max) return null
        val s = max.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /** model copy name next to the original: photo.png → photo.1568.jpg */
    fun viewName(original: String): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        return "$base.$MAX_EDGE.jpg"
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "$bytes B"
    }

    /** [Mines-38.9.11 b] a model with native vision gets the model copy inline in the turn it was sent (user 「图片看不见」). */
    fun inlineNow(nativeVision: Boolean): Boolean = nativeVision

    /**
     * One marker line per image. [viewPath] = the copy to read (the original when it already fits).
     * [readable] = the current model can look at images (native vision, or read_image routed to a Vision Group / an
     * auto-picked vision model). [inlined] = the pixels are already in this message (this turn only).
     */
    fun marker(name: String, path: String, viewPath: String, mime: String, bytes: Long, w: Int, h: Int, readable: Boolean, inlined: Boolean = false): String {
        val dims = if (w > 0 && h > 0) "，${w}×${h}" else ""
        val orig = if (viewPath != path) "（原图 $path）" else ""
        return when {
            inlined -> "[图片 $name（文件 $viewPath$orig，$mime，${sizeText(bytes)}$dims）发送时已直接附在这条消息里；看不到图时用 read_image $viewPath]"
            readable -> "[图片 $name：$viewPath$orig，$mime，${sizeText(bytes)}$dims。用户发了这张图，回答前先用 read_image $viewPath 看一下]"
            else -> "[图片 $name 已存到 $viewPath$orig，$mime，${sizeText(bytes)}$dims。当前模型不能看图片；告诉用户：在 模型组 加一个能看图的模型（例如谷歌账号登录的 Gemini）就能看，或用命令行工具处理这个文件]"
        }
    }
}
