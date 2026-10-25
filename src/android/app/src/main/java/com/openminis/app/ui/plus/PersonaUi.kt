package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.openminis.app.persona.LimitGuardCore
import com.openminis.app.persona.Persona
import com.openminis.app.persona.PersonaCore
import com.openminis.app.persona.PersonaNotify
import com.openminis.app.ui.brand.MinesIcons

/**
 * [Mines-38.9.15 伪人核心] 应用 → 伪人核心: who I am, which accounts I may act on, the limits, what I did, 全部停止.
 *
 * Everything on this page writes through [Persona.userSet*] with [LimitGuardCore.USER_TAP], behind the device lock
 * where the phone has one — the agent has no path to any of it (user 2026-10-25 「限制层听我号令」).
 */
object PersonaRoutes { const val PAGE = "plus_persona" }

private fun ic(n: String) = MinesIcons.named(n)!!

/**
 * Every change on this page is the user's tap; a change that LOOSENS a limit (resume, allow an account, stop asking,
 * raise a cap, approve a send) also asks for the device lock (fingerprint / face / screen lock) where the phone has
 * one. Tightening — 全部停止, switching an account off — is instant.
 */
@Composable
private fun rememberUserTap(): (String, Boolean, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) {
        { title: String, loosen: Boolean, body: () -> Unit -> if (!loosen) body() else deviceUnlock(ctx, title) { ok -> if (ok) body() } }
    }
}

/** biometrics or the screen lock; no lock on the phone = the tap itself is the proof */
private fun deviceUnlock(ctx: android.content.Context, title: String, done: (Boolean) -> Unit) {
    val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
    if (km?.isDeviceSecure != true || android.os.Build.VERSION.SDK_INT < 29) { done(true); return }
    val b = android.hardware.biometrics.BiometricPrompt.Builder(ctx).setTitle(title).setSubtitle("限制层只听你的，先验证是你本人")
    if (android.os.Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(
        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
    runCatching {
        b.build().authenticate(android.os.CancellationSignal(), ctx.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { done(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(false) }
        })
    }.onFailure { done(false) }
}

@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Persona.init(ctx)
    val m = mines()
    val profile by Persona.profile.collectAsState()
    val limits by Persona.limits.collectAsState()
    val log by Persona.log.collectAsState()
    val pending by Persona.pending.collectAsState()
    val style by Persona.style.collectAsState()
    val flagOn by Persona.on.collectAsState()
    val tap = rememberUserTap()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editName by remember { mutableStateOf(false) }

    PlusPage("伪人核心", onBack) {
        // ── kill switch ──
        PlusSection("总开关", footer = "关掉后我不会再用你的任何账号做事：不发、不删、不付款，也不替你操作网页。通知栏上也有一个「全部停止」。") {
            SwitchRow(
                if (limits.killed) "已全部停止" else "允许我以你的身份做事",
                if (limits.killed) "我现在什么都不会做" else "只在你允许的账号上，发出去的还要你点头",
                !limits.killed,
            ) { on -> tap("恢复伪人核心", on) { Persona.userSetKill(ctx, LimitGuardCore.USER_TAP, !on); PersonaNotify.refresh() } }
            WarmDivider(16.dp)
            SwitchRow("只在我的活动时间里做事", "${profile.activeFromHour} 点到 ${profile.activeToHour} 点之外先放着", limits.quietHours) { on ->
                tap("不再限活动时间", !on) { Persona.userSetQuietHours(ctx, LimitGuardCore.USER_TAP, on) }
            }
        }

        // ── waiting for the user ──
        if (pending.isNotEmpty()) PlusSection("等你点头") {
            pending.forEachIndexed { i, p ->
                if (i > 0) WarmDivider(16.dp)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("${p.account}：${LimitGuardCore.Act.values().firstOrNull { a -> a.id == p.act }?.zh ?: p.act}",
                        fontSize = MT.Body, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p.note.isNotBlank()) Text(p.note, fontSize = MT.Sub, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("同意这一次", Modifier.weight(1f)) { tap("同意这一步", true) { Persona.userApprove(ctx, LimitGuardCore.USER_TAP, p.id, true) } }
                        PillButton("不要做", Modifier.weight(1f), primary = false) { Persona.userApprove(ctx, LimitGuardCore.USER_TAP, p.id, false) }
                    }
                }
            }
        }

        // ── who I am ──
        PlusSection("我是谁", footer = "我替你操作网站和邮箱时就是你本人：第一人称说话，不自称 AI。说话的样子从你自己发出去的邮件里学。") {
            PlusRow(ic("user"), "显示名", profile.displayName.ifBlank { "还没填" }, onClick = { editName = true })
            WarmDivider()
            PlusRow(ic("pencil"), "说话习惯", profile.traits.joinToString("、").ifBlank { "还没填" }, onClick = { editName = true })
            WarmDivider()
            PlusRow(ic("mail"), "学我的说法", if (style.known) "已学 ${style.samples} 封，每句约 ${style.sentenceLen} 字" else "还没学（看你发件箱）",
                onClick = { scope.launch { Persona.learnStyle(ctx) } })
        }
        if (editName) PersonaProfileDialog(profile) { p -> editName = false; if (p != null) tap("改伪人资料", true) { Persona.userSetProfile(ctx, LimitGuardCore.USER_TAP, p) } }

        // ── accounts ──
        PlusSection("账号", footer = "新发现的账号一律是关着的。打开一个，我才会用它做事；每个账号可以单独定每天的次数、要不要先问你。") {
            if (profile.accounts.isEmpty()) InfoLine("还没有账号。等我替你打开微博、小红书或邮箱时会自己记在这里，默认都是关着的。")
            profile.accounts.forEachIndexed { i, a ->
                if (i > 0) WarmDivider(16.dp)
                PersonaAccountRow(a) { changed -> tap("放宽这个账号", loosens(a, changed)) { Persona.userSetAccount(ctx, LimitGuardCore.USER_TAP, changed); PersonaNotify.refresh() } }
            }
        }

        // ── audit ──
        PlusSection("我做过什么", action = if (log.isEmpty()) null else "清空", onAction = { tap("清空记录", true) { Persona.clearLog(ctx, LimitGuardCore.USER_TAP) } }) {
            if (log.isEmpty()) InfoLine("还没有记录。")
            log.asReversed().take(30).forEachIndexed { i, e ->
                if (i > 0) WarmDivider(16.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(logLine(e), fontSize = MT.Body, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(hm(e.at) + (if (e.account.isNotBlank()) " · " + e.account else ""), fontSize = MT.Meta, color = m.ink2, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(if (e.allowed) "做了" else "拦下", e.allowed)
                }
            }
        }

        if (!flagOn) InfoLine("「伪人核心」在 设置 → 实验功能 里关着，我现在不会以你的身份做事。")
    }
}

/** does this change give the agent more room than before? (then the device lock is asked) */
internal fun loosens(old: PersonaCore.Account, new: PersonaCore.Account): Boolean =
    (new.allowed && !old.allowed) || (!new.confirmSend && old.confirmSend) || new.dailyCap > old.dailyCap

private fun hm(at: Long): String =
    if (at <= 0L) "" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

private fun logLine(e: LimitGuardCore.Entry): String {
    val act = LimitGuardCore.Act.values().firstOrNull { it.id == e.act }?.zh ?: when (e.act) {
        "kill-on" -> "你按了全部停止"; "kill-off" -> "你恢复了"; "allow" -> "你允许了这个账号"; "disallow" -> "你关掉了这个账号"
        "voice" -> "改掉了助手口吻"; "send" -> "发出去"; else -> e.act
    }
    val why = LimitGuardCore.Why.values().firstOrNull { it.name == e.why }?.zh.orEmpty()
    return (act + (if (e.note.isNotBlank()) " " + e.note else "") + (if (!e.allowed && why.isNotBlank()) "（${why.substringBefore('，')}）" else "")).trim()
}

@Composable
private fun PersonaAccountRow(a: PersonaCore.Account, onChange: (PersonaCore.Account) -> Unit) {
    val m = mines()
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SwitchRow(a.site + (if (a.handle.isNotBlank()) " · " + a.handle else ""),
            if (a.allowed) "每天 ${a.dailyCap} 次 · " + (if (a.confirmSend) "发出去先问我" else "发出去不用问") else "没允许",
            a.allowed) { on -> onChange(a.copy(allowed = on)) }
        if (a.allowed) {
            WarmDivider(16.dp)
            PlusRow(null, "这个账号的规矩", "次数上限、要不要先问我", onClick = { open = true })
        }
    }
    if (open) PersonaAccountDialog(a) { changed -> open = false; if (changed != null) onChange(changed) }
}

@Composable
private fun PersonaAccountDialog(a: PersonaCore.Account, done: (PersonaCore.Account?) -> Unit) {
    var cap by remember { mutableStateOf(a.dailyCap.toString()) }
    var confirm by remember { mutableStateOf(a.confirmSend) }
    MinesDialog(a.site, onDismiss = { done(null) }, confirm = "保存",
        onConfirm = { done(a.copy(dailyCap = cap.toIntOrNull()?.coerceIn(0, 999) ?: a.dailyCap, confirmSend = confirm)) }) {
        OutlinedTextField(cap, { cap = it.filter(Char::isDigit).take(3) }, singleLine = true, label = { Text("每天最多做几次") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "每天最多做几次" })
        SwitchRow("发出去、发布、删除、付款先问我", "关掉就不再逐条问你", confirm) { confirm = it }
    }
}

@Composable
private fun PersonaProfileDialog(p: PersonaCore.Profile, done: (PersonaCore.Profile?) -> Unit) {
    var name by remember { mutableStateOf(p.displayName) }
    var traits by remember { mutableStateOf(p.traits.joinToString("、")) }
    var from by remember { mutableStateOf(p.activeFromHour.toString()) }
    var to by remember { mutableStateOf(p.activeToHour.toString()) }
    MinesDialog("我是谁", onDismiss = { done(null) }, confirm = "保存", onConfirm = {
        done(p.copy(displayName = name.trim().take(24),
            traits = traits.split('、', ',', '，').map { it.trim() }.filter { it.isNotBlank() }.take(6),
            activeFromHour = from.toIntOrNull()?.coerceIn(0, 23) ?: p.activeFromHour,
            activeToHour = to.toIntOrNull()?.coerceIn(1, 24) ?: p.activeToHour))
    }) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("显示名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(traits, { traits = it }, label = { Text("说话习惯，用、隔开") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(from, { from = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("从几点") }, modifier = Modifier.weight(1f))
            OutlinedTextField(to, { to = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("到几点") }, modifier = Modifier.weight(1f))
        }
    }
}

/** 设置 → 实验功能 row */
@Composable
fun PersonaFlagRow() {
    val ctx = LocalContext.current
    Persona.init(ctx)
    val on by Persona.on.collectAsState()
    SwitchRow("伪人核心", "替你管社交账号和邮箱时，就是你本人", on) { v -> Persona.setFlag(ctx, v); PersonaNotify.refresh() }
}
