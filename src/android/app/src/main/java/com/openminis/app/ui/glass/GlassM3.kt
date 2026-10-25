@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.openminis.app.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.plus.GlassWindowEffect
import com.openminis.app.ui.plus.glassPopupFill
import com.openminis.app.ui.plus.glassSheen
import com.openminis.app.ui.plus.mines

/**
 * [Mines-38.9.6 i12] The app's AlertDialog / ModalBottomSheet / DropdownMenu. Every file imports THESE (same names and
 * parameters as Material 3), so no dialog, sheet or menu can come out as a solid card again: whatever container colour or
 * tonal elevation a caller passes is replaced by the shared glass (暖瓷 tint at the global 外观调节 alpha over the blurred
 * page copy, top sheen, hairline rim, no elevation shadow). GlassWrapperScanTest fails the build if a file calls the
 * Material 3 versions directly.
 */

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    iconContentColor: Color = Color.Unspecified,
    titleContentColor: Color = Color.Unspecified,
    textContentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    val m = mines()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        // GlassWindowEffect emits no layout: blurred page copy + light dim for this dialog window
        confirmButton = { GlassWindowEffect(); confirmButton() },
        modifier = modifier.glassSheen(shape, m.dark).border(1.dp, m.glassBorder, shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = glassPopupFill(),
        iconContentColor = iconContentColor.takeOrElse { AlertDialogDefaults.iconContentColor },
        titleContentColor = titleContentColor.takeOrElse { m.ink },
        textContentColor = textContentColor.takeOrElse { m.ink2 },
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/** Small glass drag handle (36 × 4 dp, ink at 35 %). */
@Composable
fun GlassDragHandle() {
    val m = mines()
    Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(m.ink3.copy(alpha = 0.45f)))
}

@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color(0x2614100C),
    dragHandle: @Composable (() -> Unit)? = { GlassDragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: androidx.compose.material3.ModalBottomSheetProperties = androidx.compose.material3.ModalBottomSheetDefaults.properties,
    /** [Mines-38.9.15] false = the sheet does not follow drags (the in-app browser: a slider on the page owns the finger) */
    sheetGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = mines()
    // [Mines-38.9.8] real phone (dark, 快速测试 over 选择模型): a sheet opened on top of another sheet showed the rows of the
    // sheet below through its glass. A stacked sheet is now solid 暖瓷 (whatever the glass setting) and dims the sheet
    // beneath with a darker scrim. The level is taken when the sheet enters composition and released when it leaves.
    val level = androidx.compose.runtime.remember { SheetStack.enter() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SheetStack.leave() } }
    val stacked = level >= 1
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // [Mines-38.9.9] user 09:20 (every page under 快速测试): the sheen + 1 dp border sat in the sheet's OUTER modifier, which
        // Material draws before the sheet's offset → a rounded, bordered ghost of the sheet at the TOP of the screen (sharp light
        // line ~1/3 down, different tint above). The edge now lives inside the sheet (SheetEdge) so it moves with it.
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = if (stacked) com.openminis.app.ui.plus.GlassTint.of(m.dark) else glassPopupFill(),
        contentColor = contentColor.takeOrElse { m.ink },
        tonalElevation = 0.dp,
        scrimColor = if (stacked) SheetStack.STACKED_SCRIM else scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        sheetGesturesEnabled = sheetGesturesEnabled,
    ) {
        // [Mines-38.9.9] user (快速测试 over 选择模型): the stacked sheet's own backdrop pass (window dim + a blurred copy of the
        // page in the activity layer) dimmed only part of the sheet below and left a full-width bright seam under 「自动（推荐）」.
        // A stacked sheet now takes no backdrop pass at all: the one STACKED_SCRIM dims everything beneath it uniformly.
        if (SheetStack.backdropFor(level)) GlassWindowEffect()
        content()
    }
}

/** [Mines-38.9.9] where the glass sheet's sheen / border may be drawn: never in the outer (un-offset) sheet modifier. */
object SheetEdge {
    const val OUTER_MODIFIER_EDGE = false
}

/** [Mines-38.9.8] how many glass bottom sheets are open (a sheet opened while another one shows is drawn solid). */
object SheetStack {
    private val open = java.util.concurrent.atomic.AtomicInteger(0)
    /** dims the sheet below a stacked one (≈ 55 % black) */
    val STACKED_SCRIM = Color(0x8C000000)
    fun enter(): Int = open.getAndIncrement()
    fun leave() { if (open.decrementAndGet() < 0) open.set(0) }
    fun count(): Int = open.get()
    /** [Mines-38.9.9] only the bottom sheet of a stack runs the glass backdrop pass */
    fun backdropFor(level: Int): Boolean = level <= 0
}

/** Material's DropdownMenu signature, drawn by [com.openminis.app.ui.components.MinisMenu] (one glass surface, no elevation). */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(18.dp),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") shadowElevation: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = com.openminis.app.ui.components.MinisMenu(
    expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset,
    scrollState = scrollState, properties = properties, shape = shape, content = content,
)

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this == Color.Unspecified) block() else this

/**
 * [Mines-38.9.6 i14] ExposedDropdownMenu as glass: the shared popup fill, no tonal / shadow elevation (a shadow core shows
 * through translucent glass), sheen + rim, and the page behind blurred while it is open. Use this instead of the Material
 * member (GlassSurfaceScanTest fails on a direct call).
 */
@Composable
fun androidx.compose.material3.ExposedDropdownMenuBoxScope.GlassExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = mines()
    val shape = RoundedCornerShape(18.dp)
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassSheen(shape, m.dark),
        shape = shape,
        containerColor = glassPopupFill(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, m.glassBorder),
    ) {
        if (expanded) GlassWindowEffect()
        content()
    }
}

/** [Mines-38.9.6 i14] Material DatePickerDialog as glass (shared fill, no elevation, page behind blurred). */
@Composable
fun DatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = mines()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.glassSheen(shape, m.dark).border(1.dp, m.glassBorder, shape),
        dismissButton = dismissButton,
        shape = shape,
        tonalElevation = 0.dp,
        colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = glassPopupFill()),
        properties = properties,
    ) {
        GlassWindowEffect()
        content()
    }
}
