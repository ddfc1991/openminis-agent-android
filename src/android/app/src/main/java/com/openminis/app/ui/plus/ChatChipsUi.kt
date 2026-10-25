package com.openminis.app.ui.plus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.QuoteReply
import com.openminis.app.plus.ValueChips

/** [Mines-38.9.5 i] chip glass: the same tokens as every other glass surface (GlassAuto fill, hairline border). */
@Composable
internal fun chipGlass(): Pair<Color, Color> {
    val m = mines()
    return minesGlassFill(strong = true) to m.glassBorder
}

/**
 * Small glass chips for the copyable values in one message (ValueChips.detect). Tap = copy exactly that value +
 * 「已复制」; long-press = open (links, API bases, mail, phone) or share. Secrets are shown masked and never copied.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ValueChipRow(text: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val chips = remember(text) { ValueChips.detect(text) }
    if (chips.isEmpty()) return
    val m = mines()
    val ctx = LocalContext.current
    val (fill, border) = chipGlass()
    FlowRow(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { c ->
            val shape = RoundedCornerShape(50)
            val desc = if (c.copyable) "${c.kind.label} ${c.value}，点按复制，长按打开或分享" else "${c.kind.label}已隐藏，不能复制"
            Row(
                Modifier.heightIn(min = 28.dp).widthIn(max = 260.dp).clip(shape).background(fill).border(0.75.dp, border, shape)
                    .combinedClickable(
                        onClick = {
                            if (c.copyable) copyValue(ctx, c.value) else toast(ctx, "密钥已隐藏，不能复制")
                        },
                        onLongClick = { if (c.copyable) openOrShare(ctx, c) else toast(ctx, "密钥已隐藏，不能复制") },
                    )
                    .semantics(mergeDescendants = true) { contentDescription = desc }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.kind.label, fontSize = MT.Meta, lineHeight = MT.SubLH, color = if (c.copyable) m.primaryText else m.ink2,
                    fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                Text(c.display, fontSize = MT.Meta, lineHeight = MT.SubLH, color = m.ink,
                    fontFamily = if (c.kind.mono) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun copyValue(ctx: Context, v: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("value", v))
    toast(ctx, "已复制")
}

private fun openOrShare(ctx: Context, c: ValueChips.ValueChip) {
    val v = c.value
    val view: Intent? = when (c.kind) {
        ValueChips.Kind.URL, ValueChips.Kind.API -> Intent(Intent.ACTION_VIEW, Uri.parse(v))
        ValueChips.Kind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$v"))
        ValueChips.Kind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$v"))
        else -> null
    }
    val share = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v), null)
    try {
        ctx.startActivity((view ?: share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try { ctx.startActivity(share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "没有能打开它的应用") }
    }
}

/** [Mines-38.9.14.1] 「回复」 bar above the input: 「回复 Mines：…」 one line, × to drop. Sent as a short reference. */
@Composable
fun ReplyDraftBlock(who: String, target: String, onClear: () -> Unit) {
    val m = mines()
    val (fill, border) = chipGlass()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(shape).background(fill).border(0.75.dp, border, shape)
            .semantics(mergeDescendants = true) { contentDescription = "回复 $who：" + QuoteReply.preview(target, 120) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Reply, null, tint = m.primary, modifier = Modifier.padding(start = 10.dp).size(16.dp))
        Text("回复 $who：" + QuoteReply.preview(target), fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp, top = 8.dp, bottom = 8.dp))
        Box(Modifier.size(36.dp).clickable(onClick = onClear).semantics { contentDescription = "取消回复" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, null, tint = m.ink2, modifier = Modifier.size(16.dp))
        }
    }
}

/** 「引用回复」 block above the input: collapsed to one line, tap to expand, × to drop. Sent as a Markdown quote. */
@Composable
fun QuoteDraftBlock(quote: String, onClear: () -> Unit) {
    val m = mines()
    var open by remember(quote) { mutableStateOf(false) }
    val (fill, border) = chipGlass()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(shape).background(fill).border(0.75.dp, border, shape)
            .clickable { open = !open }.animateContentSize()
            .semantics(mergeDescendants = true) { contentDescription = "引用：" + QuoteReply.preview(quote, 120) + "。点按展开" },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).width(3.dp).height(if (open) 48.dp else 18.dp).clip(RoundedCornerShape(2.dp)).background(m.primary))
        Icon(Icons.Filled.FormatQuote, null, tint = m.icon, modifier = Modifier.padding(start = 6.dp, top = 8.dp).size(16.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 7.dp, bottom = 7.dp)) {
            Text(if (open) quote.take(QuoteReply.MAX_QUOTE) else QuoteReply.preview(quote), fontSize = MT.Caption, lineHeight = MT.CaptionLH,
                color = m.ink2, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp).clickable(onClick = onClear).semantics { contentDescription = "取消引用" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, null, tint = m.ink2, modifier = Modifier.size(16.dp))
        }
    }
}
