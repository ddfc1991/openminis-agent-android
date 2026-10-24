package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.plus.AppPasswordCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.11 b] 应用专用密码向导: one step per screen (the user is partly blind and finds multi-step setup hard —
 * one simple step at a time), Google pages open in the browser where the user is already signed in, the 16-letter
 * password is picked up from the clipboard on return, then a REAL IMAP login + newest code read, saved to the vault.
 */
@Composable
fun AppPasswordGuidePage(email: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val known = remember {
        runCatching { com.openminis.app.plus.IdentityStore.current(ctx).map { it.value.lowercase() }.filter { AppPasswordCore.isGmail(it) }.distinct() }.getOrDefault(emptyList())
    }
    var addr by remember { mutableStateOf(email.ifBlank { known.firstOrNull().orEmpty() }) }
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    // back from the Google page: a copied 16-letter password jumps to the test step, filled in
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && step in 2..3 && pw.isBlank()) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val txt = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                AppPasswordCore.parse(txt)?.let { pw = it; step = 3 }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val s = AppPasswordCore.STEPS[step]
    PlusPage("Gmail 验证码 · 应用专用密码", onBack = { if (step > 0 && step < AppPasswordCore.STEPS.size - 1) step-- else onBack() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppPasswordCore.progress(step), fontSize = MT.Caption, color = m.ink2)
            Text(s.title, fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text(s.body, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            when (step) {
                0 -> {
                    GuideField(addr, { addr = it.trim() }, "Gmail 地址", secret = false)
                    known.filter { it != addr }.forEach { k ->
                        Text(k, fontSize = MT.Body, color = m.primaryText, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { addr = k }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    if (addr.isNotBlank() && !AppPasswordCore.isGmail(addr)) Text("这里只管 Gmail；163 / QQ 邮箱用它们自己的「授权码」，在 账号 › 邮箱 里加。", fontSize = MT.Caption, color = m.ink2)
                    PillButton("下一步", Modifier.fillMaxWidth(), enabled = AppPasswordCore.isGmail(addr)) { step = 1 }
                }
                1 -> {
                    PillButton(s.button!!, Modifier.fillMaxWidth()) { openGoogle(ctx, AppPasswordCore.TWO_STEP_URL, addr) }
                    PillButton("已经开好了，下一步", Modifier.fillMaxWidth(), primary = false) { step = 2 }
                }
                2 -> {
                    PillButton(s.button!!, Modifier.fillMaxWidth()) { openGoogle(ctx, AppPasswordCore.APP_PASSWORDS_URL, addr) }
                    PillButton("我复制好了，下一步", Modifier.fillMaxWidth(), primary = false) { step = 3 }
                    Text("页面说「你要查找的设置不适用于你的账号」= 两步验证还没开好，回上一步。", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
                }
                3 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { GuideField(pw, { pw = it; result = null }, "16 位应用专用密码", secret = !show) }
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { show = !show }.semantics { contentDescription = if (show) "隐藏密码" else "显示密码" },
                            contentAlignment = Alignment.Center) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = m.ink2) }
                    }
                    result?.let { Text(it, fontSize = MT.Body, lineHeight = MT.BodyLH, color = if (failed) (if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) else m.ink) }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = m.primary); Spacer(Modifier.size(10.dp))
                        Text("正在登录 Gmail 收件箱…", fontSize = MT.Body, color = m.ink2)
                    }
                    PillButton("测试并保存", Modifier.fillMaxWidth(), enabled = !busy && pw.replace(" ", "").length >= 16) {
                        busy = true; result = null
                        scope.launch {
                            val err = com.openminis.app.plus.MailInbox.verifyAndAdd(ctx, addr, pw)
                            if (err != null) { failed = true; result = err; busy = false; return@launch }
                            // a real read: newest code mail of the last day
                            val line = withContext(Dispatchers.IO) {
                                runCatching {
                                    val host = com.openminis.app.plus.MailImap.hostFor(addr) ?: return@runCatching AppPasswordCore.testLine(null, null, null)
                                    val box = com.openminis.app.plus.MailImap.inbox(host, com.openminis.app.plus.MailConfig.normalizeAddress(addr), com.openminis.app.plus.MailConfig.normalizeCode(pw), max = 10)
                                    val since = System.currentTimeMillis() - 24 * 3600_000L
                                    val hit = box.msgs.filter { it.at == 0L || it.at >= since }.sortedByDescending { it.at }
                                        .firstNotNullOfOrNull { msg -> com.openminis.app.plus.MailParse.extractCode(msg.subject + "\n" + msg.text)?.let { it to msg } }
                                    AppPasswordCore.testLine(hit?.first, hit?.second?.from, hit?.second?.at?.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 60_000L })
                                }.getOrDefault(AppPasswordCore.testLine(null, null, null))
                            }
                            failed = false; result = "已保存到保险箱。$line"; busy = false
                            runCatching { com.openminis.app.plus.MailSources.invalidate() }
                            step = 4
                        }
                    }
                }
                else -> {
                    result?.let { Text(it, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink) }
                    PillButton("完成", Modifier.fillMaxWidth()) { onBack() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openGoogle(ctx: android.content.Context, url: String, addr: String) {
    // the user's own browser already holds their Google sign-in; authuser picks the right account when several are signed in
    val u = if (addr.contains('@')) "$url?authuser=" + java.net.URLEncoder.encode(addr, "UTF-8") else url
    runCatching { com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(ctx, u) }
}

@Composable
private fun GuideField(v: String, onChange: (String) -> Unit, hint: String, secret: Boolean) {
    val m = mines()
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(minesGlassFill(strong = true))
        .border(1.dp, m.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (v.isEmpty()) Text(hint, fontSize = MT.Body, color = m.ink3)
        BasicTextField(v, onChange, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink), cursorBrush = SolidColor(m.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
    }
}

/** Opens the 应用专用密码向导 (own activity screen "apppw"). */
fun openAppPasswordGuide(c: android.content.Context, email: String = "") {
    val i = android.content.Intent(c, GeminiAccountsActivity::class.java).putExtra("screen", "apppw").putExtra("email", email)
    if (c !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    c.startActivity(i)
}
