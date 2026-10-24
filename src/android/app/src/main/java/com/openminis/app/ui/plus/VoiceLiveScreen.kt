package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import android.Manifest
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openminis.app.plus.GeminiLive
import com.openminis.app.plus.PlusVault

/**
 * Full-screen live voice call with Gemini. Needs a Gemini key saved under
 * 账号连接; talks back instantly and can be interrupted mid-sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLiveScreen(onBack: () -> Unit, onOpenConnections: () -> Unit) {
    val context = LocalContext.current
    // [Mines-38.1b] the call page is dark: light status-bar icons while it shows (smoke run 36: dark clock on dark)
    val sbView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val win = (sbView.context as? android.app.Activity)?.window
        val ctl = win?.let { androidx.core.view.WindowInsetsControllerCompat(it, sbView) }
        val prev = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose { if (prev != null) ctl?.isAppearanceLightStatusBars = prev }
    }
    androidx.compose.runtime.SideEffect {
        (sbView.context as? android.app.Activity)?.window?.let { androidx.core.view.WindowInsetsControllerCompat(it, sbView).isAppearanceLightStatusBars = false }
    }
    PlusVault.init(context)
    // [Mines-voice-26] Default account under 账号连接 → Gemini first, then the plain entry.
    val key = remember { com.openminis.app.plus.GeminiRoute.apiKey() }
    var diag by remember { mutableStateOf<List<String>?>(null) }
    var diagBusy by remember { mutableStateOf(false) }
    val diagScope = androidx.compose.runtime.rememberCoroutineScope()
    fun runDiag() {
        if (diagBusy) return
        diagBusy = true
        diagScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.openminis.app.plus.GeminiRoute.diagnose(context.applicationContext, PlusVault.extra("gemini", "model") ?: GeminiLive.DEFAULT_MODEL)
            }
            diagBusy = false; diag = r
        }
    }
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
    // [Mines-voice-31] The call lives in VoiceSession (foreground service), so
    // leaving this page no longer hangs up; this screen is a view onto it.
    var showSettings by remember { mutableStateOf(false) }
    val liveState by com.openminis.app.plus.VoiceSession.live.collectAsState()
    val live = liveState
    var hungUp by remember { mutableStateOf(false) }

    // Keep the screen on while the page is open (the call itself continues when it is left).
    DisposableEffect(Unit) {
        val act = context as? android.app.Activity
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(key, hasMic) {
        if (key != null && hasMic && !hungUp && com.openminis.app.plus.VoiceSession.live.value == null) com.openminis.app.plus.VoiceSession.start(context)
        else if (key != null && !hasMic) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    diag?.let { d ->
        com.openminis.app.ui.glass.AlertDialog(
            onDismissRequest = { diag = null },
            title = { Text("语音测试结果") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    d.forEach { Text(it, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { diag = null; if (live != null) live.start() else com.openminis.app.plus.VoiceSession.start(context) }) { Text("重新连接") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { diag = null }) { Text("关闭") } },
        )
    }
    // [Mines-voice-29] 普通语音模式 (no Google): local STT → normal chat model → TTS.
    // [Mines-voice-35.2] It is now the call's backup channel, held by VoiceSession: Gemini
    // rejecting the setup / model / key or no route working switches to it by itself.
    val backupState by com.openminis.app.plus.VoiceSession.backup.collectAsState()
    val plainMode = backupState != null
    fun enterPlain() { hungUp = true; if (hasMic) com.openminis.app.plus.VoiceSession.startBackup(context, null) else askMic.launch(Manifest.permission.RECORD_AUDIO) }
    // [Mines-voice-35.2] the desk pet hides while this page shows the character (no second figure)
    DisposableEffect(Unit) {
        com.openminis.app.plus.PetBus.voicePageOpen.value = true
        onDispose { com.openminis.app.plus.PetBus.voicePageOpen.value = false }
    }
    // [Mines-voice-35.2] pages asked for by voice (「录入我的声音」 → 声纹录入)
    var showEnrol by remember { mutableStateOf(false) }
    val intentReq by com.openminis.app.plus.VoiceIntents.request.collectAsState()
    LaunchedEffect(intentReq) {
        when (intentReq) {
            com.openminis.app.plus.VoiceIntents.VOICEPRINT -> showEnrol = true
            com.openminis.app.plus.VoiceIntents.VOICE_SETTINGS -> showSettings = true
        }
        if (intentReq != null) com.openminis.app.plus.VoiceIntents.request.value = null
    }
    if (showEnrol) VoiceprintEnrolDialog(onDone = { showEnrol = false })
    if (showSettings) VoiceSettingsSheet(onDismiss = { showSettings = false }, onChanged = { com.openminis.app.plus.VoiceSession.restart(context) })
    // [Mines-design-34] Showcase voice page (design33 screen 09): frosted haze over the blurred
    // character, voice-reactive rings in the character colour, glass transcript bubbles, a glass
    // control bar. No route / proxy / model text anywhere on this page.
    val mc = mines()
    var showCaptions by remember { mutableStateOf(true) }
    var speaker by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(MinesDesign.VoiceBg)) {
        VoiceHaze(mc)
        Column(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // top bar: back · 通话中 mm:ss · ⋯
            val callStart = remember(live) { System.currentTimeMillis() }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(live) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (live != null || plainMode) {
                    val sec = ((now - callStart) / 1000).coerceAtLeast(0)
                    Row(Modifier.clip(RoundedCornerShape(50)).background(MinesDesign.VoiceGlass)
                        .border(1.dp, MinesDesign.VoiceGlassBorder, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(MinesDesign.OnlineDot))
                        Spacer(Modifier.width(8.dp))
                        Text("通话中 %02d:%02d".format(sec / 60, sec % 60), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassCircle(com.openminis.app.ui.brand.MinesIcons.named("ellipsis")!!, "语音设置") { showSettings = true }
            }
            val plain = backupState
            if (plain != null) {
                PlainVoiceContent(plain, onBackToLive = if (key != null) ({ hungUp = false; com.openminis.app.plus.VoiceSession.start(context) }) else null,
                    onHangUp = { hungUp = true; com.openminis.app.plus.VoiceSession.stop(context); onBack() })
                return@Column
            }
            if (live == null) {
                Spacer(Modifier.weight(0.3f))
                VoiceCharacter(mc, GeminiLive.Phase.IDLE, 0f, Modifier.fillMaxWidth().weight(1f))
                Text(com.openminis.app.plus.Avatars.name(currentCharacter()), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                if (key != null) {
                    VoicePrimary("开始语音") { hungUp = false; if (hasMic) com.openminis.app.plus.VoiceSession.start(context) else askMic.launch(Manifest.permission.RECORD_AUDIO) }
                } else {
                    Text("先在 账号连接 填一个 Gemini API Key", color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    VoicePrimary("去 账号连接 填写", onClick = onOpenConnections)
                    Spacer(Modifier.height(10.dp))
                    VoiceSecondary("先用普通语音模式（不用 Google）") { enterPlain() }
                }
                Spacer(Modifier.height(40.dp))
                return@Column
            }
            val phase by live.phase.collectAsState()
            val level by live.level.collectAsState()
            val muted by live.muted.collectAsState()
            val error by live.error.collectAsState()
            val lines by live.lines.collectAsState()
            val allFailed by live.allFailed.collectAsState()

            VoiceCharacter(mc, phase, level, Modifier.fillMaxWidth().weight(1f))
            Text(com.openminis.app.plus.Avatars.name(currentCharacter()), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (phase) {
                        GeminiLive.Phase.IDLE -> "准备中"
                        GeminiLive.Phase.CONNECTING -> error?.let { plainVoiceError(it) } ?: "正在连接…"
                        GeminiLive.Phase.LISTENING -> if (muted) "已静音" else "正在听你说"
                        GeminiLive.Phase.SPEAKING -> "正在说（随时打断我）"
                        GeminiLive.Phase.ERROR -> error?.let { plainVoiceError(it) } ?: "连不上，点「重新连接」"
                    },
                    color = if (phase == GeminiLive.Phase.ERROR) Color(0xFFFFB4AB) else Color(0xFFF3E9EF),
                    fontSize = 17.sp, textAlign = TextAlign.Center,
                )
                if (phase == GeminiLive.Phase.LISTENING || phase == GeminiLive.Phase.SPEAKING) { Spacer(Modifier.width(10.dp)); VoiceWave(level, phase == GeminiLive.Phase.SPEAKING) }
            }
            if (phase != GeminiLive.Phase.ERROR && phase != GeminiLive.Phase.CONNECTING && !error.isNullOrBlank()) {
                Text(plainVoiceError(error ?: ""), color = Color(0xFFFFD08A), fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            if (phase == GeminiLive.Phase.ERROR) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
                    VoicePrimary("重新连接", Modifier.weight(1f)) { live.start() }
                    VoiceSecondary(if (diagBusy) "测试中…" else "测试语音", Modifier.weight(1f)) { runDiag() }
                }
                if (allFailed) { Spacer(Modifier.height(8.dp)); VoiceSecondary("改用普通语音模式（不用 Google）") { enterPlain() } }
            }
            // glass transcript bubbles (last few lines)
            if (showCaptions) {
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lines.takeLast(12)) { l -> VoiceBubble(l.user, l.text) }
                }
            } else Spacer(Modifier.height(16.dp))
            // glass control bar: 静音 · 字幕 · 扬声器 · 挂断
            Row(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp)).background(MinesDesign.VoiceGlass)
                    .border(1.dp, MinesDesign.VoiceGlassBorder, RoundedCornerShape(32.dp)).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) "取消静音" else "静音",
                    if (muted) Color.White else Color(0x29FFFFFF), dark = muted) { live.setMuted(!muted) }
                RoundButton(com.openminis.app.ui.brand.MinesIcons.named("captions")!!, "字幕",
                    if (showCaptions) Color.White else Color(0x29FFFFFF), dark = showCaptions) { showCaptions = !showCaptions }
                RoundButton(com.openminis.app.ui.brand.MinesIcons.named("volume-2")!!, "扬声器",
                    if (speaker) Color.White else Color(0x29FFFFFF), dark = speaker) {
                    speaker = !speaker
                    runCatching {
                        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = speaker
                    }
                }
                RoundButton(Icons.Filled.CallEnd, "挂断", MinesDesign.HangUp) { hungUp = true; com.openminis.app.plus.VoiceSession.stop(context); onBack() }
            }
        }
    }
}

/** Dreamy backdrop: the character, heavily blurred, under a warm glow + lavender haze + bottom scrim. */
@Composable
private fun VoiceHaze(mc: MinesColors) {
    val b = com.openminis.app.plus.Avatars.builtins.firstOrNull { it.id == currentCharacter() }
    Box(Modifier.fillMaxSize()) {
        if (b != null) androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(b.full), null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.55f,
            modifier = Modifier.fillMaxSize().blur(40.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(Brush.radialGradient(listOf(mc.ring.copy(alpha = 0.40f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f), radius = w * 0.9f),
                radius = w * 0.9f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.30f))
            drawCircle(Brush.radialGradient(listOf(Color(0x47BAA0FF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.65f), radius = w * 0.8f),
                radius = w * 0.8f, center = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.65f))
            drawRect(Brush.verticalGradient(listOf(Color(0x332A1C2C), Color(0x002A1C2C), Color(0xCC1E1420)), startY = 0f, endY = h))
        }
    }
}

/** Sharp character in the middle, breathing, with voice-reactive halo + 3 expanding rings (2.4 s). */
@Composable
private fun VoiceCharacter(mc: MinesColors, phase: GeminiLive.Phase, level: Float, modifier: Modifier) {
    val active = phase == GeminiLive.Phase.LISTENING || phase == GeminiLive.Phase.SPEAKING
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice")
    val ring by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "ring")
    val breath by t.animateFloat(0.985f, 1.015f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2000), androidx.compose.animation.core.RepeatMode.Reverse), label = "breath")
    val lv by animateFloatAsState(if (phase == GeminiLive.Phase.SPEAKING) 0.6f else level, androidx.compose.animation.core.tween(80), label = "lv")
    val halo = 1f + lv.coerceIn(0f, 1f) * 0.12f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.45f)
            val r0 = minOf(size.width, size.height) * 0.30f
            drawCircle(Brush.radialGradient(listOf(mc.ring.copy(alpha = 0.55f), mc.ring.copy(alpha = 0f)), center = c, radius = r0 * 1.5f * halo),
                radius = r0 * 1.5f * halo, center = c)
            if (active) for (i in 0 until 3) {
                val p = (ring + i / 3f) % 1f
                drawCircle(mc.ring.copy(alpha = (1f - p) * 0.55f), radius = r0 * (1f + p * 0.9f), center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            }
        }
        Box(Modifier.fillMaxSize().padding(vertical = 8.dp).graphicsLayer { scaleX = breath * (if (active) 1f + lv * 0.02f else 1f); scaleY = scaleX },
            contentAlignment = Alignment.Center) {
            val b = com.openminis.app.plus.Avatars.builtins.firstOrNull { it.id == currentCharacter() }
            if (b != null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(b.full), com.openminis.app.plus.Avatars.name(b.id),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else PetAvatar(size = 180.dp)
        }
    }
}

/** 60 fps-ish bars following the mic / speech level. */
@Composable
private fun VoiceWave(level: Float, speaking: Boolean) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.2832f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "ph")
    val amp = if (speaking) 0.7f else level.coerceIn(0.08f, 1f)
    Canvas(Modifier.width(96.dp).height(26.dp)) {
        val n = 13; val gap = size.width / n
        for (i in 0 until n) {
            val v = (0.25f + 0.75f * amp * (0.5f + 0.5f * kotlin.math.sin((ph + i * 0.9f).toDouble()).toFloat())).coerceIn(0.15f, 1f)
            val hgt = size.height * v
            drawRoundRect(Color(0xFFF3E9EF), androidx.compose.ui.geometry.Offset(i * gap + gap * 0.3f, (size.height - hgt) / 2f),
                androidx.compose.ui.geometry.Size(gap * 0.4f, hgt), androidx.compose.ui.geometry.CornerRadius(gap * 0.2f))
        }
    }
}

@Composable
private fun VoiceBubble(user: Boolean, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Text(
            text, color = Color.White, fontSize = 17.sp, lineHeight = 25.sp,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp))
                // dark-enough glass: white text stays ≥ 4.5:1 whatever the haze behind it
                .background(if (user) Color(0x52FFC8B0).compositeOver(Color(0xB0241820)) else Color(0x21FFFFFF).compositeOver(Color(0xB0241820)))
                .border(1.dp, MinesDesign.VoiceGlassBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun GlassCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(MinesDesign.VoiceGlass).border(1.dp, MinesDesign.VoiceGlassBorder, CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        MinesIcon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun VoicePrimary(text: String, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), onClick: () -> Unit) {
    val mc = mines()
    Box(modifier.height(56.dp).clip(RoundedCornerShape(50)).background(mc.character.primary).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VoiceSecondary(text: String, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), onClick: () -> Unit) {
    Box(modifier.height(52.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).border(1.dp, MinesDesign.VoiceGlassBorder, RoundedCornerShape(50))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** [Mines-voice] Voice choice, speed, pitch, phone control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    // [Mines-38.1] ⋯ → 「声音」: only the current AI character's voice. Top line 「Kimi 的声音：免费声音 · 晓伊」
    // + 试听; then the source tabs (sources without a key greyed + 去连接); then only that source's voices —
    // one tick on the whole sheet. A pick applies at once and is saved for this character.
    val wm = mines()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val before = remember { com.openminis.app.plus.CharacterVoices.get(ctx, com.openminis.app.plus.CharacterVoices.current()) }
    com.openminis.app.ui.glass.ModalBottomSheet(onDismissRequest = {
            val after = com.openminis.app.plus.CharacterVoices.get(ctx, com.openminis.app.plus.CharacterVoices.current())
            if (after != before) onChanged()
            onDismiss() },
        containerColor = if (wm.dark) wm.surface else Color(0xFFFBF8F4), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("声音", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = wm.ink)
            VoiceSourceSection(onChanged = {})
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onDismiss(); PlusNav.go(PlusRoutes.VOICE_SETTINGS) }
                .heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("每个 AI 的声音、声纹、通话线路", fontSize = 13.sp, color = wm.primaryText, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = wm.primaryText)
            }
        }
    }
}

@Composable
private fun Orb(phase: GeminiLive.Phase, level: Float) {
    val target = when (phase) {
        GeminiLive.Phase.SPEAKING -> 1.15f
        GeminiLive.Phase.LISTENING -> 1f + level * 0.35f
        else -> 0.9f
    }
    val scale by animateFloatAsState(target, label = "orb")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f * 0.8f * scale
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        if (phase == GeminiLive.Phase.SPEAKING) Color(0xFF8E7CFF) else Color(0xFF4FA3FF),
                        Color(0x004FA3FF),
                    ),
                    center = center, radius = r * 1.25f,
                ),
                radius = r * 1.25f,
            )
        }
        PetAvatar(size = 120.dp)
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, dark: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { MinesIcon(icon, label, tint = if (dark) Color(0xFF2A1C2C) else Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

/** [Mines-voice-29] 普通语音模式 screen body. */
@Composable
private fun PlainVoiceContent(plain: com.openminis.app.plus.PlainVoice, onBackToLive: (() -> Unit)?, onHangUp: () -> Unit) {
    val phase by plain.phase.collectAsState()
    val status by plain.status.collectAsState()
    val lines by plain.lines.collectAsState()
    val note by plain.note.collectAsState()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        PetAvatar(size = 96.dp)
        Spacer(Modifier.height(12.dp))
        Text(com.openminis.app.plus.Avatars.name(currentCharacter()), color = Color.White, style = MaterialTheme.typography.titleMedium)
        // [Mines-voice-35.2] small print only: which channel and why
        Text(note ?: "普通语音模式（手机识别 + Mines + 朗读）", color = Color(0xFFB8C2D6), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            status ?: when (phase) {
                com.openminis.app.plus.PlainVoice.Phase.LISTENING -> "我在听"
                com.openminis.app.plus.PlainVoice.Phase.THINKING -> "正在想…"
                com.openminis.app.plus.PlainVoice.Phase.SPEAKING -> "正在回答"
                com.openminis.app.plus.PlainVoice.Phase.ERROR -> "出错了"
                com.openminis.app.plus.PlainVoice.Phase.IDLE -> "已暂停"
            },
            color = if (phase == com.openminis.app.plus.PlainVoice.Phase.ERROR) Color(0xFFFF6B6B) else Color.White,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (phase == com.openminis.app.plus.PlainVoice.Phase.IDLE || phase == com.openminis.app.plus.PlainVoice.Phase.ERROR) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { plain.start() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp)) { Text("继续") }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines) { l ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.user) Arrangement.End else Arrangement.Start) {
                    Text(l.text, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (l.user) Color(0xFF3A5BD9) else Color(0x33FFFFFF))
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            if (onBackToLive != null) androidx.compose.material3.OutlinedButton(onClick = onBackToLive) { Text("再试 Gemini 实时语音", color = Color.White) }
            RoundButton(Icons.Filled.CallEnd, "挂断", Color(0xFFFF3B30)) { onHangUp() }
        }
    }
}


/** [Mines-voice-33] One short plain line for the call screen: no route / proxy / model / codes. */
internal fun plainVoiceError(raw: String): String {
    val first = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("[（(]线路[：:][^）)]*[）)]"), "").replace(Regex("线路[：:].*"), "").trim().trimEnd('，', ',', '·')
    return when {
        // [Mines-voice-35.2] a rejection keeps the server's own words (build-35.1 showed only 「…：」)
        first.contains("拒绝") -> if (first.length > 140) first.take(140) + "…" else first
        first.startsWith("正在试") || first.contains("线路「") || first.contains("换下一条") -> "正在连接…"  // [Mines-38.1]
        first.contains("网络抖了一下") -> first
        first.contains("重连") -> "网络抖了一下，正在接回…"
        first.isBlank() -> "连不上，点「重新连接」"
        first.length > 40 -> first.take(40) + "…"
        else -> first
    }
}
