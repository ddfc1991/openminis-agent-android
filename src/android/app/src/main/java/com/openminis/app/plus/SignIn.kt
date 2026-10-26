package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.SignInCore.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.15] Android side of [SignInCore]: the saved accounts grouped by how they sign in, each site's link
 * (group + account), and the per-task attempt budget the automatic sign-in and the agent's browser both obey.
 */
object SignIn {
    private const val PREFS = "mines_signin_links"
    @Volatile private var app: Context? = null
    private val _links = MutableStateFlow<Map<String, SignInCore.Link>>(emptyMap())
    val links: StateFlow<Map<String, SignInCore.Link>> = _links.asStateFlow()

    /** "sid|site" → tries in this task, and the ways that failed */
    private data class Budget(var attempts: Int = 0, val failed: MutableSet<String> = HashSet(), val tried: MutableList<SignInCore.Way> = ArrayList(), var at: Long = 0L)
    private val budgets = ConcurrentHashMap<String, Budget>()
    /** the way the running hidden sign-in should try first, per site (read by LoginAssist.plansFor) */
    private val preferred = ConcurrentHashMap<String, SignInCore.Way>()
    private val excluded = ConcurrentHashMap<String, Set<String>>()
    private const val BUDGET_MS = 30 * 60_000L
    /** the user's pick for this task only (「记住」 off): "sid|root" → link */
    private val taskChoice = ConcurrentHashMap<String, SignInCore.Link>()
    /** smoke only: fake saved accounts per site (no Keychain writes) */
    val smokeWays = ConcurrentHashMap<String, List<SignInCore.Way>>()

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        _links.value = SignInCore.linksFromJson(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("links", null))
    }

    private fun save() { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("links", SignInCore.linksToJson(_links.value))?.apply() }

    fun link(site: String): SignInCore.Link? = _links.value[SignInCore.linkKey(Keychain.root(site).ifBlank { site })]

    fun setLink(c: Context, site: String, l: SignInCore.Link?) {
        init(c)
        val k = SignInCore.linkKey(Keychain.root(site).ifBlank { site })
        _links.value = if (l == null) _links.value - k else _links.value + (k to l)
        save()
    }

    // ── grouping ──────────────────────────────────────────────────────────────

    data class Item(val site: String, val host: String, val account: String, val group: Group, val keychainId: String?)

    /** every saved login + key service, with its sign-in group (the site's link wins over the guess) */
    fun items(c: Context): List<Item> {
        init(c)
        val out = ArrayList<Item>()
        runCatching {
            Keychain.all(c).forEach { a ->
                val root = Keychain.root(a.host).ifBlank { a.host }
                val linked = link(root)?.takeIf { it.account.equals(a.account, true) }?.group
                val g = linked ?: SignInCore.groupOf(a.method, a.provider, a.account, a.hasPw)
                out += Item(a.site, root, a.account, g, a.id)
            }
        }
        runCatching {
            PlusVault.init(c)
            PlusVault.entries.value.forEach { (id, e) ->
                val k = PlusVault.kind(id) ?: return@forEach
                if (k.method != ConnMethod.KEY || PlusVault.secret(id) == null) return@forEach
                val host = Keychain.hostOf(k.keyUrl ?: k.loginUrl ?: k.site ?: "").ifBlank { k.id }
                out += Item(k.name, Keychain.root(host).ifBlank { host }, e.account.orEmpty(), Group.API_KEY, null)
            }
        }
        runCatching { if (CloudflareApi.token(c) != null) out += Item("Cloudflare API", "cloudflare.com", "API token", Group.API_KEY, null) }
        smokeWays.forEach { (host, ws) -> ws.forEach { w -> out += Item(host, host, w.account, link(host)?.takeIf { it.account.equals(w.account, true) }?.group ?: w.group, null) } }
        return out.distinctBy { it.host + "|" + it.account.lowercase() + "|" + it.group.id }
    }

    /** 账号台账: one section per sign-in group, sites inside */
    fun grouped(c: Context): List<Pair<Group, List<Item>>> =
        items(c).groupBy { it.group }.toList().sortedBy { it.first.order }.map { (g, l) -> g to l.sortedBy { it.host } }

    /** the ways in for one site */
    fun waysFor(c: Context, site: String): List<SignInCore.Way> {
        val root = Keychain.root(site).ifBlank { site }
        smokeWays[root]?.let { return it }
        return items(c).filter { it.host == root }.map { SignInCore.Way(it.account, it.group) }
    }

    // ── the attempt budget ────────────────────────────────────────────────────

    private fun budget(sid: String, site: String): Budget {
        val k = sid + "|" + Keychain.root(site)
        val b = budgets.getOrPut(k) { Budget(at = System.currentTimeMillis()) }
        if (System.currentTimeMillis() - b.at > BUDGET_MS) { budgets[k] = Budget(at = System.currentTimeMillis()); return budgets[k]!! }
        return b
    }

    sealed class Plan {
        data class Try(val way: SignInCore.Way?, val attempt: Int) : Plan()
        data class Handoff(val line: String) : Plan()
    }

    /** the link that applies in this task: the user's pick for this task, else the site's saved link — only while that account is still saved */
    fun effectiveLink(c: Context, sid: String, site: String): SignInCore.Link? {
        val root = Keychain.root(site).ifBlank { site }
        val ways = waysFor(c, root)
        return (taskChoice[sid + "|" + root] ?: link(root))?.takeIf { l -> ways.any { it.account.equals(l.account, true) } }
    }

    /** before an automatic sign-in of [site] in task [sid]; [named] = the account the task named */
    fun plan(c: Context, sid: String, site: String, named: String? = null): Plan {
        init(c)
        val root = Keychain.root(site).ifBlank { site }
        val b = budget(sid, root)
        val all = waysFor(c, root)
        val l = effectiveLink(c, sid, root)
        // [38.9.15] never drift onto another account: with a link (or a named account) only that account's ways are tried
        val pool = when {
            l != null -> all.filter { it.account.equals(l.account, true) }
            !named.isNullOrBlank() -> SignInCore.forNamed(all, named)
            else -> all
        }
        val order = SignInCore.planOrder(pool, l, b.failed, b.attempts)
        val haveAny = all.isNotEmpty()
        if (SignInCore.next(b.attempts, if (haveAny) order.size else -1) == SignInCore.Next.HANDOFF)
            return Plan.Handoff(SignInCore.handoffLine(root, b.attempts, b.tried))
        val way = order.firstOrNull()
        if (way != null) preferred[root] = way else preferred.remove(root)
        excluded[root] = b.failed.toSet()
        b.attempts++
        way?.let { b.tried += it }
        return Plan.Try(way, b.attempts)
    }

    fun result(c: Context, sid: String, site: String, way: SignInCore.Way?, ok: Boolean) {
        val root = Keychain.root(site).ifBlank { site }
        val b = budget(sid, root)
        preferred.remove(root); excluded.remove(root)
        if (ok) {
            // remembered unless the user picked for this task only (「记住」 off) or set the link themselves
            if (way != null && link(root)?.byUser != true && taskChoice[sid + "|" + root] == null) setLink(c, root, SignInCore.Link(way.group, way.account))
            budgets.remove(sid + "|" + root)
        } else way?.let { b.failed += it.key }
    }

    /** the agent's own browser kept opening this site's sign-in page: counts like an attempt */
    fun browserAuthHit(sid: String, site: String): String? {
        val b = budget(sid, site)
        b.attempts++
        return if (b.attempts > SignInCore.MAX_AUTO + 1) SignInCore.handoffLine(Keychain.root(site).ifBlank { site }, b.attempts - 1, b.tried) else null
    }

    /** LoginAssist: put the linked / chosen way first and drop the ones that already failed in this task */
    fun reorder(host: String, plans: List<IdentityCore.Plan>): List<IdentityCore.Plan> {
        val root = Keychain.root(host)
        val pref = preferred[root]
        val bad = excluded[root].orEmpty()
        fun wayKey(p: IdentityCore.Plan): String? {
            val acct = p.ident?.value ?: return null
            val g = when (p.method) {
                IdentityCore.Method.OAUTH -> if (p.provider == IdentityCore.IdType.GOOGLE) Group.GOOGLE else if (p.provider == IdentityCore.IdType.GITHUB) Group.GITHUB else return null
                else -> SignInCore.groupOf(p.method, p.provider, acct, p.method in setOf(IdentityCore.Method.EMAIL_PW, IdentityCore.Method.USER_PW, IdentityCore.Method.PHONE_PW))
            }
            return SignInCore.Way(acct, g).key
        }
        val kept = plans.filter { wayKey(it)?.let { k -> k !in bad } ?: true }
        if (pref == null) return kept
        val methods = SignInCore.methodsOf(pref.group); val prov = SignInCore.providerOf(pref.group)
        val first = kept.filter { p -> p.method in methods && (prov == null || p.provider == prov) &&
            (p.ident == null || p.ident.value.equals(pref.account, true) || p.method == IdentityCore.Method.OAUTH) }
        return first + (kept - first.toSet())
    }

    fun notifyUser(c: Context, sid: String, site: String, line: String) {
        runCatching { TaskProgress.onWaiting(sid, "等你登录 " + (Keychain.root(site).ifBlank { site })) }
        runCatching { MinesCall.onNeedsAttention(c, "需要你登录 " + (Keychain.root(site).ifBlank { site }), line.take(80), sid) }   // respects the user's 来电 switch
    }

    // ── the whole automatic sign-in, shared by accounts login and the smoke ───────

    sealed class Outcome {
        /** one attempt ran ([ok] or not); [attempt] = 1-based count in this task */
        data class Tried(val ok: Boolean, val line: String, val way: SignInCore.Way?, val attempt: Int) : Outcome()
        /** budget used up → 等你处理 */
        data class Handoff(val line: String) : Outcome()
        /** several unlinked accounts and the user has not picked one (card closed / timed out) */
        data class NoPick(val line: String) : Outcome()
    }

    /**
     * [38.9.15] (1) several saved accounts, none linked, none named → the picker card; the user's tap becomes the site's
     * link (「记住」 on, the default) or this task's choice (off). Never an automatic pick among them.
     * (2) ≤ [SignInCore.MAX_AUTO] attempts per site per task with the linked account only, then 等你处理.
     */
    suspend fun run(c: Context, sid: String, site: String, named: String?, task: String,
                    login: suspend (SignInCore.Way?) -> Pair<Boolean, String>): Outcome {
        init(c)
        val root = Keychain.root(site).ifBlank { site }
        val ways = waysFor(c, root)
        if (SignInCore.needsPick(ways, effectiveLink(c, sid, root), named)) {
            // [38.9.15.1] no 「选账号」 card any more (user 2026-10-26 14:18 「记录在案的他就可以调用，不是查看」, 14:16 「动不动就弹，显得
            // 它没脑记忆」 — replaces the 2026-10-25 「Mines 不替你挑」 rule): Mines picks a recorded account itself — the one this task's
            // project used, else the first saved one — and remembers it as the site's link, so it is never asked again
            val choices = SignInCore.pickChoices(ways)
            val projAcct = runCatching { TaskProjects.projectOf(sid)?.accounts?.lastOrNull { it.contact.isNotBlank() }?.contact }.getOrNull()
            val pick = choices.firstOrNull { projAcct != null && it.account.equals(projAcct, true) } ?: choices.firstOrNull()
            if (pick != null) setLink(c, root, SignInCore.Link(pick.group, pick.account))
        }
        val p = plan(c, sid, root, named)
        if (p is Plan.Handoff) { notifyUser(c, sid, root, p.line); return Outcome.Handoff(p.line) }
        val way = (p as Plan.Try).way
        val (ok, line) = login(way)
        result(c, sid, root, way, ok)
        return Outcome.Tried(ok, line, way, p.attempt)
    }

    /** smoke: forget the budget, the task choice and the link of one site */
    fun smokeReset(c: Context, site: String) {
        val root = Keychain.root(site).ifBlank { site }
        budgets.keys.filter { it.endsWith("|" + root) }.forEach { budgets.remove(it) }
        taskChoice.keys.filter { it.endsWith("|" + root) }.forEach { taskChoice.remove(it) }
        setLink(c, root, null)
    }
}

/**
 * [Mines-38.9.15] 「选账号」 card (user 2026-10-25 12:59): a site with several saved accounts and no link → the user taps one
 * (account + sign-in group); 「记住」 is on by default and the choice becomes the site's link (changeable in 账号台账).
 * One card at a time; the agent's accounts login waits on it.
 */
object AccountPick {
    data class Req(val site: String, val task: String, val ways: List<SignInCore.Way>) { val id = System.nanoTime() }
    data class Answer(val way: SignInCore.Way, val remember: Boolean)

    private val _pending = MutableStateFlow<Req?>(null)
    val pending: StateFlow<Req?> = _pending.asStateFlow()
    private var waiter: kotlinx.coroutines.CompletableDeferred<Answer?>? = null
    /** smoke: the last card shown and its answer */
    @Volatile var lastShown: Req? = null; private set
    @Volatile var lastAnswer: Answer? = null; private set
    private const val WAIT_MS = 15 * 60_000L

    suspend fun ask(c: Context, r: Req): Answer? {
        lastShown = r; lastAnswer = null
        runCatching {
            if ((c.applicationContext as? com.openminis.app.MinisApp)?.isAppForeground() == false)
                MinesCall.onNeedsAttention(c, "选一个 ${r.site} 账号", "Mines 在等你选用哪个账号登录 ${r.site}", null)
        }
        waiter?.complete(null)
        val d = kotlinx.coroutines.CompletableDeferred<Answer?>()
        waiter = d
        _pending.value = r
        return try { kotlinx.coroutines.withTimeoutOrNull(WAIT_MS) { d.await() } } finally { if (_pending.value?.id == r.id) _pending.value = null }
    }

    fun answer(a: Answer?) { lastAnswer = a; val w = waiter; waiter = null; _pending.value = null; w?.complete(a) }
}
