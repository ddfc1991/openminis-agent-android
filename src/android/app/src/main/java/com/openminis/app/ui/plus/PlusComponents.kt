package com.openminis.app.ui.plus

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import com.openminis.app.ui.brand.MinesIcon

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.PlusPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin

/** Lets screens outside the NavHost lambda (the conversation list) open Plus pages. */
object PlusNav {
    @Volatile var navigate: ((String) -> Unit)? = null
    /** Route asked for while no screen was up (e.g. from the desk pet); opened on next start. */
    @Volatile var pending: String? = null
    /** [Mines-apps-32] 「应用」 opens this service's detail page next (from a notification). */
    @Volatile var openKind: String? = null
    /** [Mines-fix-35.1] account id to open 一键修复 for when 应用/账号 opens */
    @Volatile var fixAccount: String? = null
    /** [Mines-feed-35.3] with openKind: start 帮我领 (一键接入) right away, as from the 应用 list */
    @Volatile var autoClaim: Boolean = false
    /** [Mines-38.7] with route plus_connections: open this account's in-app sign-in page right away */
    @Volatile var openLogin: String? = null
    /** [Mines-38.8] 消息 page opens on this tab next ("MAIL" = 邮箱; self-test / notification) */
    @Volatile var digestTab: String? = null
    /** [Mines-webfit-38.3] open the in-app browser on this URL (home screen picks it up; smoke self-test). */
    val openBrowser = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** [Mines-38.9.11.2] open a 无痕 session on this URL ("" = blank) — self-test / menu */
    val openIncognito = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** [Mines-38.9] in-app browser on [url] from any page (set by AppNavigation; falls back to the home pick-up) */
    @Volatile var openWeb: ((String) -> Unit)? = null
    fun web(url: String) { openWeb?.invoke(url) ?: run { openBrowser.value = url } }
    fun go(route: String) { navigate?.invoke(route) ?: run { pending = route } }
}

object PlusRoutes {
    const val WORK = "plus_work_location"
    const val CONNECTIONS = "plus_connections"
    /** [Mines-38.9.5 i] ☰ 「AI 服务」: the services list on its own (no 服务 / 登录 tabs), title 「AI 服务」 */
    const val AI_SERVICES = "plus_ai_services"
    const val PERSONALIZE = "plus_personalize"
    const val VOICE = "plus_voice_live"
    const val SANDBOX = "plus_sandbox"
    const val PLUGINS = "plus_plugins"
    const val MIGRATE = "plus_migrate"
    const val MARKET = "plus_market"
    const val ROUTING = "plus_routing"
    const val TIDY = "plus_tidy"
    const val KNOWLEDGE = "plus_knowledge"
    const val VERIFY = "plus_verify"
    const val UPDATE = "plus_update"
    const val REPORT = "plus_report"
    const val DIGEST = "plus_digest"
    const val CARDS = "plus_cards"
    const val VOICE_SETTINGS = "plus_voice_settings"
    // [Mines-38.9]
    const val KEYCHAIN = "plus_keychain"
    const val KEYCHAIN_DETAIL = "plus_keychain_detail/{id}"
    fun keychainDetail(id: String) = "plus_keychain_detail/" + android.net.Uri.encode(id)
    const val AGENTS = "plus_app_agents"
    const val LOOK = "plus_look"
    const val EXPERIENCE = "plus_experience"
    const val WORKFLOWS = "plus_workflows"   // [Mines-38.9.8] 工作流记录
    const val FREE_AI = "plus_free_ai"   // [Mines-38.9.9] 免登录 AI page (home ☰)
    const val SERVER = "plus_server/{id}"
    const val CODESRC = "plus_code_sources"   // [Mines-38.9.5 i] 验证码与 AI 邮箱
    fun server(id: String) = "plus_server/$id"
}

/** Built-in wallpapers: soft gradients, look good under both light and dark bubbles. */
data class WallpaperPreset(val id: String, val name: String, val colors: List<Color>)

val wallpaperPresets = listOf(
    WallpaperPreset("aurora", "极光", listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364), Color(0xFF3FA08F))),
    WallpaperPreset("sunset", "晚霞", listOf(Color(0xFFFFB199), Color(0xFFFF6F91), Color(0xFF845EC2))),
    WallpaperPreset("ocean", "深海", listOf(Color(0xFF1A2980), Color(0xFF26D0CE))),
    WallpaperPreset("mint", "薄荷", listOf(Color(0xFFE0F7EF), Color(0xFFB8F2E6), Color(0xFFAED9E0))),
    WallpaperPreset("peach", "蜜桃", listOf(Color(0xFFFFF1EB), Color(0xFFFFD6C9), Color(0xFFF7B7A3))),
    WallpaperPreset("night", "星夜", listOf(Color(0xFF141E30), Color(0xFF243B55))),
    WallpaperPreset("lavender", "薰衣草", listOf(Color(0xFFE6DEFF), Color(0xFFC3B1E1), Color(0xFF9D8EC7))),
    WallpaperPreset("forest", "森林", listOf(Color(0xFF134E5E), Color(0xFF71B280))),
)

fun wallpaperFile(context: android.content.Context) = File(context.filesDir, "plus_wallpaper.jpg")

/** Loads the custom wallpaper image, downsampled to screen size. */
@Composable
fun rememberWallpaperBitmap(spec: String): ImageBitmap? {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, spec) {
        value = if (!spec.startsWith("file:")) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = wallpaperFile(context)
                if (!f.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val target = maxOf(context.resources.displayMetrics.heightPixels, 1)
                var sample = 1
                while (bounds.outHeight / (sample * 2) >= target) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

/** Chat background. Draws nothing when no wallpaper is chosen. */
@Composable
fun ChatWallpaperLayer(modifier: Modifier = Modifier, home: Boolean = false) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    val spec = st.wallpaper
    if (spec.isEmpty() || (home && !st.wallpaperOnHome)) return
    val m = mines()
    val dark = m.dark
    // [Mines-38.1] 「满满的廉价感」 (user 16:07, a bright pink anime wallpaper behind near-clear cards):
    // the picture is now heavily blurred (48 dp, Android 12+) and covered by a warm porcelain veil —
    // ~70 % at the very top so it shows faintly behind the avatar, ≥ 88 % from the header down, so
    // body text never sits on a clear picture. Android ≤ 11 cannot blur: the veil is 93 % there.
    val canBlur = android.os.Build.VERSION.SDK_INT >= 31
    Box(modifier) {
        // [Mines-38.9.5 i11] no full-screen RenderEffect: a preset is already a smooth gradient (the photo uses the baked frost)
        val imgMod = Modifier.fillMaxSize()
        if (spec.startsWith("preset:")) {
            val p = wallpaperPresets.firstOrNull { it.id == spec.removePrefix("preset:") }
            if (p != null) Box(imgMod.background(Brush.linearGradient(p.colors.map { it.copy(alpha = st.wallpaperAlpha.coerceIn(0f, 1f)) })))
        } else {
            val bmp = rememberWallpaperBitmap(spec)
            // [Mines-38.6] smooth frost: a 48 dp RenderEffect over the full-size photo showed a fine grid / dither on the
            // emulator (GPU blur of a high-frequency picture). The photo is first shrunk to ~1/16 with filtering (a cheap
            // pre-blur), drawn back up with high-quality filtering and then only lightly blurred.
            // [Mines-38.9.5 i] 38.9.4 smoke: 外观调节 over a photo ran ~200 ms / frame on the emulator (and it died there twice) —
            // a full-screen RenderEffect blur re-ran on every frame of the animated page. The frost is now baked ONCE off the
            // main thread into a small smooth bitmap (shrink + 3-pass box blur) and drawn as a plain image: no per-frame blur.
            val frost = rememberFrostedBitmap(bmp)
            if (frost != null) Image(frost, null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alpha = st.wallpaperAlpha.coerceIn(0f, 1f), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        }
        val veil = if (dark) Color(0xFF14110F) else Color(0xFFF6F2EC)
        val userDim = st.wallpaperDim.coerceIn(0f, 0.95f)
        // [Mines-38.9.4] the cards are frosted glass now (they carry the legibility), so the veil over the blurred photo is lighter
        val top = maxOf(if (canBlur) 0.62f else 0.80f, userDim)
        val body = maxOf(if (canBlur) 0.70f else 0.86f, userDim)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to veil.copy(alpha = top), 0.16f to veil.copy(alpha = top), 0.30f to veil.copy(alpha = body), 1f to veil.copy(alpha = body))))
    }
}

/**
 * [Mines-wallpaper-edge] Fill for cards and rows on the conversation list:
 * semi-transparent over a home wallpaper so the picture shows through,
 * unchanged otherwise.
 */
@Composable
fun homeCardColor(@Suppress("UNUSED_PARAMETER") base: Color): Color = minesGlassFill()  // [Mines-38.9.4] frosted glass, not a 94 % white card

/** True when a wallpaper is drawn on this screen (bars then go translucent). */
@Composable
fun wallpaperOn(home: Boolean = false): Boolean {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    return st.wallpaper.isNotEmpty() && (!home || st.wallpaperOnHome)
}

/**
 * The avatar: round head crop by default ([face] = false shows the full-body
 * character). Uses the same renderer as the desk pet, so custom GIF / animated
 * WebP pictures animate here too.
 */
@Composable
fun PetAvatar(
    size: Dp,
    style: String? = null,
    color: Int? = null,
    animated: Boolean = true,
    face: Boolean = true,
    crop: com.openminis.app.plus.AvatarCrop? = null,
    noBg: Boolean? = null,
    version: Long? = null,
    // [Mines-avatars-24] ring = draw the thin accent ring (off in the picker,
    // where the tile border shows the selection); fullBody = the full-length
    // art instead of the chibi when face is false.
    ring: Boolean = true,
    fullBody: Boolean = false,
    expression: String = com.openminis.app.plus.Avatars.NEUTRAL,
) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { com.openminis.app.plus.AvatarView(it) },
        update = { v ->
            v.style = style; v.face = face; v.bob = animated && !face
            v.showRing = face && ring; v.crop = crop; v.noBg = noBg; v.version = version
            v.fullBody = fullBody; v.expression = expression
            v.tag = st // re-run update (and redraw) whenever prefs change
            v.invalidate()
        },
        modifier = Modifier.size(size),
    )
}

// ── [Mines-design-33] One design system for every Plus page ────────────────
// User, 2026-10-13: 「设计就设计风格好点，简陋得我都看不下去」. Reference: Cue settings / feed.
// Pages sit on the home wallpaper; sections are frosted 22 dp cards with a soft shadow;
// a large bold title; rows have a line icon in a tinted circle, title 16 sp, muted
// 14 sp subtitle, an optional right-side value and a chevron; buttons are pills.

/** [Mines-design-34] Accent for icons and links: the character's link colour (≥ 5.3:1 on porcelain). */
@Composable
fun minesAccent(): Color = mines().icon  // [Mines-warm-36] icons #B5532F / #E08A6B

@Composable
fun minesMuted(): Color = mines().ink2

/** Page backdrop: the user's wallpaper when it is on for home, else the porcelain 「暖雾」. */
@Composable
fun MinesBackdrop(modifier: Modifier = Modifier) {
    if (wallpaperOn(home = true)) {
        Box(modifier) {
            PorcelainBackdrop(Modifier.fillMaxSize(), animate = false)   // [Mines-38.9.5 i11] under the photo: one still frame (the overlay fog moves)
            ChatWallpaperLayer(Modifier.fillMaxSize(), home = true)
            // [Mines-38.9.1] user: 「在添加背景也要有雾气出现」 — a lighter fog layer drifts over the photo
            PorcelainBackdrop(Modifier.fillMaxSize(), overlay = true)
        }
    } else PorcelainBackdrop(modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusPage(title: String, onBack: () -> Unit, subtitle: String? = null, actions: (@Composable () -> Unit)? = null,
             backdrop: (@Composable () -> Unit)? = null,   // [Mines-38.8 v5] 知识库 脑图 draws its own deep-space page
             bottomBar: (@Composable () -> Unit)? = null,  // [Mines-38.9.1] fixed glass bar (账号详情 打开网站 / 改密码, mockup kc-04)
             clearHeader: Boolean = false,                 // [Mines-38.9.15.1] 任务 / 应用: transparent header, only HeaderFade (mock 3915-1b)
             content: @Composable ColumnScope.() -> Unit) {
    // [Mines-design-34] 「暖瓷 · 毛玻璃」: backdrop, 60 dp bar with back + 22 sp bold title, glass sections.
    val m = mines()
    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) backdrop() else MinesBackdrop(Modifier.fillMaxSize())
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(title, fontSize = MT.PageTitle, lineHeight = MT.TitleLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            MinesIcon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = m.ink)
                        }
                    },
                    actions = { actions?.invoke() },
                    modifier = if (clearHeader) Modifier.background(HeaderFade.brush()) else Modifier,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                )
            },
            bottomBar = { bottomBar?.invoke() },
        ) { padding ->
            // [Mines-38.5] content scrolling under the bar fades out instead of being cut hard (user list C)
            // [Mines-38.9.5 i11] the fog holds still while the page scrolls (less GPU work per scroll frame; 外观调节 crash)
            val fogHold = androidx.compose.runtime.remember { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    FogClock.hold(); return androidx.compose.ui.geometry.Offset.Zero
                }
            } }
            Box(Modifier.fillMaxSize().padding(padding).nestedScroll(fogHold)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = maxOf(96.dp, chatHomeDockPad())),   // [Mines-38.9.1] last section never sits under the gesture bar; [38.9.14.1] nor the dock
                ) {
                    // [Mines-38.5] A subtitle that needs two lines breaks at the punctuation nearest its middle (「…；」 / 「…，」),
                    // never mid-word (「一 / 键连接」) and never with one orphan character on line 2 (「…悬浮挂 / 件」).
                    if (subtitle != null) {
                        val halves = androidx.compose.runtime.remember(subtitle) { subtitleHalves(subtitle) }
                        var twoLines by androidx.compose.runtime.remember(subtitle) { androidx.compose.runtime.mutableStateOf(false) }
                        // [Mines-38.9.1] 38.9 review: subtitles on 外观调节 / 经验 were light grey tight under the bar → ink, more air
                        Text(if (twoLines && halves != null) halves else subtitle, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink.copy(alpha = 0.86f),
                            style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                            onTextLayout = { r -> if (!twoLines && halves != null && r.lineCount > 1) twoLines = true },
                            // [Mines-38.9.4] 知识库 星空 (own backdrop): the line sits on a frosted pill so it reads over the glow
                            modifier = if (backdrop != null) Modifier.padding(horizontal = MS.PageH).padding(top = 6.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF16121A).copy(alpha = 0.55f))
                                .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
                            else Modifier.padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 8.dp))
                    }
                    content()
                }
                if (backdrop == null) {
                    // [Mines-38.9.15.1] the opaque porcelain top fade read as a beige band under a clear header → skipped there
                    if (!clearHeader) EdgeFade(top = true, modifier = Modifier.align(Alignment.TopCenter))
                    EdgeFade(top = false, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

/** [Mines-38.8 v5d] warm porcelain tint of the light-theme glass (v2 design's peach-tinted cards). */
val LightGlassTint = GlassTint.Light   // [Mines-38.9.6 i12] 暖瓷 warm ivory #F3EFE9 (38.9.4–38.9.5: #FFF5EF read pink-red on the phone)

/** Glass section card fill: translucent porcelain, opaque enough that ink stays ≥ 4.5:1 over any backdrop. */
@Composable
fun minesGlassFill(strong: Boolean = false, bands: IntRange? = null): Color {
    val m = mines()
    val photo = wallpaperOn(home = true)
    val tint = GlassTint.of(m.dark)
    // [Mines-38.9.4] 自动 (default): the most transparent tint that keeps ink2 ≥ 4.5:1 over what is behind THIS card
    // ([bands] = the screen bands the card covers; null = the worst band on screen). See GlassAuto.
    if (MinesLook.glassAuto(m.dark)) {
        val (per, all) = glassAutoAlphas(m.dark, tint, m.ink2)
        val a = bands?.let { r -> r.mapNotNull { per.getOrNull(it) }.maxOrNull() } ?: all
        // [Mines-38.9.5 i] never denser than the cap (light 45 % / dark 50 %), strong variant included
        return tint.copy(alpha = (a + (if (strong) 0.05f else 0f)).coerceIn(GlassFloor.card(m.dark), com.openminis.app.plus.GlassAuto.cap(m.dark)))   // [Mines-38.9.6] floor
    }
    // [Mines-38.9.4] user 2026-10-17 「要磨砂底，不要白底」 (approved mock-27): ONE frosted-glass token for cards, lists, bars,
    // menus — warm white ~58 % light / dark tint ~50 % over the (already blurred) fog or photo wallpaper, 1 dp white
    // hairline. Over a photo it was 94 % white (the 「白底」); the photo is blurred + veiled underneath, so it is frosted
    // glass there too, a little denser. Ink2 on it stays ≥ 4.5:1. Reading surfaces (md / code / tables) stay opaque.
    val a = when {
        m.dark -> if (strong) 0.56f else 0.50f
        else -> if (strong) 0.64f else 0.58f
    } + (if (photo) 0.08f else 0f)
    val k = MinesLook.glassScale(m.dark)   // [Mines-38.9] 外观调节 › 玻璃透明度 (手动)
    return tint.copy(alpha = (a * k).coerceIn(GlassFloor.card(m.dark), 0.98f))   // [Mines-38.9.6] floor: never 「透穿」
}

/** A frosted 22 dp card (no blur pass: translucent fill + 1 dp highlight + soft warm shadow). */
@Composable
fun MinesCard(modifier: Modifier = Modifier, strong: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(MS.CardRadius)
    // [Mines-38.9.4] glass 自动: which screen bands this card covers (updated only when that changes, not every scroll frame)
    var bands by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<IntRange?>(null) }
    val screenH = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
    Column(
        modifier.onGloballyPositioned { c ->
            val r = c.boundsInRoot(); val n = com.openminis.app.plus.GlassAuto.BANDS
            val b = (r.top / screenH * n).toInt().coerceIn(0, n - 1)..(r.bottom / screenH * n).toInt().coerceIn(0, n - 1)
            if (b != bands) bands = b
        }.shadow(0.dp /* [Mines-38.8] glass: an elevation shadow would show through as a grey core */, shape, clip = false, ambientColor = Color(0xFF3C2814).copy(alpha = 0.10f), spotColor = Color(0xFF3C2814).copy(alpha = 0.12f))
            .clip(shape).background(minesGlassFill(strong, bands)).border(1.dp, m.glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // [Mines-38.9.5 i] glass 自动 over a hard spot: a soft halo in the glass tint behind the text rows of THIS card
        // (only on the bands that need it) instead of whitening the whole card
        val halo = glassAutoHalo(m.dark, bands)
        if (halo > 0f) {
            val tint = GlassTint.of(m.dark)
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
            val base = androidx.compose.material3.LocalTextStyle.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides base.copy(
                shadow = androidx.compose.ui.graphics.Shadow(tint.copy(alpha = halo), androidx.compose.ui.geometry.Offset.Zero, px))) { content() }
        } else content()
    }
}

@Composable
fun PlusSection(title: String?, footer: String? = null, action: String? = null, onAction: (() -> Unit)? = null,
                /** [Mines-38.9.4] footer that carries real instructions: full-contrast ink2, never clipped. */
                footerStrong: Boolean = false,
                content: @Composable ColumnScope.() -> Unit) {
    val m = mines()
    Column(Modifier.fillMaxWidth().padding(top = MS.L)) {
        // [Mines-38.9.1] section label + footer start where PlusRow content starts (20 dp card inset + 16 dp row inset)
        // [Mines-38.9.5 i] user 22:24: no explanation paragraphs under cards — the footer text sits behind a small ⓘ
        // (in the section label row; a label-less section gets the ⓘ alone at the right)
        if (title != null || footer != null) Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 20.dp, bottom = if (title != null) 8.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (title != null) Text(title, fontSize = MT.Section, color = m.ink2, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            if (action != null && onAction != null) Text(action, fontSize = MT.Sub, color = m.primaryText, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
            if (footer != null) InfoDot(title ?: "说明", footer)
        }
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
        @Suppress("UNUSED_VARIABLE") val strongUnused = footerStrong
    }
}

/** [Mines-38.9.5 i] small ⓘ (16 dp icon, 32 dp touch target) → the explanation in a glass dialog. Nothing on screen otherwise. */
@Composable
fun InfoDot(title: String, text: String, modifier: Modifier = Modifier) {
    val m = mines()
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier.size(32.dp).clip(CircleShape).clickable { open = true }.semantics { contentDescription = "说明：$title" },
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Info, null, tint = m.ink2, modifier = Modifier.size(16.dp))
    }
    if (open) MinesDialog(title, onDismiss = { open = false }, dismiss = "知道了") {
        Text(text, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
    }
}

/** Line icon in a tinted circle (40 dp; icon in the tint, circle in its light container). */
@Composable
fun IconBubble(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = minesAccent(), size: Dp = 36.dp, fill: Color? = null) {
    val m = mines()
    val bg = fill ?: if (tint == minesAccent()) m.container else tint.copy(alpha = if (m.dark) 0.22f else 0.13f)
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        MinesIcon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Icon circle in one of the semantic tones (要处理 = danger, 余额 = warning, 可薅 = success, 可学 = info …). */
@Composable
fun ToneBubble(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, size: Dp = 40.dp) {
    val (fg, bg) = toneColors(tone)
    IconBubble(icon, fg, size, bg)
}

/** The standard row: icon bubble · title / subtitle · value · chevron (min 64 dp). */
@Composable
fun PlusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    sub: String? = null,
    value: String? = null,
    tint: Color = minesAccent(),
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val m = mines()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconBubble(icon, tint); Spacer(Modifier.width(MS.M)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
            if (!sub.isNullOrBlank()) Text(sub, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph))
        }
        if (value != null) Text(value, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp))
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        else if (chevron && onClick != null) MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("chevron-right")!!, null, tint = m.ink3,
            modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

/** Status dot + word (能用 / 连不上 …). */
@Composable
fun StatusPill(text: String, ok: Boolean?) {
    val (c, bg) = toneColors(when (ok) { true -> Tone.SUCCESS; false -> Tone.DANGER; null -> Tone.NEUTRAL })
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = MT.Meta, lineHeight = MT.CaptionLH, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, softWrap = false)
    }
}

/** Pill buttons: primary (character colour, white text ≥ 4.8:1) and secondary (quiet fill + ink). 56 dp. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    val bg = when { primary -> m.primary; else -> if (m.dark) Color(0x1FFFFFFF) else Color(0x141C1915) }
    val fg = when { primary -> m.onPrimary; else -> m.ink }
    Box(
        modifier.heightIn(min = MS.ButtonH).graphicsLayerAlpha(if (enabled) 1f else 0.38f)
            .shadow(if (primary && enabled && !m.dark) 6.dp else 0.dp, shape, clip = false, spotColor = m.primary.copy(alpha = 0.35f), ambientColor = m.primary.copy(alpha = 0.2f))
            .clip(shape).background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false) }
}

/**
 * [Mines-38.9.1] user 2026-10-16 「登录和对话应该不同颜色」: the row action pill. [primary] = filled accent (聊天 / 测试,
 * already signed in); otherwise an outlined tonal pill — frosted glass, 1.5 dp accent outline, accent text (登录 / 去登录).
 */
@Composable
fun ActionPill2(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    val base = Modifier.widthIn(min = 84.dp).heightIn(min = 40.dp).clip(shape)
    val mod = if (primary) base.background(m.primary) else base.background(minesGlassFill(true)).border(1.5.dp, m.primary.copy(alpha = if (m.dark) 0.85f else 0.75f), shape)
    Box(mod.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = text }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = if (primary) m.onPrimary else m.primaryText, maxLines = 1, softWrap = false)
    }
}

/** [Mines-38.9.1] small outlined pill for secondary row actions (忽略 / 恢复): 32 dp, 13 sp — a row's trailing action
 *  must not outweigh the row (user 2026-10-17: 忽略 buttons were oversized). */
@Composable
fun SmallPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    Box(
        modifier.heightIn(min = 32.dp).clip(shape).border(1.dp, m.ink3.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.ink2, maxLines = 1, softWrap = false) }
}

private fun Modifier.graphicsLayerAlpha(a: Float): Modifier = if (a >= 1f) this else this.then(Modifier.graphicsLayer { alpha = a })

/** Short hint line with an ⓘ, instead of long paragraphs. */
@Composable
fun InfoLine(text: String) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text("ⓘ", fontSize = MT.Caption, color = m.ink3)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink3)
    }
}

// ── [Mines-a11y-26] Readable text on cards ───────────────────────────────
// The user is partially sighted: card text must reach WCAG AA (4.5:1). build-25's
// home status chips left the value text uncoloured, so it inherited a dark grey
// and sat on a near-black chip (≈1.5:1). CI greps for these helpers.

/** WCAG contrast ratio between two opaque colours (1…21). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
    val hi = maxOf(la, lb); val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** White or black, whichever reads better on [bg]. */
fun autoTextOn(bg: Color): Color = if (contrastRatio(Color.White, bg) >= contrastRatio(Color.Black, bg)) Color.White else Color.Black

/**
 * Text colour for a home card whose (possibly translucent) fill is [fill]:
 * the fill is composited over the theme surface first, then the user's choice
 * (设置 → 壁纸与形象 → 卡片文字颜色) applies; 自动 picks white/black by brightness.
 */
@Composable
fun cardTextColor(fill: Color): Color {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val bg = LocalCardSurface.current ?: fill.compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    return when (st.cardTextMode) {
        "white" -> Color.White
        "black" -> Color.Black
        "custom" -> Color(st.cardTextColor)
        else -> autoTextOn(bg)
    }
}

/** Secondary text (labels, timestamps) that still passes 4.5:1 on [bg]: dimmed only while it stays readable. */
fun readableSecondary(fg: Color, bg: Color): Color {
    for (a in floatArrayOf(0.8f, 0.9f)) {
        val c = fg.copy(alpha = a).compositeOver(bg)
        if (contrastRatio(c, bg) >= 4.5) return fg.copy(alpha = a)
    }
    return fg
}

/** Secondary text on the theme surface (conversation list timestamps). */
@Composable
fun readableSecondary(): Color {
    val cs = MaterialTheme.colorScheme
    return readableSecondary(cs.onSurface, cs.surface)
}


/** [Mines-38.5] Soft fade at the top / bottom edge of a scrolling page (porcelain → transparent, 18 dp). */
@Composable
fun EdgeFade(top: Boolean, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    val m = mines()
    val c = if (m.dark) MinesDesign.BgDark else Color(0xFFF7F1EB)
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(
        if (top) listOf(c, c.copy(alpha = 0f)) else listOf(c.copy(alpha = 0f), c))))
}


/** [Mines-38.5] 「A；B」 → 「A；\nB」 at the punctuation nearest the middle, or null when there is none to break at. */
internal fun subtitleHalves(s: String): String? {
    if ('\n' in s || s.length < 12) return null
    val mid = s.length / 2
    val i = s.indices.filter { s[it] in "；，。、" && it in 4 until s.length - 4 }.minByOrNull { kotlin.math.abs(it - mid) } ?: return null
    return s.substring(0, i + 1) + "\n" + s.substring(i + 1).trimStart()
}


/**
 * [Mines-38.6] A heavily downsampled copy of [bmp] (longest side ≈ 64 px, two filtered halving steps so no aliasing),
 * used as a pre-blurred backdrop: upscaled with bilinear filtering it is already smooth, so the GPU blur on top can stay
 * small and shows no grid / dither pattern.
 */
@Composable
fun rememberSoftBitmap(bmp: androidx.compose.ui.graphics.ImageBitmap?): androidx.compose.ui.graphics.ImageBitmap? = androidx.compose.runtime.remember(bmp) {
    if (bmp == null) null else runCatching {
        var b = bmp.asAndroidBitmap()  // asAndroidBitmap: androidx.compose.ui.graphics
        val target = 64
        while (maxOf(b.width, b.height) / 2 >= target) {
            b = android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, b.width / 2), maxOf(1, b.height / 2), true)
        }
        b.asImageBitmap()
    }.getOrNull() ?: bmp
}


/** [Mines-38.9.5 i] the photo frost baked once (Dispatchers.Default): ~1/16 shrink, then 3 box-blur passes at ~160 px wide. */
@Composable
fun rememberFrostedBitmap(bmp: androidx.compose.ui.graphics.ImageBitmap?): androidx.compose.ui.graphics.ImageBitmap? {
    val out by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bmp) {
        value = if (bmp == null) null else withContext(Dispatchers.Default) { runCatching { frostBitmap(bmp.asAndroidBitmap()).asImageBitmap() }.getOrNull() }
    }
    return out
}

internal fun frostBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
    var b = src
    while (maxOf(b.width, b.height) / 2 >= 96) b = android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, b.width / 2), maxOf(1, b.height / 2), true)
    val w = 160; val h = maxOf(1, (160f * b.height / maxOf(1, b.width)).toInt())
    val s = android.graphics.Bitmap.createScaledBitmap(b, w, h, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, 6) }
    s.setPixels(px, 0, w, 0, 0, w, h)
    return s
}

/** One horizontal + one vertical box pass of [r] px, in place (clamped edges). */
internal fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size); val n = 2 * r + 1
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
        for (l in 0 until lines) {
            var a = 0; var rr = 0; var g = 0; var bb = 0
            val base = l * lineStep
            for (k in -r..r) { val c = src[base + k.coerceIn(0, len - 1) * step]; a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF }
            for (i in 0 until len) {
                dst[base + i * step] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
                val o = src[base + (i - r).coerceIn(0, len - 1) * step]; val ni = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                a += (ni ushr 24) - (o ushr 24); rr += ((ni shr 16) and 0xFF) - ((o shr 16) and 0xFF)
                g += ((ni shr 8) and 0xFF) - ((o shr 8) and 0xFF); bb += (ni and 0xFF) - (o and 0xFF)
            }
        }
    }
    pass(px, tmp, w, h, 1, w)      // rows
    pass(tmp, px, h, w, w, 1)      // columns
}
