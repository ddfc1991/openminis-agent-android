package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NotInterested
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.plus.DeskPetService
import com.openminis.app.plus.PlusPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PersonalizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var wantPet by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        wallpaperFile(context).outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
            }
            if (ok) PlusPrefs.update(context) { it.copy(wallpaper = "file:${System.currentTimeMillis()}") }
            else Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
        }
    }

    // Coming back from the overlay-permission screen: start the pet if granted.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && wantPet && DeskPetService.canShow(context)) {
                wantPet = false
                PlusPrefs.update(context) { it.copy(petEnabled = true) }
                DeskPetService.start(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    PlusPage("壁纸与形象", onBack) {
        CardTextSection()
        // ---- Wallpaper ----
        PlusSection("聊天壁纸") {
            // live preview
            Box(
                Modifier.fillMaxWidth().height(150.dp).padding(12.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background),
            ) {
                ChatWallpaperLayer(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Bubble("今天帮我看看服务器", mine = true)
                    Bubble("好的，两台都在线，延迟 38 ms", mine = false)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Swatch("不用", selected = st.wallpaper.isEmpty(), onClick = {
                    PlusPrefs.update(context) { it.copy(wallpaper = "") }
                }) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        MinesIcon(Icons.Outlined.NotInterested, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Swatch("相册", selected = st.wallpaper.startsWith("file:"), onClick = { picker.launch("image/*") }) {
                    val bmp = rememberWallpaperBitmap(st.wallpaper)
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        MinesIcon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                wallpaperPresets.forEach { p ->
                    Swatch(p.name, selected = st.wallpaper == "preset:${p.id}", onClick = {
                        PlusPrefs.update(context) { it.copy(wallpaper = "preset:${p.id}") }
                    }) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(p.colors)))
                    }
                }
            }
            if (st.wallpaper.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("壁纸透明度 ${(st.wallpaperAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(132.dp))
                    Slider(
                        value = st.wallpaperAlpha, valueRange = 0f..1f,
                        onValueChange = { v -> PlusPrefs.update(context) { it.copy(wallpaperAlpha = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("首页也用这张壁纸", style = MaterialTheme.typography.bodyLarge)
                        Text("对话列表背后也显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(st.wallpaperOnHome, onCheckedChange = { on -> PlusPrefs.update(context) { it.copy(wallpaperOnHome = on) } })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("遮罩", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(132.dp))
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = st.wallpaperDim, valueRange = 0f..0.8f,
                        onValueChange = { v -> PlusPrefs.update(context) { it.copy(wallpaperDim = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Avatar ----
        PlusSection("我的形象", footer = "形象会出现在首页卡片和桌宠上。点首页卡片上的头像、或长按桌宠，随时能换。也可以用相册里的图片或动图（GIF / 动态 WebP）。") {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                PetAvatar(size = 200.dp, face = st.petStyle == com.openminis.app.plus.Avatars.CUSTOM, animated = false, fullBody = true)
            }
            Text(
                com.openminis.app.plus.Avatars.name(st.petStyle),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            AvatarGrid()
            Text("边框颜色", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                com.openminis.app.plus.Avatars.colors.forEach { c ->
                    val sel = st.petColor == c
                    Box(
                        Modifier.size(if (sel) 38.dp else 32.dp).clip(CircleShape).background(Color(c))
                            .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant), CircleShape)
                            .clickable { PlusPrefs.update(context) { it.copy(petColor = c) } }
                    )
                }
            }
        }

        // ---- Desk pet ----
        PlusSection("桌宠", footer = "打开后它会浮在所有应用上面。拖动它换位置，点一下看任务和余额，双击打开 Mines，长按换形象、调大小或收起。") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示桌宠", fontWeight = FontWeight.Medium)
                    if (!DeskPetService.canShow(context)) Text(
                        "需要允许“显示在其他应用上层”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(st.petEnabled, onCheckedChange = { on ->
                    if (!on) {
                        PlusPrefs.update(context) { it.copy(petEnabled = false) }
                        DeskPetService.stop(context)
                    } else if (DeskPetService.canShow(context)) {
                        PlusPrefs.update(context) { it.copy(petEnabled = true) }
                        DeskPetService.start(context)
                    } else {
                        wantPet = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                })
            }
        }
        if (st.petEnabled) PetBehaviourSection()
        WakeWordSection()
    }
}

/** [Mines-pet-25] How the desk pet behaves: size, staying out of the way, panel. */
@Composable
private fun PetBehaviourSection() {
    val context = LocalContext.current
    val st by PlusPrefs.state.collectAsState()
    fun set(block: (com.openminis.app.plus.PlusState) -> com.openminis.app.plus.PlusState) {
        PlusPrefs.update(context, block); DeskPetService.refresh(context)
    }
    PlusSection("桌宠行为", footer = "穿透模式下点不到桌宠，触摸会直接给下面的应用；在通知栏点「关闭穿透」恢复。") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("大小 ${st.petSizeDp}", fontWeight = FontWeight.Medium)
            Slider(st.petSizeDp.toFloat(), { v -> set { it.copy(petSizeDp = v.toInt()) } }, valueRange = 70f..340f)
        }
        PetToggle("别挡着我", "在别的应用里点屏幕或打字时，桌宠变小变透明", st.petAutoFade) { on -> set { it.copy(petAutoFade = on) } }
        PetToggle("自动贴边", "松手后靠到最近的屏幕边", st.petEdgeSnap) { on -> set { it.copy(petEdgeSnap = on) } }
        PetToggle("贴边半藏", "不动的时候藏一半到屏幕外，点一下出来", st.petHalfHide) { on -> set { it.copy(petHalfHide = on) } }
        PetToggle("偶尔散步", "闲着时沿屏幕边走几步", st.petWalk) { on -> set { it.copy(petWalk = on) } }
        PetToggle("点一下开始/结束语音", "通话时桌宠旁边显示你说的话和回答；双击打开状态面板", st.petTapVoice) { on -> set { it.copy(petTapVoice = on) } }
        PetToggle("点它显示状态面板", "任务进度、服务器任务、模型余额和快捷按钮", st.petPanel) { on -> set { it.copy(petPanel = on) } }
        PetToggle("穿透模式", "触摸穿过桌宠", st.petPassThrough) { on -> set { it.copy(petPassThrough = on) } }
        PetToggle("白色描边", "在花哨的壁纸上也能一眼看到桌宠", st.petOutline) { on -> set { it.copy(petOutline = on) } }
    }
    // [Mines-pet-32] 「生成动作」 for the user's own picture (built-in characters already have frames)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prog by com.openminis.app.plus.PetFrameGen.progress.collectAsState()
    var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    PlusSection("会动的形象", footer = "自带的 7 个角色已经会眨眼、说话时动嘴、思考时看向一边。用自己的图片时，点「生成动作」：用你的 Gemini 密钥做 4 张同构图的表情（眨眼、两种口型、思考），大约 1–2 分钟，消耗 4 次图片编辑额度（Gemini 免费层通常够用）。") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            androidx.compose.material3.Button(
                onClick = { result = null; scope.launch { result = com.openminis.app.plus.PetFrameGen.generate(context); DeskPetService.refresh(context) } },
                enabled = prog == null && st.petStyle == com.openminis.app.plus.Avatars.CUSTOM,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (prog != null) prog!! else if (st.petStyle == com.openminis.app.plus.Avatars.CUSTOM) "生成动作" else "自带角色已经会动了", fontSize = MT.Body) }
            result?.let { Text(it, fontSize = MT.Body, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun PetToggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onCheckedChange = onChange)
    }
}

/** [Mines-wake-25] Offline wake word → live voice chat with the pet. */
@Composable
private fun WakeWordSection() {
    val context = LocalContext.current
    val st by PlusPrefs.state.collectAsState()
    var phrase by remember { mutableStateOf(st.wakeText) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { PlusPrefs.update(context) { it.copy(wakeWord = true) }; com.openminis.app.plus.WakeWordService.start(context) }
        else msg = "没有麦克风权限，唤醒词用不了"
    }
    PlusSection("唤醒词", footer = "完全离线（Vosk 中文小模型，约 40 MB，第一次打开时下载）。开着时麦克风一直在听，会多耗一些电，通知栏会一直显示。听到唤醒词就打开实时语音，桌宠会做出说话的样子。") {
        PetToggle("用唤醒词叫它", "说「${st.wakeText}」开始语音聊天", st.wakeWord) { on ->
            if (!on) { PlusPrefs.update(context) { it.copy(wakeWord = false) }; com.openminis.app.plus.WakeWordService.stop(context) }
            else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                msg = "正在准备离线模型…"
                scope.launch {
                    // [Mines-models-35] live % from the mirrored downloader, plain reason on failure
                    val ticker = launch { while (true) { com.openminis.app.speech.OfflineAsrEngine.lastStatus?.let { msg = "离线模型：$it" }; kotlinx.coroutines.delay(500) } }
                    val ok = withContext(Dispatchers.IO) { com.openminis.app.plus.WakeWordService.prepare(context) }
                    ticker.cancel()
                    msg = if (ok) "" else (com.openminis.app.speech.OfflineAsrEngine.lastError ?: "离线模型下载失败，检查网络后再试")
                    if (ok) { PlusPrefs.update(context) { it.copy(wakeWord = true) }; com.openminis.app.plus.WakeWordService.start(context) }
                }
            } else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            androidx.compose.material3.OutlinedTextField(phrase, { phrase = it.take(12) }, label = { Text("唤醒词（中文，3–6 个字最好）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.TextButton(enabled = phrase.isNotBlank() && phrase != st.wakeText, onClick = {
                PlusPrefs.update(context) { it.copy(wakeText = phrase.trim()) }
                if (st.wakeWord) { com.openminis.app.plus.WakeWordService.stop(context); com.openminis.app.plus.WakeWordService.start(context) }
            }) { Text("保存唤醒词") }
            if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Swatch(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 56.dp, height = 84.dp).clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) { content() }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}
