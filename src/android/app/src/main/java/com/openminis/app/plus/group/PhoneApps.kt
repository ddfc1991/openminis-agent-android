package com.openminis.app.plus.group

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * [Mines-group-37] Which AI apps on the phone can join a group chat, and why.
 * Installed apps are found with PackageManager (QUERY_ALL_PACKAGES is already
 * granted to Mines); nothing leaves the phone.
 */
object PhoneApps {
    enum class Verdict(val label: String) { API("能接"), TYPING("代打字"), INDIRECT("间接"), NO("不能接") }

    data class Known(
        val name: String,
        val packages: List<String>,
        val labelHints: List<String>,
        val verdict: Verdict,
        val reason: String,
        /** AiCatalog id that replaces the app (API verdict). */
        val partner: String? = null,
    )

    data class Found(val known: Known, val pkg: String, val label: String)

    val known = listOf(
        Known("ChatGPT", listOf("com.openai.chatgpt"), listOf("ChatGPT"), Verdict.API, "用你的 OpenAI 账号登录，Mines 自动拿到钥匙", "openai"),
        Known("Claude", listOf("com.anthropic.claude"), listOf("Claude"), Verdict.API, "用你的 Anthropic 账号登录即可", "anthropic"),
        Known("Grok", listOf("ai.x.grok"), listOf("Grok"), Verdict.API, "用你的 X / xAI 账号登录即可", "xai"),
        Known("DeepSeek", listOf("com.deepseek.chat"), listOf("DeepSeek"), Verdict.API, "手机号登录 DeepSeek 即可，便宜稳定", "deepseek"),
        Known("Gemini", listOf("com.google.android.apps.bard"), listOf("Gemini"), Verdict.API, "用你的 Google 账号登录即可", "gemini"),
        Known("Kimi", listOf("com.moonshot.kimichat"), listOf("Kimi"), Verdict.API, "手机号登录 Kimi 开放平台即可", "kimi"),
        Known("智谱清言", listOf("com.zhipuai.qingyan"), listOf("智谱清言", "清言"), Verdict.API, "手机号登录智谱开放平台即可", "zhipu"),
        Known("豆包", listOf("com.larus.nova"), listOf("豆包"), Verdict.API, "登录火山引擎即可（同一模型，没有 App 里的聊天记录）", "volcengine"),
        Known("元宝", listOf("com.tencent.hunyuan.app.chat"), listOf("元宝"), Verdict.API, "登录腾讯云即可（同一模型，没有 App 里的聊天记录）", "hunyuan"),
        Known("海螺 / MiniMax", listOf("com.minimax.ai", "com.hailuo.ai"), listOf("海螺", "MiniMax"), Verdict.API, "手机号登录 MiniMax 即可", "minimax"),
        Known("Perplexity", listOf("ai.perplexity.app.android"), listOf("Perplexity"), Verdict.API, "登录 Perplexity 即可", "perplexity"),
        Known("Manus", listOf("tech.butterfly.app", "im.manus.app"), listOf("Manus"), Verdict.API, "登录 Manus 即可（做任务，回复较慢）", "manus"),
        Known("扣子", listOf("com.coze.space", "com.larus.coze"), listOf("扣子", "Coze"), Verdict.API, "登录扣子即可，自动找到你的智能体", "coze"),
        Known("WorkBuddy", emptyList(), listOf("WorkBuddy"), Verdict.API, "打开 WorkBuddy，Mines 自动接上", "workbuddy"),
        Known("Muse", listOf("ai.muse.app", "com.muse.ai"), listOf("Muse"), Verdict.TYPING,
            "官方 App 没有公开接口，只能无障碍代打字（慢、可能断）；你的 nanoMuse 中继可以直接进群"),
        Known("Genspark", listOf("ai.genspark.app", "com.genspark.app"), listOf("Genspark"), Verdict.TYPING, "没有公开接口，只能无障碍代打字（慢、可能断）"),
        Known("Cue", listOf("com.cue.app", "ai.cue.app"), listOf("Cue"), Verdict.TYPING, "没有公开接口，只能无障碍代打字（慢、可能断）"),
        Known("Operit AI", listOf("com.ai.assistance.operit"), listOf("Operit"), Verdict.TYPING, "没有对外接口，只能无障碍代打字（慢、可能断）"),
        Known("Meta AI", listOf("com.facebook.stella", "com.meta.ai"), listOf("Meta AI"), Verdict.TYPING, "国内没有可用 API，只能无障碍代打字（慢、可能断）"),
        Known("MNN Chat", listOf("com.alibaba.mnnllm.android"), listOf("MNN"), Verdict.INDIRECT, "本地模型，直接加载进 Mines 更省事"),
        Known("PocketPal", listOf("com.pocketpalai"), listOf("PocketPal"), Verdict.INDIRECT, "本地模型，直接加载进 Mines 更省事"),
        Known("Edge Gallery", listOf("com.google.ai.edge.gallery"), listOf("Edge Gallery", "AI Edge"), Verdict.INDIRECT, "本地模型，直接加载进 Mines 更省事"),
        Known("飞书 / Telegram / Slack", listOf("com.ss.android.lark", "org.telegram.messenger", "com.Slack"), listOf("飞书", "Telegram", "Slack"),
            Verdict.INDIRECT, "有机器人接口，可当群聊场地；本版群聊开在 Mines 里"),
        Known("小布助手", listOf("com.heytap.speechassist", "com.coloros.speechassist"), listOf("小布"), Verdict.NO, "系统语音助手，没有接口，界面也读不出回复"),
        Known("夸克", listOf("com.quark.browser"), listOf("夸克"), Verdict.NO, "搜索/浏览器 App，没有对话接口，界面不好读"),
        Known("微信", listOf("com.tencent.mm"), listOf("微信"), Verdict.NO, "没有个人号官方接口；自动化收发消息会触发风控，可能封号"),
        Known("QQ", listOf("com.tencent.mobileqq"), listOf("QQ"), Verdict.NO, "没有个人号官方接口；自动化收发消息会触发风控，可能封号"),
    )

    private val aiWords = Regex("""(?i)\bAI\b|GPT|Chat|助手|智能体|大模型""")

    /** Installed launcher apps matched against [known]; unknown AI-looking apps become 代打字 candidates. */
    fun scan(context: Context): List<Found> = runCatching {
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val acts = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        val out = ArrayList<Found>()
        for (ri in acts) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val k = known.firstOrNull { pkg in it.packages } ?: known.firstOrNull { kn ->
                kn.labelHints.any { h -> label.equals(h, true) || (h.length >= 3 && label.contains(h, true)) }
            }
            when {
                k != null -> out += Found(k, pkg, label)
                aiWords.containsMatchIn(label) -> out += Found(
                    Known(label, listOf(pkg), emptyList(), Verdict.TYPING, "没找到公开接口，只能无障碍代打字（慢、可能断）"), pkg, label)
            }
        }
        out.sortedBy { it.known.verdict.ordinal }
    }.getOrDefault(emptyList())

    // ── 代打字 partners ─────────────────────────────────────────────────

    private const val PREFIX = "phone:"
    fun idFor(pkg: String) = PREFIX + pkg
    fun pkgOf(id: String) = id.removePrefix(PREFIX)
    fun isPhone(id: String) = id.startsWith(PREFIX)

    @Volatile private var labels: Map<String, String> = emptyMap()
    fun remember(found: List<Found>) { labels = labels + found.associate { it.pkg to it.label } }

    fun specFor(id: String): AiSpec? {
        if (!isPhone(id)) return null
        val pkg = pkgOf(id)
        val name = labels[pkg] ?: known.firstOrNull { pkg in it.packages }?.name ?: pkg.substringAfterLast('.')
        return AiSpec(id, name, null, Proto.PHONE, pkg, "App", icon = 0, color = 0xFF8A7F74,
            note = "代打字：慢、可能断", keyOptional = true)
    }
}
