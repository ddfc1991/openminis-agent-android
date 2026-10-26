package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Box
import com.openminis.app.ui.glass.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.components.MenuItemIcon
import com.openminis.app.ui.components.SwipeRowAction
import com.openminis.app.ui.components.SwipeRowActions

/**
 * [Mines-38.9.15 task_archive] user 2026-10-26 10:14 「这个页面没办法手动归档」: every row on 任务 (等你处理 / 进行中 / 已完成 /
 * 项目 / 小任务) can be archived by hand — swipe left → 「归档」, or long-press → menu 「归档」. The caller's row puts
 * [content]'s long-press callback on its own combinedClickable. 撤销 is the 6 s bar at the bottom (ArchiveUndoHost).
 * At rest the row keeps its frosted glass (transparent fill); only the slid row turns solid.
 */
@Composable
fun ArchiveSwipe(onArchive: () -> Unit, actionLabel: String = "归档", content: @Composable (onLongPress: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val icon = WarmIcons.of("archive")!!
    Box {
        SwipeRowActions(
            actions = listOf(SwipeRowAction(actionLabel, icon, Color(0xFF8A6D3B), Color.White) { onArchive() }),
            rowColor = Color.Transparent,
        ) { content { menu = true } }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(actionLabel) }, onClick = { menu = false; onArchive() }, leadingIcon = { MenuItemIcon(icon) })
        }
    }
}
