package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.MinesCrash

private fun copyText(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Mines 崩溃记录", com.openminis.app.crash.CrashRedactCore.redact(text)))   // [38.9.11.3]
        android.widget.Toast.makeText(context, "已复制崩溃记录", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** [Mines-crash-28] One prompt after a launch that follows a crash. */
@Composable
fun MinesCrashPrompt() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(runCatching { MinesCrash.unseen(context) }.getOrNull()) }
    val t = text ?: return
    // [Mines-38.9.12.1] the safe-start card on home already says what happened; no dialog on top of it
    if (com.openminis.app.plus.SafeStart.card.collectAsState().value != null) return
    val close = { MinesCrash.markSeen(context); text = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Mines 上次意外退出了") },
        text = {
            Column {
                Text("下面是出错记录，可以复制发给开发者。以后也能在 设置 → 关于 → 上次崩溃 里找到。",
                    style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(t.take(4000), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = { TextButton(onClick = { copyText(context, t); close() }) { Text("复制") } },
        dismissButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

/** [Mines-crash-28] 设置 → 关于 → 上次崩溃. */
@Composable
fun LastCrashSection() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(runCatching { MinesCrash.last(context) }.getOrNull()) }
    var open by remember { mutableStateOf(false) }
    PlusSection("上次崩溃", footer = if (text == null) "没有崩溃记录。" else null) {
        val t = text
        if (t != null) {
            Text(t.lineSequence().take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { open = true }) { Text("查看全部") }
                TextButton(onClick = { copyText(context, t) }) { Text("复制") }
                TextButton(onClick = { MinesCrash.clear(context); text = null }) { Text("清除") }
            }
        }
    }
    val t = text
    if (open && t != null) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("上次崩溃") },
        text = {
            SelectionContainer {
                Text(t, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = { copyText(context, t) }) { Text("复制") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
}
