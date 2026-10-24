package com.openminis.app.ui.plus

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.PartnerGateway
import com.openminis.app.plus.RouteStatusCore
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.5 i] 模型组 top card 「免登录 AI（Mines）」: one row per login-based route (扣子 / Muse 中继 / Memoh / 网页版) and
 * per self-hosted gateway in 模型组, each with its real state and one short reason (e.g. 「网关没有响应（502）…」).
 * Buttons: 刷新 (re-checks every route), 加到模型组 (when the provider row is missing), 重新登录 on rows that can renew
 * themselves (Muse 中继 reads the code from the user's linked mailbox). Small type (user: 不要大字).
 */
@Composable
fun PartnerRouteStatusCard(modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val rows by PartnerGateway.rows.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(PartnerGateway.providerAdded(c)) }
    LaunchedEffect(Unit) {
        if (rows.isEmpty()) { busy = true; runCatching { PartnerGateway.refreshStatus(c) }; busy = false }
        added = PartnerGateway.providerAdded(c)
    }
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("免登录 AI（Mines）", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                val ok = rows.count { it.st == RouteStatusCore.St.OK }
                Text(
                    if (rows.isEmpty()) (if (busy) "正在检查…" else "还没有登录类的 AI（扣子、Muse 中继、Memoh、网页版）")
                    else "能用 $ok / ${rows.size} · 本机 127.0.0.1:${PartnerGateway.PORT}",
                    fontSize = MT.Caption, color = cs.onSurfaceVariant,
                )
            }
            SmallAction(if (busy) "检查中" else "刷新", enabled = !busy) {
                busy = true
                scope.launch { runCatching { PartnerGateway.refreshStatus(c) }; added = PartnerGateway.providerAdded(c); busy = false }
            }
            if (!added && rows.any { !it.id.startsWith("inst:") }) {
                Spacer(Modifier.width(6.dp))
                SmallAction("加到模型组") {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { runCatching { PartnerGateway.ensureProvider(c) }.getOrElse { it.message ?: "没加上" } }
                        added = PartnerGateway.providerAdded(c)
                        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // [Mines-38.9.9] user: two 扣子 bots both named 「Mines-Test」 were listed with no difference → id tail
        val shown = remember(rows) { com.openminis.app.plus.FreeRouteCore.distinctNames(rows.map { it.id to it.name }) }
        rows.map { it.copy(name = shown[it.id] ?: it.name) }.forEach { r -> RouteRow(r, busy) { id ->
            busy = true
            scope.launch {
                val why = if (id == "nanomuse") PartnerGateway.relayAutoRelogin(c) else "请在「AI 伙伴」里点这一项重新登录"
                runCatching { PartnerGateway.refreshStatus(c) }
                busy = false
                Toast.makeText(c, why ?: "已重新登录", Toast.LENGTH_SHORT).show()
            }
        } }
    }
}

@Composable
private fun RouteRow(r: RouteStatusCore.Row, busy: Boolean, onRelogin: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dot = stColor(r.st)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "${r.name}，${r.st.label}" + if (r.reason.isNotBlank()) "，${r.reason}" else "" },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = MT.Body, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(r.via, fontSize = MT.Caption, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.st != RouteStatusCore.St.OK && r.reason.isNotBlank() && r.reason != r.st.label)
                Text(r.reason, fontSize = MT.Caption, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(r.st.label, fontSize = MT.Caption, color = dot, fontWeight = FontWeight.Medium)
        if (r.canAutoRelogin) {
            Spacer(Modifier.width(6.dp))
            SmallAction(if (r.st == RouteStatusCore.St.NEED_LOGIN) "登录" else "重新登录", enabled = !busy) { onRelogin(r.id) }   // [i11] never signed in = 登录
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, fontSize = MT.Caption, color = if (enabled) cs.primary else cs.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.primary.copy(alpha = 0.10f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun stColor(st: RouteStatusCore.St): Color = when (st) {
    RouteStatusCore.St.OK -> Color(0xFF2E9E5B)
    RouteStatusCore.St.RELOGIN, RouteStatusCore.St.NEED_LOGIN -> Color(0xFFC98A00)
    RouteStatusCore.St.DOWN -> Color(0xFFD64545)
    RouteStatusCore.St.WARN -> Color(0xFFE07B39)
    RouteStatusCore.St.UNCHECKED -> Color(0xFF8A8A8A)
}

/** [Mines-38.9.9] 一键登录 queue: the not-signed-in web routes are opened one by one; the page continues when the user comes back. */
object FreeAiLoginQueue {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    /** web routes that need a sign-in (only these have a login page Mines can open) */
    fun needLogin(rows: List<RouteStatusCore.Row>): List<String> =
        rows.filter { it.st != RouteStatusCore.St.OK && it.id.startsWith("web:") }.map { it.id }
}

/**
 * [Mines-38.9.9] user 09:24 / 09:28: the 免登录 AI block has its own page, opened from the home ☰ menu: 一键检查 re-checks every
 * session and shows 能用 / 过期 per item; 一键登录 walks through each not-signed-in web route (its login page opens, coming
 * back continues with the next one).
 */
@Composable
fun FreeAiScreen(onBack: () -> Unit, openLogin: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by PartnerGateway.rows.collectAsState()
    val queue by FreeAiLoginQueue.pending.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // coming back from a login page: re-check, drop what is signed in now, open the next one
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val q = FreeAiLoginQueue.pending.value
            if (q.isNotEmpty()) {
                busy = true
                val now = runCatching { PartnerGateway.refreshStatus(c) }.getOrDefault(rows)
                busy = false
                val still = FreeAiLoginQueue.needLogin(now).filter { it in q }
                val next = still.drop(if (still.firstOrNull() == q.first()) 1 else 0)
                FreeAiLoginQueue.pending.value = next
                line = if (next.isEmpty()) "一键登录走完了" else "下一个：${now.firstOrNull { it.id == next.first() }?.name ?: next.first()}"
                next.firstOrNull()?.let(openLogin)
            }
        }
    }
    PlusPage("免登录 AI", onBack, subtitle = "用你自己登录的网页账号、扣子、中继，不用 API Key") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(if (busy) "检查中…" else "一键检查", enabled = !busy) {
                busy = true
                scope.launch {
                    val r = runCatching { PartnerGateway.refreshStatus(c) }.getOrDefault(emptyList())
                    busy = false
                    val ok = r.count { it.st == RouteStatusCore.St.OK }
                    line = "能用 $ok 个 · 过期或没登录 ${r.size - ok} 个"
                }
            }
            val need = FreeAiLoginQueue.needLogin(rows)
            SmallAction(if (queue.isNotEmpty()) "登录中（还剩 ${queue.size}）" else "一键登录", enabled = !busy && need.isNotEmpty() && queue.isEmpty()) {
                FreeAiLoginQueue.pending.value = need
                line = "先登录：${rows.firstOrNull { it.id == need.first() }?.name ?: need.first()}"
                openLogin(need.first())
            }
            if (queue.isNotEmpty()) SmallAction("停止") { FreeAiLoginQueue.pending.value = emptyList(); line = "已停止一键登录" }
        }
        if (line.isNotBlank()) Text(line, fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        PartnerRouteStatusCard()
    }
}

/** [Mines-38.9.9] one line on 模型组 pointing to the 免登录 AI page. */
@Composable
fun FreeAiLink() {
    val rows by PartnerGateway.rows.collectAsState()
    val ok = rows.count { it.st == RouteStatusCore.St.OK }
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow)
        .clickable { PlusNav.go(PlusRoutes.FREE_AI) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("免登录 AI", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text(if (rows.isEmpty()) "打开" else "能用 $ok / ${rows.size} ›", fontSize = MT.Caption, color = cs.onSurfaceVariant)
    }
}
