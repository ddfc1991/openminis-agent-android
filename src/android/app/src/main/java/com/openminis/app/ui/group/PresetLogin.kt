package com.openminis.app.ui.group

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.LoginFlow
import com.openminis.app.plus.group.MultiAgentGroup
import com.openminis.app.plus.group.Partner
import com.openminis.app.plus.group.Partners
import com.openminis.app.plus.group.PresetAuth
import com.openminis.app.plus.group.PresetPartners
import com.openminis.app.plus.group.PresetRegistry
import com.openminis.app.ui.plus.GlassTextButton
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MinesDesign
import com.openminis.app.ui.plus.MinesDialog
import com.openminis.app.ui.plus.PlusRow
import com.openminis.app.ui.plus.PlusSection
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.launch

/**
 * [Mines-38.8] Sign-in for the registry's login-flow presets, one simple step at a time (user is partially sighted):
 *  - Muse 中继 (nanoMuse): 邮箱 → 「发验证码」 → 验证码 → 「登录」 (the relay prints the code in its server log; Hark can read
 *    it there and tell the user) → nm_ key stored;
 *  - Memoh: 服务器地址 + 用户名 + 密码 → 「登录」 → the account's bots become members.
 */
@Composable
fun PresetLoginDialog(p: Partner, onDismiss: () -> Unit, onDone: () -> Unit) {
    when (PresetRegistry.loginFlowOf(p.id)) {
        is LoginFlow.RelayCode -> RelayCodeDialog(onDismiss, onDone)
        is LoginFlow.PasswordJwt -> MemohLoginDialog(onDismiss, onDone)
        else -> androidx.compose.runtime.LaunchedEffect(p.id) { onDismiss() }
    }
}

@Composable
private fun Busy(text: String) {
    val m = mines()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
        Text(text, fontSize = MT.Sub, color = m.ink2)
    }
}

@Composable
private fun RelayCodeDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(PresetAuth.relayEmail() ?: "") }
    // [Mines-38.9.1] no built-in relay: the user's own address (remembered once given)
    var relay by remember { mutableStateOf(GroupPrefs.get("base_nanomuse") ?: "") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val signedIn = PresetAuth.key("nanomuse") != null
    MinesDialog("登录 Muse 中继", onDismiss = onDismiss,
        confirm = if (!sent) "发验证码" else "登录", confirmEnabled = !busy && (if (!sent) email.contains('@') && relay.startsWith("http") else code.trim().length >= 4),
        onConfirm = {
            busy = true; msg = null
            scope.launch {
                if (!sent) {
                    GroupPrefs.put("base_nanomuse", relay.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" })
                    val r = PresetAuth.relayRequestCode(email)
                    busy = false
                    if (r.isSuccess) { sent = true; msg = "验证码已发出。私人中继会把它写在服务器日志里：让 Hark 帮你读，或自己看日志，然后填在下面" }
                    else msg = r.exceptionOrNull()?.message ?: "没发出去"
                } else {
                    val r = PresetAuth.relayVerify(email, code)
                    busy = false
                    if (r.isSuccess) { Partners.refresh(ctx); onDone() } else msg = r.exceptionOrNull()?.message ?: "没登录上"
                }
            }
        }) {
        Text(if (signedIn) "已经登录过。重新登录会换一个新密钥。" else "第 1 步：填你在中继里的邮箱，点「发验证码」。",
            fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        OutlinedTextField(relay, { relay = it.trim() }, singleLine = true, enabled = !sent, modifier = Modifier.fillMaxWidth(),
            label = { Text("中继地址") }, placeholder = { Text("http://你的服务器:端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(email, { email = it.trim() }, singleLine = true, enabled = !sent, modifier = Modifier.fillMaxWidth(),
            label = { Text("邮箱") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (sent) {
            Text("第 2 步：填 6 位验证码，点「登录」。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            OutlinedTextField(code, { code = it.filter { c -> c.isLetterOrDigit() }.take(8) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("验证码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            GlassTextButton("重新发验证码") { sent = false; code = "" }
        }
        if (busy) Busy(if (!sent) "正在请中继发验证码…" else "正在登录…")
        msg?.let { Text(it, fontSize = MT.Sub, lineHeight = MT.SubLH, color = if (it.startsWith("验证码已发出")) m.ink2 else MinesDesign.Danger) }
        if (signedIn) GlassTextButton("退出中继", color = MinesDesign.Danger) { PresetAuth.relaySignOut(); scope.launch { Partners.refresh(ctx) }; onDone() }
    }
}

@Composable
private fun MemohLoginDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(PresetAuth.memohBase()?.removeSuffix("/api") ?: "") }
    var user by remember { mutableStateOf(PresetAuth.memohUser() ?: "") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val signedIn = PresetAuth.key(PresetRegistry.MEMOH) != null
    MinesDialog("登录 Memoh", onDismiss = onDismiss, confirm = "登录",
        confirmEnabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
        onConfirm = {
            busy = true; msg = null
            scope.launch {
                val r = PresetAuth.memohLogin(url, user, pass)
                if (r.isFailure) { busy = false; msg = r.exceptionOrNull()?.message ?: "没登录上"; return@launch }
                val bots = PresetPartners.memohBots()
                busy = false
                Partners.refresh(ctx)
                val n = bots.getOrNull()?.size ?: 0
                if (n > 0) onDone() else msg = bots.exceptionOrNull()?.message ?: "登录好了，但 Memoh 里还没有机器人：在 Memoh 建一个，回来自动出现"
            }
        }) {
        Text("填你打开 Memoh 网页的地址和账号。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        OutlinedTextField(url, { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("服务器地址") },
            placeholder = { Text("http://你的服务器") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("用户名") })
        OutlinedTextField(pass, { pass = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (busy) Busy("正在登录…")
        msg?.let { Text(it, fontSize = MT.Sub, lineHeight = MT.SubLH, color = MinesDesign.Danger) }
        if (signedIn) GlassTextButton("退出 Memoh", color = MinesDesign.Danger) { PresetAuth.memohSignOut(); scope.launch { Partners.refresh(ctx) }; onDone() }
    }
}

/**
 * [Mines-38.8] 「更多免费接入」 at the bottom of AI 伙伴: Muse 网页版 switch (US network + age check), web addresses of the
 * bridge apps, and one tap to start the Mines × Muse multi-agent group.
 */
@Composable
fun PresetExtrasSection(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var muse by remember { mutableStateOf(PresetRegistry.museOn()) }
    var editUrl by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    PlusSection("更多免费接入", footer = "都不用付费密钥：登录账号就行。Muse 网页版只在美国网络能用，年龄验证要你自己在登录页里完成。") {
        PlusRow(null, "建一个 Mines × Muse 群", if (making) "正在建…" else "Mines、Muse 中继和你已连接的智能体在一个群里，共用同一段上下文") {
            if (making) return@PlusRow
            making = true
            scope.launch { val gid = MultiAgentGroup.ensure(ctx); making = false; go(GroupRoutes.chat(gid)) }
        }
        PlusRow(null, "Meta Muse 网页版", if (muse) "已打开 · 需要美国网络" else "默认关闭：我在美国网络下使用时再打开", chevron = false, trailing = {
            Switch(muse, { on -> muse = on; PresetRegistry.setMuseOn(on); scope.launch { Partners.refresh(ctx) } })
        })
        val bridges = (listOf("web:hark", "web:manus") + if (muse) listOf("web:muse") else emptyList()).mapNotNull { PresetRegistry.preset(it) }
        bridges.forEach { p ->
            PlusRow(null, "${p.name} 网页地址", PresetRegistry.webUrl(p.id).ifBlank { "还没填" }) { editUrl = p.id }
        }
    }
    editUrl?.let { id ->
        val m = mines()
        var v by remember(id) { mutableStateOf(PresetRegistry.webUrl(id)) }
        MinesDialog("网页地址", onDismiss = { editUrl = null }, confirm = "保存", confirmEnabled = v.isBlank() || v.startsWith("http"),
            onConfirm = {
                GroupPrefs.put("base_$id", v.trim().ifBlank { null })
                editUrl = null
                scope.launch { Partners.refresh(ctx) }
            }) {
            Text("填你在浏览器里打开聊天的地址（以 https:// 开头）。留空用默认。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            OutlinedTextField(v, { v = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("地址") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
    }
}
