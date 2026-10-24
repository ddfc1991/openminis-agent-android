package com.openminis.app.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Mines-38.9.6 i13] Extends GlassWrapperScanTest: (1) a raw Compose Dialog / Popup may only appear in the files listed here,
 * each with its reason; a new one must use the shared glass (ui.glass.* / MinesDialog / MinesSheet / GlassSurface) or be added
 * here on purpose. (2) The input bars (home + chat composer + slash/@ panel) draw the shared input glass with its rim and floor.
 */
class GlassSurfaceScanTest {
    private fun root(): File = listOf("src/main/java/com/openminis/app", "app/src/main/java/com/openminis/app").map { File(it) }.first { it.isDirectory }
    private fun code(f: File): String = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    /**
     * [Mines-38.9.6 i14] user 「这种窗口的一定要全部解决不要穿透了」: the allow-list is split by HOW each raw window is glass.
     * MODAL = a popup / dialog that blurs the page behind (GlassWindowEffect) and draws the shared GlassSurface.
     * TOAST = non-modal (nothing behind is blurred): the shared GlassToast (dense 暖瓷). Both are CHECKED, not trusted.
     * OPAQUE = full-screen pages that cover everything with an opaque colour (not overlays). SYSTEM = text-selection chrome.
     */
    private val modal = mapOf(
        "ui/components/MinisAlertDialog.kt" to "the shared glass dialog itself",
        "ui/components/MinisMenu.kt" to "the shared glass menu itself",
        "ui/chat/SendReturnModeMenu.kt" to "send-mode menu",
        "ui/chat/PastedTextBuffer.kt" to "pasted-text preview",
        "ui/plus/TaskProgressUi.kt" to "progress card",
    )
    private val toast = mapOf(
        "ui/plus/KeychainScreen.kt" to "「已记下 X 账号」",
        "ui/plus/MinesJobsHost.kt" to "job banner + result toast",
    )
    private val opaque = mapOf(
        "ui/chat/ChatAssistantMessageUI.kt" to "full-screen 深度思考 reader, opaque page colour",
        "ui/chat/ChatUserMessageUI.kt" to "full-screen image viewer, black",
        "ui/components/FullscreenImageViewer.kt" to "full-screen image viewer, black",
        "ui/components/ImageGalleryViewer.kt" to "full-screen gallery, black",
        "ui/media/InlineMediaPlayer.kt" to "full-screen video",
        "ui/preview/WebPreviewFullscreenScreen.kt" to "full-screen web preview",
        "ui/plus/AvatarPicker.kt" to "full-screen editor, opaque page colour",
        "ui/plus/KeyAgentScreen.kt" to "full-screen web page, opaque porcelain",
        "ui/plus/VoiceprintSection.kt" to "full-screen recorder, opaque page colour",
    )
    private val system = mapOf(
        "ui/chat/ChatScreen.kt" to "slash / @ panels (glassInput, docked to the composer) + attachment pill",
        "ui/chat/MinisMarkdownTextToolbar.kt" to "text-selection toolbar",
        "ui/chat/MinisTextKitGesture.kt" to "selection handles / magnifier",
    )
    private val allowed = modal + toast + opaque + system

    @Test fun allowedWindowsReallyUseTheSharedGlass() {
        val r = root(); val bad = mutableListOf<String>()
        modal.keys.forEach { rel -> val c = code(File(r, rel))
            if (!c.contains("GlassWindowEffect()")) bad += "$rel: modal overlay without GlassWindowEffect() (page behind not blurred)"
            if (!c.contains("GlassSurface(")) bad += "$rel: modal overlay without GlassSurface(" }
        toast.keys.forEach { rel -> val c = code(File(r, rel))
            if (!c.contains("GlassToast(")) bad += "$rel: toast without GlassToast(" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** View dialogs, ExposedDropdownMenu, DatePickerDialog and snackbars: only through the glass versions. */
    @Test fun noDefaultMaterialOrViewOverlays() {
        val r = root(); val bad = mutableListOf<String>()
        val member = Regex("""(?<![A-Za-z_.])ExposedDropdownMenu\s*\(""")
        r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(r).path.replace('\\', '/')
            if (rel.startsWith("ui/glass/") || rel == "ui/plus/GlassKit.kt") return@forEach
            val c = code(f)
            if (c.contains("AlertDialog.Builder(")) bad += "$rel: AlertDialog.Builder (use GlassViewDialog.builder)"
            if (member.containsMatchIn(c)) bad += "$rel: ExposedDropdownMenu (use GlassExposedDropdownMenu)"
            if (c.contains("import androidx.compose.material3.DatePickerDialog") || c.contains("material3.DatePickerDialog(")) bad += "$rel: Material DatePickerDialog (use ui.glass.DatePickerDialog)"
            if (c.contains("material3.Snackbar(") || Regex("""(?<![A-Za-z_.])Snackbar\s*\(""").containsMatchIn(c)) bad += "$rel: Material Snackbar (use GlassSnackbar / GlassToast)"
            Regex("""SnackbarHost\(([A-Za-z_]+)\)(?!\s*\{)""").findAll(c).forEach { bad += "$rel: SnackbarHost without GlassSnackbar" }
            if (c.contains("PopupWindow(") || c.contains("BottomSheetDialog(")) bad += "$rel: View PopupWindow / BottomSheetDialog"
        }
        assertTrue("overlays outside the shared glass:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun overlayFallbackIsDense() {
        val kit = code(File(root(), "ui/plus/GlassKit.kt"))
        val v = Regex("""NO_BLUR = ([0-9.]+)f""").find(kit)?.groupValues?.get(1)?.toFloat() ?: 0f
        assertTrue("no-blur fallback fill must be >= 0.96 (is $v)", v >= 0.96f)
        assertTrue("stacked overlays go dense", kit.contains("GlassBackdrop.depth"))
        val q = code(File(root(), "ui/components/QuickTestSheet.kt"))
        assertTrue("quick test: no English errors", !q.contains("\"No API key configured") && q.contains("该服务商还没配置 API Key"))
    }

    @Test fun rawDialogsAndPopupsOnlyWhereAllowed() {
        val r = root()
        val bad = mutableListOf<String>()
        val raw = Regex("""(?<![A-Za-z_.])(Dialog|Popup)\s*\(|androidx\.compose\.ui\.window\.(Dialog|Popup)\s*\(""")
        val imp = Regex("""^import androidx\.compose\.ui\.window\.(Dialog|Popup)$""", RegexOption.MULTILINE)
        r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(r).path.replace('\\', '/')
            if (rel.startsWith("ui/glass/")) return@forEach
            val c = code(f)
            val usesWindow = c.contains("androidx.compose.ui.window.Dialog(") || c.contains("androidx.compose.ui.window.Popup(") ||
                (imp.containsMatchIn(c) && raw.containsMatchIn(c))
            if (usesWindow && rel !in allowed) bad += rel
        }
        assertTrue("raw Dialog / Popup outside the shared glass (use MinesDialog / MinesSheet / GlassSurface, or add to the list with a reason):\n" +
            bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun pastedTextAndSheetsAreGlass() {
        val r = root()
        val p = code(File(r, "ui/chat/PastedTextBuffer.kt"))
        assertTrue("pasted-text dialog draws GlassSurface", p.contains("GlassSurface("))
        val d = code(File(r, "ui/plus/GlassDialogs.kt"))
        assertTrue("MinesSheet can be FLAG_SECURE", d.contains("SecureFlagPolicy.SecureOn"))
        val card = code(File(r, "ui/plus/LoginCardUi.kt"))
        assertTrue("login / credential card is secure", card.contains("secure = "))
        assertTrue("credential card masks the password with a toggle", card.contains("PasswordVisualTransformation()") && card.contains("显示密码"))
    }

    @Test fun inputBarsUseSharedInputGlass() {
        val r = root()
        val kit = code(File(r, "ui/plus/GlassKit.kt"))
        assertTrue("glassInputFill has a floor", kit.contains("fun glassInputFill(") && kit.contains("GlassFloor."))
        assertTrue("glassInput draws sheen + halo", kit.contains("fun Modifier.glassInput("))
        val home = code(File(r, "ui/plus/WarmComponents.kt"))
        assertTrue("home input bar = shared input glass", home.contains("glassInputFill()"))
        val chat = code(File(r, "ui/chat/ChatComposerWidgets.kt")) + code(File(r, "ui/chat/ChatScreen.kt"))
        assertTrue("chat composer = shared input glass", chat.contains("glassInputFill("))
        assertTrue("no English a11y labels on the composer", !chat.contains("\"Voice input\"") && !chat.contains("\"Stop recording\""))
    }
}
