package com.openminis.app.ui.plus

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import com.openminis.app.ui.glass.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.plus.GeminiAccounts
import com.openminis.app.plus.GeminiAccountsCore
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.11 b] Own activity for the 38.9.11 b pages, so they need no NavHost route (settings / provider screens are
 * edited by the other team in parallel): extra "screen" = "gemini" (谷歌账号 · Gemini, default) or "apppw" (应用专用密码向导).
 */
class GeminiAccountsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent?.getStringExtra("screen") ?: "gemini"
        val addNew = intent?.getBooleanExtra("add", false) == true
        setContent {
            val prefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(this) }
            val mode = remember { prefs.getInt(com.openminis.app.ui.settings.KEY_THEME_MODE, 3) }
            val night by com.openminis.app.plus.DayNight.night.collectAsState()
            val dark = when (mode) { 1 -> false; 2 -> true; 3 -> night; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            com.openminis.app.ui.theme.MinisTheme(darkTheme = dark) {
                when (screen) {
                    "apppw" -> AppPasswordGuidePage(email = intent?.getStringExtra("email").orEmpty(), onBack = { finish() })
                    else -> GeminiAccountsPage(addNew = addNew, onBack = { finish() })
                }
            }
        }
    }
}

@Composable
fun GeminiAccountsPage(addNew: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    remember { GeminiAccounts.init(ctx); 0 }
    val list by GeminiAccounts.accounts.collectAsState()
    var loginSlot by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checkingAll by remember { mutableStateOf(false) }
    var addHint by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (addNew && list.none { !it.signedIn }) loginSlot = GeminiAccounts.addSlot(ctx).id
    }
    // back from Chrome (fallback path): a copied code on the clipboard finishes the pending sign-in
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && GeminiAccounts.pendingSlot() != null && loginSlot == null) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val txt = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                val code = GeminiAccountsCore.pastedCode(txt)
                if (code != null) scope.launch {
                    note = "正在完成登录…"
                    note = GeminiAccounts.finishLogin(ctx, code, null) ?: "登录好了"
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    PlusPage("谷歌账号 · Gemini", onBack = onBack, actions = {
        if (list.any { it.signedIn }) Text(if (checkingAll) "检测中…" else "全部检测", fontSize = MT.Body, color = m.primaryText, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !checkingAll) {
                checkingAll = true; scope.launch { GeminiAccounts.checkAll(ctx); checkingAll = false }
            }.padding(horizontal = 12.dp, vertical = 8.dp))
    }) {
        Column(Modifier.fillMaxWidth()) {
            val now = System.currentTimeMillis()
            Text(GeminiAccountsCore.summary(list.map { it.slot() }, now), fontSize = MT.Body, color = m.ink,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Text("用你自己谷歌账号里的 Gemini 免费额度（含 Pro），不用 API Key。多个账号轮流用，一个用完自动换下一个；每个账号单独登录、互不影响。",
                fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            note?.let { Text(it, fontSize = MT.Caption, color = m.primaryText, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            PlusSection("账号") {
                if (list.isEmpty()) PlusRow(null, "还没有谷歌账号", "点下面「添加谷歌账号」", chevron = false)
                list.forEach { a ->
                    AccountRow(a, busy = a.id in busy,
                        onLogin = { loginSlot = a.id },
                        onCheck = { busy = busy + a.id; scope.launch { GeminiAccounts.check(ctx, a.id); busy = busy - a.id } },
                        onWipe = { note = if (GeminiAccounts.wipeWebData(a.id)) "已清除 ${a.title} 的网页数据（登录凭证还在）" else "这台手机的 WebView 太旧，不能单独清除" },
                        onToggle = { GeminiAccounts.setDisabled(ctx, a.id, !a.disabled) },
                        onRemove = { GeminiAccounts.remove(ctx, a.id) })
                }
            }
            PlusSection("添加") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(minesGlassFill(strong = true))
                        .border(1.dp, m.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (addHint.isEmpty()) Text("谷歌邮箱（可不填）", fontSize = MT.Body, color = m.ink3)
                        BasicTextField(addHint, { addHint = it }, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink),
                            cursorBrush = SolidColor(m.primary), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "谷歌邮箱" })
                    }
                    Spacer(Modifier.width(10.dp))
                    ActionPill2("添加并登录", primary = true) {
                        val s = GeminiAccounts.addSlot(ctx, addHint); addHint = ""; loginSlot = s.id
                    }
                }
                val unsigned = list.filter { !it.signedIn || it.needsLogin }
                if (unsigned.size > 1) PlusRow(null, "依次登录 ${unsigned.size} 个账号", "一个登录好自动打开下一个", onClick = { loginSlot = unsigned.first().id })
            }
            PlusSection(null, footer = "Gemini 不对国内网络开放时，Mines 会自动改走「应用代理」里的美国出口线路（内置 Clash / 你加的代理）。登录在 Mines 里单独的网页环境完成，不碰其它账号；谷歌不让在 App 里登录时，点「用浏览器登录」，在浏览器里复制登录码，回到 Mines 会自动填好。") {
                PlusRow(null, "在模型组里使用", "登录后会出现「${GeminiAccounts.INSTANCE_LABEL}」服务商，模型组里选 gemini-2.5-pro 即可", chevron = false)
            }
        }
    }

    loginSlot?.let { slot ->
        GoogleLoginLayer(slotId = slot, onDone = { err ->
            loginSlot = null
            note = err ?: "登录好了"
            if (err == null) {
                // 依次登录: open the next account still waiting for a sign-in
                val next = GeminiAccounts.accounts.value.firstOrNull { (!it.signedIn || it.needsLogin) && it.id != slot }
                if (next != null) loginSlot = next.id
            }
        }, onClose = { loginSlot = null })
    }
}

@Composable
private fun AccountRow(a: GeminiAccounts.Acct, busy: Boolean, onLogin: () -> Unit, onCheck: () -> Unit, onWipe: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit) {
    val m = mines()
    var menu by remember { mutableStateOf(false) }
    val danger = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger
    val now = System.currentTimeMillis()
    val line = when {
        a.disabled -> "已停用"
        !a.signedIn -> "还没登录"
        a.needsLogin -> a.status.ifBlank { "登录过期了" }
        a.coolUntil > now -> "额度用完，约 ${((a.coolUntil - now) / 60_000L).coerceAtLeast(1)} 分钟后再用"
        else -> a.status.ifBlank { "已登录，点「检测」看额度" }
    }
    val bad = a.disabled || !a.signedIn || a.needsLogin || a.ok == false
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(when { a.ok == true && !bad -> if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success; bad -> danger; else -> m.ink3 }))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, fontSize = MT.Body, color = m.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, fontSize = MT.Sub, lineHeight = MT.SubLH, color = if (bad && a.signedIn) danger else m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.quota.isNotBlank() && !bad) Text(a.quota, fontSize = MT.Meta, color = m.ink2, maxLines = 1)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = m.primary)
        else if (!a.signedIn || a.needsLogin) ActionPill2("登录", primary = true, onClick = onLogin)
        else ActionPill2("检测", primary = false, onClick = onCheck)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = m.ink2) }
            DropdownMenu(menu, { menu = false }) {
                if (a.signedIn) DropdownMenuItem({ Text("重新登录") }, { menu = false; onLogin() })
                DropdownMenuItem({ Text("清除网页数据") }, { menu = false; onWipe() })
                DropdownMenuItem({ Text(if (a.disabled) "启用" else "停用") }, { menu = false; onToggle() })
                DropdownMenuItem({ Text("删除这个账号", color = danger) }, { menu = false; onRemove() })
            }
        }
    }
}

/**
 * Full-screen Google sign-in for ONE slot, inside that slot's own WebView profile (cookies / storage / cache of other
 * accounts are not visible here). The redirect to codeassist.google.com/authcode is caught before it loads, so the user
 * never copies a code. 「用浏览器登录」 = Chrome Custom Tab fallback (code copied there is picked up on return).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleLoginLayer(slotId: String, onDone: (String?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    val url = remember(slotId) { GeminiAccounts.beginLogin(ctx, slotId) }
    var finishing by remember(slotId) { mutableStateOf(false) }
    var hint by remember(slotId) { mutableStateOf<String?>(null) }
    var pasted by remember(slotId) { mutableStateOf("") }
    val shared = !com.openminis.app.plus.LoginWeb.profilesSupported()

    fun finish(code: String, state: String?) {
        if (finishing) return
        finishing = true
        scope.launch { val err = GeminiAccounts.finishLogin(ctx, code, state); finishing = false; onDone(err) }
    }

    Box(Modifier.fillMaxSize().background(m.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = m.ink) }
                Text("登录谷歌账号", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.weight(1f))
                Text("用浏览器登录", fontSize = MT.Body, color = m.primaryText, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        hint = "在浏览器里登录后，页面会显示一串登录码：复制它，回到 Mines 会自动填好（或粘贴到下面）"
                        runCatching { com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(ctx, url) }
                    }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (shared || hint != null) Text(hint ?: "这台手机的 WebView 不支持单独的网页环境：登录页会让你选账号，请选要添加的那个",
                fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (hint != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(minesGlassFill(strong = true))
                    .border(1.dp, m.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (pasted.isEmpty()) Text("粘贴登录码（4/ 开头）", fontSize = MT.Body, color = m.ink3)
                    BasicTextField(pasted, { pasted = it }, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink),
                        cursorBrush = SolidColor(m.primary), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "登录码" })
                }
                Spacer(Modifier.width(8.dp))
                ActionPill2("完成", primary = true, enabled = GeminiAccountsCore.pastedCode(pasted) != null) {
                    GeminiAccountsCore.pastedCode(pasted)?.let { finish(it, null) }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { c ->
                    WebView(c).also { w ->
                        w.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        // own profile BEFORE anything loads: this account's Google cookies only
                        if (!shared) runCatching {
                            val name = GeminiAccounts.webProfile(slotId)
                            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(name)
                            androidx.webkit.WebViewCompat.setProfile(w, name)
                        }
                        com.openminis.app.plus.LoginWeb.configure(w)
                        w.webViewClient = object : WebViewClient() {
                            fun catchRedirect(u: String?): Boolean {
                                val r = GeminiAccountsCore.parseRedirect(u ?: return false) ?: return false
                                if (r.code != null) finish(r.code, r.state)
                                else onDone(if (r.error == "access_denied") "你在谷歌页面点了取消" else "谷歌登录没成功：${r.error}")
                                return true
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (catchRedirect(u)) return true
                                if (u.contains("disallowed_useragent")) {
                                    hint = "谷歌不让在 App 里登录这个账号：点右上角「用浏览器登录」"; return false
                                }
                                return !u.startsWith("http")
                            }
                            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                                if (catchRedirect(u)) { runCatching { view.stopLoading() } }
                            }
                        }
                        w.loadUrl(url)
                    }
                }, modifier = Modifier.fillMaxSize())
                if (finishing) Box(Modifier.fillMaxSize().background(m.bg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = m.primary)
                        Text("正在开通 Gemini…", fontSize = MT.Body, color = m.ink)
                    }
                }
            }
        }
    }
}
