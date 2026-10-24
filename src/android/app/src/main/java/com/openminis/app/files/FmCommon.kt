package com.openminis.app.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.mines
import com.openminis.app.ui.plus.minesGlassFill
import java.io.File

/** [Mines-38.9.11 d] Shared bits of the file manager UI: compact glass panels, rows, top bar, intents. */
internal object Fm {
    fun ic(name: String): ImageVector = MinesIcons.named(name) ?: MinesIcons.named("file-text")!!

    fun iconFor(f: File): String = when {
        f.isDirectory -> "folder"
        else -> when (f.extension.lowercase()) {
            "apk", "apks", "xapk" -> "smartphone"
            "zip", "jar", "aar", "7z", "tar", "gz", "tgz" -> "archive"
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image"
            "dex", "smali", "kt", "java", "js", "ts", "py", "sh", "c", "cpp", "h", "xml", "json", "html" -> "code"
            "jks", "keystore", "p12", "pfx", "bks" -> "key-round"
            else -> "file-text"
        }
    }

    val TEXT_EXT = setOf("txt", "md", "markdown", "log", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "sh",
        "bash", "c", "cpp", "h", "hpp", "go", "rs", "swift", "gradle", "properties", "yml", "yaml", "toml", "ini", "conf", "cfg", "csv",
        "tsv", "smali", "env", "sql", "gitignore", "pro", "svg", "mjs", "cjs", "rc", "profile")

    fun mime(f: File): String = when (f.extension.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    }

    fun uri(c: Context, f: File) = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

    fun copyText(c: Context, label: String, text: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        toast(c, "已复制$label")
    }

    /** ACTION_VIEW through the FileProvider; [pkg] pins one app (MT 管理器). */
    fun openExternal(c: Context, f: File, pkg: String? = null, mime: String = mime(f)): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        c.startActivity(if (pkg != null) i else Intent.createChooser(i, "打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        toast(c, "打不开：" + (e.message ?: e.javaClass.simpleName)); false
    }

    fun openInMt(c: Context, f: File) {
        val p = FileRoots.mtPackage(c)
        if (p == null) { toast(c, "没有装 MT 管理器，已改用系统的「打开方式」"); openExternal(c, f); return }
        openExternal(c, f, p, "*/*")
    }

    fun share(c: Context, f: File) = runCatching {
        val i = Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri(c, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(c, "分享失败：" + it.message) }

    fun install(c: Context, apk: File) = openExternal(c, apk, mime = "application/vnd.android.package-archive")

    fun time(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
}

/** Compact glass panel (the app's frosted card, 16 dp radius). */
@Composable
internal fun FmPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(minesGlassFill()).border(1.dp, m.glassBorder, shape), content = content)
}

/** Top bar: back · title (+ one-line sub) · actions. 52 dp, 17 sp title — compact on purpose (user: 不要大字). */
@Composable
internal fun FmTopBar(title: String, sub: String? = null, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    val m = mines()
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) FmIconButton("arrow-left", "返回", onBack) else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, fontSize = 12.sp, lineHeight = 15.sp, color = m.ink2, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        actions()
    }
}

@Composable
internal fun FmIconButton(icon: String, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color? = null) {
    val m = mines()
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        MinesIcon(Fm.ic(icon), null, tint = (tint ?: m.icon).copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(20.dp))
    }
}

/** Small pill action (toolbar / bottom bar): icon over a 11 sp label. */
@Composable
internal fun FmAction(icon: String, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val m = mines()
    val fg = if (danger) Color(0xFFD9534F) else m.ink
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
        .semantics { role = Role.Button }, horizontalAlignment = Alignment.CenterHorizontally) {
        MinesIcon(Fm.ic(icon), null, tint = (if (danger) fg else m.icon).copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = fg.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}

/** Key · value row (info panels); value is monospace when [mono]; tap copies it. */
@Composable
internal fun FmKv(key: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    val m = mines()
    Row(Modifier.fillMaxWidth().then(if (onCopy != null) Modifier.clickable(onClick = onCopy) else Modifier).padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(key, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.width(92.dp), maxLines = 2)
        Text(value, fontSize = if (mono) 12.sp else MT.Sub, lineHeight = 17.sp, color = m.ink, fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun FmSection(title: String) {
    Text(title, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = mines().ink2, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp))
}

/** Chip (tabs, filters). */
@Composable
internal fun FmChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    Text(text, fontSize = 13.sp, lineHeight = 16.sp, color = if (selected) Color.White else m.ink, maxLines = 1,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(shape).background(if (selected) m.primary else minesGlassFill()).border(1.dp, if (selected) m.primary else m.glassBorder, shape)
            .clickable(onClick = onClick).semantics { role = Role.Tab }.padding(horizontal = 12.dp, vertical = 6.dp))
}
