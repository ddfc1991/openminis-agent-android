package com.openminis.app.ui.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.ConnMethod
import com.openminis.app.plus.ConnectionKind
import com.openminis.app.plus.IdentityCore
import com.openminis.app.plus.IdentityStore
import com.openminis.app.plus.Keychain
import com.openminis.app.plus.SavedLogins
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.Partners
import com.openminis.app.plus.group.PresetAuth
import com.openminis.app.plus.group.PresetRegistry
import com.openminis.app.ui.plus.GlassPill
import com.openminis.app.ui.plus.GlassSecondaryPill
import com.openminis.app.ui.plus.InfoLine
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MinesDialog
import com.openminis.app.ui.plus.PlusPage
import com.openminis.app.ui.plus.WebLoginScreen
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.1] user 2026-10-16 「memoh 的登录就很怪，为什么不是我登录你记录自动填写？」: a self-hosted / web service is
 * signed in ON ITS REAL PAGE inside Mines (shared cookie store), never with a bespoke 服务器地址 / 用户名 / 密码 form.
 *  - The address comes from what Mines already knows (the service's saved base, a site in the identity store / keychain,
 *    a page visited before); only when nothing is known the user types it once (with those as suggestions).
 *  - While the page is open the typed account / password are only kept in the page's own sessionStorage; once signed
 *    in, Mines reads the session token the page stored (localStorage JWT) and, if the page only gave a password, gets a
 *    token through the service's API with it. Then it offers 「存下密码，下次自动填」 (SavedLogins, Keystore-encrypted)
 *    and records the account in the one identity store, so the next sign-in fills itself.
 */
object SiteLogin {
    const val ROUTE = "plus_sitelogin/{svc}"
    fun route(svc: String) = "plus_sitelogin/" + android.net.Uri.encode(svc)

    /** Known addresses for a self-hosted service: saved base, identity store / keychain sites, earlier visits. */
    fun suggestions(c: android.content.Context, svc: String): List<String> {
        val out = LinkedHashSet<String>()
        when (svc) {
            PresetRegistry.MEMOH -> { PresetAuth.memohOrigin()?.let { out += it }; out += "https://" + PresetAuth.MEMOH_HOSTED }
        }
        GroupPrefs.get("sitelogin_url_$svc")?.split('\n')?.filter { it.startsWith("http") }?.forEach { out += it }
        runCatching { IdentityStore.init(c); IdentityStore.recs.value.filter { it.site.contains(svc, true) || it.name.contains(svc, true) }.forEach { out += "https://" + it.site } }
        return out.toList().take(5)
    }

    fun remember(svc: String, url: String) {
        val old = GroupPrefs.get("sitelogin_url_$svc")?.split('\n').orEmpty()
        GroupPrefs.put("sitelogin_url_$svc", (listOf(url) + old).distinct().filter { it.startsWith("http") }.take(5).joinToString("\n"))
    }

    /** Polled while the page is open: installs a typed-value catcher once; truthy when a session token is stored. */
    const val DONE_JS = """(function(){try{if(!window.__mh){window.__mh=1;document.addEventListener('input',function(e){var t=e.target;if(!t||t.tagName!='INPUT')return;
if(t.type=='password')sessionStorage.__mpw=t.value;else if(t.type!='hidden'&&t.type!='checkbox')sessionStorage.__mus=t.value;},true);}
for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i),v=localStorage.getItem(k)||'';if(/token|auth|jwt|session/i.test(k)&&/eyJ[\w-]+\.[\w-]+\.[\w-]+/.test(v))return 'yes';}
if(/(^|\.)app\.memoh\.net$/.test(location.hostname)&&!/\/login/.test(location.pathname)){if(window.__mmok)return 'yes';if(!window.__mmq){window.__mmq=1;fetch('/api/v1/users/me',{credentials:'include'}).then(function(r){window.__mmok=r.ok;window.__mmq=0;}).catch(function(){window.__mmq=0;});}}
return '';}catch(e){return '';}})()"""

    /** account \u0001 token \u0001 password typed on the page (all optional). */
    const val ACCOUNT_JS = """(function(){try{var tok='';for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i),v=localStorage.getItem(k)||'';var m=/eyJ[\w-]+\.[\w-]+\.[\w-]+/.exec(v);if(/token|auth|jwt|session/i.test(k)&&m){tok=m[0];break;}}
return (sessionStorage.__mus||'')+'\u0001'+tok+'\u0001'+(sessionStorage.__mpw||'');}catch(e){return '';}})()"""
}

@Composable
fun SiteLoginScreen(svc: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    remember { GroupPrefs.init(ctx); 0 }
    val name = PresetRegistry.preset(svc)?.name ?: svc
    val sugg = remember { SiteLogin.suggestions(ctx, svc) }
    var url by remember { mutableStateOf(sugg.firstOrNull()) }
    var typed by remember { mutableStateOf("") }
    var save by remember { mutableStateOf<Triple<String, String, String>?>(null) }  // host, user, pass
    var msg by remember { mutableStateOf<String?>(null) }
    val u = url
    if (u == null) {
        // the address is truly unknown: ask for it once (with earlier ones as suggestions)
        PlusPage("登录 $name", onBack) {
            Text("你的 $name 在哪个网址？填一次，打开它自己的登录页，你像平时一样登录就行，我会记下来，下次自动填。",
                fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            OutlinedTextField(typed, { typed = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text("网址") }, placeholder = { Text("https://") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSecondaryPill("取消", Modifier.weight(0.4f)) { onBack() }
                GlassPill("打开登录页", enabled = typed.length > 3, modifier = Modifier.weight(0.6f)) {
                    url = (if (typed.startsWith("http")) typed else "https://$typed").trimEnd('/')
                }
            }
        }
        return
    }
    BackHandler { onBack() }
    val kind = remember(u) {
        ConnectionKind("svc:$svc", name, "", "", 0, ConnMethod.WEB, loginUrl = u, doneJs = SiteLogin.DONE_JS, accountJs = SiteLogin.ACCOUNT_JS)
    }
    val pending = save
    if (pending == null) WebLoginScreen(kind = kind, url = u, title = "登录 $name", detect = true,
        onDone = { raw, _ ->
            // [Mines-38.9.6 i13] real separators (or escape text from an older build); empty / control-char values rejected
            val parts = com.openminis.app.plus.LoginValueCore.parseAccount(raw)
            val user = parts.user; val token = parts.token; val pass = parts.pass
            SiteLogin.remember(svc, u)
            val host = Keychain.hostOf(u).ifBlank { u.removePrefix("https://").removePrefix("http://").substringBefore('/') }
            IdentityStore.addRec(ctx, IdentityCore.Rec(Keychain.root(host).ifBlank { host }, name, user, null,
                if (pass.isNotBlank()) IdentityCore.Method.of(when (IdentityCore.guessType(user)) { IdentityCore.IdType.EMAIL -> "email_pw"; IdentityCore.IdType.PHONE -> "phone_pw"; else -> "user_pw" }) else IdentityCore.Method.WEB,
                sources = setOf("网页登录"), at = System.currentTimeMillis()))
            scope.launch {
                if (svc == PresetRegistry.MEMOH) {
                    // token from the page; else the API with the password the user just typed
                    // [Mines-38.9.4] hosted app.memoh.net = cookie session under /api/memoh; self-hosted = JWT under /api
                    GroupPrefs.put("memoh_base", PresetAuth.memohApiBaseFor(u)); if (user.isNotBlank()) GroupPrefs.put("memoh_user", user)
                    GroupPrefs.put("memoh_web", if (token.isBlank()) "1" else null)
                    runCatching { android.webkit.CookieManager.getInstance().flush() }
                    runCatching { com.openminis.app.plus.SessionKeeper.snapshot(ctx, 0L) }
                    if (token.isNotBlank()) PresetAuth.setKey(PresetRegistry.MEMOH, token)
                    else if (user.isNotBlank() && pass.isNotBlank() && !PresetAuth.isMemohHosted(u)) PresetAuth.memohLogin(u, user, pass).onFailure { msg = "网页登录好了，但没拿到 Memoh 的令牌：${it.message}" }
                    runCatching { Partners.refresh(ctx) }
                }
                if (pass.isNotBlank() && user.isNotBlank() && runCatching { SavedLogins.find(ctx, host, user)?.pass }.getOrNull() != pass) save = Triple(host, user, pass)
                else onBack()
            }
        },
        onClose = onBack)
    else MinesDialog("存下 $name 的密码？", onDismiss = { save = null; onBack() }, confirm = "存下，下次自动填", onConfirm = {
        SavedLogins.put(ctx, SavedLogins.Entry(name, "https://${pending.first}/", pending.second, pending.third))
        if (svc == PresetRegistry.MEMOH) runCatching { com.openminis.app.plus.PlusVault.setSecretExtra("preset_auth", "memoh_pass", pending.third) }
        save = null; onBack()
    }, dismiss = "不用") {
        Text("账号 ${pending.second}。密码加密存在这台手机里，下次打开登录页我自动填好。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        msg?.let { Text(it, fontSize = MT.Sub, color = m.ink2) }
    }
}

/** For self-hosted services whose sign-in is a web page: no Mines-made form. */
fun usesSiteLogin(id: String): Boolean = PresetRegistry.loginFlowOf(id) is com.openminis.app.plus.group.LoginFlow.PasswordJwt
