package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * [Mines-38.9.9] user 10:02: copying a reply was painful (long-press picked 2 characters, then two stacked floating menus
 * hid the text and 复制全文 sat in a submenu). Now: a one-tap 复制 in each reply's footer (whole reply, markdown stripped,
 * toast 「已复制」), and long-press opens ONE bottom sheet — 复制全文 / 复制为 Markdown / 选择文字 / 引用 / 朗读.
 */
object ReplyCopy {
    /** the reply's own markdown: its text blocks in order (legacy messages: content) */
    fun markdownOf(messages: List<ChatMessage>, itemMessageId: String): String {
        val base = itemMessageId.substringBefore('#')
        val ms = messages.filter { it.id == itemMessageId || it.id.substringBefore('#') == base }
        return ms.joinToString("\n\n") { m ->
            val t = m.toolBlocks.filter { it.kind == "text" && it.content.isNotBlank() }.joinToString("\n\n") { it.content.trim() }
            t.ifBlank { m.content.trim() }
        }.trim()
    }

    fun copyPlain(context: android.content.Context, markdown: String) {
        if (markdown.isBlank()) return
        MarkdownClipboard.copyPlain(context, markdown)
        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun copyMarkdown(context: android.content.Context, markdown: String) {
        if (markdown.isBlank()) return
        MarkdownClipboard.copyMarkdown(context, markdown)
        android.widget.Toast.makeText(context, "已复制 Markdown", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The small 「复制」 button in a reply's footer row. */
@Composable
internal fun ReplyCopyButton(markdown: () -> String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(
        modifier = modifier
            .clickable { ReplyCopy.copyPlain(ctx, markdown()) }
            .heightIn(min = 32.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = "复制整条回复", tint = ChatColors.tertiaryText, modifier = Modifier.size(15.dp))
        Text("复制", fontSize = 11.sp, color = ChatColors.tertiaryText, modifier = Modifier.padding(start = 3.dp))
    }
}

/** One sheet for a long-pressed message (replaces the floating bar + its submenu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyActionSheet(
    markdown: String,
    onDismiss: () -> Unit,
    onSelectText: () -> Unit,
    onQuote: ((String) -> Unit)?,
    onRead: ((String) -> Unit)?,
) {
    val ctx = LocalContext.current
    com.openminis.app.ui.glass.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            @Composable
            fun row(icon: ImageVector, label: String, action: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clickable { action() }.heightIn(min = 52.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 16.dp))
                }
            }
            row(Icons.Default.ContentCopy, "复制全文") { ReplyCopy.copyPlain(ctx, markdown); onDismiss() }
            row(Icons.Default.Code, "复制为 Markdown") { ReplyCopy.copyMarkdown(ctx, markdown); onDismiss() }
            row(Icons.Default.SelectAll, "选择文字") { onSelectText() }
            if (onQuote != null) row(Icons.Default.FormatQuote, "引用") { onQuote(markdown); onDismiss() }
            if (onRead != null) row(Icons.Default.RecordVoiceOver, "朗读") { onRead(markdown); onDismiss() }
            Spacer(Modifier.height(8.dp))
        }
    }
}
