package com.openminis.app.tools

import android.content.Context

/**
 * [Mines-tool-tier] 工具裁剪三档。
 *
 * 用户 2026-10-27 要求：lite(10 工具) / medium(15) / heavy(25)，按设备或任务自动选。
 *
 * 为什么：工具定义要全部塞进每次请求的 system prompt，工具越多越费 token、越容易
 * 让模型选错。小任务（改个文件）不需要 25 个工具，弱设备也扛不住。
 *
 * 档位含义：
 *  - LITE   10 个核心：读写文件、跑命令、记忆、看图片。什么设备都能跑，最省 token。
 *  - MEDIUM 15 个：+ 浏览器、知识库、子代理、MCP。默认档。
 *  - HEAVY  25 个：全开。
 *
 * 自动选档（[auto]）：
 *  - 设备内存 <= 4GB 或 CPU 核 <= 4 → LITE
 *  - 内存 >= 8GB 且核 >= 8 → HEAVY
 *  - 其余 → MEDIUM
 *
 * 存 `agent_settings` 的 `agent.tools.tier`（"auto" / "lite" / "medium" / "heavy"）。
 * 与 [AgentToolSwitch] 并存：档位先做粗筛，单工具开关再做细调。
 */
enum class ToolTier(val id: String, val label: String, val maxTools: Int) {
    LITE("lite", "轻量（10 工具）", 10),
    MEDIUM("medium", "中等（15 工具）", 15),
    HEAVY("heavy", "全量（25 工具）", 25),
    ;

    companion object {
        const val PREFS = AgentToolSwitch.PREFS
        const val KEY = "agent.tools.tier"
        const val AUTO = "auto"

        /** 各档允许的工具名。没列出的 = 该档不带。 */
        val LITE_TOOLS = setOf(
            "shell_execute",
            "file_read",
            "file_write",
            "file_edit",
            "read_image",
            "memory_write",
            "memory_get",
            "todo_write",
            "ask_user",
            "finish",
        )

        val MEDIUM_TOOLS = LITE_TOOLS + setOf(
            "browser_use",
            "search_knowledge",
            "subagent_task",
            "delegate_task",
            "agent_status",
            "minis_config",
        )

        val HEAVY_TOOLS = MEDIUM_TOOLS + setOf(
            "web_search",
            "web_fetch",
            "image_generate",
            "speech_to_text",
            "text_to_speech",
            "mcp_call",
            "mcp_list",
            "plugin_call",
            "persona",
            "scheduled_task",
        )

        fun forName(n: String): ToolTier? = entries.firstOrNull { it.id.equals(n, true) }
    }

    /** 该档是否带这个工具。 */
    fun allows(toolName: String): Boolean = when (this) {
        LITE -> toolName in LITE_TOOLS
        MEDIUM -> toolName in MEDIUM_TOOLS
        HEAVY -> true   // 全开，不拦
    }
}

object ToolTierStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            AgentToolSwitch.PREFS,
            Context.MODE_PRIVATE,
        )

    /** 用户选的档位字符串（可能是 "auto"）。 */
    fun rawChoice(context: Context): String =
        prefs(context).getString(ToolTier.KEY, ToolTier.AUTO) ?: ToolTier.AUTO

    fun setChoice(context: Context, value: String) =
        prefs(context).edit().putString(ToolTier.KEY, value).apply()

    /**
     * 实际生效的档位：用户选死了就用选的，"auto" 则按设备能力推断。
     */
    fun effective(context: Context): ToolTier {
        val raw = rawChoice(context)
        if (raw != ToolTier.AUTO) {
            ToolTier.forName(raw)?.let { return it }
        }
        return autoTier()
    }

    /** 按设备内存和 CPU 核数推断。 */
    fun autoTier(): ToolTier {
        val cores = Runtime.getRuntime().availableProcessors()
        val memGb = deviceMemGb()
        return when {
            memGb in 0.1..4.0 || cores <= 4 -> ToolTier.LITE
            memGb >= 8.0 && cores >= 8 -> ToolTier.HEAVY
            else -> ToolTier.MEDIUM
        }
    }

    /** 从 /proc/meminfo 读总内存（GB）。读不到返回 0。 */
    private fun deviceMemGb(): Double = runCatching {
        java.io.File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemTotal:") }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024.0 * 1024.0)
            ?: 0.0
    }.getOrDefault(0.0)

    /**
     * 总闸：这个工具在当前档位下要不要暴露。
     * 先看档位，再看单工具开关 —— 任一关掉就不给。
     */
    fun allows(context: Context, toolName: String): Boolean {
        if (!effective(context).allows(toolName)) return false
        return AgentToolSwitch.isToolEnabled(context, toolName)
    }
}
