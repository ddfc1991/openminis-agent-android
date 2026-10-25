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
