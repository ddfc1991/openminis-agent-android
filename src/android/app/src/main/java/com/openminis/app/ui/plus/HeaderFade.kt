package com.openminis.app.ui.plus

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * [Mines-38.9.15.1] Transparent page headers (user 14:50 「头顶那片就不能透明？」).
 * Over the weather sky / wallpaper the 聊天主页, task-page and 全部对话 headers have no band of
 * their own: the sky shows straight through. Only this very light top fade remains, strong
 * enough to keep the status bar icons and the title legible, fading to nothing at the bottom
 * edge of the header so there is no visible block.
 */
object HeaderFade {
    /** Peak alpha at the very top (status bar); 0 at the header's bottom edge. */
    const val TOP_ALPHA_LIGHT = 0.20f
    const val TOP_ALPHA_DARK = 0.24f

    fun colors(dark: Boolean): List<Color> {
        val base = if (dark) Color.Black else Color.White
        val a = if (dark) TOP_ALPHA_DARK else TOP_ALPHA_LIGHT
        return listOf(base.copy(alpha = a), base.copy(alpha = a * 0.45f), Color.Transparent)
    }

    @Composable
    fun brush(): Brush = Brush.verticalGradient(colors(isSystemInDarkTheme()))
}
