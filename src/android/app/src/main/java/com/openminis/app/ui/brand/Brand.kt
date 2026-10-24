package com.openminis.app.ui.brand

/**
 * [Mines-brand-26] One place for the app's own brand, so a later rename only
 * touches this file (plus the launcher icon resources and app_name strings it
 * points to). The Android package (com.openminis.mines.plus) and the signing
 * key stay as they are so updates keep installing in place.
 */
object Brand {
    /** Name shown in the UI, prompts and notifications. */
    const val NAME = "Mines"
    /** Command prefix of the sandbox tools (old `minis-*` names stay as aliases). */
    const val CMD = "mines"
    /** Upstream credit required by the GPL-3.0 notice (kept, not shown as our brand). */
    const val UPSTREAM = "OpenMinis"
    const val UPSTREAM_URL = "https://github.com/OpenMinis/OpenMinis"
    const val ABOUT_LINE = "$NAME 基于 $UPSTREAM 继续开发"
    /** Launcher icon / monochrome icon resources (change these drawables to rebrand). */
    val LAUNCHER_ICON: Int get() = com.openminis.app.R.mipmap.ic_launcher
    val MONO_ICON: Int get() = com.openminis.app.R.drawable.ic_launcher_monochrome
    /** Line-icon set used across the UI ([MinesIcons]). */
    const val ICON_SET = "Lucide (ISC)"
}
