package com.openminis.app.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.AccountsCore
import com.openminis.app.plus.Ledger
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.11.3] 「已用 台账：邮箱」 — a small glass chip at the top after a 台账 account was filled on a sign-in page.
 * Never blocks: no scrim, hides after 6 s (stays while 换一个 is open). 换一个 lists the site's other 台账 accounts;
 * 设为默认 makes this one the site's default. Normal type size (MT.Body / MT.Meta).
 */
@Composable
fun LedgerChipHost() {
    val ch by Ledger.chip.collectAsState()
    val cur = ch ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = mines()
    var open by remember(cur.at) { mutableStateOf(false) }
    var note by remember(cur.at) { mutableStateOf<String?>(null) }
    LaunchedEffect(cur.at, open) { if (!open) { kotlinx.coroutines.delay(6000); Ledger.clearChip(cur) } }
    Box(Modifier.fillMaxSize().padding(top = 56.dp), contentAlignment = Alignment.TopCenter) {
        GlassToast(Modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note ?: ("已用 台账：" + AccountsCore.maskPhone(cur.account)), fontSize = MT.Body, color = m.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (cur.others.isNotEmpty()) ChipAction(if (open) "收起" else "换一个") { open = !open }
                if (!cur.isDefault && cur.others.isNotEmpty()) ChipAction("设为默认") {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { Ledger.setDefault(ctx, cur.host, cur.account) } }
                    note = "已设为 ${cur.site} 的默认账号"
                }
            }
            if (open) Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                cur.others.take(6).forEach { a ->
                    Text(AccountsCore.maskPhone(a), fontSize = MT.Body, color = m.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                            open = false
                            scope.launch { runCatching { Ledger.switchTo(ctx, cur, a) } }
                        }.padding(horizontal = 16.dp, vertical = 11.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipAction(text: String, onClick: () -> Unit) {
    val m = mines()
    Text(text, fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1, softWrap = false,
        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp))
}

/** [Mines-38.9.11.3] one card on the launch after a crash / ANR: 「复制崩溃日志」 (redacted) · 关闭. Never blocks the screen. */
@Composable
fun CrashCardHost() {
    val o by com.openminis.app.crash.CrashCard.offer.collectAsState()
    val cur = o ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = mines()
    var done by remember(cur) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
        GlassToast(Modifier.widthIn(max = 460.dp).padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (done) "已复制（密钥、密码已隐去）" else cur.title, fontSize = MT.Body, color = m.ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!done) ChipAction("复制崩溃日志") {
                    scope.launch {
                        val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.crash.CrashCard.text(cur) }
                        runCatching {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Mines 崩溃日志", t))
                        }
                        done = true
                        kotlinx.coroutines.delay(2500); com.openminis.app.crash.CrashCard.dismiss(ctx, cur)
                    }
                }
                ChipAction("关闭") { com.openminis.app.crash.CrashCard.dismiss(ctx, cur) }
            }
        }
    }
}
