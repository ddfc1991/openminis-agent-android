package com.openminis.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.ui.plus.MinesBackdrop
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.MinisTheme
import java.io.File

/**
 * [Mines-38.9.11 d] Built-in MT-style file + APK manager, as its own activity so it touches almost nothing shared:
 * dual-pane browser over 共享文件 / 对话工作区 / 沙箱系统 / 手机存储, copy / move / rename / delete / multi-select /
 * search, text editor (light highlighting), hex viewer + editor, ZIP pack / unpack, APK info (manifest, permissions,
 * components, v1/v2/v3 certificates), dex class list + smali-like view, resources.arsc tables, repack + re-sign of the
 * user's own / authorised apps (apksig), zipalign. Opens files and APKs from other apps (ACTION_VIEW / ACTION_SEND)
 * and hands files to the real MT 管理器 when it is installed.
 */
class FileManagerActivity : ComponentActivity() {
    private val incoming = mutableStateOf<File?>(null)
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = resolve(intent)
        setContent {
            FmTheme {
                FileManagerApp(incoming.value, resumeTick.intValue, onClose = { finish() }, onConsumed = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = resolve(intent)
    }

    override fun onResume() { super.onResume(); resumeTick.intValue++ }

    /** EXTRA_PATH (internal), ACTION_VIEW / ACTION_SEND (other apps) → a readable File (content:// is copied in). */
    private fun resolve(i: Intent?): File? {
        if (i == null) return null
        i.getStringExtra(EXTRA_PATH)?.let { return File(it) }
        val uri0: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        val uri: Uri = uri0 ?: return null
        return runCatching { importUri(this, uri) }.onFailure { Fm.toast(this, "打不开这个文件：" + it.message) }.getOrNull()
    }

    companion object {
        const val EXTRA_PATH = "com.openminis.app.files.PATH"

        /** Open the manager at [path] (a folder opens; a file opens its folder and the file itself). */
        fun open(c: Context, path: File? = null) {
            val i = Intent(c, FileManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (path != null) i.putExtra(EXTRA_PATH, path.absolutePath)
            c.startActivity(i)
        }

        /** file:// that we can read is used in place; anything else is copied to 共享文件/文件管理/收到的文件. */
        fun importUri(c: Context, uri: Uri): File {
            if (uri.scheme == "file") uri.path?.let { p -> File(p).takeIf { it.canRead() }?.let { return it } }
            var name = "收到的文件"
            runCatching {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
                }
            }
            name = name.replace('/', '_').ifBlank { "收到的文件" }
            val dir = File(FileRoots.work(c), "收到的文件").apply { mkdirs() }
            val out = FileOpsCore.uniqueTarget(dir, name)
            c.contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } } ?: throw FormatException("读取失败")
            return out
        }
    }
}

/** The app's own theme (light / dark / 随日出日落, font level) — same prefs MainActivity reads; never the system dark flag. */
@Composable
internal fun FmTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { getAppearancePrefs(ctx) }
    val mode = remember { prefs.getInt(KEY_THEME_MODE, 3) }
    val sunNight by com.openminis.app.plus.DayNight.night.collectAsState()
    LaunchedEffect(mode) { if (mode == 3) com.openminis.app.plus.DayNight.tick(ctx) }
    val sysNight = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (mode) { 1 -> false; 2 -> true; 3 -> sunNight; else -> sysNight }
    MinisTheme(darkTheme = dark, fontScale = fontScaleForLevel(prefs.getInt(KEY_FONT_APP_BASE, 0))) {
        Box(Modifier.fillMaxSize()) {
            MinesBackdrop(Modifier.fillMaxSize())
            content()
        }
    }
}

/** One screen of the manager's own back stack. */
internal sealed class FmPage {
    data object Browser : FmPage()
    data class Text(val file: File, val readOnlyText: String? = null, val title: String? = null) : FmPage()
    data class Hex(val file: File) : FmPage()
    data class Zip(val file: File) : FmPage()
    data class Apk(val file: File) : FmPage()
    data class Dex(val source: File, val entry: String?) : FmPage()
    data class Arsc(val source: File, val entry: String?) : FmPage()
}

@Composable
internal fun FileManagerApp(incoming: File?, resumeTick: Int, onClose: () -> Unit, onConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<FmPage>(FmPage.Browser) }
    val browser = remember { BrowserState(ctx) }
    var openRequest by remember { mutableStateOf<File?>(null) }

    fun push(p: FmPage) { stack.add(p) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) else onClose() }

    LaunchedEffect(incoming) {
        val f = incoming ?: return@LaunchedEffect
        onConsumed()
        if (f.isDirectory) browser.active.go(f) else { f.parentFile?.let { browser.active.go(it) }; openRequest = f }
    }
    LaunchedEffect(openRequest) {
        val f = openRequest ?: return@LaunchedEffect
        openRequest = null
        FmOpen.pageFor(f)?.let { push(it) }
    }

    BackHandler(enabled = true) {
        if (stack.size == 1 && browser.handleBack()) return@BackHandler
        pop()
    }

    when (val page = stack.last()) {
        FmPage.Browser -> FmBrowser(browser, resumeTick, onOpen = { p -> push(p) }, onClose = onClose)
        is FmPage.Text -> FmTextScreen(page, onBack = { pop() })
        is FmPage.Hex -> FmHexScreen(page.file, onBack = { pop() })
        is FmPage.Zip -> FmZipScreen(page.file, browser, onBack = { pop() }, onOpen = { push(it) })
        is FmPage.Apk -> FmApkScreen(page.file, browser, onBack = { pop() }, onOpen = { push(it) })
        is FmPage.Dex -> FmDexScreen(page.source, page.entry, onBack = { pop() }, onOpen = { push(it) })
        is FmPage.Arsc -> FmArscScreen(page.source, page.entry, onBack = { pop() })
    }
}

/** Which viewer a file opens in. */
internal object FmOpen {
    fun pageFor(f: File): FmPage? {
        if (!f.exists() || f.isDirectory) return null
        val ext = f.extension.lowercase()
        val head = runCatching { HexCore.window(f, 0, 512) }.getOrDefault(ByteArray(0))
        return when {
            ext == "apk" || (ext != "jar" && ext != "zip" && ZipCore.isZip(head) && runCatching { ZipCore.readEntry(f, "AndroidManifest.xml", 4 shl 20) != null }.getOrDefault(false)) -> FmPage.Apk(f)
            ext == "dex" || DexCore.isDex(head) -> FmPage.Dex(f, null)
            ext == "arsc" || ArscCore.isArsc(head) -> FmPage.Arsc(f, null)
            AxmlCore.isAxml(head) -> runCatching { FmPage.Text(f, AxmlCore.parse(f.readBytes()).xml, f.name + "（解码）") }.getOrElse { FmPage.Hex(f) }
            ZipCore.isZip(head) -> FmPage.Zip(f)
            ext in Fm.TEXT_EXT || (HexCore.isMostlyText(head) && !SyntaxCore.tooBigToEdit(f.length())) -> FmPage.Text(f)
            else -> FmPage.Hex(f)
        }
    }
}
