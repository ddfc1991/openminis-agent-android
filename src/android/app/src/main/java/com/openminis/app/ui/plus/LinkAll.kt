package com.openminis.app.ui.plus

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.group.AiCatalog
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.WebAccounts
import com.openminis.app.plus.group.WebChat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [Mines-38.7] 「一键关联」 (user 2026-10-15): goes through every AI platform's sign-in page one after another. The phone
 * number and the SMS code are filled in for the user ([com.openminis.app.plus.LoginAssist], auto mode presses
 * 获取验证码 / 登录 too); Mines only stops when the user has to scan a QR code or pass a robot check, with ONE plain step
 * in big type. Afterwards 「关联结果」 sorts every platform into 已关联 / 需要你操作 / 过期 / 还没关联.
 */
object LinkAll {
    /** true while 一键关联 runs: the sign-in page then also presses 获取验证码 / 登录 and closes itself once signed in */
    var autoLogin by mutableStateOf(false)
    var running by mutableStateOf(false)
    val queue = mutableStateListOf<String>()
    var index by mutableIntStateOf(0)
    /** the account whose sign-in page is open right now (set before opening it, read when coming back) */
    var waiting: String? = null
    /** [Mines-38.9.1] 关联结果 lives in 账号关联 now: open its 「需要你操作」 row when arriving from a finished run */
    var openResults = false

    const val ROUTE = "plus_linkall"
    const val SUMMARY = "plus_linkall_summary"
    /** [Mines-38.7] 设置 › 已忽略的平台: restore what 一键关联 was told to ignore */
    const val IGNORED = "plus_linkall_ignored"
    fun ignoredList(): List<String> = platforms().filter { ignored(it) }

    /**
     * [Mines-38.7] user 2026-10-15 (priority #1): 一键关联 covers EVERY platform listed in 账号连接 (AI, 开发, 办公, 金融,
     * 社交 incl. X / 微博 …), not only the nine web AI apps. Those rows are "conn:<kind id>".
     */
    const val CONN = "conn:"
    fun isConn(id: String) = id.startsWith(CONN)
    fun connKind(id: String): com.openminis.app.plus.ConnectionKind? = if (isConn(id)) com.openminis.app.plus.PlusVault.kind(id.removePrefix(CONN)) else null
    private const val ZHIHU = "site:www.zhihu.com"

    /** Every 账号连接 platform (no MCP servers, no hidden legacy rows, no extra-account copies) + 知乎 (a 消息 source). */
    fun connKinds(): List<com.openminis.app.plus.ConnectionKind> {
        val V = com.openminis.app.plus.PlusVault
        return V.kinds.filter { !it.hidden && it.group != V.G_MCP && !it.id.contains('#') && !it.id.startsWith("mcp_") } +
            listOfNotNull(V.kind(ZHIHU))
    }

    /** Every account of every web AI platform (豆包, 豆包·账号B, 元宝 …), then every 账号连接 platform. */
    fun platforms(): List<String> = AiCatalog.webApps.flatMap { WebAccounts.ids(it.id) } + connKinds().map { CONN + it.id }

    fun name(id: String): String {
        if (isConn(id)) { val k = connKind(id) ?: return id.removePrefix(CONN); return if (k.id == ZHIHU) "知乎" else k.name }
        return AiCatalog.spec(id)?.let { WebAccounts.display(AiCatalog.spec(WebAccounts.site(id))?.name ?: it.name, id) } ?: id
    }

    /** Which 账号连接 category a platform sits in (same words as that page). */
    fun category(id: String): String {
        val k = connKind(id) ?: return "AI App 网页版"
        val V = com.openminis.app.plus.PlusVault
        return when {
            k.id == "x" || k.group == V.G_SOCIAL || k.id == ZHIHU -> "社交与资讯"
            k.group == V.G_AI -> "AI 服务商"
            k.group == V.G_DEV -> "开发与服务器"
            k.group == V.G_FIN -> "金融与加密"
            k.group == V.G_COMMON -> "效率与办公"
            else -> "其他"
        }
    }

    /** How this platform is linked, in plain words (the report table's 登录方式). */
    fun how(id: String): String {
        val k = connKind(id) ?: return "在 Mines 内登录网页版"
        return when (k.method) {
            com.openminis.app.plus.ConnMethod.WEB -> "在 Mines 内登录"
            com.openminis.app.plus.ConnMethod.GOOGLE -> "Chrome 登录（Google 不让 App 内登录）"
            com.openminis.app.plus.ConnMethod.GITHUB, com.openminis.app.plus.ConnMethod.MICROSOFT -> "一键授权（输入配对码）"
            com.openminis.app.plus.ConnMethod.CALENDAR -> "手机日历权限"
            com.openminis.app.plus.ConnMethod.A11Y -> "无障碍代操作"
            com.openminis.app.plus.ConnMethod.KEY -> if (inAppKey(k)) "在 Mines 内登录官网，自动取密钥" else if (k.keyUrl != null) "去官网复制密钥，回来自动填" else "粘贴${k.keyLabel}"
        }
    }

    private fun inAppKey(k: com.openminis.app.plus.ConnectionKind) =
        com.openminis.app.plus.KeyAgent.recipe(k.id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW

    /** Mines can run it by itself inside the app (the user only types a code / scans if asked). */
    fun automatic(id: String): Boolean {
        val k = connKind(id) ?: return true
        return when (k.method) {
            com.openminis.app.plus.ConnMethod.WEB, com.openminis.app.plus.ConnMethod.GITHUB, com.openminis.app.plus.ConnMethod.MICROSOFT -> true
            com.openminis.app.plus.ConnMethod.KEY -> inAppKey(k)
            else -> false
        }
    }

    /** Can a sign-in done in another browser / app reach Mines? (none has an official OAuth client configured) */
    fun externalSync(id: String): String {
        val k = connKind(id) ?: return "不能：外部浏览器只是打开，登录要在 Mines 内做"
        return when (k.method) {
            com.openminis.app.plus.ConnMethod.GITHUB, com.openminis.app.plus.ConnMethod.MICROSOFT -> "能：在任意浏览器输入配对码，授权结果回到 Mines"
            com.openminis.app.plus.ConnMethod.CALENDAR -> "能：读手机里已同步的日历"
            com.openminis.app.plus.ConnMethod.GOOGLE -> "不能自动同步：Chrome 登录后回来点「我已经登录好了」"
            com.openminis.app.plus.ConnMethod.KEY -> "部分：在外部复制密钥，回到 Mines 自动识别剪贴板"
            else -> "不能：外部浏览器只是打开，登录要在 Mines 内做"
        }
    }

    /** Where a platform's link is opened (web app page, or 账号连接 with its login / 一键接入 started). */
    fun open(id: String, go: (String) -> Unit, auto: Boolean) {
        val k = connKind(id)
        if (k == null) { go(com.openminis.app.ui.group.GroupRoutes.webLogin(id)); return }
        if (k.id == ZHIHU) com.openminis.app.plus.PlusVault.setExtra(k.id, "url", "https://www.zhihu.com/signin")
        if (k.method == com.openminis.app.plus.ConnMethod.WEB) PlusNav.openLogin = k.id
        else { PlusNav.openKind = k.id; PlusNav.autoClaim = auto }
        go(PlusRoutes.CONNECTIONS)
    }

    /** [Mines-38.9.3] the 一键关联 platform (web AI account or 账号连接 kind) that signs in to [site] (registrable domain). */
    fun platformFor(site: String): String? {
        val r = com.openminis.app.plus.Keychain.root(site)
        if (r.isBlank()) return null
        return runCatching { platforms().firstOrNull { id ->
            val k = connKind(id)
            val h = if (k != null) com.openminis.app.plus.Keychain.hostOf(k.loginUrl ?: k.site ?: k.panelUrl ?: k.keyUrl)
                else com.openminis.app.plus.Keychain.hostOf(AiCatalog.spec(id)?.webUrl)
            h.isNotBlank() && com.openminis.app.plus.Keychain.root(h) == r
        } }.getOrNull()
    }

    /** start time of the link that is open right now: a 账号连接 entry saved after it counts as linked */
    var waitingSince = 0L

    /** What the last attempt left: 「扫码」/「验证」 (user step), 「跳过」, 「未完成」, or null. */
    fun note(id: String): String? = GroupPrefs.get("linkres_$id")
    fun setNote(id: String, v: String?) = GroupPrefs.put("linkres_$id", v)

    // [Mines-38.9.3] UNKNOWN = had an account but this phone cannot tell right now (no cookie seen, never probed): 「未检查」,
    // never 「过期了」 — EXPIRED is only a confirmed sign-out (probe saw the login wall / the key was refused).
    enum class St(val label: String) { LINKED("已关联"), USER("需要你操作"), EXPIRED("过期了"), UNKNOWN("未检查"), NONE("还没关联"), IGNORED("已忽略") }

    /** [Mines-38.7] 「忽略」: a platform that cannot be fixed is no longer offered / counted (restore in 关联结果) */
    fun ignored(id: String) = GroupPrefs.get("linkignore_$id") == "1"
    fun setIgnored(id: String, on: Boolean) = GroupPrefs.put("linkignore_$id", if (on) "1" else null)

    fun state(id: String): St {
        if (isConn(id)) return connState(id)
        val spec = AiCatalog.spec(id) ?: return St.NONE
        if (WebChat.signedIn(spec, id)) return St.LINKED
        if (ignored(id)) return St.IGNORED
        if (GroupPrefs.get("weblogin_$id") == "0") return St.EXPIRED
        val n = note(id)
        if (n == "扫码" || n == "验证") return St.USER
        return St.NONE
    }

    /**
     * [Mines-38.9.4] what the 「一键关联的结果」 summary shows — the SAME rule as the account rows (Keychain.all →
     * SessionStatus.loginPill): 过期了 only when the account's own record is a confirmed sign-out; a record that is
     * recorded / never verified (the row says 未检查) is counted under 未检查, not 过期了 (豆包 showed both).
     */
    fun shownState(id: String): St {
        val s = state(id)
        if (s != St.EXPIRED) return s
        val recs = runCatching { com.openminis.app.plus.IdentityStore.recs.value.filter { r -> r.site.isNotBlank() && platformFor(r.site) == id } }
            .getOrDefault(emptyList())
        if (recs.isEmpty()) return s
        return if (recs.any { com.openminis.app.plus.SessionStatus.loginPill(it.status, false, it.status == com.openminis.app.plus.IdentityCore.EXPIRED) ==
                com.openminis.app.plus.SessionStatus.Pill.WARN }) St.EXPIRED else St.UNKNOWN
    }

    private fun connState(id: String): St {
        val k = connKind(id) ?: return St.NONE
        val V = com.openminis.app.plus.PlusVault
        val accts = V.accountsOf(k.id)
        if (accts.isNotEmpty()) {
            // [Mines-38.9.3] user 2026-10-17: OpenRouter (an API-key service) showed 「登录失效」 — ANY patrol note (429, low
            // balance, a network blip = WARN) counted as 过期. A key is judged only by its key test: 401 / 403 (BAD) = 过期.
            val bad = runCatching { com.openminis.app.plus.AccountPatrol.status.value.any { (kid, st) -> V.baseId(kid) == k.id &&
                st.level == com.openminis.app.plus.AccountPatrol.Level.BAD && !kid.startsWith("vps:") } }.getOrDefault(false)
            val first = V.entries.value[accts.first()]
            val webLogin = k.method == com.openminis.app.plus.ConnMethod.WEB && k.doneCookies.isNotEmpty() && first?.via != "oauth" &&
                first?.via != "key" && first?.via != "token" && first?.via != "model"
            if (webLogin) {
                // a web sign-in: the session cookie decides; no cookie seen is 「未检查」 unless a sign-out was confirmed
                if (runCatching { com.openminis.app.plus.LoginWeb.signedIn(V.kind(accts.first()) ?: k) }.getOrDefault(false)) return St.LINKED
                if (ignored(id)) return St.IGNORED
                return if (V.extra(accts.first(), "session") == "0") St.EXPIRED else St.UNKNOWN
            }
            if (!bad) return St.LINKED
            if (ignored(id)) return St.IGNORED
            // [Mines-38.9.5 i] an API-key / token service whose key was refused (OpenRouter 401) is NOT a sign-in that expired:
            // it stays out of 登录 › 过期了; the 服务 row says 「key 无效」 and offers 一键接入 / delete there.
            if (first?.via in setOf("key", "token", "model") || k.method == com.openminis.app.plus.ConnMethod.KEY) return St.LINKED
            return St.EXPIRED
        }
        if (ignored(id)) return St.IGNORED
        val n = note(id)
        if (n == "扫码" || n == "验证" || n == "手动") return St.USER
        return St.NONE
    }

    fun userStepText(id: String): String = if (isConn(id) && note(id) == "手动") manualStep(id) else when (note(id)) {
        "扫码" -> "打开手机上的 ${name(id)} App，扫一下登录页上的二维码"
        "验证" -> "登录页要你过一下人机验证，按页面提示做一下"
        else -> "点一下，登录 ${name(id)}"
    }

    /** The one thing the user does for a platform Mines cannot link by itself. */
    fun manualStep(id: String): String {
        val k = connKind(id) ?: return "点一下，登录 ${name(id)}"
        return when (k.method) {
            com.openminis.app.plus.ConnMethod.GOOGLE -> "Google 不让在 App 里登录：点一下，用 Chrome 登录"
            com.openminis.app.plus.ConnMethod.CALENDAR -> "点一下，允许 Mines 读手机日历"
            com.openminis.app.plus.ConnMethod.A11Y -> "点一下，按步骤打开无障碍"
            else -> if (k.keyUrl != null) "点一下去官网复制${k.keyLabel}，回来会自动填好" else "点一下，粘贴${k.keyLabel}"
        }
    }

    fun start(only: List<String>? = null) {
        queue.clear(); queue.addAll((only ?: platforms()).filter { state(it) != St.LINKED && state(it) != St.IGNORED })
        index = 0; waiting = null
        running = queue.isNotEmpty(); autoLogin = running
    }

    fun stop() { running = false; autoLogin = false; waiting = null }

    suspend fun eval(wv: WebView, js: String): String = suspendCancellableCoroutine { c ->
        runCatching { wv.evaluateJavascript(js) { r -> if (c.isActive) c.resume(runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()) } }
            .onFailure { if (c.isActive) c.resume("") }
    }

    /**
     * Runs inside the sign-in page during 一键关联: every 3 s looks at the page; signed in → marks it and calls [done].
     */
    suspend fun watch(id: String, web: () -> WebView?, isDone: () -> Boolean, done: () -> Unit) {
        while (!isDone() && autoLogin) {
            delay(3000)
            val wv = web() ?: continue
            val o = runCatching { org.json.JSONObject(eval(wv, WebChat.probeJs(WebChat.site(id)))) }.getOrNull() ?: continue
            if (WebChat.classifyFor(AiCatalog.spec(id), id, o) == WebChat.PageState.SIGNED_IN) {
                WebChat.markSignedIn(id, true); setNote(id, null)
                com.openminis.app.plus.LoginWeb.flush()
                AiCatalog.spec(id)?.let { com.openminis.app.plus.AccountJar.rememberNow(id, WebChat.urls(it, id)) }
                // [Mines-38.9.1] record which identity / method worked for this site
                runCatching { val h = com.openminis.app.plus.Keychain.hostOf(wv.url); com.openminis.app.plus.IdentityStore.recordSuccess(wv.context, h, name(id), com.openminis.app.plus.LoginAssist.planFor(h)) }
                done(); return
            }
        }
    }
}

@Composable
private fun BigText(t: String, size: Int = 18, weight: FontWeight = FontWeight.SemiBold, inset: androidx.compose.ui.unit.Dp = 20.dp) {
    val m = mines()
    // [Mines-38.9.1] user 2026-10-17 「字体 UI 没对齐」: left-aligned on the page's 20 dp margin (was centred paragraphs)
    Text(t, fontSize = size.sp, lineHeight = (size * 1.45f).sp, fontWeight = weight, color = if (weight == FontWeight.Normal) m.ink2 else m.ink, textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 6.dp).semantics { contentDescription = t })
}

/** 一键关联: one step at a time, big type. */
@Composable
fun LinkAllScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { GroupPrefs.init(ctx); 0 }
    // [Mines-38.8] keep a warm WebView engine while 一键关联 walks through the sign-in pages
    remember { com.openminis.app.browser.WebPrewarm.warm(ctx, keepMs = 120_000L, again = true); 0 }
    var tick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    // coming back from a sign-in page: note how it went, then move on
    LaunchedEffect(Unit) {
        val w = LinkAll.waiting
        if (LinkAll.running && w != null) {
            LinkAll.waiting = null
            val st = LinkAll.state(w)
            if (st != LinkAll.St.LINKED && LinkAll.note(w) == null) LinkAll.setNote(w, "未完成")
            last = LinkAll.name(w) + "：" + (if (st == LinkAll.St.LINKED) "已关联 ✓" else if (st == LinkAll.St.USER) "需要你操作" else "没登录上")
            LinkAll.index++
            tick++
        }
    }
    val q = LinkAll.queue
    val cur = q.getOrNull(LinkAll.index)
    LaunchedEffect(tick, LinkAll.running, paused, cur) {
        if (!LinkAll.running) return@LaunchedEffect
        if (cur == null) { LinkAll.stop(); LinkAll.openResults = true; go(PlusRoutes.KEYCHAIN); return@LaunchedEffect }
        if (paused) return@LaunchedEffect
        // [Mines-38.7] a platform Mines cannot link by itself (Chrome-only Google, 日历权限, 无障碍, pasted key) is not
        // opened mid-run: it goes to 「需要你操作」 with its one step, and the run moves on
        if (!LinkAll.automatic(cur)) {
            delay(600)
            LinkAll.setNote(cur, "手动")
            last = LinkAll.name(cur) + "：需要你操作（" + LinkAll.manualStep(cur) + "）"
            LinkAll.index++; tick++
            return@LaunchedEffect
        }
        delay(3000)
        if (LinkAll.running && !paused && LinkAll.queue.getOrNull(LinkAll.index) == cur) {
            LinkAll.waiting = cur; LinkAll.waitingSince = System.currentTimeMillis()
            LinkAll.open(cur, go, auto = true)
        }
    }
    PlusPage("一键关联", { LinkAll.stop(); onBack() }) {
        Spacer(Modifier.height(16.dp))
        if (!LinkAll.running) {
            val all = remember(tick) { LinkAll.platforms() }
            val linked = all.count { LinkAll.state(it) == LinkAll.St.LINKED }
            val ign = all.count { LinkAll.state(it) == LinkAll.St.IGNORED }
            BigText("把账号都关联上", 18)
            BigText("我一个一个打开各平台的登录页，按你有的身份登录：先用这个网站上次成功的方式，再用已登录的 Google / GitHub 等，然后邮箱，最后手机号。验证码我从邮箱和短信里读，只有扫码或人机验证时才叫你。", 15, FontWeight.Normal)
            LinkIdentities()
            BigText("共 ${all.size - ign} 个，已关联 $linked 个" + if (ign > 0) "（忽略了 $ign 个）" else "", 15, FontWeight.Normal)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (linked + ign >= all.size) "全部都关联好了" else "开始", Modifier.fillMaxWidth(), enabled = linked + ign < all.size) { LinkAll.start(); paused = false; tick++ }
                PillButton("看关联结果", Modifier.fillMaxWidth(), primary = false) { LinkAll.openResults = true; go(PlusRoutes.KEYCHAIN) }
            }
            Spacer(Modifier.height(20.dp))
            LinkAllHelpers()
            // [Mines-38.8 v3] smokes 38841427883 + 38845542672: the first sign-in page of a run was a fully white screen for
            // seconds (header included) while the window drew its FIRST WebView (engine prewarm alone did not help: the
            // cost is the window's first WebView draw). A 1 dp, almost transparent WebView is drawn here on the start
            // page, so that cost is paid while the user reads it; the real pages then open at once.
            WindowWebWarm()
        } else {
            BigText("第 ${(LinkAll.index + 1).coerceAtMost(q.size)} 个，共 ${q.size} 个", 15, FontWeight.Normal)
            last?.let { BigText(it, 15, FontWeight.Normal) }
            if (cur != null) {
                BigText("下一个：${LinkAll.name(cur)}", 18)
                BigText(LinkAll.category(cur) + " · " + LinkAll.how(cur), 15, FontWeight.Normal)
                BigText(if (paused) "已暂停。点「继续」打开它的登录页" else "马上打开它的登录页…", 15, FontWeight.Normal)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (paused) PillButton("继续", Modifier.fillMaxWidth()) { paused = false; tick++ }
                PillButton("跳过这个", Modifier.fillMaxWidth(), primary = false) {
                    cur?.let { LinkAll.setNote(it, "跳过") }; LinkAll.index++; tick++
                }
                PillButton(if (paused) "结束，看结果" else "停一下", Modifier.fillMaxWidth(), primary = false) {
                    if (paused) { LinkAll.stop(); LinkAll.openResults = true; go(PlusRoutes.KEYCHAIN) } else paused = true
                }
            }
        }
    }
}

/** 关联结果: 已关联 / 需要你操作 / 过期了 / 还没关联. Tapping a row opens that platform's sign-in page. */
@Composable
fun LinkSummaryScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { GroupPrefs.init(ctx); 0 }
    val all = remember { LinkAll.platforms() }
    var rev by remember { mutableIntStateOf(0) }
    val groups = remember(rev) { all.groupBy { LinkAll.state(it) } }
    PlusPage("关联结果", onBack) {
        val linked = groups[LinkAll.St.LINKED].orEmpty().size
        val ign = groups[LinkAll.St.IGNORED].orEmpty().size
        Spacer(Modifier.height(8.dp))
        BigText("已关联 $linked 个，共 ${all.size - ign} 个", 18)
        for (st in listOf(LinkAll.St.USER, LinkAll.St.EXPIRED, LinkAll.St.UNKNOWN, LinkAll.St.NONE, LinkAll.St.LINKED, LinkAll.St.IGNORED)) {
            val l = groups[st].orEmpty()
            if (l.isEmpty()) continue
            // [Mines-38.9.1] one card per section (PlusSection already draws it — the inner MinesCard made a card in a card)
            PlusSection(st.label + "（${l.size}）") {
                run {
                    l.forEach { id ->
                        val sub = LinkAll.category(id) + " · " + when (st) {
                            LinkAll.St.LINKED -> if (LinkAll.isConn(id)) "已连上" else "可以直接在群聊、单聊里用"
                            LinkAll.St.USER -> LinkAll.userStepText(id)
                            LinkAll.St.EXPIRED -> "登录过期了，点一下重新登录"
                            LinkAll.St.UNKNOWN -> "还没确认登录状态，点一下检查"
                            LinkAll.St.NONE -> if (LinkAll.note(id) == "跳过") "上次跳过了，点一下登录" else "点一下登录"
                            LinkAll.St.IGNORED -> "不再提醒，也不算失败"
                        }
                        PlusRow(null, LinkAll.name(id), sub, trailing = {
                            when (st) {
                                LinkAll.St.LINKED -> StatusPill(st.label, true)
                                LinkAll.St.IGNORED -> SmallPill("恢复") { LinkAll.setIgnored(id, false); rev++ }
                                else -> SmallPill("忽略") { LinkAll.setIgnored(id, true); rev++ }
                            }
                        }, onClick = { LinkAll.autoLogin = false; LinkAll.open(id, go, auto = false) })
                    }
                }
            }
        }
        // [Mines-38.8] accounts Mines noticed by itself (a sign-up / sign-in in Mines, an SMS code, a welcome mail)
        remember { com.openminis.app.plus.AutoAccounts.init(ctx); 0 }
        val auto by com.openminis.app.plus.AutoAccounts.list.collectAsState()
        if (auto.isNotEmpty()) PlusSection("自动记下的账号（${auto.size}）") {
            run {
                auto.forEach { e ->
                    PlusRow(null, e.site, listOf(e.account.ifBlank { "账号没看到" }, e.source).joinToString(" · "), chevron = false,
                        trailing = { StatusPill(e.status, e.status == com.openminis.app.plus.AutoAccounts.LINKED) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (linked + ign < all.size) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("再关联一遍没好的", Modifier.fillMaxWidth()) { LinkAll.start(); go(LinkAll.ROUTE) }
        }
        Spacer(Modifier.height(24.dp))
    }
}


/**
 * [Mines-38.7] Edge / Chrome passwords: import the browser's password export (CSV) once, kept encrypted on the phone;
 * and the system autofill service (Microsoft Authenticator syncs Edge passwords) — both fill Mines' own sign-in pages.
 * Everything stays inside Mines: a sign-in in another browser could never reach Mines.
 */
@Composable
fun LinkAllHelpers() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var n by remember { mutableIntStateOf(com.openminis.app.plus.SavedLogins.count(ctx)) }
    var lastUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val k = com.openminis.app.plus.SavedLogins.importUri(ctx, uri)
        n = com.openminis.app.plus.SavedLogins.count(ctx)
        lastUri = if (k > 0) uri else null
        msg = if (k > 0) "已导入 $k 个账号密码，加密存在这台手机里。原文件里是明文密码，最好删掉它。" else "没读到密码。请选 Edge / Chrome 导出的「密码 CSV」文件"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigText(if (n > 0) "已存 $n 个网站密码，关联时自动填" else "密码在 Edge 里？导出一次，关联时我自动填", 15, FontWeight.Normal, inset = 0.dp)
        msg?.let { BigText(it, 15, FontWeight.Normal, inset = 0.dp) }
        val u = lastUri
        if (u != null) PillButton("删掉原文件", Modifier.fillMaxWidth()) {
            val ok = runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, u) }.getOrDefault(false)
            msg = if (ok) "原文件已删掉" else "这个文件删不了，请到「文件管理」里手动删掉它"; lastUri = null
        }
        else PillButton(if (n > 0) "再导入一次密码文件" else "导入 Edge 密码文件", Modifier.fillMaxWidth(), primary = false) {
            pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
        }
        BigText("也可以把「Microsoft Authenticator」设成自动填充，登录页一点就填", 15, FontWeight.Normal, inset = 0.dp)
        PillButton("设置自动填充", Modifier.fillMaxWidth(), primary = false) {
            // Microsoft Authenticator installed → Android asks right away to make it the autofill service
            val ms = "com.azure.authenticator"
            val has = runCatching { ctx.packageManager.getPackageInfo(ms, 0); true }.getOrDefault(false)
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                android.net.Uri.parse("package:" + (if (has) ms else ctx.packageName))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}


/** [Mines-38.7] 设置 › 已忽略的平台: every platform 一键关联 was told to ignore, each with 恢复 (and 全部恢复). */
@Composable
fun LinkIgnoredScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { GroupPrefs.init(ctx); com.openminis.app.plus.PlusVault.init(ctx); 0 }
    var rev by remember { mutableIntStateOf(0) }
    val list = remember(rev) { LinkAll.ignoredList() }
    PlusPage("已忽略的平台", onBack) {
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) BigText("没有忽略的平台。在「一键关联 › 关联结果」里点「忽略」的平台会出现在这里。", 15, FontWeight.Normal)
        else {
            BigText("这些平台不再提醒，也不算失败。点「恢复」重新加入一键关联。", 15, FontWeight.Normal)
            PlusSection("已忽略（${list.size}）") {
                MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                    list.forEach { id ->
                        PlusRow(null, LinkAll.name(id), LinkAll.category(id) + " · " + LinkAll.how(id), chevron = false, trailing = {
                            PillButton("恢复", primary = false) { LinkAll.setIgnored(id, false); rev++ }
                        })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                PillButton("全部恢复", Modifier.fillMaxWidth()) { list.forEach { LinkAll.setIgnored(it, false) }; rev++ }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


/** [Mines-38.8 v3] a 1 dp near-transparent WebView drawn in the window (see LinkAllScreen); destroyed on leave. */
@Composable
private fun WindowWebWarm() {
    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
        WebView(c).also { w ->
            w.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            w.settings.javaScriptEnabled = false
            w.isFocusable = false
            w.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            w.loadDataWithBaseURL("about:blank", "<html><body style='background:transparent'></body></html>", "text/html", "utf-8", null)
        }
    }, onRelease = { w -> runCatching { w.stopLoading(); w.destroy() } },
        modifier = Modifier.size(1.dp).alpha(0.01f))
}


/**
 * [Mines-38.9.1] user 2026-10-16 「应该是用户有什么就登记什么」: the identities 一键关联 will use — only what the user has
 * (seen in sign-ins / mail / SMS / autofill, or added here), any type; + adds one of any type. Was a phone-only text.
 */
@Composable
private fun LinkIdentities() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = mines()
    remember { com.openminis.app.plus.IdentityStore.init(ctx); 0 }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.openminis.app.plus.IdentityStore.sync(ctx, force = true) } } }
    val ids by com.openminis.app.plus.IdentityStore.idents.collectAsState()
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("会用这些身份登录", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        MinesCard(Modifier.fillMaxWidth()) {
            if (ids.isEmpty()) Text("还没有登记的身份。点下面「添加身份」，手机号、邮箱、Google、X、GitHub、微信……有什么加什么。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2, modifier = Modifier.padding(16.dp))
            ids.sortedBy { it.type.ordinal }.forEach { i ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MethodBadge(if (i.type.provider) com.openminis.app.plus.IdentityCore.Method.OAUTH else when (i.type) {
                        com.openminis.app.plus.IdentityCore.IdType.EMAIL -> com.openminis.app.plus.IdentityCore.Method.EMAIL_CODE
                        com.openminis.app.plus.IdentityCore.IdType.PHONE -> com.openminis.app.plus.IdentityCore.Method.PHONE_SMS
                        else -> com.openminis.app.plus.IdentityCore.Method.USER_PW }, i.type.takeIf { it.provider }, 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(i.type.label + if (i.note.isNotBlank()) " · " + i.note else "", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                        Text(maskIdent(i.type, i.value), fontSize = MT.Caption, color = m.ink3, maxLines = 1)
                    }
                    if (i.type.provider && i.signedIn) StatusPill("已登录", true)
                    else if (i.source == "手动添加") Text("删除", fontSize = MT.Caption, color = m.primaryText, modifier = Modifier.clickable { com.openminis.app.plus.IdentityStore.removeIdent(ctx, i.key) }.padding(6.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PillButton("＋ 添加身份", Modifier.fillMaxWidth(), primary = false) { add = true }
    }
    if (add) AddIdentitySheet({ add = false }) { add = false }
}
