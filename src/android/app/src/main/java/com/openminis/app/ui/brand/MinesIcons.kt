package com.openminis.app.ui.brand

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * [Mines-brand-26] Mines' own icon set: thin single-colour line icons in the
 * iOS / SF Symbols spirit — 24-unit grid, 1.75 stroke, round caps and joins,
 * tinted with the text colour (no colour fills, no coloured badges).
 *
 * Shapes are Lucide icons (https://lucide.dev, ISC licence; see
 * assets/licenses/mines-brand-icons.txt). Generated from the Lucide SVGs.
 *
 * [of] maps the Material icons the OpenMinis code base passes around to the
 * Mines equivalent, so swapping the whole set later is a change in this file.
 */
object MinesIcons {
    const val STROKE = 1.75f

    private val paths: Map<String, List<String>> = mapOf(
        // [Mines-38.1] lucide (ISC): menu, square-terminal, hard-drive, eye, eye-off
        "menu" to listOf("M4 6h16", "M4 12h16", "M4 18h16"),
        "terminal" to listOf("m7 11 2-2-2-2", "M11 13h4", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z"),
        "hard-drive" to listOf("M22 12H2", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16h.01", "M10 16h.01"),
        "eye" to listOf("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M15 12a3 3 0 1 1 -6 0a3 3 0 1 1 6 0Z"),
        "smartphone" to listOf("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01"),  // [Mines-38.9]
        "laptop" to listOf("M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16"),  // [Mines-38.9]
        "map-pin" to listOf("M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0", "M9 10a3 3 0 1 0 6 0a3 3 0 1 0-6 0"),  // [Mines-38.9]
        "history" to listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2"),  // [Mines-38.9]
        "shield-alert" to listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "M12 8v4", "M12 16h.01"),  // [Mines-38.9]
        "external-link" to listOf("M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"),  // [Mines-38.9]
        "phone" to listOf("M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"),  // [Mines-38.9]
        "bot" to listOf("M12 8V4H8", "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2"),  // [Mines-38.9]
        "sliders" to listOf("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M2 14h4", "M10 8h4", "M18 16h4"),  // [Mines-38.9]
        "eye-off" to listOf("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20"),
        "list-select" to listOf("m3 17 2 2 4-4", "m3 7 2 2 4-4", "M13 6h8", "M13 12h8", "M13 18h8"),
        "archive" to listOf("M3.0 3.0h18.0a1.0 1.0 0 0 1 1.0 1.0v3.0a1.0 1.0 0 0 1 -1.0 1.0h-18.0a1.0 1.0 0 0 1 -1.0 -1.0v-3.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4"),
        "arrow-left" to listOf("m12 19-7-7 7-7", "M19 12H5"),
        "battery-full" to listOf("M10 10v4", "M14 10v4", "M22 14v-4", "M6 10v4", "M4.0 6.0h12.0a2.0 2.0 0 0 1 2.0 2.0v8.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-8.0a2.0 2.0 0 0 1 2.0 -2.0Z"),
        "book-open" to listOf("M12 5v16", "M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z"),
        "brain" to listOf("M12 18V5", "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4", "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5", "M17.997 5.125a4 4 0 0 1 2.526 5.77", "M18 18a4 4 0 0 0 2-7.464", "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517", "M6 18a4 4 0 0 1-2-7.464", "M6.003 5.125a4 4 0 0 0-2.526 5.77"),
        "brush" to listOf("m11 10 3 3", "M6.5 21A3.5 3.5 0 1 0 3 17.5a2.62 2.62 0 0 1-.708 1.792A1 1 0 0 0 3 21z", "M9.969 17.031 21.378 5.624a1 1 0 0 0-3.002-3.002L6.967 14.031"),
        "bug" to listOf("M12 20v-9", "M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4z", "M14.12 3.88 16 2", "M21 21a4 4 0 0 0-3.81-4", "M21 5a4 4 0 0 1-3.55 3.97", "M22 13h-4", "M3 21a4 4 0 0 1 3.81-4", "M3 5a4 4 0 0 0 3.55 3.97", "M6 13H2", "m8 2 1.88 1.88", "M9 7.13V6a3 3 0 1 1 6 0v1.13"),
        "calculator" to listOf("M6.0 2.0h12.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M8 6L16 6", "M16 14L16 18", "M16 10h.01", "M12 10h.01", "M8 10h.01", "M12 14h.01", "M8 14h.01", "M12 18h.01", "M8 18h.01"),
        "calendar" to listOf("M8 2v3", "M16 2v3", "M5.0 3.0h14.0a2.0 2.0 0 0 1 2.0 2.0v14.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-14.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M3 9h18"),
        "chart-column" to listOf("M3 3v16a2 2 0 0 0 2 2h16", "M18 17V9", "M13 17V5", "M8 17v-3"),
        "check" to listOf("M20 6 9 17l-5-5"),
        "chevron-down" to listOf("m6 9 6 6 6-6"),
        "chevron-right" to listOf("m9 18 6-6-6-6"),
        "chevron-up" to listOf("m18 15-6-6-6 6"),
        "circle-check" to listOf("M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "m16 9-5.5 5.5L8 12"),
        "circle" to listOf("M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0"),
        "clock-arrow-up" to listOf("M12 6v6l1.56.78", "M13.227 21.925a10 10 0 1 1 8.767-9.588", "m14 18 4-4 4 4", "M18 22v-8"),
        "clock" to listOf("M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 6v6l4 2"),
        "cloud" to listOf("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"),
        // [Mines-38.7] weather (lucide, ISC)
        "sun" to listOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41"),
        "moon" to listOf("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"),
        "cloud-sun" to listOf("M12 2v2", "m4.93 4.93 1.41 1.41", "M20 12h2", "m19.07 4.93-1.41 1.41", "M15.947 12.65a4 4 0 0 0-5.925-4.128", "M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6Z"),
        "cloud-rain" to listOf("M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M16 14v6", "M8 14v6", "M12 16v6"),
        "cloud-drizzle" to listOf("M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M8 19v1", "M8 14v1", "M16 19v1", "M16 14v1", "M12 21v1", "M12 16v1"),
        "cloud-snow" to listOf("M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M8 15h.01", "M8 19h.01", "M12 17h.01", "M12 21h.01", "M16 15h.01", "M16 19h.01"),
        "cloud-lightning" to listOf("M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973", "m13 12-3 5h4l-3 5"),
        "cloud-fog" to listOf("M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M16 17H7", "M17 21H9"),
        "code" to listOf("m16 18 6-6-6-6", "m8 6-6 6 6 6"),
        "copy" to listOf("M10.0 8.0h10.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"),
        "cpu" to listOf("M12 20v2", "M12 2v2", "M17 20v2", "M17 2v2", "M2 12h2", "M2 17h2", "M2 7h2", "M20 12h2", "M20 17h2", "M20 7h2", "M7 20v2", "M7 2v2", "M6.0 4.0h12.0a2.0 2.0 0 0 1 2.0 2.0v12.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-12.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M9.0 8.0h6.0a1.0 1.0 0 0 1 1.0 1.0v6.0a1.0 1.0 0 0 1 -1.0 1.0h-6.0a1.0 1.0 0 0 1 -1.0 -1.0v-6.0a1.0 1.0 0 0 1 1.0 -1.0Z"),
        "download" to listOf("M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"),
        "ellipsis" to listOf("M11.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0", "M18.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0", "M4.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0"),
        "file-text" to listOf("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8"),
        "folder-input" to listOf("M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1", "M2 13h10", "m9 16 3-3-3-3"),
        "folder-open" to listOf("m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2"),
        "folder-x" to listOf("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z", "m9.5 10.5 5 5", "m14.5 10.5-5 5"),
        "folder" to listOf("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"),
        "gauge" to listOf("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0"),
        "globe" to listOf("M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20"),
        "hammer" to listOf("m15 12-9.373 9.373a1 1 0 0 1-3.001-3L12 9", "m18 15 4-4", "m21.5 11.5-1.914-1.914A2 2 0 0 1 19 8.172v-.344a2 2 0 0 0-.586-1.414l-1.657-1.657A6 6 0 0 0 12.516 3H9l1.243 1.243A6 6 0 0 1 12 8.485V10l2 2h1.172a2 2 0 0 1 1.414.586L18.5 14.5"),
        "hand" to listOf("M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2", "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2", "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8", "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"),
        "hard-drive-download" to listOf("M12 2v8", "m16 6-4 4-4-4", "M4.0 14.0h16.0a2.0 2.0 0 0 1 2.0 2.0v4.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-4.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M6 18h.01", "M10 18h.01"),
        "heart" to listOf("M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5"),
        "image" to listOf("M5.0 3.0h14.0a2.0 2.0 0 0 1 2.0 2.0v14.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-14.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M7.0 9.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"),
        "info" to listOf("M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 16v-4", "M12 8h.01"),
        "key-round" to listOf("M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z", "M16.0 7.5a0.5 0.5 0 1 0 1.0 0a0.5 0.5 0 1 0 -1.0 0"),
        "languages" to listOf("m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6"),
        "layout-dashboard" to listOf("M4.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v7.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-7.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M15.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v3.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-3.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M15.0 12.0h5.0a1.0 1.0 0 0 1 1.0 1.0v7.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-7.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M4.0 16.0h5.0a1.0 1.0 0 0 1 1.0 1.0v3.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-3.0a1.0 1.0 0 0 1 1.0 -1.0Z"),
        "layout-grid" to listOf("M4.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M15.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M15.0 14.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M4.0 14.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0Z"),
        "list-checks" to listOf("M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "m3 7 2 2 4-4"),
        "lock" to listOf("M5.0 11.0h14.0a2.0 2.0 0 0 1 2.0 2.0v7.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-7.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M7 11V7a5 5 0 0 1 10 0v4"),
        "mail" to listOf("m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7", "M4.0 4.0h16.0a2.0 2.0 0 0 1 2.0 2.0v12.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-12.0a2.0 2.0 0 0 1 2.0 -2.0Z"),
        "map" to listOf("M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z", "M15 5.764v15", "M9 3.236v15"),
        "message-square-plus" to listOf("M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z", "M12 8v6", "M9 11h6"),
        "message-square-warning" to listOf("M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z", "M12 15h.01", "M12 7v4"),
        "messages-square" to listOf("M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z", "M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1"),
        "mic-off" to listOf("M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7 7 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12"),
        "mic" to listOf("M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2", "M12.0 2.0h0.0a3.0 3.0 0 0 1 3.0 3.0v7.0a3.0 3.0 0 0 1 -3.0 3.0h-0.0a3.0 3.0 0 0 1 -3.0 -3.0v-7.0a3.0 3.0 0 0 1 3.0 -3.0Z"),
        "minus" to listOf("M5 12h14"),
        "palette" to listOf("M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z", "M13.0 6.5a0.5 0.5 0 1 0 1.0 0a0.5 0.5 0 1 0 -1.0 0", "M17.0 10.5a0.5 0.5 0 1 0 1.0 0a0.5 0.5 0 1 0 -1.0 0", "M6.0 12.5a0.5 0.5 0 1 0 1.0 0a0.5 0.5 0 1 0 -1.0 0", "M8.0 7.5a0.5 0.5 0 1 0 1.0 0a0.5 0.5 0 1 0 -1.0 0"),
        "pause" to listOf("M15.0 3.0h3.0a1.0 1.0 0 0 1 1.0 1.0v16.0a1.0 1.0 0 0 1 -1.0 1.0h-3.0a1.0 1.0 0 0 1 -1.0 -1.0v-16.0a1.0 1.0 0 0 1 1.0 -1.0Z", "M6.0 3.0h3.0a1.0 1.0 0 0 1 1.0 1.0v16.0a1.0 1.0 0 0 1 -1.0 1.0h-3.0a1.0 1.0 0 0 1 -1.0 -1.0v-16.0a1.0 1.0 0 0 1 1.0 -1.0Z"),
        "pencil" to listOf("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4"),
        "phone-off" to listOf("M10.1 13.9a14 14 0 0 0 3.732 2.668 1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2 18 18 0 0 1-12.728-5.272", "M22 2 2 22", "M4.76 13.582A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 .244.473"),
        "pin" to listOf("M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"),
        "plus" to listOf("M5 12h14", "M12 5v14"),
        "puzzle" to listOf("M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z"),
        "refresh-cw" to listOf("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5"),
        "rotate-ccw" to listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5"),
        "search" to listOf("m21 21-4.34-4.34", "M3.0 11.0a8.0 8.0 0 1 0 16.0 0a8.0 8.0 0 1 0 -16.0 0"),
        "send" to listOf("M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z", "m21.854 2.147-10.94 10.939"),
        "server" to listOf("M4.0 2.0h16.0a2.0 2.0 0 0 1 2.0 2.0v4.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-4.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M4.0 14.0h16.0a2.0 2.0 0 0 1 2.0 2.0v4.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-4.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M6 6L6.01 6", "M6 18L6.01 18"),
        "settings" to listOf("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", "M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0"),
        "share" to listOf("M12 2v13", "m16 6-4-4-4 4", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"),
        "shield-check" to listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4"),
        "shield" to listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"),
        "sparkles" to listOf("M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z", "M20 2v4", "M22 4h-4", "M2.0 20.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0"),
        "square-terminal" to listOf("m7 11 2-2-2-2", "M11 13h4", "M5.0 3.0h14.0a2.0 2.0 0 0 1 2.0 2.0v14.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-14.0a2.0 2.0 0 0 1 2.0 -2.0Z"),
        "stethoscope" to listOf("M11 2v2", "M5 2v2", "M5 3H4a2 2 0 0 0-2 2v4a6 6 0 0 0 12 0V5a2 2 0 0 0-2-2h-1", "M8 15a6 6 0 0 0 12 0v-3", "M18.0 10.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0"),
        "store" to listOf("M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5", "M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244", "M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05"),
        "trash" to listOf("M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
        "user" to listOf("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8.0 7.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0"),
        "users" to listOf("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M16 3.128a4 4 0 0 1 0 7.744", "M22 21v-2a4 4 0 0 0-3-3.87", "M5.0 7.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0"),
        "wallet" to listOf("M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1", "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"),
        "wifi" to listOf("M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0"),
        "wrench" to listOf("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z"),
        "volume-2" to listOf("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728"),
        "captions" to listOf("M5.0 5.0h14.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0Z", "M7 15h4", "M15 15h2", "M7 11h2", "M13 11h4"),
        "audio-lines" to listOf("M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3"),
        "fingerprint" to listOf("M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4", "M14 13.12c0 2.38 0 6.38-1 8.88", "M17.29 21.02c.12-.6.43-2.3.5-3.02", "M2 12a10 10 0 0 1 18-6", "M2 16h.01", "M21.8 16c.2-2 .131-5.354 0-6", "M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2", "M8.65 22c.21-.66.45-1.32.57-2", "M9 6.8a6 6 0 0 1 9 5.2v2"),
        "x" to listOf("M18 6 6 18", "m6 6 12 12"),
        "layers" to listOf("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z", "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12", "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17"),
    )

    private val cache = HashMap<String, ImageVector>()

    /** A Lucide icon by name, or null if it isn't bundled. */
    fun named(name: String): ImageVector? = synchronized(cache) {
        cache[name] ?: paths[name]?.let { ps ->
            val b = ImageVector.Builder(name = "Mines.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            ps.forEach { d ->
                b.addPath(
                    pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            b.build().also { cache[name] = it }
        }
    }

    /** Material icon name (e.g. "Outlined.Settings") → Lucide name. */
    private val fromMaterial: Map<String, String> = mapOf(
        "Settings" to "settings", "Forum" to "messages-square", "Terminal" to "square-terminal", "PushPin" to "pin",
        "KeyboardArrowRight" to "chevron-right", "Speed" to "gauge", "Search" to "search", "Schedule" to "clock",
        "Palette" to "palette", "Mic" to "mic", "MicOff" to "mic-off", "Language" to "globe", "Key" to "key-round",
        "Inventory" to "archive", "Inventory2" to "archive", "Extension" to "puzzle", "Description" to "file-text",
        "Cloud" to "cloud", "ChecklistRtl" to "list-checks", "Book" to "book-open", "BarChart" to "chart-column",
        "Backup" to "hard-drive-download", "Share" to "share", "Refresh" to "refresh-cw", "Folder" to "folder",
        "Delete" to "trash", "Close" to "x", "Wallpaper" to "image", "VerifiedUser" to "shield-check",
        "Translate" to "languages", "SystemUpdate" to "download", "Storefront" to "store", "Shield" to "shield",
        "Psychology" to "brain", "Payments" to "wallet", "Memory" to "cpu", "Map" to "map", "Lock" to "lock",
        "Info" to "info", "HealthAndSafety" to "stethoscope", "Handyman" to "wrench", "Groups" to "users",
        "GridView" to "layout-grid", "FrontHand" to "hand", "FolderShared" to "folder-open", "FolderOff" to "folder-x",
        "Feedback" to "message-square-warning", "Favorite" to "heart", "ExpandMore" to "chevron-down",
        "ExpandLess" to "chevron-up", "KeyboardArrowDown" to "chevron-down", "KeyboardArrowUp" to "chevron-up",
        "Email" to "mail", "Edit" to "pencil", "Dns" to "server", "Dashboard" to "layout-dashboard", "Code" to "code",
        "Circle" to "circle", "CalendarMonth" to "calendar", "Calculate" to "calculator", "Build" to "hammer",
        "BugReport" to "bug", "Brush" to "brush", "BatteryFull" to "battery-full", "AutoAwesome" to "sparkles",
        "AddComment" to "message-square-plus", "History" to "rotate-ccw", "ContentCopy" to "copy", "Check" to "check",
        "Send" to "send", "ArrowBack" to "arrow-left", "DriveFileMove" to "folder-input", "Pause" to "pause",
        "CheckCircle" to "circle-check", "CallEnd" to "phone-off", "Person" to "user", "Wifi" to "wifi",
        "Add" to "plus", "Remove" to "minus", "MoreHoriz" to "ellipsis", "Speed" to "gauge", "Mic" to "mic",
    )

    /** The Mines line icon for a Material icon, or the icon itself when there is no match. */
    fun of(icon: ImageVector): ImageVector {
        if (icon.name.startsWith("Mines.")) return icon
        val key = icon.name.substringAfterLast('.')
        return fromMaterial[key]?.let { named(it) } ?: icon
    }
}

/** Drop-in for material3 Icon that swaps Material icons for the Mines line set. */
@Composable
fun MinesIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(MinesIcons.of(imageVector), contentDescription, modifier, tint)

@Composable
fun MinesIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(painter, contentDescription, modifier, tint)

@Composable
fun MinesIcon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(bitmap, contentDescription, modifier, tint)
