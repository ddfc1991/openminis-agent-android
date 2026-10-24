package com.openminis.app.plus

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.plus.ProviderHealthCore.Check
import com.openminis.app.plus.ProviderHealthCore.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * [Mines-38.9.11 i32] Real 能用 / 不能用 state for AI 服务商 (see [ProviderHealthCore]). A check = one tiny chat (≤ 8 output
 * tokens) to the provider's cheapest model through ProviderFactory — exactly what a conversation does, so OAuth (Copilot),
 * relays, the 免登录 gateway and plain keys are all judged the same way. Results are cached (prefs `mines_provider_health`),
 * re-run in the background when the page opens and they are stale, three at a time, never on the main thread.
 * 「一键修复」 = [fix]: re-test → refresh the login / swap in a newer key from the vault / refresh the model list → when only the
 * user can finish it (device-code login, 官网 key), say so and hand back where to go.
 */
object ProviderHealth {
    private const val PREFS = "mines_provider_health"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _checks = MutableStateFlow<Map<String, Check>>(emptyMap())
    val checks: StateFlow<Map<String, Check>> = _checks.asStateFlow()
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()
    /** per provider: what 一键修复 is doing now (empty = idle) */
    private val _fixing = MutableStateFlow<Map<String, String>>(emptyMap())
    val fixing: StateFlow<Map<String, String>> = _fixing.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var autoLoginId: String? = null

    /** the provider detail page starts its sign-in at once when opened from 一键修复 */
    fun requestAutoLogin(id: String) { autoLoginId = id }
    fun consumeAutoLogin(id: String): Boolean = (autoLoginId == id).also { if (it) autoLoginId = null }

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("checks", "{}") ?: "{}"
            _checks.value = runCatching {
                val o = JSONObject(raw); o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let(ProviderHealthCore::fromJson)?.let { k to it } }.toMap()
            }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    private fun save(c: Context) {
        val o = JSONObject(); _checks.value.forEach { (k, v) -> o.put(k, ProviderHealthCore.toJson(v)) }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("checks", o.toString()).apply()
    }

    private fun put(c: Context, ch: Check) { _checks.value = _checks.value + (ch.id to ch); save(c) }

    fun forget(c: Context, id: String) { init(c); _checks.value = _checks.value - id; save(c) }

    private fun repo(c: Context): ProviderRepository? = (c.applicationContext as? com.openminis.app.MinisApp)?.providerRepository

    fun isOAuth(inst: ProviderInstance) = inst.credentialType == ProviderCredential.oauth

    /** page open: re-check stale ones in the background; [force] = 「全部检测」 */
    fun checkAll(c: Context, force: Boolean = false) {
        init(c)
        val r = repo(c) ?: return
        val now = System.currentTimeMillis()
        val todo = r.config.value.instances.filter { it.isEnabled && (force || ProviderHealthCore.stale(_checks.value[it.id], now)) }
            .filter { it.id !in _running.value }
        if (todo.isEmpty()) return
        scope.launch {
            val gate = Semaphore(3)
            todo.map { inst -> async { gate.withPermit { checkNow(c, inst.id) } } }.awaitAll()
        }
    }

    fun checkOne(c: Context, id: String) { init(c); if (id !in _running.value) scope.launch { checkNow(c, id) } }

    /** one check, blocking the caller's coroutine (IO). */
    suspend fun checkNow(c: Context, id: String): Check? {
        val r = repo(c) ?: return null
        val inst = r.instance(id) ?: return null
        _running.value = _running.value + id
        try {
            val ch = probe(c, r, inst)
            put(c, ch)
            return ch
        } finally { _running.value = _running.value - id }
    }

    private suspend fun probe(c: Context, r: ProviderRepository, inst: ProviderInstance): Check {
        val now = System.currentTimeMillis()
        val oauth = isOAuth(inst)
        val apiKey = r.usableApiKey(inst) ?: (if (r.hasAnyCredential(inst)) "" else null)
            ?: return Check(inst.id, false, if (oauth) Kind.LOGIN else Kind.AUTH,
                if (oauth) "还没登录：点「一键修复」登录" else "还没填 API Key：点进去填一个，或点「一键修复」自动去拿", at = now)
        val entries = r.visibleEntries(inst.id)
        val pick = ProviderHealthCore.pickTestModel(entries.map { it.baseModel.id })
        val entry = entries.firstOrNull { it.baseModel.id == pick }
            ?: return Check(inst.id, false, Kind.NO_MODEL, "还没有模型：点「一键修复」刷新模型列表", at = now)
        val gateway = inst.customBaseURL?.contains("127.0.0.1") == true
        val limit = if (gateway) 60_000L else 35_000L
        val t0 = System.currentTimeMillis()
        val call = CoroutineScope(Dispatchers.IO).async {
            runCatching {
                com.openminis.app.provider.ProviderFactory.create(inst, apiKey, entry.baseModel, c, overrides = entry.overrides)
                    .sendMessage(messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "回复 OK")),
                        systemPrompt = "Reply with OK only.", maxTokens = 8).text
            }
        }
        val res = withTimeoutOrNull(limit) { call.await() }
        val ms = System.currentTimeMillis() - t0
        if (res == null) {
            call.cancel()
            val why = if (gateway) PartnerGateway.statusOf(entry.baseModel.id)?.reason?.takeIf { it.isNotBlank() } else null
            return Check(inst.id, false, Kind.NETWORK, why ?: "${limit / 1000} 秒没有回复：检查网络 / 代理", "timeout ${limit}ms", entry.baseModel.id, ms, System.currentTimeMillis())
        }
        return res.fold(
            { Check(inst.id, true, Kind.OK, "能用", "", entry.baseModel.id, ms, System.currentTimeMillis()) },
            { e ->
                var msg = e.message ?: e.javaClass.simpleName
                if (inst.providerType == ProviderType.githubCopilot) com.openminis.app.auth.CopilotOAuthManager.lastError[inst.id]?.takeIf { it.isNotBlank() && !msg.contains(it) }?.let { msg = "$it\n$msg" }
                val (kind, reason) = ProviderHealthCore.classify(msg, oauth)
                Check(inst.id, false, kind, reason, msg.take(600), entry.baseModel.id, ms, System.currentTimeMillis())
            },
        )
    }

    /** where the UI has to go when the user must finish the fix themselves */
    sealed class Hand {
        object None : Hand()
        data class Relogin(val id: String) : Hand()
        data class FetchKey(val kind: String) : Hand()
    }

    data class FixResult(val ok: Boolean, val line: String, val hand: Hand = Hand.None)

    private fun step(id: String, s: String) { _fixing.value = if (s.isEmpty()) _fixing.value - id else _fixing.value + (id to s) }

    /** 一键修复 (suspend, IO). */
    suspend fun fix(c: Context, id: String): FixResult {
        init(c)
        val r = repo(c) ?: return FixResult(false, "Mines 还没准备好，稍后再试")
        val inst = r.instance(id) ?: return FixResult(false, "找不到这个服务商")
        try {
            step(id, "重新检测…")
            checkNow(c, id)?.let { if (it.ok) return FixResult(true, "能用了") }
            val oauth = isOAuth(inst)
            if (oauth) {
                step(id, "刷新登录…")
                runCatching {
                    if (inst.providerType == ProviderType.githubCopilot) com.openminis.app.auth.CopilotOAuthManager(c, id).let { m -> m.invalidateSession(); m.validAccessToken() }
                    else com.openminis.app.auth.OAuthManager.forInstance(c, inst)?.validAccessToken()
                }
                checkNow(c, id)?.let { if (it.ok) return FixResult(true, "刷新登录后能用了") }
                requestAutoLogin(id)
                return FixResult(false, "要重新登录：已打开登录页", Hand.Relogin(id))
            }
            val cur = _checks.value[id]
            if (cur?.kind == Kind.NO_MODEL || cur?.kind == Kind.MODEL) {
                step(id, "刷新模型列表…")
                runCatching { r.triggerAsyncModelReconcile(id, forceRefresh = true).join() }
                checkNow(c, id)?.let { if (it.ok) return FixResult(true, "刷新模型后能用了") }
            }
            val kind = ProviderKeys.kindFor(inst.customBaseURL ?: inst.effectiveBaseURL, inst.providerType)
            if (kind != null && (cur?.kind == Kind.AUTH || cur?.kind == Kind.UNKNOWN)) {
                val old = r.loadApiKey(id)
                val fresh = (PlusVault.accountsOf(kind) + kind).distinct().mapNotNull { PlusVault.secret(it) }.firstOrNull { it.isNotBlank() && it != old }
                if (fresh != null) {
                    step(id, "换用保险箱里的新密钥…")
                    val t = KeyTester.test(kind, fresh)
                    if (t.ok) {
                        r.saveApiKey(id, fresh)
                        checkNow(c, id)?.let { if (it.ok) return FixResult(true, "换上新密钥后能用了") }
                        if (old != null) r.saveApiKey(id, old)
                    }
                }
                if (KeyAgent.recipe(kind) != null) return FixResult(false, "密钥失效：已打开「一键接入」去官网拿新的", Hand.FetchKey(kind))
            }
            val last = _checks.value[id]
            return FixResult(false, last?.reason ?: "没修好")
        } finally { step(id, "") }
    }
}
