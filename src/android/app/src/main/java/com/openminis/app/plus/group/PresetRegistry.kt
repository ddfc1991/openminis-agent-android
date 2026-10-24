package com.openminis.app.plus.group

/**
 * [Mines-38.8] The free, login-based presets (no paid API key anywhere). See [PlatformPreset].
 *
 * Hooks into 38.7 code are kept to single lines:
 *  - [AiCatalog.specs]: `.map { PresetRegistry.override(it) } + PresetRegistry.extraSpecs(…)`; [AiCatalog.spec]: `?: PresetRegistry.specOf(id)`
 *  - Partners: [visible] filter, [PresetAuth.key] for login-flow keys, [PresetPartners.collect] for listed agents (Memoh bots)
 *  - AiClient.replyOnce: `Proto.ADAPTER -> Adapters.reply(…)`; WEBAPP members of this registry → [WebBridge]
 *  - WebChat.site: `?: PresetRegistry.webSite(id)`
 */
object PresetRegistry {
    const val MEMOH = "memoh"
    const val MUSE_TOGGLE = "preset_muse_us"

    private val genericInput = "textarea,[contenteditable='true'],input[type='text']"
    private val genericSend = "button[aria-label*='Send'],button[aria-label*='send'],button[aria-label*='发送'],button[data-testid*='send'],button[type='submit']"
    private val genericReply = "[data-message-author-role='assistant'],[data-role='assistant'],[class*='assistant'] [class*='markdown'],[class*='assistant-message']," +
        "[class*='AssistantMessage'],[class*='bot-message'],[class*='markdown'],.prose"

    val all: List<PlatformPreset> = listOf(
        // 扣子: website sign-in → KeyAgent creates the PAT on www.coze.cn/open/oauth/pats → CozeBots lists + publishes every bot
        PlatformPreset("coze", "扣子", Kind.AGENT, Transport.ASYNC_TASK, "https://api.coze.cn/v3",
            auth = AuthSpec(vaultKind = "coze", keyPage = "https://www.coze.cn/open/oauth/pats", login = LoginFlow.KeyAgentPat("coze")),
            target = TargetId.Listed, region = Region.DOMESTIC, timeouts = Timeouts(readSec = 60, pollMs = 1500, totalMs = 180_000),
            docs = "https://www.coze.cn/open/docs/developer_guides/chat_v3", verified = Verified.DOC, color = 0xFF4E40E5,
            note = "登录扣子就行：Mines 自己拿令牌，你的每个智能体自动出现（免费额度）"),
        // nanoMuse relay (the user's own server): e-mail → code (relay log, CODE_SENDER=log) → nm_ key; OpenAI-compatible
        PlatformPreset("nanomuse", "Muse 中继", Kind.AGENT, Transport.OPENAI_CHAT, AiCatalog.NANOMUSE_BASE,
            auth = AuthSpec(login = LoginFlow.RelayCode()),
            target = TargetId.Model("deepseek-v4.1-flash", listOf("deepseek-v4.1-flash", "qwen3.8-flash", "qwen3.8-27b", "deepseek")),
            region = Region.SELF_HOSTED, streaming = true, hosts = emptyList(),
            docs = "https://raw.githubusercontent.com/nano-muse/nanoMuse/master/cloud/README.md", verified = Verified.DOC, color = 0xFF8E6CEF,
            note = "你自己部署的中继：先填它的地址，再用邮箱验证码登录"),
        // Memoh (self-hosted): server address + account login → JWT; every bot of the account is its own member
        PlatformPreset(MEMOH, "Memoh", Kind.AGENT, Transport.MEMOH, "",
            auth = AuthSpec(login = LoginFlow.PasswordJwt()), target = TargetId.Listed, region = Region.SELF_HOSTED,
            timeouts = Timeouts(readSec = 30, pollMs = 1500, totalMs = 180_000),
            userFields = listOf(UserField("base", "服务器地址", hint = "http://你的服务器/api"), UserField("username", "用户名"), UserField("password", "密码", secret = true)),
            docs = "https://raw.githubusercontent.com/memohai/Memoh/main/spec/swagger.yaml", verified = Verified.PARTIAL, color = 0xFF2F7D6D,
            note = "你自己部署的 Memoh：填服务器地址和账号密码登录，你的机器人会自动出现"),
        // Web-chat bridges (38.7 WebView store, one isolated profile per account); selectors generic until probed
        PlatformPreset("web:hark", "Hark", Kind.APP, Transport.WEBVIEW, "https://hark.com/",
            auth = AuthSpec(login = LoginFlow.WebSession), target = null, region = Region.OVERSEAS, color = 0xFF1C1C1C,
            note = "用你的 Hark 账号登录网页版，Mines 替你提问、读回复（网页地址可在「更多接入」里改）",
            web = WebProfile(WebChat.Site(genericInput, genericSend, genericReply,
                wall = Regex("Sign in to continue|Log in to continue|Continue with Google|Continue with email")),
                stableMs = 6000, timeoutMs = 6 * 60_000L)),
        PlatformPreset("web:manus", "Manus 网页版", Kind.APP, Transport.WEBVIEW, "https://manus.im/app",
            auth = AuthSpec(login = LoginFlow.WebSession), target = null, region = Region.OVERSEAS, color = 0xFF2B2B2B,
            note = "用你的 Manus 账号登录网页版（不用 API 密钥）；每个任务照常消耗积分",
            web = WebProfile(WebChat.Site(genericInput, genericSend, genericReply,
                wall = Regex("not available in your region|服务不可用|Sign in to Manus|Log in to Manus")),
                stableMs = 9000, timeoutMs = 10 * 60_000L,
                noise = setOf("Manus is working", "Manus 正在工作", "Task completed", "任务已完成", "Thinking", "思考中", "Knowledge recalled", "View all", "Share", "分享"),
                extraUrls = listOf("https://manus.im/"))),
        PlatformPreset("web:muse", "Meta Muse", Kind.APP, Transport.WEBVIEW, "",
            auth = AuthSpec(login = LoginFlow.WebSession), target = null, region = Region.US_ONLY, color = 0xFF0866FF,
            note = "需要美国网络和 Meta 账号年龄验证；验证和人机检查都在「登录」里由你自己完成",
            web = WebProfile(WebChat.Site(genericInput, genericSend, genericReply,
                wall = Regex("not available in your (country|region)|age verification|verify your age|confirm your age|Log in with Meta|Continue with Meta")),
                stableMs = 6000, timeoutMs = 6 * 60_000L, proxyPin = "us", urlRequired = true),
            offByDefault = true),
    )

    private val byId = all.associateBy { it.id }

    fun preset(id: String): PlatformPreset? = byId[id] ?: byId[id.substringBefore('#')] ?: byId[id.substringBefore(':').takeIf { id.contains(':') && !id.startsWith("web:") } ?: ""]

    /** Login flow of a member id (nanomuse / memoh / memoh:<bot> / coze:<bot>). */
    fun loginFlowOf(id: String): LoginFlow? = preset(id)?.auth?.login

    /** True when the AI 伙伴 row should open [com.openminis.app.ui.group.PresetLoginDialog] instead of 38.7's flows. */
    fun ownLogin(id: String): Boolean = loginFlowOf(id).let { it is LoginFlow.RelayCode || it is LoginFlow.PasswordJwt }

    /** Muse is off unless the user said they are on a US network. */
    fun museOn(): Boolean = GroupPrefs.get(MUSE_TOGGLE) == "1"
    fun setMuseOn(on: Boolean) = GroupPrefs.put(MUSE_TOGGLE, if (on) "1" else null)

    fun visible(id: String): Boolean {
        val p = preset(id) ?: return true
        return !p.offByDefault || (p.id == "web:muse" && museOn())
    }

    /** 38.7 rows this registry corrects (nanoMuse needed a key all along; keyOptional=true was wrong). */
    fun override(s: AiSpec): AiSpec {
        val p = byId[s.id] ?: return s
        return when (p.transport) {
            Transport.OPENAI_CHAT -> s.copy(keyOptional = false, note = p.note,
                defaultModel = (p.target as? TargetId.Model)?.default ?: s.defaultModel,
                prefer = (p.target as? TargetId.Model)?.prefer ?: s.prefer)
            Transport.ASYNC_TASK -> s.copy(note = p.note)
            else -> s
        }
    }

    /** Presets 38.7 does not have as rows: the web-chat bridges (Memoh is listed per bot by [PresetPartners]). */
    fun extraSpecs(existing: Set<String>): List<AiSpec> =
        all.filter { it.id !in existing && it.transport == Transport.WEBVIEW }.map { it.toSpec() }

    /** Member spec for ids 38.7 cannot resolve: "memoh", "memoh:<bot>". */
    fun specOf(id: String): AiSpec? {
        if (id == MEMOH) return byId.getValue(MEMOH).toSpec()
        if (id.startsWith("$MEMOH:")) return PresetPartners.memohSpec(id)
        return null
    }

    /** WebChat.Site of a bridge preset (login screen classification, 38.7 check()). */
    fun webSite(id: String): WebChat.Site? = byId[WebAccounts.site(id)]?.web?.site

    fun webProfile(id: String): WebProfile? = byId[WebAccounts.site(id)]?.web

    /** The page a bridge member opens (user override first). */
    fun webUrl(id: String): String = GroupPrefs.get("base_${WebAccounts.site(id)}")?.takeIf { it.isNotBlank() } ?: byId[WebAccounts.site(id)]?.baseUrl.orEmpty()

    /** AI 伙伴 button text for a member that is not ready. */
    fun fixLabel(id: String): String? = when (loginFlowOf(id)) {
        is LoginFlow.RelayCode, is LoginFlow.PasswordJwt -> "登录"
        is LoginFlow.KeyAgentPat -> "登录扣子"
        else -> null
    }
}
