package com.openminis.app.plus.group

import com.openminis.app.R

/**
 * [Mines-group-37] Every AI the 群聊 / AI 伙伴 pages know.
 *
 * API partners reuse the keys the user already has in 账号连接 (PlusVault, same
 * kind ids) or in 模型组 (provider instances); nothing new is stored for them.
 * Phone-only apps (no public API) are driven by the accessibility / Shizuku
 * FastUi path ("代打字"): slow, can break when the app changes, default off.
 */
enum class Proto { OPENAI, ANTHROPIC, MANUS, COZE, PHONE, WEBAPP, MINES, /** [Mines-38.8] registry transports (Memoh …) → Adapters */ ADAPTER }

/**
 * [Mines-38.6] user 2026-10-15 「群聊我是要 app、AI agent 这些群聊，不是模型群聊」: members are grouped as
 * AGENT (Manus, 扣子 bots, WorkBuddy, Muse 中继, Mines itself), APP (an AI app's web chat driven with the user's
 * signed-in session, no key) and MODEL (a bare model API — only under 「高级：直接用模型」).
 */
enum class Kind { AGENT, APP, MODEL }

data class AiSpec(
    val id: String,
    val name: String,
    /** PlusVault kind id the key lives under (also the 账号连接 row to open for 「连接」). */
    val vaultKind: String?,
    val proto: Proto,
    val baseUrl: String,
    val defaultModel: String,
    /** Model ids tried in order when the endpoint lists its models (first prefix match wins). */
    val prefer: List<String> = emptyList(),
    val icon: Int = 0,
    val color: Long = 0xFF8A7F74,
    val domestic: Boolean = false,
    val note: String = "",
    /** Hosts that identify this service in a 模型组 provider's base URL. */
    val hosts: List<String> = emptyList(),
    /** Needs no key (local gateway / own relay that may be open). */
    val keyOptional: Boolean = false,
    val kind: Kind = Kind.MODEL,
    /** [Mines-38.6] WEBAPP: the chat page the member is driven on (signed-in session of the shared WebView store). */
    val webUrl: String = "",
    /** [Mines-38.6] WEBAPP: session cookie names that mean 「已登录」 (best guess per site; a successful run also marks it). */
    val cookies: List<String> = emptyList(),
    /** [Mines-38.6] remote avatar (扣子 bot icon). */
    val iconUrl: String? = null,
)

object AiCatalog {
    /** [Mines-38.9.1] no built-in address: the relay is the user's own server (set in AI 伙伴), not every user's */
    const val NANOMUSE_BASE = ""
    const val WORKBUDDY_BASE = "http://127.0.0.1:8788/v1"

    val specs: List<AiSpec> by lazy { listOf(
        AiSpec("anthropic", "Claude", "anthropic", Proto.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-4-5",
            listOf("claude-sonnet-4", "claude-haiku-4", "claude-3-5-haiku"), R.drawable.ic_brand_anthropic, 0xFFD97757, hosts = listOf("api.anthropic.com")),
        AiSpec("openai", "ChatGPT", "openai", Proto.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini",
            listOf("gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini"), R.drawable.ic_brand_openai, 0xFF10A37F, hosts = listOf("api.openai.com")),
        AiSpec("xai", "Grok", "xai", Proto.OPENAI, "https://api.x.ai/v1", "grok-3-mini",
            listOf("grok-4-fast", "grok-4", "grok-3-mini"), R.drawable.ic_brand_xai, 0xFF3A3F4B, hosts = listOf("api.x.ai")),
        AiSpec("deepseek", "DeepSeek", "deepseek", Proto.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat",
            listOf("deepseek-chat"), R.drawable.ic_brand_deepseek, 0xFF4D6BFE, domestic = true, hosts = listOf("api.deepseek.com")),
        AiSpec("gemini", "Gemini", "gemini", Proto.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
            listOf("models/gemini-2.5-flash", "gemini-2.5-flash", "models/gemini-flash-latest"), R.drawable.ic_brand_gemini, 0xFF4285F4,
            hosts = listOf("generativelanguage.googleapis.com")),
        AiSpec("kimi", "Kimi", "kimi", Proto.OPENAI, "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            listOf("kimi-k2", "kimi-latest", "moonshot-v1-8k"), R.drawable.ic_brand_kimi, 0xFF1F1F1F, domestic = true, hosts = listOf("api.moonshot.cn", "api.moonshot.ai")),
        AiSpec("zhipu", "智谱 GLM", "zhipu", Proto.OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
            listOf("glm-4.5-flash", "glm-4-flash"), R.drawable.ic_brand_zhipu, 0xFF3859FF, domestic = true, hosts = listOf("open.bigmodel.cn")),
        AiSpec("siliconflow", "硅基流动", "siliconflow", Proto.OPENAI, "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3",
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3", "Qwen/Qwen2.5-7B-Instruct"), R.drawable.ic_brand_siliconflow, 0xFF7C3AED, domestic = true,
            hosts = listOf("api.siliconflow.cn", "api.siliconflow.com")),
        AiSpec("sensenova", "商汤日日新", "sensenova", Proto.OPENAI, "https://api.sensenova.cn/compatible-mode/v1", "SenseChat-5",
            listOf("SenseNova", "SenseChat-5"), R.drawable.ic_brand_sensenova, 0xFF6B4EFF, domestic = true, hosts = listOf("sensenova", "sensetime", "sensecore"),
            note = "优先用你模型组里配好的商汤地址"),
        AiSpec("volcengine", "豆包", "volcengine", Proto.OPENAI, "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-flash-250828",
            listOf("doubao-seed-1-6-flash", "doubao-seed", "doubao-1-5-pro", "doubao"), R.drawable.ic_brand_volcengine, 0xFF3370FF, domestic = true,
            hosts = listOf("volces.com", "volcengineapi.com"), note = "火山方舟 API，同一模型，但没有 App 里的记忆"),
        AiSpec("hunyuan", "元宝", "hunyuan", Proto.OPENAI, "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest",
            listOf("hunyuan-turbos-latest", "hunyuan-lite"), 0, 0xFF1F8B4C, domestic = true, hosts = listOf("hunyuan.cloud.tencent.com"),
            note = "腾讯混元 API，同一模型，但没有 App 里的记忆"),
        AiSpec("minimax", "MiniMax", "minimax", Proto.OPENAI, "https://api.minimaxi.com/v1", "MiniMax-M2",
            listOf("MiniMax-M2", "MiniMax-Text-01"), R.drawable.ic_brand_minimax, 0xFFE2165E, domestic = true,
            hosts = listOf("api.minimaxi.com", "api.minimax.chat", "api.minimax.io")),
        AiSpec("qwen", "通义千问", "qwen", Proto.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            listOf("qwen-plus", "qwen-turbo"), R.drawable.ic_brand_qwen, 0xFF615CED, domestic = true, hosts = listOf("dashscope.aliyuncs.com")),
        AiSpec("openrouter", "OpenRouter", "openrouter", Proto.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto",
            emptyList(), R.drawable.ic_brand_openrouter, 0xFF6467F2, hosts = listOf("openrouter.ai"), note = "默认自动选模型，可在成员设置里改"),
        AiSpec("perplexity", "Perplexity", "perplexity", Proto.OPENAI, "https://api.perplexity.ai", "sonar",
            listOf("sonar"), R.drawable.ic_brand_perplexity, 0xFF20808D, hosts = listOf("api.perplexity.ai")),
        AiSpec("manus", "Manus", "manus", Proto.MANUS, "https://api.manus.ai/v2", "standard",
            emptyList(), 0, 0xFF2B2B2B, note = "交给它一件事，它自己做完再回你（几十秒到几分钟，按积分计费）", kind = Kind.AGENT),
        AiSpec("coze", "扣子", "coze", Proto.COZE, "https://api.coze.cn/v3", "",
            emptyList(), 0, 0xFF4E40E5, domestic = true, note = "登录扣子后，你的每个智能体都会自动出现在这里", kind = Kind.AGENT),
        AiSpec("nanomuse", "Muse 中继", null, Proto.OPENAI, NANOMUSE_BASE, "",
            emptyList(), 0, 0xFF8E6CEF, hosts = emptyList(), keyOptional = true,
            note = "你自己部署的 nanoMuse 中继；Muse 官方 App 没有接口", kind = Kind.AGENT),
        AiSpec("workbuddy", "WorkBuddy", null, Proto.OPENAI, WORKBUDDY_BASE, "",
            emptyList(), 0, 0xFF0E9F6E, hosts = listOf("127.0.0.1:8788", "localhost:8788"), keyOptional = true,
            note = "你的 WorkBuddy 网关（手机本机，或经 SSH 接到你的服务器）", kind = Kind.AGENT),
        // [Mines-38.6] Mines itself as a member: the real agent (tools, files, web), not a bare model call
        AiSpec(MINES_ID, "Mines 智能体", null, Proto.MINES, "", "", emptyList(), 0, 0xFFB5532F, keyOptional = true,
            note = "Mines 自己：能用工具、查资料、读文件", kind = Kind.AGENT),
    ).map { PresetRegistry.override(it) }.let { l -> l + webApps + PresetRegistry.extraSpecs((l + webApps).map { it.id }.toSet()) } }  // [Mines-38.8]

    const val MINES_ID = "mines"

    /**
     * [Mines-38.6] AI apps joined through their web chat with the user's own sign-in (no key). Cookie names are best guesses
     * from each site's public behaviour; a page that shows its chat box (no login wall) also counts as signed in.
     */
    val webApps: List<AiSpec> = listOf(
        web("doubao", "豆包", "https://www.doubao.com/chat/", listOf("sessionid", "sessionid_ss", "sid_tt"), R.drawable.ic_brand_volcengine, 0xFF3370FF),
        web("yuanbao", "元宝", "https://yuanbao.tencent.com/chat", listOf("hy_token", "hy_user"), 0, 0xFF1F8B4C),
        web("kimi", "Kimi", "https://www.kimi.com/", listOf("kimi-auth", "access_token"), R.drawable.ic_brand_kimi, 0xFF1F1F1F),
        web("deepseek", "DeepSeek", "https://chat.deepseek.com/", listOf("ds_session_id"), R.drawable.ic_brand_deepseek, 0xFF4D6BFE),
        web("tongyi", "通义", "https://www.tongyi.com/qianwen/", listOf("tongyi_sso_ticket", "login_tongyi_ticket"), R.drawable.ic_brand_qwen, 0xFF615CED),
        web("chatglm", "智谱清言", "https://chatglm.cn/main/alltoolsdetail", listOf("chatglm_token", "chatglm_refresh_token"), R.drawable.ic_brand_zhipu, 0xFF3859FF),
        web("chatgpt", "ChatGPT", "https://chatgpt.com/", listOf("__Secure-next-auth.session-token"), R.drawable.ic_brand_openai, 0xFF10A37F, domestic = false),
        web("gemini", "Gemini", "https://gemini.google.com/app", listOf("__Secure-1PSID"), R.drawable.ic_brand_gemini, 0xFF4285F4, domestic = false),
        web("grok", "Grok", "https://grok.com/", listOf("sso", "sso-rw"), R.drawable.ic_brand_xai, 0xFF3A3F4B, domestic = false),
    )

    private fun web(id: String, name: String, url: String, cookies: List<String>, icon: Int, color: Long, domestic: Boolean = true) =
        AiSpec("web:$id", name, null, Proto.WEBAPP, url, "", emptyList(), icon, color, domestic = domestic, keyOptional = true,
            note = "用你的 $name 账号登录网页版，Mines 替你提问、读回复", kind = Kind.APP, webUrl = url, cookies = cookies)

    fun spec(id: String): AiSpec? = specs.firstOrNull { it.id == id } ?: CozeBots.spec(id) ?: PhoneApps.specFor(id)
        // [Mines-38.7] 2nd+ account of a web app ("web:doubao#2") / of Manus ("manus@manus#2"): same platform, own name
        ?: id.takeIf { it.startsWith("web:") && it.contains('#') }?.let { a -> specs.firstOrNull { it.id == WebAccounts.site(a) }?.let { it.copy(id = a, name = WebAccounts.display(it.name, a)) } }
        ?: id.takeIf { it.startsWith("manus@") }?.let { a -> Partners.find(a)?.spec }
        ?: PresetRegistry.specOf(id)  // [Mines-38.8] memoh / memoh:<bot>

    /** [Mines-38.7] display name of a member id, multi-account aware (「豆包·账号A」). */
    fun nameOf(id: String): String = Partners.find(id)?.spec?.name ?: spec(id)?.let { if (id.startsWith("web:")) WebAccounts.display(it.name, id) else it.name } ?: id

    /** Host order for 主持人 (Mines itself): cheap, fast, domestic first. */
    val hostOrder = listOf("deepseek", "zhipu", "siliconflow", "kimi", "qwen", "volcengine", "hunyuan", "sensenova",
        "openrouter", "openai", "gemini", "anthropic", "xai", "minimax", "nanomuse", "workbuddy")
}
