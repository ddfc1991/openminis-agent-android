package com.openminis.app.plus.group

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderType
import com.openminis.app.plus.KeyBalance
import com.openminis.app.plus.PlusConnect
import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One AI that can (or could) sit in a group. */
data class Partner(
    val spec: AiSpec,
    val state: State,
    val base: String,
    val key: String?,
    val model: String,
    /** Where the key came from: 账号连接 / 模型组 / 本机 / 中继 / 自定义 / 代打字. */
    val via: String,
    val balance: String? = null,
    val lowBalance: Boolean = false,
    val reason: String? = null,
) {
    enum class State { READY, NEED_KEY, OFF, UNREACHABLE, NEED_LOGIN }
    val id: String get() = spec.id
    val ready: Boolean get() = state == State.READY
}

/** Per-member settings the user changed (model, Bot ID, 代打字 on/off, custom endpoints). */
object GroupPrefs {
    private var p: SharedPreferences? = null
    fun init(context: Context) {
        if (p == null) p = context.applicationContext.getSharedPreferences("mines_group_prefs", Context.MODE_PRIVATE)
        PlusVault.init(context)
    }
    fun get(k: String): String? = p?.getString(k, null)?.takeIf { it.isNotBlank() }
    fun put(k: String, v: String?) { p?.edit()?.apply { if (v.isNullOrBlank()) remove(k) else putString(k, v.trim()) }?.apply() }

    fun model(id: String) = get("model_$id")
    fun setModel(id: String, m: String?) = put("model_$id", m)
    fun autoModel(id: String) = get("auto_$id")
    fun setAutoModel(id: String, m: String?) = put("auto_$id", m)
    fun bot(id: String) = get("bot_$id")
    fun setBot(id: String, b: String?) = put("bot_$id", b)

    fun phoneOn(id: String) = get("phone_$id") == "1"
    fun setPhoneOn(id: String, on: Boolean) = put("phone_$id", if (on) "1" else null)

    data class Custom(val id: String, val name: String, val base: String, val model: String)
    fun customs(): List<Custom> = runCatching {
        val a = JSONArray(get("customs") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Custom(o.getString("id"), o.getString("name"), o.getString("base"), o.optString("model")) } }
    }.getOrDefault(emptyList())
    fun addCustom(name: String, base: String, key: String?, model: String): String {
        val id = "custom:" + UUID.randomUUID().toString().take(8)
        val a = JSONArray(get("customs") ?: "[]")
        a.put(JSONObject().put("id", id).put("name", name).put("base", base.trimEnd('/')).put("model", model))
        put("customs", a.toString())
        if (!key.isNullOrBlank()) PlusVault.setSecretExtra("group_member", id, key.trim())
        return id
    }
    fun removeCustom(id: String) {
        val a = JSONArray(get("customs") ?: "[]"); val b = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != id) b.put(a.getJSONObject(i))
        put("customs", b.toString())
        PlusVault.setSecretExtra("group_member", id, null)
    }
    fun customKey(id: String): String? = PlusVault.secretExtra("group_member", id)
}

/**
 * Finds every usable AI: keys in 账号连接, providers in 模型组 (matched by host,
 * key and first model reused), the nanoMuse relay and the WorkBuddy gateway
 * (probed), user-added endpoints, and enabled 代打字 apps.
 */
object Partners {
    private val _list = MutableStateFlow<List<Partner>>(emptyList())
    val list: StateFlow<List<Partner>> = _list.asStateFlow()
    private val _apps = MutableStateFlow<List<PhoneApps.Found>>(emptyList())
    val apps: StateFlow<List<PhoneApps.Found>> = _apps.asStateFlow()
    @Volatile private var lastScan = 0L

    fun find(id: String): Partner? = _list.value.firstOrNull { it.id == id }

    private data class Inst(val id: String, val label: String, val base: String?, val type: ProviderType, val key: String?, val model: String?)

    private fun instances(context: Context): List<Inst> {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return emptyList()
        return runCatching {
            val cfg = repo.config.value
            cfg.instances.filter { it.isEnabled }.map { inst ->
                val model = cfg.modelEntries.firstOrNull { it.providerInstanceId == inst.id && !it.isHidden }?.baseModel?.id
                Inst(inst.id, inst.label, inst.effectiveBaseURL, inst.providerType, runCatching { repo.loadApiKey(inst.id) }.getOrNull()?.trim(), model)
            }
        }.getOrDefault(emptyList())
    }

    private fun hostPort(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { val u = java.net.URI(url.trim()); if (u.port > 0) "${u.host}:${u.port}" else (u.host ?: "") }.getOrDefault("")
    }

    private fun matches(spec: AiSpec, inst: Inst): Boolean {
        val hp = hostPort(inst.base)
        val host = PlusConnect.hostOf(inst.base ?: "")
        if (hp.isNotEmpty()) return spec.hosts.any { h -> if (h.contains(':')) hp == h else host == h || host.endsWith(".$h") || host.contains(h) }
        return when (spec.id) {
            "openai" -> inst.type == ProviderType.openAI
            "anthropic" -> inst.type == ProviderType.anthropic
            "gemini" -> inst.type == ProviderType.gemini
            "openrouter" -> inst.type == ProviderType.openRouter
            "xai" -> inst.type == ProviderType.xAI
            else -> false
        }
    }

    private fun modelFor(spec: AiSpec, instModel: String?): String =
        GroupPrefs.model(spec.id) ?: instModel ?: GroupPrefs.autoModel(spec.id) ?: spec.defaultModel

    private val gate = kotlinx.coroutines.sync.Mutex()

    /** [Mines-38.5] the key for a vault kind: the default account first, then any other account of that service that has one. */
    fun vaultKey(kind: String): String? {
        val ids = listOfNotNull(PlusVault.defaultAccount(kind)) + PlusVault.accountsOf(kind)
        return ids.distinct().firstNotNullOfOrNull { PlusVault.secret(it)?.trim()?.takeIf { k -> k.isNotBlank() } }
    }

    /**
     * [Mines-38.5] refreshes are serialised: build-37 ran up to three at once (AI 伙伴, 群聊 list, chat screen) and the
     * slowest — often one that started BEFORE 一键接入 saved the key — wrote its stale list last, so a service that was
     * just connected showed 「还没有密钥」 again. Each refresh now reads the vault under one lock and always publishes the
     * newest result.
     */
    suspend fun refresh(context: Context, rescanApps: Boolean = false): List<Partner> = gate.withLock { refreshLocked(context, rescanApps) }

    private suspend fun refreshLocked(context: Context, rescanApps: Boolean): List<Partner> = withContext(Dispatchers.IO) {
        GroupPrefs.init(context)
        val insts = instances(context)
        val used = HashSet<String>()
        val out = ArrayList<Partner>()
        if (rescanApps || System.currentTimeMillis() - lastScan > 60_000) {
            val found = PhoneApps.scan(context); PhoneApps.remember(found); _apps.value = found; lastScan = System.currentTimeMillis()
        }
        val probes = coroutineScope {
            AiCatalog.specs.filter { it.keyOptional && it.proto == Proto.OPENAI }.map { s -> async { s.id to (GroupPrefs.get("base_${s.id}") ?: s.baseUrl).let { u -> if (u.isBlank()) AiClient.Probe(false, null, false) else AiClient.probe(u) } } }.awaitAll().toMap()
        }
        for (spec in AiCatalog.specs) {
            if (!PresetRegistry.visible(spec.id)) continue  // [Mines-38.8] Muse web stays hidden until the US toggle is on
            // [Mines-38.6] Mines itself and the web-chat apps need no key
            if (spec.proto == Proto.MINES) {
                out += if (insts.isNotEmpty()) Partner(spec, Partner.State.READY, "", null, "", "本机")
                else Partner(spec, Partner.State.NEED_KEY, "", null, "", "", reason = "Mines 还没有能用的模型：先在设置里加一个")
                continue
            }
            if (spec.proto == Proto.WEBAPP) {
                // [Mines-38.7] one member per account of the platform (「豆包·账号A」, 「豆包·账号B」), each with its own sign-in
                val url = GroupPrefs.get("base_${spec.id}") ?: spec.webUrl
                for (aid in WebAccounts.ids(spec.id)) {
                    val s = spec.copy(id = aid, name = WebAccounts.display(spec.name, aid))
                    out += if (WebChat.signedIn(s, aid)) Partner(s, Partner.State.READY, url, null, "网页版", "已登录")
                    else Partner(s, Partner.State.NEED_LOGIN, url, null, "", "",
                        reason = if (WebChat.guestOk(aid)) "先登录${s.name}（没登录也能游客试用）" else "点「登录」登一次就行"  /* [Mines-38.8] was 「需登录：点一下登录X，只要一次」: wrapped with an orphan 「次」 */)
                }
                continue
            }
            val inst = insts.firstOrNull { it.id !in used && matches(spec, it) }
            if (inst != null) used += inst.id
            // [Mines-38.9.1] 「用户有什么就登记什么」: a self-hosted relay / gateway is listed only when this user has one
            // (an address they set, a key from signing in, a 模型组 entry, or a gateway that answers) — never a built-in server
            if (spec.id == "nanomuse" && GroupPrefs.get("base_nanomuse") == null && PresetAuth.key("nanomuse") == null && inst == null) continue
            if (spec.id == "workbuddy" && inst == null && GroupPrefs.get("base_workbuddy") == null && probes["workbuddy"]?.reachable != true) continue
            val vaultKey = spec.vaultKind?.let { k -> vaultKey(k) } ?: PresetAuth.key(spec.id)  // [Mines-38.8] nanoMuse code sign-in key
            val key = vaultKey ?: inst?.key?.takeIf { it.isNotBlank() }
            val base0 = (if (spec.id == "nanomuse" && GroupPrefs.get("base_nanomuse") != null) PresetAuth.relayBase() else null)   // [Mines-38.9.5 i] always …/v1
                ?: GroupPrefs.get("base_${spec.id}")?.trimEnd('/') ?: inst?.base?.trimEnd('/') ?: spec.baseUrl  // base_<id>: smoke override
            // [Mines-38.9.5 i] 百炼 workspace keys (sk-ws-…) only answer on the workspace MaaS endpoint
            val base = if (spec.id == "qwen") com.openminis.app.plus.RouteStatusCore.bailianBase(key, base0, GroupPrefs.get("bailian_ws")) else base0
            // [Mines-38.6] 扣子: every bot of the user is its own member (listed, auto-published to the API channel)
            if (spec.proto == Proto.COZE) {
                // [Mines-38.7] every 扣子 account (token) is listed on its own; with several accounts the bots say whose they are
                val accts = spec.vaultKind?.let { k -> (listOfNotNull(com.openminis.app.plus.PlusVault.defaultAccount(k)) + com.openminis.app.plus.PlusVault.accountsOf(k)).distinct()
                    .mapNotNull { a -> com.openminis.app.plus.PlusVault.secret(a)?.trim()?.takeIf { it.isNotBlank() } } }?.distinct().orEmpty()
                    .ifEmpty { listOfNotNull(key) }
                if (accts.isEmpty()) {
                    out += Partner(spec, Partner.State.NEED_KEY, base, null, "", "", reason = "登录扣子，你的智能体会自动出现")
                    continue
                }
                var any = false
                accts.forEachIndexed { ai, k ->
                    val sync = runCatching { CozeBots.sync(base, k) }.getOrNull()
                    val bots = sync?.bots.orEmpty()
                    for (b in bots) {
                        val bs0 = CozeBots.specFor(b.id, b.name, b.icon)
                        val bs = if (accts.size > 1) bs0.copy(name = bs0.name + "·账号" + ('A' + ai)) else bs0
                        any = true
                        out += if (b.ready && sync?.relogin != true) Partner(bs, Partner.State.READY, base, k, "", "扣子")
                        else Partner(bs, Partner.State.NEED_KEY, base, k, "", "扣子", reason = sync?.problem?.takeIf { sync.relogin } ?: b.problem ?: "还不能用")
                    }
                    if (!any && ai == accts.lastIndex) out += Partner(spec, Partner.State.NEED_KEY, base, k, "", "扣子",
                        reason = sync?.problem ?: "扣子里还没有智能体：在扣子建一个，回来自动出现")
                }
                continue
            }
            // [Mines-38.7] several Manus accounts → one member each (「Manus·账号B」), each with its own key
            if (spec.proto == Proto.MANUS && spec.vaultKind != null) {
                val accts = com.openminis.app.plus.PlusVault.accountsOf(spec.vaultKind).filter { com.openminis.app.plus.PlusVault.secret(it) != null }
                if (accts.size > 1) {
                    accts.forEachIndexed { i, a ->
                        val mid = if (i == 0) spec.id else "${spec.id}@$a"
                        val ms = spec.copy(id = mid, name = "${spec.name}·账号" + ('A' + i))
                        out += Partner(ms, Partner.State.READY, GroupPrefs.get("base_${spec.id}")?.trimEnd('/') ?: spec.baseUrl,
                            com.openminis.app.plus.PlusVault.secret(a), modelFor(spec, null), "账号连接")
                    }
                    continue
                }
            }
            val via = when { vaultKey != null -> "账号连接"; inst != null -> "模型组"; spec.id == "nanomuse" -> "中继"; spec.id == "workbuddy" -> "本机"; else -> "" }
            val bal = spec.vaultKind?.let { k -> PlusVault.defaultAccount(k) }
            val p = if (spec.keyOptional) {
                val probe = if (inst != null) AiClient.Probe(true, inst.model, false) else probes[spec.id] ?: AiClient.Probe(false, null, false)
                when {
                    inst != null || (probe.reachable && !probe.needsKey) ->
                        Partner(spec, Partner.State.READY, base, key, modelFor(spec, inst?.model ?: probe.firstModel), via)
                    probe.reachable && probe.needsKey ->
                        Partner(spec, Partner.State.NEED_KEY, base, null, "", via, reason = "连得上，但要密钥：在模型组添加这个地址和密钥")
                    else -> Partner(spec, Partner.State.UNREACHABLE, base, null, "", via,
                        reason = if (spec.id == "workbuddy") com.openminis.app.plus.GatewayTunnel.explain(base, "refused", false, false) else "中继 $base 没有响应")
                }
            } else if (key != null) {
                Partner(spec, Partner.State.READY, base, key, modelFor(spec, inst?.model), via,
                    balance = bal?.let { KeyBalance.text(it) }, lowBalance = bal?.let { KeyBalance.low(it) } == true)
            } else Partner(spec, Partner.State.NEED_KEY, base, null, "", "",
                // [Mines-38.5] 账号连接 shows 「已连接」 for a website sign-in (secret = null), but a group call needs the API key:
                // say so plainly and let 一键连接 fetch the key with that session, instead of 「还没有密钥」.
                reason = if (spec.vaultKind != null && PlusVault.accountsOf(spec.vaultKind).isNotEmpty()) "已登录，还差钥匙：点「一键连接」自动拿" else null)
            out += p
        }
        // other OpenAI-compatible providers in 模型组
        for (inst in insts) {
            if (inst.id in used || inst.base.isNullOrBlank()) continue
            if (inst.type != ProviderType.openAI && inst.type != ProviderType.anthropic) continue
            val spec = AiSpec("model:${inst.id}", inst.label, null, if (inst.type == ProviderType.anthropic) Proto.ANTHROPIC else Proto.OPENAI,
                inst.base.trimEnd('/'), inst.model ?: "", keyOptional = true, note = "模型组里的服务")
            out += Partner(spec, Partner.State.READY, spec.baseUrl, inst.key, modelFor(spec, inst.model), "模型组")
        }
        for (c in GroupPrefs.customs()) {
            val spec = AiSpec(c.id, c.name, null, Proto.OPENAI, c.base, c.model, keyOptional = true, note = "自定义接口")
            out += Partner(spec, Partner.State.READY, c.base, GroupPrefs.customKey(c.id), modelFor(spec, c.model), "自定义")
        }
        for (f in _apps.value.filter { it.known.verdict == PhoneApps.Verdict.TYPING }) {
            val spec = PhoneApps.specFor(PhoneApps.idFor(f.pkg)) ?: continue
            out += Partner(spec, if (GroupPrefs.phoneOn(spec.id)) Partner.State.READY else Partner.State.OFF, f.pkg, null, "App", "代打字",
                reason = "慢、可能断：Mines 替你在 ${f.label} 里打字、读回复")
        }
        runCatching { out += PresetPartners.collect(context) }  // [Mines-38.8] Memoh bots (account login, listed)
        // [Mines-38.5] one row per service: a READY entry wins over a duplicate that is not
        val dedup = out.groupBy { it.id }.map { (_, l) -> l.firstOrNull { it.ready } ?: l.first() }
        _list.value = dedup
        dedup
    }

    /** Balance refresh for members with a balance API (DeepSeek / 硅基 / Kimi / OpenRouter). */
    suspend fun refreshBalances(ids: Collection<String>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val kind = AiCatalog.spec(id)?.vaultKind ?: continue
            if (!KeyBalance.hasApi(kind)) continue
            PlusVault.defaultAccount(kind)?.let { runCatching { KeyBalance.refresh(it) } }
        }
    }

    /** Mines' own voice in the group (主持): the first ready API member by [AiCatalog.hostOrder]. */
    fun host(): Partner? = hostCandidates().firstOrNull()

    /** [Mines-38.5] host candidates in order (cheap, fast, domestic first); the group falls back down this list on failure. */
    fun hostCandidates(): List<Partner> {
        val ready = _list.value.filter { it.ready && it.spec.proto in setOf(Proto.OPENAI, Proto.ANTHROPIC) && it.spec.id != "workbuddy" }
        val ordered = AiCatalog.hostOrder.mapNotNull { id -> ready.firstOrNull { it.id == id } }
        // [Mines-38.6] no bare model API at all → Mines' own agent hosts (its model group)
        val mines = _list.value.filter { it.ready && it.spec.proto == Proto.MINES }
        return (ordered + ready + mines).distinctBy { it.id }
    }
}


