package com.openminis.app.ui.browser

import com.openminis.app.ui.plus.MT

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserZoom

/** [Mines-zoom-34] 文字大小 for the current site (WebSettings.textZoom), remembered per site. */
@Composable
fun BrowserTextSizeDialog(webView: WebView, onDismiss: () -> Unit) {
    val ctx = webView.context
    val url = webView.url
    val host = BrowserZoom.hostOf(url).ifEmpty { "这个网页" }
    var pct by remember { mutableIntStateOf(webView.settings.textZoom.coerceIn(BrowserZoom.MIN, BrowserZoom.MAX)) }
    fun apply(v: Int) {
        pct = v.coerceIn(BrowserZoom.MIN, BrowserZoom.MAX)
        webView.settings.textZoom = pct
        BrowserZoom.setTextZoom(ctx, url, pct)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文字大小", fontSize = MT.PageTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$host · $pct%", fontSize = MT.Body)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { apply(pct - 10) }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "缩小文字" }) { Text("A−", fontSize = MT.PageTitle) }
                    Slider(value = pct.toFloat(), onValueChange = { apply((it / 10).toInt() * 10) },
                        valueRange = BrowserZoom.MIN.toFloat()..BrowserZoom.MAX.toFloat(), steps = 9,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { apply(pct + 10) }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "放大文字" }) { Text("A+", fontSize = MT.Display) }
                }
                Text("页面也可以用两指捏合放大，或双击放大。", fontSize = MT.Body)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好", fontSize = MT.Body) } },
        dismissButton = { TextButton(onClick = { apply(BrowserZoom.defaultTextZoom(ctx)) }) { Text("恢复默认", fontSize = MT.Body) } },
    )
}
