package com.openminis.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.plus.mines

/**
 * [Mines-38.1] Theme-level 暖瓷 bars. Every screen that imported Material's TopAppBar /
 * FloatingActionButton now imports these instead (same parameters), so pages that were never
 * restyled one by one still get: a transparent bar over the porcelain backdrop, a centred
 * 18 sp SemiBold title, the back button in a 40 dp frosted circle, and a terracotta FAB.
 * Callers' own colours are ignored on purpose (single accent, no teal left anywhere).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    @Suppress("UNUSED_PARAMETER") colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    /** [Mines-38.9.1] opaque bar colour for reading surfaces (file / markdown / code viewer); null = transparent chrome. */
    solid: Color? = null,
) {
    val m = mines()
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            ProvideTextStyle(TextStyle(fontSize = com.openminis.app.ui.plus.MT.PageTitle, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = m.ink))  /* [Mines-38.5] one top-bar title size everywhere */ {
                CompositionLocalProvider(LocalContentColor provides m.ink) { title() }
            }
        },
        modifier = modifier,
        navigationIcon = { WarmBarCircle { navigationIcon() } },
        actions = { CompositionLocalProvider(LocalContentColor provides m.ink) { actions() } },
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = solid ?: Color.Transparent, scrolledContainerColor = solid ?: m.bg.copy(alpha = 0.96f),
            navigationIconContentColor = m.ink, titleContentColor = m.ink, actionIconContentColor = m.ink,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) = TopAppBar(title, modifier, navigationIcon, actions, windowInsets, colors, scrollBehavior)

/** The back button sits in a small frosted circle, like the design's round page buttons. */
@Composable
private fun WarmBarCircle(content: @Composable () -> Unit) {
    val m = mines()
    Box(Modifier.padding(start = 8.dp).size(40.dp).shadow(if (m.dark) 0.dp else 1.dp, CircleShape, clip = false)
        .clip(CircleShape).background(com.openminis.app.ui.plus.minesGlassFill(strong = true)).border(1.dp, m.glassBorder, CircleShape),
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides m.ink) { content() }
    }
}

/** Terracotta FAB (#B5532F + white) whatever colour the caller asked for. */
@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") contentColor: Color = Color.Unspecified,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 5.dp),
    content: @Composable () -> Unit,
) {
    val m = mines()
    androidx.compose.material3.FloatingActionButton(onClick = onClick, modifier = modifier, shape = CircleShape,
        containerColor = m.primary, contentColor = m.onPrimary, elevation = elevation, content = content)
}
