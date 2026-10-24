package com.openminis.app.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Mines-38.9.6 i12] Every dialog, bottom sheet and dropdown menu must be the shared glass (com.openminis.app.ui.glass.*):
 * a file that calls Material 3's AlertDialog / ModalBottomSheet / DropdownMenu directly would bring back a solid card with
 * an elevation shadow (the 「白底」 + square inner block of 38.9.5). Also: no glass surface may use an elevation shadow.
 */
class GlassWrapperScanTest {
    private val names = listOf("AlertDialog", "ModalBottomSheet", "DropdownMenu")
    private val wrapperFile = "ui/glass/GlassM3.kt"

    private fun root(): File = listOf("src/main/java/com/openminis/app", "app/src/main/java/com/openminis/app").map { File(it) }.first { it.isDirectory }
    private fun code(f: File): String = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test fun noDirectMaterialDialogsSheetsMenus() {
        val bad = mutableListOf<String>()
        val r = root()
        r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(r).path.replace('\\', '/')
            if (rel == wrapperFile) return@forEach
            val c = code(f)
            for (n in names) {
                if (Regex("androidx\\.compose\\.material3\\.$n\\s*\\(").containsMatchIn(c)) bad += "$rel: androidx.compose.material3.$n("
                if (Regex("^import androidx\\.compose\\.material3\\.$n$", RegexOption.MULTILINE).containsMatchIn(c)) bad += "$rel: import material3.$n"
                // a star import resolves the bare name to Material 3 unless the glass one is imported explicitly
                val called = Regex("(?<![A-Za-z_.])$n\\s*\\(").containsMatchIn(c)
                val star = Regex("^import androidx\\.compose\\.material3\\.\\*$", RegexOption.MULTILINE).containsMatchIn(c)
                val glass = Regex("^import com\\.openminis\\.app\\.ui\\.glass\\.$n$", RegexOption.MULTILINE).containsMatchIn(c)
                val ownPkg = c.contains("package com.openminis.app.ui.glass")
                if (called && star && !glass && !ownPkg) bad += "$rel: $n via material3.* without the glass import"
            }
        }
        assertTrue("use com.openminis.app.ui.glass.* (shared glass):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun wrappersReplaceContainerAndElevation() {
        val c = File(root(), wrapperFile).readText()
        assertTrue(c.contains("containerColor = glassPopupFill()"))
        assertTrue(Regex("tonalElevation = 0\\.dp,\\s*\\n\\s*(properties|scrimColor)").containsMatchIn(c))
        assertTrue("DropdownMenu must draw through MinisMenu", c.contains("com.openminis.app.ui.components.MinisMenu("))
    }

    @Test fun glassMenusHaveNoElevationShadow() {
        val r = root()
        val menu = code(File(r, "ui/components/MinisMenu.kt"))
        assertTrue("MinisMenu must render through GlassSurface", menu.contains("GlassSurface("))
        assertTrue("no Modifier.shadow under the translucent menu", !Regex("\\.shadow\\(").containsMatchIn(menu))
        val warm = code(File(r, "ui/plus/WarmComponents.kt"))
        val wm = warm.substringAfter("fun WarmMenu(").substringBefore("fun WarmMenuItem(")
        assertTrue("WarmMenu must be the MinisMenu glass", wm.contains("MinisMenu(") && !wm.contains("shadowElevation"))
        val kit = code(File(r, "ui/plus/GlassKit.kt"))
        assertTrue("halo drawn outside the outline only", kit.contains("ClipOp.Difference"))
        val prog = code(File(r, "ui/plus/TaskProgressUi.kt"))
        assertTrue("progress card is the shared glass", prog.contains("GlassSurface(") && !prog.contains("0xF7FFFAF6"))
    }

    @Test fun porcelainTintIsCalm() {
        // 暖瓷: warm ivory, low saturation (no pink-red cast); dark = warm charcoal
        fun sat(argb: Long): Float {
            val r = ((argb shr 16) and 255) / 255f; val g = ((argb shr 8) and 255) / 255f; val b = (argb and 255) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2
            return if (mx == mn) 0f else (mx - mn) / (1 - kotlin.math.abs(2 * l - 1))
        }
        val kit = File(root(), "ui/plus/GlassKit.kt").readText()
        val light = Regex("val Light = Color\\(0x([0-9A-Fa-f]{8})\\)").find(kit)!!.groupValues[1].toLong(16)
        val dark = Regex("val Dark = Color\\(0x([0-9A-Fa-f]{8})\\)").find(kit)!!.groupValues[1].toLong(16)
        assertTrue("light tint saturation ${sat(light)}", sat(light) < 0.40f)
        val r = (light shr 16) and 255; val b = light and 255; val g = (light shr 8) and 255
        assertTrue("warm, not pink: r ≥ g ≥ b and g close to r", r >= g && g >= b && r - g <= 8)
        assertTrue("dark tint saturation ${sat(dark)}", sat(dark) < 0.15f)
    }
}
