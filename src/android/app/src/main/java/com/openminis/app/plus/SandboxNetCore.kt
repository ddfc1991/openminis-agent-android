package com.openminis.app.plus

/** Pure formatting for the sandbox route diagnostics shown in Settings. */
object SandboxNetCore {
    /** Never include proxy user-info or control characters in a visible host label. */
    fun safeAuthority(value: String): String = value
        .substringAfter("://", value)
        .substringAfterLast('@')
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .filterNot { it.isISOControl() }
        .trim()

    fun splitStatus(
        port: Int,
        configured: Boolean,
        active: String?,
        activeHealth: String?,
        fallbacks: List<String>,
    ): String {
        val entrance = "分流入口 127.0.0.1:$port"
        val backups = fallbacks.filter { it != active }.distinct()
        val backupText = if (backups.isEmpty()) "无其它已配置上游" else "备用上游：${backups.joinToString("、")}"
        return when {
            active != null -> "$entrance；当前选择 $active${activeHealth?.let { "（$it）" }.orEmpty()}；$backupText"
            configured -> "$entrance；目前没有可用上游（$backupText），非 Google 目标按规则直连，Google 目标会提示代理不可用"
            else -> "$entrance；未配置上游，境外目标按规则直连"
        }
    }

    fun selectedLocalStatus(port: Int, probeSummary: String, splitUnavailable: Boolean): String =
        (if (splitUnavailable) "分流入口 127.0.0.1:${SplitProxy.PORT} 未通过检测；" else "") +
            "沙箱使用本机代理 127.0.0.1:$port；$probeSummary"

    fun systemStatus(authority: String, probeSummary: String): String =
        "沙箱使用系统代理 $authority；本机候选：$probeSummary"

    fun directStatus(reason: String, probeSummary: String, splitFallback: String = ""): String =
        "$reason；本机候选：$probeSummary$splitFallback"

    fun splitFallbackStatus(port: Int, upstreams: List<String>): String =
        if (upstreams.isEmpty()) "；分流入口 127.0.0.1:$port 未接入，未配置上游"
        else "；分流入口 127.0.0.1:$port 未接入，已回退；分流上游候选：${upstreams.joinToString("、")}"
}
