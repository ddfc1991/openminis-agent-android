package com.openminis.app.plus

/** [38.9.15.1] the instant one-line answer to a message sent while a run is going (no model call). Pure. */
object QueuedAck {
    fun line(step: String, steps: Int, elapsedMs: Long): String {
        val s = step.trim().take(24)
        val took = if (elapsedMs >= 60_000) "已做 ${elapsedMs / 60_000} 分钟" else if (elapsedMs > 0) "已做 ${(elapsedMs / 1000).coerceAtLeast(1)} 秒" else ""
        val where = listOfNotNull(s.takeIf { it.isNotEmpty() }?.let { "正在：$it" }, steps.takeIf { it > 0 }?.let { "第 $it 步" }, took.ifEmpty { null })
            .joinToString(" · ")
        return "收到，" + (if (where.isEmpty()) "还在做" else where) + "；这步做完马上回你"
    }
}
