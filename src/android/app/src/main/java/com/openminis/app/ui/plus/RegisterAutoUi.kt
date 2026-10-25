package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.plus.Inbox3912Core
import com.openminis.app.plus.RegisterAuto
import com.openminis.app.plus.RegisterAutoCore
import com.openminis.app.plus.RegisterAutoCore.Platform
import com.openminis.app.plus.RegisterBrowser
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 register_auto] 一键注册 UI: the pick sheet (preset platforms + 自定义网址, several at once, one tap to start),
 * the folded task card on 注册台账 (「一键注册 · 进行中 1/2」 → 「Zeabur 注册 · 完成 ›」), and the takeover screen where the
 * user does the one step Mines stops for (captcha / phone / payment) on the same page. Normal type sizes (MT).
 */
private const val ORANGE = 0xFFC0633F

object RegisterPicks {
    private const val PREFS = "mines_register_auto"
    /** presets + the user's own 自定义网址 (kept for next time) */
    fun all(c: android.content.Context): List<Platform> = Inbox3912Core.PLATFORMS.map { (n, u) -> Platform(n, u) } + custom(c)
    fun custom(c: android.content.Context): List<Platform> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("custom", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Platform(o.getString("n"), o.getString("u"), true) } }
    }.getOrDefault(emptyList())
    fun addCustom(c: android.content.Context, p: Platform) {
        val l = (custom(c).filterNot { it.host == p.host } + p).takeLast(12)
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("custom", JSONArray().apply { l.forEach { put(JSONObject().put("n", it.name).put("u", it.url)) } }.toString()).apply()
    }
}

/** 开始注册: tick platforms (or add a 自定义网址), then one tap runs everything */
@Composable
fun RegisterPickSheet(onDismiss: () -> Unit, onStarted: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val list = remember { mutableStateListOf<Platform>().apply { addAll(RegisterPicks.all(ctx)) } }
    val ticked = remember { mutableStateListOf<String>() }
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun add() {
        val (p, why) = RegisterAutoCore.custom(url, list)
        if (p == null) { err = why; return }
        RegisterPicks.addCustom(ctx, p); list.add(p); ticked.add(p.host); url = ""; err = null
    }
    MinesSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("选择要注册的平台", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text("每个平台单独建一个临时邮箱，一个接一个注册；人机验证、手机号或付款时停下来问你。", fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            list.forEach { p ->
                val on = p.host in ticked
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (on) ticked.remove(p.host) else ticked.add(p.host) }
                    .heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { role = Role.Checkbox; selected = on; contentDescription = "${p.name}${if (p.custom) " 自定义" else ""}，${if (on) "已选" else "未选"}" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Color(ORANGE) else Color.Transparent)
                        .border(1.5.dp, if (on) Color(ORANGE) else m.ink3, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (on) Text("✓", fontSize = MT.Meta, color = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.name, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.host + if (p.custom) " · 自定义" else "", fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val fc = OutlinedTextFieldDefaults.colors(focusedBorderColor = m.primary, unfocusedBorderColor = m.glassBorder,
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = m.primary,
                focusedLabelColor = m.primaryText, unfocusedLabelColor = m.ink2, focusedTextColor = m.ink, unfocusedTextColor = m.ink)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it; err = null }, label = { Text("自定义网址", fontSize = MT.Sub) }, placeholder = { Text("zeabur.com 或完整链接", fontSize = MT.Sub) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    shape = RoundedCornerShape(14.dp), colors = fc, modifier = Modifier.weight(1f).semantics { contentDescription = "自定义网址" })
                Text("添加", fontSize = MT.Body, color = Color(ORANGE), modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50))
                    .background(Color(0x22C0633F)).clickable { add() }.padding(horizontal = 14.dp, vertical = 8.dp).semantics { contentDescription = "添加自定义网址" })
            }
            err?.let { Text(it, fontSize = MT.Meta, color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger, modifier = Modifier.padding(top = 4.dp)) }
            val picked = RegisterAutoCore.picked(list, ticked.toSet())
            val busy = RegisterAuto.running()
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                .background(if (picked.isNotEmpty() && !busy) Color(0xFFD06A3A) else m.ink3.copy(alpha = 0.3f))
                .clickable(enabled = picked.isNotEmpty() && !busy) { if (RegisterAuto.start(ctx, picked)) onStarted() }
                .padding(vertical = 12.dp).semantics { contentDescription = "开始注册" }, contentAlignment = Alignment.Center) {
                Text(when { busy -> "上一轮还在注册"; picked.isEmpty() -> "先选平台"; else -> "开始注册（${picked.size} 个）" },
                    color = Color.White, fontSize = MT.Button, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** the one folded task row on 注册台账; unfolds to one line per platform */
@Composable
fun RegisterRunCard(go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    remember { RegisterAuto.init(ctx); 0 }
    val r by RegisterAuto.run.collectAsState()
    val run = r ?: return
    val waiting = run.items.any { it.st == RegisterAutoCore.St.WAITING }
    var open by remember(run.id) { mutableStateOf(false) }
    val tone = when { waiting -> Color(0xFFD08A1E); !run.finished -> Color(0xFF3A7BD5); run.items.all { it.st == RegisterAutoCore.St.DONE } -> Color(0xFF2E9E5B); else -> Color(0xFFB0574F) }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = RegisterAutoCore.runTitle(run) + "。" + RegisterAutoCore.runLine(run) + if (open) "。点按收起" else "。点按展开" },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(RegisterAutoCore.runTitle(run), fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(RegisterAutoCore.runLine(run), fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (open) "收起" else "›", fontSize = MT.Meta, color = m.ink2)
            }
            if (open || waiting) run.items.forEach { it0 ->
                val w = it0.st == RegisterAutoCore.St.WAITING
                WarmDivider(16.dp)
                Row(Modifier.fillMaxWidth().clickable(enabled = w) { go(RegisterTakeover.ROUTE) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = RegisterAutoCore.itemTitle(it0) + "。" + it0.line },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 20.dp)) {
                        Text(RegisterAutoCore.itemTitle(it0) + " ›", fontSize = MT.Sub, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when (it0.st) {
                            RegisterAutoCore.St.DONE -> Inbox3912Core.maskMail(it0.email)
                            else -> it0.line.ifBlank { it0.p.host }
                        }
                        Text(sub, fontSize = MT.Meta, color = if (w) Color(0xFFD08A1E) else m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    StatusPill(when (it0.st) { RegisterAutoCore.St.DONE -> "已验证"; RegisterAutoCore.St.PENDING -> "未完成"; else -> it0.st.zh },
                        when (it0.st) { RegisterAutoCore.St.DONE -> true; RegisterAutoCore.St.PENDING -> false; else -> null })
                }
            }
        }
    }
}

/** the step Mines stops for, done by the user on the same page (form already filled), then 继续 / 跳过 */
object RegisterTakeover {
    const val ROUTE = "plus_register_takeover"

    @Composable
    fun Screen(onBack: () -> Unit) {
        val m = mines()
        val ctx = LocalContext.current
        val r by RegisterAuto.run.collectAsState()
        val item = r?.items?.firstOrNull { it.st == RegisterAutoCore.St.WAITING }
        val web = remember { RegisterBrowser.attachTo(ctx) }
        DisposableEffect(Unit) { onDispose { RegisterBrowser.detach() } }
        Column(Modifier.fillMaxSize().background(m.bg).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ 返回", fontSize = MT.Body, color = m.ink, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(8.dp))
                Text(item?.let { "${it.p.name} 注册 · 等你" } ?: "一键注册", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            Text(item?.line ?: "现在没有要你处理的步骤。", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                if (web != null && item != null) AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
            }
            if (item != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, m.glassBorder, RoundedCornerShape(12.dp))
                    .clickable { RegisterAuto.answer(false); onBack() }.padding(vertical = 12.dp).semantics { contentDescription = "跳过这个平台" }, contentAlignment = Alignment.Center) {
                    Text("跳过这个平台", fontSize = MT.Button, color = m.ink)
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFD06A3A))
                    .clickable { RegisterAuto.answer(true); onBack() }.padding(vertical = 12.dp).semantics { contentDescription = "好了，继续" }, contentAlignment = Alignment.Center) {
                    Text("好了，继续", fontSize = MT.Button, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

// ───────────── [Mines-38.9.15 register_auto] 临时邮箱台账 · 自动延时 / 探针保活 · 整理 · 最近删除 ─────────────

private fun hmd(t: Long): String = if (t <= 0) "—" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))

/** boxes shown under 临时邮箱: every box with a record, plus 台账 accounts on the same temp domains (never gmail / 163 …) */
internal fun tempBoxesOf(recs: List<com.openminis.app.plus.RegLedgerCore.Rec>, rows: List<Inbox3912Core.RegRow>): List<String> {
    val known = recs.map { it.box.lowercase() }.toSet() + com.openminis.app.plus.RegLedger.knownBoxes()
    val doms = known.map { it.substringAfter('@') }.toSet()
    return (recs.sortedByDescending { it.updatedAt }.map { it.box.lowercase() } +
        rows.map { it.account.lowercase() }.filter { com.openminis.app.plus.RegLedgerCore.isTemp(it, doms, known) }).distinct()
}

/** the two small switches + 「下次续期 · 上次探活」 under a row (default off) */
@Composable
fun KaLine(key: String, label: String) {
    val m = mines()
    val ctx = LocalContext.current
    val all by com.openminis.app.plus.RegLedger.ka.collectAsState()
    val k = all[key] ?: com.openminis.app.plus.RegLedgerCore.Ka(key)
    Column(Modifier.fillMaxWidth().padding(start = if (key.startsWith("box|")) 16.dp else 62.dp, end = 16.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KaChip("自动延时", k.extend, "自动延时 $label") { com.openminis.app.plus.RegLedger.setKa(ctx, k.copy(extend = !k.extend)) }
            KaChip("探针保活" + if (k.probe) " ${k.everyH}h" else "", k.probe, "探针保活 $label") { com.openminis.app.plus.RegLedger.setKa(ctx, k.copy(probe = !k.probe)) }
            if (k.probe) Text("改间隔", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.clip(RoundedCornerShape(50))
                .clickable { com.openminis.app.plus.RegLedger.setKa(ctx, k.copy(everyH = com.openminis.app.plus.RegLedgerCore.cycleHours(k.everyH))) }
                .padding(horizontal = 6.dp, vertical = 3.dp).semantics { contentDescription = "探活间隔 ${k.everyH} 小时，点按切换 $label" })
        }
        val line = com.openminis.app.plus.RegLedgerCore.statusLine(k, ::hmd)
        if (line.isNotEmpty()) Text(line, fontSize = MT.Meta, color = if (!k.probeOk) Color(0xFFB0574F) else m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun KaChip(text: String, on: Boolean, desc: String, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0x22C0633F) else Color.Transparent).border(1.dp, if (on) Color(ORANGE) else m.glassBorder, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp)
        .semantics(mergeDescendants = true) { role = Role.Switch; selected = on; contentDescription = desc + if (on) "，已开" else "，已关" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Color(ORANGE) else m.ink3))
        Text(text, fontSize = MT.Meta, color = if (on) Color(ORANGE) else m.ink2, modifier = Modifier.padding(start = 5.dp))
    }
}

/** one temporary box on 注册台账: masked address + 「临时」, its platforms with status, its own switches; tap → the box */
@Composable
fun TempBoxCard(box: String, recs: List<com.openminis.app.plus.RegLedgerCore.Rec>, rows: List<Inbox3912Core.RegRow>, go: (String) -> Unit) {
    val m = mines()
    val hosts = (recs.map { it.host } + rows.map { it.host }).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), onClick = { go(RegisterBox.route(box)) }) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "临时邮箱 ${Inbox3912Core.maskMail(box)}，${hosts.size} 个平台。点按查看" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(Inbox3912Core.maskMail(box), fontSize = MT.Sub, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = m.ink, modifier = Modifier.weight(1f), maxLines = 1)
                StatusPill("临时", null)
            }
            hosts.take(4).forEach { h ->
                val r = recs.firstOrNull { it.host.equals(h, true) }
                val row = rows.firstOrNull { it.host.equals(h, true) }
                val st = r?.st?.pill ?: row?.st?.zh ?: "已保存"
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r?.platform?.ifBlank { null } ?: h, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(h.takeIf { r?.platform?.isNotBlank() == true }, r?.st?.takeIf { !it.ok && it != com.openminis.app.plus.RegLedgerCore.RSt.RUNNING }?.zh?.takeIf { it != "未完成" },
                            r?.why?.takeIf { it.isNotBlank() }, r?.let { hmd(it.createdAt.takeIf { t -> t > 0 } ?: it.updatedAt) }).joinToString(" · "),
                            fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusPill(st, when (st) { "已验证" -> true; "未完成" -> false; else -> null })
                }
                KaLine(com.openminis.app.plus.RegLedgerCore.acctKey(h, box), h)   // this account: 自动延时 / 探针保活
            }
            KaLine(com.openminis.app.plus.RegLedgerCore.boxKey(box), "邮箱 " + Inbox3912Core.maskMail(box))
        }
    }
}

/** 「整理 N 个 · 补全 N · 删除 N ›」 once after a 整理 (tap → 最近删除) */
@Composable
fun TidySummaryLine(go: (String) -> Unit) {
    val m = mines()
    val s by com.openminis.app.plus.RegLedger.lastTidy.collectAsState()
    val sum = s ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14C0633F))
        .clickable { go(RegisterBox.TRASH) }.padding(horizontal = 14.dp, vertical = 9.dp).semantics(mergeDescendants = true) { contentDescription = sum.line + " 点按查看最近删除" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(sum.line, fontSize = MT.Sub, color = m.ink, modifier = Modifier.weight(1f), maxLines = 1)
        Text("✕", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.clip(CircleShape).clickable { com.openminis.app.plus.RegLedger.lastTidy.value = null }.padding(6.dp)
            .semantics { contentDescription = "关闭整理小结" })
    }
}

object RegisterBox {
    const val ROUTE = "plus_register_box/{a}"
    const val TRASH = "plus_register_trash"
    fun route(a: String) = "plus_register_box/" + android.net.Uri.encode(a)

    /** one temporary box: what it registered (platform, URL, status, time) + 重新收码 */
    @Composable
    fun Screen(box: String, onBack: () -> Unit) {
        val m = mines()
        val ctx = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        remember { com.openminis.app.plus.RegLedger.init(ctx); 0 }
        val all by com.openminis.app.plus.RegLedger.recs.collectAsState()
        val recs = all.filter { it.box.equals(box, true) }
        var codes by remember { mutableStateOf<List<com.openminis.app.plus.RegLedger.Code>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        val hasPass = remember { com.openminis.app.plus.RegLedger.boxPass(box) != null }
        PlusPage("临时邮箱", onBack, subtitle = box) {
            WarmGroupLabel("注册了 ${recs.size} 个平台")
            if (recs.isEmpty()) InfoLine("这个邮箱还没有注册记录。")
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    recs.forEachIndexed { i, r ->
                        if (i > 0) WarmDivider(16.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "${r.platform.ifBlank { r.host }}，${r.st.zh}" }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.platform.ifBlank { r.host }, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.url.ifBlank { r.host }, fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(r.st.zh, r.why, "注册于 " + hmd(r.createdAt.takeIf { it > 0 } ?: r.updatedAt), "密码在台账").filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                    fontSize = MT.Meta, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(r.st.pill, when { r.st.ok -> true; r.st == com.openminis.app.plus.RegLedgerCore.RSt.RUNNING -> null; else -> false })
                        }
                    }
                }
            }
            WarmGroupLabel("收件")
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(if (hasPass) "Mail.tm · 邮箱密码在钥匙串，可随时再收信" else "Mail.tm · 没有这个邮箱的密码，收不了信", fontSize = MT.Meta, color = m.ink2)
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(if (busy) m.ink3.copy(alpha = 0.3f) else Color(0xFFD06A3A))
                        .clickable(enabled = !busy) {
                            busy = true; err = null
                            scope.launch {
                                val r = com.openminis.app.plus.RegLedger.codes(box, recs.firstOrNull()?.host.orEmpty())
                                if (r == null) err = "邮箱登录不上（可能已失效）" else codes = r
                                busy = false
                            }
                        }.padding(vertical = 11.dp).semantics { contentDescription = "重新收码" }, contentAlignment = Alignment.Center) {
                        Text(if (busy) "收信中…" else "重新收码", color = Color.White, fontSize = MT.Button, fontWeight = FontWeight.Medium)
                    }
                    err?.let { Text(it, fontSize = MT.Meta, color = Color(0xFFB0574F), modifier = Modifier.padding(top = 6.dp)) }
                    codes?.let { l ->
                        if (l.isEmpty()) Text("没有验证码或验证链接。", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 8.dp))
                        l.take(5).forEach { c0 ->
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(if (c0.isLink) "验证链接" else Inbox3912Core.spaced(c0.text), fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink,
                                    modifier = Modifier.semantics { contentDescription = if (c0.isLink) "验证链接" else "验证码 ${c0.text}" })
                                Text(c0.subject.take(40) + " · " + hmd(c0.at), fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            KaLine(com.openminis.app.plus.RegLedgerCore.boxKey(box), Inbox3912Core.maskMail(box))
        }
    }

    /** 最近删除: what 整理 removed, kept 7 days, 恢复 puts the row (and its password) back */
    @Composable
    fun Trash(onBack: () -> Unit) {
        val m = mines()
        val ctx = LocalContext.current
        remember { com.openminis.app.plus.RegLedger.init(ctx); 0 }
        val l by com.openminis.app.plus.RegLedger.trash.collectAsState()
        val now = System.currentTimeMillis()
        PlusPage("最近删除", onBack, subtitle = "整理删掉的临时邮箱账号，保留 7 天") {
            if (l.isEmpty()) InfoLine("没有删除的账号。")
            if (l.isNotEmpty()) MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    l.forEachIndexed { i, t ->
                        if (i > 0) WarmDivider(16.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.rec.platform.ifBlank { t.host.ifBlank { "未知平台" } }, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Inbox3912Core.maskMail(t.account) + " · 还剩 ${com.openminis.app.plus.RegLedgerCore.daysLeft(t, now)} 天", fontSize = MT.Meta, color = m.ink2, maxLines = 1)
                            }
                            Text("恢复", fontSize = MT.Sub, color = Color(ORANGE), modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22C0633F))
                                .clickable { com.openminis.app.plus.RegLedger.restore(ctx, t.id) }.padding(horizontal = 12.dp, vertical = 5.dp)
                                .semantics { contentDescription = "恢复 ${t.host}" })
                        }
                    }
                }
            }
        }
    }
}
