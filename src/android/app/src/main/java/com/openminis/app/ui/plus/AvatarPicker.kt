package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.glass.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.plus.AvatarCrop
import com.openminis.app.plus.Avatars
import com.openminis.app.plus.PlusPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid of characters (+ the user's own picture) — used in 壁纸与形象 and the quick switcher sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarGrid(onPicked: () -> Unit = {}) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    Avatars.pickFile(context).outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (ok) editing = true else Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        Avatars.builtins.forEach { b ->
            AvatarTile(b.name, selected = st.petStyle == b.id, onClick = {
                PlusPrefs.update(context) { it.copy(petStyle = b.id) }; onPicked()
            }) { PetAvatar(84.dp, style = b.id, animated = false, ring = false) }
        }
        if (Avatars.hasCustom(context)) {
            AvatarTile("我的", selected = st.petStyle == Avatars.CUSTOM, onClick = {
                PlusPrefs.update(context) { it.copy(petStyle = Avatars.CUSTOM) }; onPicked()
            }) { PetAvatar(84.dp, style = Avatars.CUSTOM, animated = false, ring = false) }
        }
        AvatarTile("相册…", selected = false, onClick = { picker.launch("image/*") }) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                MinesIcon(Icons.Outlined.AddPhotoAlternate, contentDescription = "从相册选", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp))
            }
        }
    }
    if (editing) CustomAvatarEditor(onDone = { saved -> editing = false; if (saved) onPicked() })
}

@Composable
private fun AvatarTile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.width(104.dp).clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = MT.Body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** Quick switcher: tap the avatar on the Today card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarSwitcherSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("换个形象", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp))
            AvatarGrid(onPicked = onDismiss)
        }
    }
}

/**
 * Circle crop for the user's own picture / GIF / animated WebP: drag to move,
 * pinch to zoom. "去掉背景" works on still pictures with a plain background.
 */
@Composable
fun CustomAvatarEditor(onDone: (saved: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = Avatars.pickFile(context)
    val animated = remember { Avatars.isAnimated(pick) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var removeBg by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The preview renders the picked file through the same renderer by staging it as the source.
    val previewVersion by remember { mutableLongStateOf(System.nanoTime()) }
    remember {
        // Keep the previous custom picture until the user saves: preview from a staging copy.
        runCatching { pick.copyTo(Avatars.sourceFile(context).resolveSibling("plus_avatar_preview"), overwrite = true) }
    }

    Dialog(onDismissRequest = { onDone(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // [Mines-38.9.6 i13] full-screen editor: opaque page colour (surfaceContainerLow is glass now → page showed through)
        Surface(Modifier.fillMaxSize(), color = mines().bg) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("调整头像", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("拖动移动，双指缩放", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.size(280.dp).pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offX = (offX + pan.x / size.width).coerceIn(-1f, 1f)
                            offY = (offY + pan.y / size.height).coerceIn(-1f, 1f)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    StagedAvatarPreview(280.dp, AvatarCrop(scale, offX, offY), previewVersion)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("去掉背景", style = MaterialTheme.typography.titleMedium)
                        Text(if (animated) "动图不支持去背景" else "纯色背景的图片效果最好", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(removeBg, onCheckedChange = { removeBg = it }, enabled = !animated)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    pick.copyTo(Avatars.sourceFile(context), overwrite = true)
                                    Avatars.noBgFile(context).delete()
                                    if (removeBg && !animated) Avatars.removeBackground(context) else true
                                }.getOrDefault(false)
                            }
                            if (removeBg && !ok) Toast.makeText(context, "没找到纯色背景，已保留原图", Toast.LENGTH_SHORT).show()
                            PlusPrefs.update(context) {
                                it.copy(petStyle = Avatars.CUSTOM, avatarScale = scale, avatarOffX = offX, avatarOffY = offY,
                                    avatarRemoveBg = removeBg && ok && !animated, avatarVersion = System.currentTimeMillis())
                            }
                            busy = false
                            onDone(true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (busy) "处理中…" else "用这张", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("取消", fontSize = MT.Body) }
            }
        }
    }
}

/** Preview of the staged (not yet saved) picture with a crop. */
@Composable
private fun StagedAvatarPreview(size: androidx.compose.ui.unit.Dp, crop: AvatarCrop, version: Long) {
    val context = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { StagedPreviewView(it) },
        update = { v -> v.crop = crop; v.version = version; v.invalidate() },
        modifier = Modifier.size(size),
    )
    @Suppress("UNUSED_EXPRESSION") context
}

private class StagedPreviewView(context: android.content.Context) : android.view.View(context) {
    var crop = AvatarCrop()
    var version = 0L
    private var drawable: android.graphics.drawable.Drawable? = null
    private var loaded = -1L
    private val clip = android.graphics.Path()
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = 0xFFD97757.toInt()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (loaded != version) {
            loaded = version
            val f = Avatars.sourceFile(context).resolveSibling("plus_avatar_preview")
            drawable = Avatars.decode(context, f)?.also { d ->
                d.callback = this
                (d as? android.graphics.drawable.Animatable)?.start()
            }
        }
        val d = drawable ?: return
        val size = minOf(width, height).toFloat()
        canvas.save()
        clip.reset(); clip.addCircle(width / 2f, height / 2f, size / 2, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        val dw = d.intrinsicWidth.coerceAtLeast(1).toFloat(); val dh = d.intrinsicHeight.coerceAtLeast(1).toFloat()
        val base = maxOf(size / dw, size / dh) * crop.scale
        val cx = width / 2f + crop.offX * size; val cy = height / 2f + crop.offY * size
        d.setBounds((cx - dw * base / 2).toInt(), (cy - dh * base / 2).toInt(), (cx + dw * base / 2).toInt(), (cy + dh * base / 2).toInt())
        d.draw(canvas)
        canvas.restore()
        ring.strokeWidth = 4f * resources.displayMetrics.density
        canvas.drawCircle(width / 2f, height / 2f, size / 2 - ring.strokeWidth / 2, ring)
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === drawable || super.verifyDrawable(who)
}
