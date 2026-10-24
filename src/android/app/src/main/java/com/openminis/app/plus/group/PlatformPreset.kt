package com.openminis.app.plus.group

/**
 * [Mines-38.8] One declarative row per platform a group / 单聊 member can come from (integration spec §10).
 *
 * User rule 2026-10-15 19:28 「黏贴key就免了，收费的」: 38.8 only ships FREE, login-based routes — 扣子 (login → PAT, free
 * quota), the nanoMuse relay (own server, e-mail code → nm_ key), self-hosted Memoh (account login → JWT) and web-chat
 * bridges (Hark, Manus web, Meta Muse web) on the 38.7 WebView session store. Paid-key rows stay as they were in
 * [AiCatalog] and are not part of this registry.
 *
 * Presets PRODUCE [AiSpec] ([PlatformPreset.toSpec]) so everything downstream (Partners, GroupRunner, AI 伙伴) keeps
 * working unchanged; only transports without a 38.7 branch go through [Proto.ADAPTER] → [Adapters].
 */
enum class Transport {
    OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, DASHSCOPE_APP, DIFY_CHAT,
    /** Memoh: POST /bots/{id}/web/messages, then poll /bots/{id}/messages. */
    MEMOH,
    /** create → poll → fetch (Manus, 扣子 v3 chat …). */
    ASYNC_TASK,
    MCP, WEBVIEW, PHONE, MINES,
}

enum class Region { DOMESTIC, OVERSEAS, SELF_HOSTED, US_ONLY }

/** Where the credential goes and how the user gets it. */
data class AuthSpec(
    val header: String = "Authorization",
    val prefix: String = "Bearer ",
    /** PlusVault kind (账号连接 row); null = stored by [PresetAuth]. */
    val vaultKind: String? = null,
    val keyPage: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val login: LoginFlow? = null,
)

sealed interface LoginFlow {
    /** nanoMuse relay: POST {base}/auth/code {identifier} → code (server log / mail) → POST {base}/auth/verify → nm_ key. */
    data class RelayCode(val codePath: String = "/auth/code", val verifyPath: String = "/auth/verify") : LoginFlow
    /** Memoh: POST {base}/auth/login {username,password} → access_token (JWT); re-login on 401 with the saved password. */
    data class PasswordJwt(val path: String = "/auth/login", val tokenField: String = "access_token") : LoginFlow
    /** Web chat signed in with the user's own account in its own isolated store (38.7 AccountJar). */
    data object WebSession : LoginFlow
    /** Website sign-in, then Mines creates the API token itself on the token page ([com.openminis.app.plus.KeyAgent]). */
    data class KeyAgentPat(val kind: String) : LoginFlow
}

sealed interface TargetId {
    data class Model(val default: String, val prefer: List<String> = emptyList(), val autoSwitch: Boolean = true) : TargetId
    data class AgentTarget(val default: String) : TargetId
    data class MemohBot(val hint: String = "bot id") : TargetId
    /** The platform lists the user's agents itself (扣子 bots, Memoh bots): one member per agent, nothing pasted. */
    data object Listed : TargetId
}

data class Timeouts(val readSec: Long = 120, val pollMs: Long = 0, val totalMs: Long = 180_000)

data class UserField(val key: String, val label: String, val secret: Boolean = false, val hint: String = "")

enum class Verified { DOC, PARTIAL, UNVERIFIED }

/** Web-chat details for [Transport.WEBVIEW] presets (generic selectors until the real-site probe fills them). */
data class WebProfile(
    val site: WebChat.Site,
    /** a reply is done when the page has not changed for this long and no Stop button shows */
    val stableMs: Long = 4500,
    val timeoutMs: Long = 180_000,
    /** step / UI lines that are never part of the answer (Manus timeline …) */
    val noise: Set<String> = emptySet(),
    /** extra cookie domains of the account (sign-out / swap) */
    val extraUrls: List<String> = emptyList(),
    /** "us": only usable on a US network (Muse); the member says so instead of trying direct */
    val proxyPin: String? = null,
    /** the user must enter the page address first (no verified default) */
    val urlRequired: Boolean = false,
)

data class PlatformPreset(
    val id: String,
    val name: String,
    val kind: Kind,
    val transport: Transport,
    val baseUrl: String,
    val auth: AuthSpec,
    val target: TargetId?,
    val region: Region,
    val streaming: Boolean = false,
    val timeouts: Timeouts = Timeouts(),
    val userFields: List<UserField> = emptyList(),
    val docs: String = "",
    val verified: Verified = Verified.UNVERIFIED,
    val note: String = "",
    val color: Long = 0xFF8A7F74,
    val hosts: List<String> = emptyList(),
    val web: WebProfile? = null,
    /** hidden until the user turns it on (Muse: US network + age check) */
    val offByDefault: Boolean = false,
) {
    /** 38.7 [Proto] this preset runs on; transports 38.7 has no branch for go through [Proto.ADAPTER]. */
    fun legacyProto(): Proto = when (transport) {
        Transport.OPENAI_CHAT -> Proto.OPENAI
        Transport.ANTHROPIC -> Proto.ANTHROPIC
        Transport.WEBVIEW -> Proto.WEBAPP
        Transport.PHONE -> Proto.PHONE
        Transport.MINES -> Proto.MINES
        Transport.ASYNC_TASK -> when (id.substringBefore(':')) { "coze" -> Proto.COZE; "manus" -> Proto.MANUS; else -> Proto.ADAPTER }
        else -> Proto.ADAPTER
    }

    /** Bridge to 38.7: everything downstream still consumes AiSpec. */
    fun toSpec(): AiSpec {
        val m = target as? TargetId.Model
        val webUrl = if (transport == Transport.WEBVIEW) baseUrl else ""
        return AiSpec(
            id = id, name = name, vaultKind = auth.vaultKind, proto = legacyProto(), baseUrl = baseUrl,
            defaultModel = m?.default.orEmpty(), prefer = m?.prefer.orEmpty(), icon = 0, color = color,
            domestic = region == Region.DOMESTIC || region == Region.SELF_HOSTED, note = note, hosts = hosts,
            // a login flow means a key IS needed (nanoMuse: every model route answers 401 bad_key without one)
            keyOptional = transport == Transport.WEBVIEW || transport == Transport.MINES,
            kind = kind, webUrl = webUrl,
        )
    }
}

/** Turn input every adapter receives (same data AiClient.reply gets). */
data class TurnRequest(val system: String, val turns: List<AiClient.Turn>, val maxTokens: Int, val stream: Boolean = false) {
    /** Group context squeezed into one prompt for task-style agents (same shape as AiClient.flat). */
    fun flat(cap: Int = 6000): String {
        val sb = StringBuilder(system).append("\n\n群聊记录：\n")
        turns.dropLast(1).takeLast(12).forEach { sb.append(if (it.role == "assistant") "（你）" else "").append(it.text).append('\n') }
        sb.append("\n请回复最新一条：\n").append(turns.lastOrNull()?.text ?: "")
        return sb.toString().takeLast(cap)
    }
}

/** One adapter per transport; stateless (conversation state lives in GroupPrefs / the platform). */
interface ChatAdapter {
    val transport: Transport
    suspend fun reply(p: Partner, preset: PlatformPreset, req: TurnRequest): AiClient.Reply
    /** 「检测」: reachability + auth, no billable call. */
    suspend fun probe(p: Partner, preset: PlatformPreset): AiClient.Probe
}

/** Shared create → poll → fetch loop (Memoh now; Manus / 扣子 / Jules can move onto it later). */
abstract class AsyncTaskAdapter : ChatAdapter {
    override val transport: Transport = Transport.ASYNC_TASK
    data class Created(val id: String, val viewUrl: String? = null, val extra: Map<String, String> = emptyMap())
    enum class Phase { RUNNING, DONE, NEEDS_INPUT, FAILED, CANCELLED }

    protected abstract suspend fun create(p: Partner, preset: PlatformPreset, prompt: String): Created
    protected abstract suspend fun poll(p: Partner, preset: PlatformPreset, c: Created): Phase
    protected abstract suspend fun fetch(p: Partner, preset: PlatformPreset, c: Created): String
    protected open suspend fun cancel(p: Partner, preset: PlatformPreset, c: Created) {}
    protected open fun prompt(req: TurnRequest): String = req.flat()

    final override suspend fun reply(p: Partner, preset: PlatformPreset, req: TurnRequest): AiClient.Reply {
        val c = create(p, preset, prompt(req))
        val t0 = System.currentTimeMillis()
        var ph = Phase.RUNNING
        try {
            while (ph == Phase.RUNNING && System.currentTimeMillis() - t0 < preset.timeouts.totalMs) {
                kotlinx.coroutines.delay(preset.timeouts.pollMs.coerceAtLeast(1500))
                ph = try { poll(p, preset, c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: MemberError) { throw e } catch (e: Exception) { Phase.RUNNING }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { cancel(p, preset, c) }
            throw e
        }
        if (ph == Phase.RUNNING) throw MemberError("${preset.name} ${preset.timeouts.totalMs / 60_000} 分钟还没做完，结果会留在 ${preset.name} 里", c.viewUrl)
        if (ph == Phase.FAILED || ph == Phase.CANCELLED) throw MemberError("${preset.name} 任务没完成（$ph）", c.viewUrl)
        val text = fetch(p, preset, c).ifBlank { throw MemberError("${preset.name} 没有给出回复") }
        return AiClient.Reply(text + if (ph == Phase.NEEDS_INPUT) "\n（${preset.name} 在等你补充信息）" else "", model = preset.name)
    }
}
