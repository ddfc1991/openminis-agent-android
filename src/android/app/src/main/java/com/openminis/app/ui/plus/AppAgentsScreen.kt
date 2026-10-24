package com.openminis.app.ui.plus

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.AppAgents
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.1] Mines' own switch (mockup r389/mock 06): 50×30 dp pill, accent track when on, quiet porcelain track when
 * off, white thumb with a soft shadow, 「启用」 / 「已启用」 underneath (caption 12 sp) — not the Material default.
 */
@Composable
fun MinesSwitch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val m = mines()
    val track by animateColorAsState(if (on) m.primary else if (m.dark) Color(0x33FFFFFF) else Color(0x241C1915), label = "sw")
    val x by animateDpAsState(if (on) 22.dp else 2.dp, label = "swx")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(!on) }
        .semantics { role = Role.Switch; contentDescription = label; stateDescription = if (on) "已启用" else "没启用" }.padding(4.dp)) {
        Box(Modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(50)).background(track)) {
            Box(Modifier.offset(x = x, y = 2.dp).size(26.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
        Text(if (on) "已启用" else "启用", fontSize = MT.Caption, lineHeight = MT.CaptionLH, fontWeight = FontWeight.Medium, color = if (on) m.primaryText else m.ink2,
            modifier = Modifier.padding(top = 3.dp))
    }
}

private fun agentTint(id: String): Long? = when (id) {
    "nanomuse" -> 0xFF8E6CEF   // Muse 中继: purple (mockup), not the hashed red
    "web:muse" -> 0xFF6A4FB4
    else -> null
}

/** 「应用代理」: only the user's own services; + opens the 添加 catalog; real login; visible 测试一下. */
@Composable
fun AppAgentsScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    remember { AppAgents.init(ctx); 0 }
    var rev by remember { mutableIntStateOf(0) }
    val partners by com.openminis.app.plus.group.Partners.list.collectAsState()
    LaunchedEffect(Unit) { runCatching { com.openminis.app.plus.group.Partners.refresh(ctx) }; AppAgents.migrate(); rev++ }
    val log by AppAgents.log.collectAsState()
    val tests = remember { mutableStateMapOf<String, AppAgents.Test>() }
    var catalog by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val mine = remember(rev, partners) { AppAgents.mine() }
    MemohKeyDialog(onSaved = { rev++ })
    PlusPage("应用代理", onBack, actions = {
        androidx.compose.material3.IconButton(onClick = { catalog = true }) { MinesIcon(MinesIcons.named("plus")!!, "添加 AI 服务", tint = m.ink) }
    }) {
        val (n, saved) = remember(log, rev) { AppAgents.today() }
        val on = mine.count { AppAgents.enabled(it.id) }
        if (mine.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 6.dp).clip(RoundedCornerShape(22.dp)).background(minesGlassFill())
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(MinesIcons.named("sparkles")!!, null, tint = m.icon, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Column {
                Text("$on 个已启用 · 今天交出去 $n 件事", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                Text(if (saved > 0) "少花了约 ${if (saved >= 10000) "%.1f 万".format(saved / 10000f) else "$saved"} token（按回复长度估）" else "启用后，长任务会交给它们做，Mines 少花 token",
                    fontSize = MT.Sub, color = m.ink2)
            }
        }
        if (mine.isEmpty()) {
            // empty state: nothing assumed about what the user has
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MinesIcon(MinesIcons.named("bot")!!, null, tint = m.icon, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("还没有你的 AI 服务", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
                    Text("点右上角 + 添加你注册过的（Hark、Manus、扣子、Memoh…或你自己的接口）。登录后，长任务交给它们做，Mines 少花 token。",
                        fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    PillButton("添加 AI 服务") { catalog = true }
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 4.dp)) {
            Text("你的 AI 服务", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.ink2, modifier = Modifier.weight(1f))
            Text("${mine.size} 个", fontSize = MT.Meta, color = m.ink3)
        }
        androidx.compose.runtime.key(rev, partners.size) {
            mine.forEach { d ->
                val login = AppAgents.loginOf(d)
                val en = AppAgents.enabled(d.id)
                val t = tests[d.id]
                MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH, vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SiteLogo(d.host, d.name, 40.dp, tint = agentTint(d.id)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                                Spacer(Modifier.width(8.dp))
                                val (fg, bg) = when (login) {
                                    AppAgents.Login.OK -> toneColors(Tone.SUCCESS)
                                    AppAgents.Login.NEED -> toneColors(Tone.WARNING)
                                    else -> toneColors(Tone.NEUTRAL)
                                }
                                Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text(login.label, fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                                }
                            }
                            Text("擅长：" + d.good, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MinesSwitch(en, (if (en) "停用 " else "启用 ") + d.name) { v -> AppAgents.setEnabled(d.id, v); rev++ }
                    }
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(m.hairline))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MinesIcon(MinesIcons.named("gauge")!!, null, tint = m.ink3, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                        Text(AppAgents.quotaOf(d), fontSize = MT.Sub, color = m.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // [Mines-38.9.1] 去登录 (outlined) and 测试一下 (filled) look different; both always answer
                        if (login == AppAgents.Login.OK) ActionPill2(if (t?.running == true) "测试中…" else "测试一下", primary = true, enabled = t?.running != true) {
                            tests[d.id] = AppAgents.Test(true)
                            scope.launch { tests[d.id] = AppAgents.test(ctx, d) }
                        } else ActionPill2(if (d.id == "memoh") "填 key" else "去登录", primary = false) { openLogin(d, go) }
                    }
                    if (t != null) TestCard(t, onClose = { tests.remove(d.id) }, onLogin = { openLogin(d, go) })
                    if (d.id == "memoh" && login == AppAgents.Login.OK) Text("改地址 / key", fontSize = MT.Caption, color = m.ink3,
                        modifier = Modifier.padding(start = 16.dp).clickable { MemohKeyUi.open = true }.padding(vertical = 4.dp))
                    Text("移除", fontSize = MT.Caption, color = m.ink3, modifier = Modifier.align(Alignment.End).clickable { AppAgents.remove(d.id); rev++ }
                        .padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = "从我的服务里移除 ${d.name}" })
                }
            }
        }
        if (mine.isNotEmpty()) PlusSection("怎么分工") {
            PlusRow(MinesIcons.named("bot"), "快速模型先看一眼", "简单的话 Mines 自己答", chevron = false)
            PlusRow(MinesIcons.named("send"), "长任务、网页办事", "交给已启用、已登录的代理", chevron = false)
            PlusRow(MinesIcons.named("shield-check"), "付款、发消息、删东西", "从不交出去，先问你", chevron = false)
        }
        if (log.isNotEmpty()) PlusSection("最近交出去的") {
            log.take(5).forEach { e ->
                val d = AppAgents.all().firstOrNull { it.name == e.agent }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteLogo(d?.host ?: "", e.agent, 32.dp, tint = d?.let { agentTint(it.id) }); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.task, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.agent} · ${if (e.ok) "用了 ${e.ms / 1000} 秒" else "没成功"}", fontSize = MT.Sub, color = m.ink2)
                    }
                }
            }
        }
    }
    if (catalog) MinesSheet({ catalog = false }) {
        Text("添加 AI 服务", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        AppAgents.catalog().forEach { d ->
            Row(Modifier.fillMaxWidth().clickable { AppAgents.add(d.id); catalog = false; rev++ }.padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics { contentDescription = "添加 ${d.name}" }, verticalAlignment = Alignment.CenterVertically) {
                SiteLogo(d.host, d.name, 36.dp, tint = agentTint(d.id)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                    Text(d.good, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MinesIcon(MinesIcons.named("plus")!!, null, tint = m.primaryText, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth().clickable { catalog = false; custom = true }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(m.container), contentAlignment = Alignment.Center) {
                MinesIcon(MinesIcons.named("code")!!, null, tint = m.icon, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("自定义接口（网址 / API）", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                Text("任何 OpenAI 兼容的地址，比如你自己服务器上的网关", fontSize = MT.Sub, color = m.ink2)
            }
        }
    }
    if (custom) {
        var name by remember { mutableStateOf("") }; var base by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
        MinesDialog("自定义接口", { custom = false }, confirm = "添加", confirmEnabled = name.isNotBlank() && base.startsWith("http"), onConfirm = {
            val id = com.openminis.app.plus.group.GroupPrefs.addCustom(name.trim(), base.trim().trimEnd('/'), key.trim().ifBlank { null }, model.trim())
            AppAgents.add(id); custom = false
            scope.launch { runCatching { com.openminis.app.plus.group.Partners.refresh(ctx) }; rev++ }
        }) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名字") })
            OutlinedTextField(base, { base = it.trim() }, singleLine = true, label = { Text("地址（…/v1）") })
            OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("密钥（可不填）") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())  // [Mines-38.9.11 b] keys masked
            OutlinedTextField(model, { model = it.trim() }, singleLine = true, label = { Text("模型（可不填，自动从 /models 选）") })
        }
    }
}

/** 去登录: the service's real sign-in (in-app web page / 扣子 login / Memoh's own page / relay). */
private fun openLogin(d: AppAgents.Def, go: (String) -> Unit) {
    when {
        d.id.startsWith("web:") -> go(com.openminis.app.ui.group.GroupRoutes.webLogin(d.id))
        d.id == "coze" -> { PlusNav.openKind = "coze"; PlusNav.autoClaim = true; go(PlusRoutes.CONNECTIONS) }
        d.id == "memoh" -> MemohKeyUi.open = true   // [Mines-38.9.7] address + key (OpenAI-compatible), no web sign-in
        else -> { com.openminis.app.ui.group.PartnersNav.open = d.id; go(com.openminis.app.ui.group.GroupRoutes.PARTNERS) }
    }
}

@Composable
private fun TestCard(t: AppAgents.Test, onClose: () -> Unit, onLogin: () -> Unit) {
    val m = mines()
    val (fg, bg) = when { t.running -> toneColors(Tone.NEUTRAL); t.ok -> toneColors(Tone.SUCCESS); else -> toneColors(Tone.DANGER) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(14.dp)).background(bg)
        .padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = if (t.running) "正在测试" else if (t.ok) "测试成功 ${t.text}" else "测试失败 ${t.reason}" },
        verticalAlignment = Alignment.CenterVertically) {
        if (t.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = m.primary)
        else MinesIcon(MinesIcons.named(if (t.ok) "circle-check" else "info")!!, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(when { t.running -> "正在发「回复 OK」…"; t.ok -> "通了 · ${"%.1f".format(t.ms / 1000f)} 秒"; else -> "没通" }, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = fg)
            if (!t.running) Text(if (t.ok) "回复：" + t.text else t.reason, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (!t.running && !t.ok && (t.reason.contains("登录"))) WarmPrimaryPill("去登录", onClick = onLogin, height = 34.dp, fontSize = 13,
            modifier = Modifier.padding(start = 6.dp))
        if (!t.running) Text("关闭", fontSize = MT.Caption, color = m.ink2, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
    }
}

/** [Mines-38.9.7] Memoh address + key sheet (测试一下 = GET /v1/models). */
object MemohKeyUi { var open by androidx.compose.runtime.mutableStateOf(false) }

@Composable
fun MemohKeyDialog(onSaved: () -> Unit) {
    if (!MemohKeyUi.open) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = mines()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var addr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.openminis.app.plus.MemohKey.base()?.removeSuffix("/v1") ?: com.openminis.app.plus.MemohKey.suggestion(ctx).orEmpty()) }
    var key by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.openminis.app.plus.MemohKey.key().orEmpty()) }
    var line by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    MinesDialog("Memoh", onDismiss = { MemohKeyUi.open = false }, confirm = if (busy) "测试中…" else "测试并保存", onConfirm = {
        val v1 = com.openminis.app.plus.MemohKeyCore.v1Base(addr)
        if (v1 == null) { line = "地址不对"; return@MinesDialog }
        if (busy) return@MinesDialog
        busy = true; line = "正在连 $v1/models …"
        scope.launch {
            val r = com.openminis.app.plus.MemohKey.check(v1, key)
            busy = false; line = r.line
            if (r.ok) { com.openminis.app.plus.MemohKey.save(v1, key, r.model); MemohKeyUi.open = false; onSaved() }
        }
    }, dismiss = "取消") {
        androidx.compose.material3.OutlinedTextField(addr, { addr = it.trim(); line = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("地址", fontSize = MT.Caption) }, placeholder = { Text("http://你的服务器:8794", fontSize = MT.Sub) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body, color = m.ink))
        androidx.compose.material3.OutlinedTextField(key, { key = it.trim(); line = null }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text("key", fontSize = MT.Caption) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body, color = m.ink))
        line?.let { Text(it, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 6.dp)) }
    }
}
