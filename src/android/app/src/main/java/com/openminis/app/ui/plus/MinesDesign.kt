package com.openminis.app.ui.plus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.PlusPrefs

/**
 * [Mines-design-34] 「暖瓷 · 毛玻璃」 design system (user-approved 2026-10-13,
 * /workspace design33/design-system.md). Warm porcelain background, soft
 * gradient wallpaper with four blurred colour blobs, translucent 22 dp cards,
 * body 17 sp (min 15 sp), all text ≥ 4.5:1.
 *
 * Per-character themes (user, 2026-10-13): the desk-pet character picks the
 * accent (buttons, links, icon circles), the wallpaper glow and the voice
 * rings. Claude = terracotta, 小鲸 = sea blue, the others from their hair
 * colour. Every primary was chosen so white text on it is ≥ 4.8:1, and the
 * link colour is ≥ 5.3:1 on the porcelain background (dark: ≥ 8:1).
 */
@Immutable
data class CharacterTheme(
    val id: String,
    val primary: Color, val primaryText: Color, val container: Color, val onContainer: Color, val brand: Color,
    val darkPrimary: Color, val darkOnPrimary: Color, val darkContainer: Color, val darkOnContainer: Color,
    val blobs: List<Color>, val darkBlobs: List<Color>, val ring: Color,
)

object MinesDesign {
    // Porcelain neutrals (light / dark)
    val Bg = Color(0xFFF6F2EC); val BgDark = Color(0xFF14110F)
    val Surface = Color(0xFFFBF8F4);  // [Mines-38.1] no pure-white blocks
    @Suppress("unused") private val surfaceWas = Color(0xFFFFFFFF); val SurfaceDark = Color(0xFF1F1B18)
    val Surface2 = Color(0xFFEFEAE3); val Surface2Dark = Color(0xFF2A2521)
    val Glass = Color(0x94F3EFE9);  // [Mines-38.9.6] 暖瓷 tint (was 0x94FFF5EF)
    val GlassDark = Color(0x80252220)          // [Mines-38.9.4] .50 frosted (was .94)
    val GlassStrong = Color(0xA3F3EFE9);  // [Mines-38.8 v5d] 35 % (was .95)
    val GlassStrongDark = Color(0x8F252220) // .56
    val GlassBorder = Color(0x80FFFFFF);  // [Mines-38.9.4] mock-27: 1 dp white hairline
    val GlassBorderDark = Color(0x29FFFFFF)   // [Mines-38.9.4] visible light hairline on dark glass
    val Hairline = Color(0x171C1915); val HairlineDark = Color(0x17FFFFFF)
    // [Mines-38.8] liquid-glass v2 ink (user-approved direction 2026-10-16): warm brown-black body, warm secondary
    val Ink = Color(0xFF2A1E1A); val InkDark = Color(0xFFF6F0EB)
    val Ink2 = Color(0xFF6B5A50); val Ink2Dark = Color(0xFFCDC0B8)
    val Ink3 = Color(0xFF6B5A50); val Ink3Dark = Color(0xFFC4B8B0)
    // Semantic (text colour on its container ≥ 5:1)
    val Success = Color(0xFF1E6B40); val SuccessC = Color(0xFFE1F2E7)
    val Warning = Color(0xFF8A4E00); val WarningC = Color(0xFFFCEFD9)
    val Danger = Color(0xFFB3261E); val DangerC = Color(0xFFFBE3E0)
    val DangerFill = Color(0xFFC9372C)
    val Info = Color(0xFF2F5FB3); val InfoC = Color(0xFFE3ECFA)
    val Violet = Color(0xFF6A4BB0); val VioletC = Color(0xFFECE6F8)
    val Teal = Color(0xFF16676B); val TealC = Color(0xFFDDF0EF)
    val SuccessDark = Color(0xFF7FD3A0); val SuccessCDark = Color(0xFF173A26)
    val WarningDark = Color(0xFFF2B866); val WarningCDark = Color(0xFF3D2A0E)
    val DangerDark = Color(0xFFFF9C92); val DangerCDark = Color(0xFF4A1A16)
    val InfoDark = Color(0xFF9DBBF2); val InfoCDark = Color(0xFF1B2A47)
    // Voice page
    val VoiceBg = Color(0xFF2A1C2C)
    val VoiceGlass = Color(0x21FFFFFF); val VoiceGlassBorder = Color(0x38FFFFFF)
    val HangUp = Color(0xFFE5484D); val OnlineDot = Color(0xFF7CE3A2)

    val themes: Map<String, CharacterTheme> = mapOf(
        "claude" to CharacterTheme("claude", Color(0xFFB5532F), Color(0xFFA84B2A), Color(0xFFFBE6DC), Color(0xFF7A2E14), Color(0xFFD97757),
            Color(0xFFE08A6B), Color(0xFF2A1206), Color(0xFF4A2416), Color(0xFFFFD9CA),
            listOf(Color(0xFFF9D9C6), Color(0xFFE9DDF3), Color(0xFFF6CDB8), Color(0xFFDCE9E1)), listOf(Color(0xFF4A2B20), Color(0xFF2E2440), Color(0xFF4A2618), Color(0xFF1C2E27)), Color(0xFFFFC4AA)),
        "whale" to CharacterTheme("whale", Color(0xFF2E73C2), Color(0xFF2865AB), Color(0xFFD9E9FC), Color(0xFF1C4778), Color(0xFF3C8EEC),
            Color(0xFF7FADE1), Color(0xFF0C1D32), Color(0xFF1D3249), Color(0xFFA0C2E9),
            listOf(Color(0xFFC9DCF3), Color(0xFFDFDBF0), Color(0xFFC1E3F1), Color(0xFFEADCDC)), listOf(Color(0xFF1F3651), Color(0xFF2A2442), Color(0xFF1C3D4A), Color(0xFF301D1D)), Color(0xFF97C4F7)),
        "grok" to CharacterTheme("grok", Color(0xFF68728A), Color(0xFF5B6479), Color(0xFFE5E8F1), Color(0xFF404654), Color(0xFF6F85B8),
            Color(0xFFA5ACBB), Color(0xFF1A1D23), Color(0xFF2E3138), Color(0xFFBCC1CD),
            listOf(Color(0xFFD4DAE8), Color(0xFFE3DBF0), Color(0xFFCCDAE6), Color(0xFFEADFDC)), listOf(Color(0xFF1F2E51), Color(0xFF2F2442), Color(0xFF1C354A), Color(0xFF30211D)), Color(0xFFB3BFDB)),
        "gpt" to CharacterTheme("gpt", Color(0xFF746BA1), Color(0xFF665D92), Color(0xFFE6E3F2), Color(0xFF464064), Color(0xFF7667C1),
            Color(0xFFADA8C7), Color(0xFF1A1825), Color(0xFF2E2B3B), Color(0xFFBCB7D1),
            listOf(Color(0xFFD6D2EA), Color(0xFFECDBF0), Color(0xFFCACDE7), Color(0xFFEAE5DC)), listOf(Color(0xFF271F51), Color(0xFF3D2442), Color(0xFF1C204A), Color(0xFF30291D)), Color(0xFFBAB3DB)),
        "gemini" to CharacterTheme("gemini", Color(0xFF835EC2), Color(0xFF744CBB), Color(0xFFE7DCF9), Color(0xFF4F3181), Color(0xFF814EDA),
            Color(0xFFB59FDB), Color(0xFF1B112C), Color(0xFF2F2343), Color(0xFFC0AEE0),
            listOf(Color(0xFFD9CDEF), Color(0xFFF0DBEF), Color(0xFFCAC5ED), Color(0xFFEAE8DC)), listOf(Color(0xFF311F51), Color(0xFF422441), Color(0xFF211C4A), Color(0xFF302D1D)), Color(0xFFBA97F7)),
        "kimi" to CharacterTheme("kimi", Color(0xFF427A85), Color(0xFF396A74), Color(0xFFDFF3F7), Color(0xFF2C5159), Color(0xFF5ABACE),
            Color(0xFF7DB4BF), Color(0xFF142629), Color(0xFF273B3F), Color(0xFFAACED5),
            listOf(Color(0xFFCFE8EC), Color(0xFFDBDFF0), Color(0xFFC7EAE7), Color(0xFFEADCE1)), listOf(Color(0xFF1F4951), Color(0xFF242942), Color(0xFF1C4A46), Color(0xFF301D23)), Color(0xFF97E7F7)),
        "wenxin" to CharacterTheme("wenxin", Color(0xFF5E6BC2), Color(0xFF4C5BBB), Color(0xFFDCE0F9), Color(0xFF313C81), Color(0xFF4E61DA),
            Color(0xFF9FA7DB), Color(0xFF11152C), Color(0xFF232743), Color(0xFFAEB4E0),
            listOf(Color(0xFFCDD1EF), Color(0xFFE6DBF0), Color(0xFFC5D4ED), Color(0xFFEAE1DC)), listOf(Color(0xFF1F2651), Color(0xFF342442), Color(0xFF1C2E4A), Color(0xFF30241D)), Color(0xFF97A4F7)),
    )

    /** Theme for a character id ("custom" and unknown ids → Claude, the brand colours). */
    fun theme(style: String?): CharacterTheme = themes[style] ?: themes.getValue("claude")
}

/** The accent family in use (follows the desk-pet character). */
@Immutable
data class MinesColors(
    val dark: Boolean, val character: CharacterTheme,
    val bg: Color, val surface: Color, val surface2: Color, val glass: Color, val glassStrong: Color, val glassBorder: Color,
    val hairline: Color, val ink: Color, val ink2: Color, val ink3: Color,
    val primary: Color, val onPrimary: Color, val primaryText: Color, val container: Color, val onContainer: Color,
    val brand: Color, val blobs: List<Color>, val ring: Color,
    /** [Mines-warm-36] line-icon colour: light #B5532F, dark #E08A6B (Claude). */
    val icon: Color = primaryText,
)

fun minesColors(style: String?, dark: Boolean): MinesColors = MinesLook.apply(minesColorsBase(style, dark))   // [Mines-38.9] 外观调节

private fun minesColorsBase(style: String?, dark: Boolean): MinesColors {
    val t = MinesDesign.theme(style)
    return if (dark) MinesColors(
        true, t, MinesDesign.BgDark, MinesDesign.SurfaceDark, MinesDesign.Surface2Dark, MinesDesign.GlassDark, MinesDesign.GlassStrongDark,
        MinesDesign.GlassBorderDark, MinesDesign.HairlineDark, MinesDesign.InkDark, MinesDesign.Ink2Dark, MinesDesign.Ink3Dark,
        // [Mines-warm-36] buttons keep the deep character colour with white text in dark mode too (#B5532F for Claude)
        t.primary, Color.White, t.darkPrimary, t.darkContainer, t.darkOnContainer, t.brand, t.darkBlobs, t.ring, t.darkPrimary,
    ) else MinesColors(
        false, t, MinesDesign.Bg, MinesDesign.Surface, MinesDesign.Surface2, MinesDesign.Glass, MinesDesign.GlassStrong,
        MinesDesign.GlassBorder, MinesDesign.Hairline, MinesDesign.Ink, MinesDesign.Ink2, MinesDesign.Ink3,
        t.primary, Color.White, t.primaryText, t.container, t.onContainer, t.brand, t.blobs, t.ring, t.primary,
    )
}

/** [Mines-38.7] colours part-way between day [a] and night [b] (the animated sunrise / sunset change). */
fun lerpMines(a: MinesColors, b: MinesColors, f: Float): MinesColors {
    fun l(x: Color, y: Color) = androidx.compose.ui.graphics.lerp(x, y, f)
    return MinesColors(
        f >= 0.5f, a.character, l(a.bg, b.bg), l(a.surface, b.surface), l(a.surface2, b.surface2), l(a.glass, b.glass), l(a.glassStrong, b.glassStrong),
        l(a.glassBorder, b.glassBorder), l(a.hairline, b.hairline), l(a.ink, b.ink), l(a.ink2, b.ink2), l(a.ink3, b.ink3),
        l(a.primary, b.primary), l(a.onPrimary, b.onPrimary), l(a.primaryText, b.primaryText), l(a.container, b.container), l(a.onContainer, b.onContainer),
        l(a.brand, b.brand), a.blobs.mapIndexed { i, c -> l(c, b.blobs.getOrElse(i) { c }) }, l(a.ring, b.ring), l(a.icon, b.icon),
    )
}

val LocalMinesColors = staticCompositionLocalOf { minesColors("claude", false) }

/** Current design colours (provided by MinisTheme from the chosen character). */
@Composable
fun mines(): MinesColors = LocalMinesColors.current

/**
 * [Mines-38.9.1] SURFACE RULE (user 2026-10-17): content / reading surfaces — the file viewer
 * (markdown, code, text, json, csv, html), and any other full-page content that opens in its own
 * window or over the chat — are OPAQUE and painted in the current theme's own colour (light, dark,
 * or the 外观调节 custom colours), so the chat or wallpaper never bleeds through and text stays readable.
 * Glass / translucency is only for chrome: the chat input bar and floating overlays (sheets, toasts,
 * menus). Use [minesReadingSurface] for the page and [minesReadingBar] for its top bar.
 */
@Composable
fun minesReadingSurface(): Color = if (mines().dark) ReadingPaperDark else ReadingPaperLight

/** [Mines-38.9.4] warm paper for reading surfaces (user: 文档不要透明到重叠): fully opaque, never fog behind text. */
val ReadingPaperLight = Color(0xFFFFFCF9)
val ReadingPaperDark = Color(0xFF24201F)

/** Opaque top-bar colour for reading surfaces (one step off the page so the bar edge is visible). */
@Composable
fun minesReadingBar(): Color = mines().let { m -> m.surface.copy(alpha = 0.55f).compositeOver(minesReadingSurface()) }

/** Semantic icon-circle colours: (icon tint, circle fill). */
enum class Tone { DANGER, WARNING, SUCCESS, INFO, VIOLET, TEAL, NEUTRAL, ACCENT }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val m = mines()
    return if (!m.dark) when (tone) {
        Tone.DANGER -> MinesDesign.Danger to MinesDesign.DangerC
        Tone.WARNING -> MinesDesign.Warning to MinesDesign.WarningC
        Tone.SUCCESS -> MinesDesign.Success to MinesDesign.SuccessC
        Tone.INFO -> MinesDesign.Info to MinesDesign.InfoC
        Tone.VIOLET -> MinesDesign.Violet to MinesDesign.VioletC
        Tone.TEAL -> MinesDesign.Teal to MinesDesign.TealC
        Tone.NEUTRAL -> m.ink2 to Color(0x0F1C1915)
        Tone.ACCENT -> m.primaryText to m.container
    } else when (tone) {
        Tone.DANGER -> MinesDesign.DangerDark to MinesDesign.DangerCDark
        Tone.WARNING -> MinesDesign.WarningDark to MinesDesign.WarningCDark
        Tone.SUCCESS -> MinesDesign.SuccessDark to MinesDesign.SuccessCDark
        Tone.INFO -> MinesDesign.InfoDark to MinesDesign.InfoCDark
        Tone.VIOLET -> Color(0xFFC3B0F0) to Color(0xFF2C2343)
        Tone.TEAL -> Color(0xFF86D3D0) to Color(0xFF123536)
        Tone.NEUTRAL -> m.ink2 to Color(0x12FFFFFF)
        Tone.ACCENT -> m.onContainer to m.container
    }
}

/**
 * The default 「暖雾」 wallpaper: warm porcelain gradient + four soft radial
 * blobs tinted by the character (apricot/lavender/peach/sage for Claude).
 * Drawn behind every Plus page and the home list when no photo wallpaper is set.
 */
@Composable
fun PorcelainBackdrop(modifier: Modifier = Modifier, overlay: Boolean = false, preview: FogWind.Sample? = null,
                      /** [Mines-38.9.5 i11] false = draw one still frame (外观调节 preview): no second animation loop on that page */
                      animate: Boolean = true) {
    // [Mines-38.7] user 2026-10-15 「背景像雾一样会动，加入底层 UI，一种朦胧美感」: the warm-porcelain backdrop is a slowly
    // flowing fog — 4 big soft radial light spots (the theme's warm / lilac / mint blobs), each drifting on its own 20–40 s
    // loop with a little scale and opacity breathing, plus two very faint fog bands sliding sideways. Drawn with dithered
    // native radial shaders (no banding / grid), ≈30 fps, only while the app is RESUMED, still when animations are off.
    // Cards stay solid frosted porcelain on top, so text contrast does not change.
    // [Mines-38.9.6 i14] the user-approved weather sky v5 replaces the page fog (not the 外观调节 preview / photo overlay)
    val ctxSky = LocalContext.current
    val skyOn by WeatherSky.tick.collectAsState().let { t -> androidx.compose.runtime.remember(t.value) { androidx.compose.runtime.mutableStateOf(WeatherSky.enabled(ctxSky)) } }
    if (skyOn && !overlay && animate && preview == null) { WeatherSkyBackdrop(modifier); return }
    val m = mines()
    // [Mines-38.8 v4d] liquid-glass v2 fog (mock/liquid.py): porcelain base + 4 big soft blobs (peach / lavender /
    // apricot / rose) that wander 180–250 px (at 1080 px) on a 10 s loop with opacity breathing, plus fine film grain.
    val base = if (m.dark) Color(0xFF161114) else Color(0xFFF4EAE4)
    val ctx = LocalContext.current
    val still = androidx.compose.runtime.remember { AnimeWeather.still(ctx) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val time = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(FogClock.t) }
    // always drifts (the fog is the page, not a UI animation; battery saver sets animator scale 0 on realme)
    // [Mines-38.9.5 i11] 外观调节 killed the emulator 3× (dark): ~20 fps is plenty for a drift of 6–32 px/s, and the fog holds
    // still while a page is being scrolled (FogClock.hold) so a scroll frame never also re-rasterises the whole backdrop
    if (animate) androidx.compose.runtime.LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) { MotionGate.awaitRun(); androidx.compose.runtime.withFrameMillis { now ->
                if (now - last >= 50L) {
                    last = now
                    if (System.currentTimeMillis() >= FogClock.holdUntil) { FogClock.tick(now); time.floatValue = FogClock.t }   // held: no step (≤ 0.1 s catch-up after)
                }
            } }
        }
    }
    val paint = androidx.compose.runtime.remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true } }
    val dark = m.dark
    // [Mines-38.9.8] real phone 01:17 (模型详情, dark, photo wallpaper → this fog drawn over it): maroon / purple at night. When
    // the weather sky is on, the fog takes the CURRENT scene's mist colours (night = deep blue), re-read every minute.
    val skyMin = androidx.compose.runtime.produceState(System.currentTimeMillis() / 60_000L) {
        while (true) { kotlinx.coroutines.delay(60_000L); value = System.currentTimeMillis() / 60_000L }
    }.value
    val sceneCols = androidx.compose.runtime.remember(skyMin, dark) {
        if (!WeatherSky.enabled(ctx)) null else runCatching {
            val sc = WeatherSky.scene(ctx); FogClock.scene = sc.key
            (com.openminis.app.plus.WeatherSkyCore.mist[sc.key] ?: com.openminis.app.plus.WeatherSkyCore.mist.getValue("cloudy")).map { (it and 0xFFFFFF) or 0xFF000000.toInt() }.toIntArray()
        }.getOrNull()
    }
    val cols = sceneCols ?: if (dark) intArrayOf(0xFF963A42.toInt(), 0xFF563296.toInt(), 0xFFA85C1C.toInt(), 0xFF8C2E60.toInt())
        // [Mines-38.8 v5d] user 2026-10-16: light fog read too faint — more saturated peach / lilac / apricot / rose, full opacity
        else intArrayOf(0xFFF0844E.toInt(), 0xFFA07CF0.toInt(), 0xFFFF9F45.toInt(), 0xFFEC72A4.toInt())
    val fogK = MinesLook.fog(dark)   // [Mines-38.9] 外观调节 › 雾气浓淡
    val als = (if (dark) floatArrayOf(0.90f, 0.85f, 0.75f, 0.78f) else floatArrayOf(1.0f, 1.0f, 0.95f, 0.95f)).map { (it * fogK).coerceIn(0f, 1f) }.toFloatArray()
    val grain = androidx.compose.runtime.remember { fogGrain() }
    val sprites = androidx.compose.runtime.remember { FogShaders() }
    val mtx = androidx.compose.runtime.remember { android.graphics.Matrix() }
    val shaft = androidx.compose.runtime.remember { android.graphics.Path() }
    // [Mines-38.9.1] user 2026-10-16 「雾气还要明显点，流动速度，加入天气，风速」: the fog drifts with the real wind (open-meteo
    // wind_speed_10m / direction from the home weather), rain / overcast make it denser, a clear day thinner with two soft
    // light shafts; 外观调节 › 流动速度 (自动 = follow the wind). Over a photo wallpaper a lighter fog layer is drawn ON TOP.
    if (animate) androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { FogWind.refresh(ctx); kotlinx.coroutines.delay(60_000) } }
    val wind = preview ?: FogWind.sample
    val dens = FogWind.density(wind.code) * (if (overlay) 0.55f else 1f)
    Box(if (overlay) modifier else modifier.background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.floatValue
            FogClock.draws++; FogClock.stillSeen = still
            val w = size.width; val h = size.height; val sc = w / 1080f
            // wind drift: px offset that grows with time (wraps), direction = where the wind blows TO
            val flowSp = FogWind.flowPxPerSec(wind) * sc
            val rad = Math.toRadians((wind.dirFrom + 180.0) % 360.0)
            val dx = (flowSp * t * kotlin.math.sin(rad)).toFloat(); val dy = (-flowSp * t * kotlin.math.cos(rad)).toFloat() * 0.35f
            // [Mines-38.9.4] user 「雾的流动要稍微明显」: slower (24 s loop, was 10 s) but ~1.7× wider wander, larger blobs, deeper breathing
            val om = (2f * Math.PI.toFloat() * t / 24f)
            val amp = 1.7f
            // (cx, cy, ax px, ay px, kx, ky, phase, radius × width, breathe phase)
            val spots = arrayOf(
                floatArrayOf(0.24f, 0.17f, 230f, 210f, 1f, 1f, 0.0f, 0.50f, 0.0f),
                floatArrayOf(0.80f, 0.40f, 210f, 250f, 1f, 2f, 1.9f, 0.48f, 1.3f),
                floatArrayOf(0.24f, 0.66f, 230f, 190f, 2f, 1f, 3.6f, 0.46f, 2.6f),
                floatArrayOf(0.78f, 0.88f, 190f, 230f, 1f, 1f, 4.8f, 0.48f, 4.0f),
            )
            val nc = drawContext.canvas.nativeCanvas
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val fall = floatArrayOf(1f, 0.726f, 0.278f, 0.056f, 0f)   // exp(-2 d²/r²) sampled out to 1.6 r
            spots.forEachIndexed { i, sp ->
                val span = w * 1.6f
                val cx = ((sp[0] * w + amp * sp[2] * sc * kotlin.math.cos(sp[4] * om + sp[6]) + dx) % span + span) % span - w * 0.3f
                val cy = sp[1] * h + amp * sp[3] * sc * kotlin.math.sin(sp[5] * om + sp[6]) + (dy % (h * 0.3f))
                val r = sp[7] * w * 1.6f * (1.12f + 0.06f * kotlin.math.sin(om * 0.7f + sp[8]))
                val a = (als[i] * dens * (0.74f + 0.26f * kotlin.math.sin(om + sp[8]))).coerceIn(0f, 1f)
                // [Mines-38.9.5 i11] one cached unit shader per blob, moved / scaled by its local matrix and faded by the paint
                // alpha — no new gradient (= new GPU texture) on every frame (was 4 RadialGradients + 3 LinearGradients × 30 fps)
                val sh = sprites.blob(i, cols[i], fall, stops)
                mtx.reset(); mtx.setScale(r, r); mtx.postTranslate(cx, cy); sh.setLocalMatrix(mtx)
                paint.shader = sh; paint.alpha = (255 * a).toInt().coerceIn(0, 255)
                nc.save(); nc.scale(1f, 1.15f, cx, cy); nc.drawCircle(cx, cy, r, paint); nc.restore()
                paint.alpha = 255
            }
            // drifting fog streaks (soft horizontal bands), faster and longer in wind, more in rain / overcast
            val streakA = ((if (dark) 0.16f else 0.22f) * fogK * dens * (0.6f + 0.4f * FogWind.windK(wind))).coerceIn(0f, 0.6f)
            val sCol = if (dark) android.graphics.Color.rgb(200, 170, 190) else android.graphics.Color.rgb(255, 236, 222)
            // [Mines-38.9.8] real phone: these bands read as bright streaks cutting across cards and inputs → not drawn any more
            for (k in 0 until 0) {
                val yy = h * (0.18f + 0.19f * k) + 30f * sc * kotlin.math.sin(om * 0.5f + k)
                val len = w * (0.9f + 0.5f * FogWind.windK(wind))
                val xx = ((dx * (1.2f + 0.2f * k) + k * w * 0.37f) % (w + len) + (w + len)) % (w + len) - len
                val sh = sprites.streak(sCol)
                mtx.reset(); mtx.setScale(len, 1f); mtx.postTranslate(xx, yy); sh.setLocalMatrix(mtx)
                paint.shader = sh; paint.alpha = (255 * streakA).toInt().coerceIn(0, 255)
                nc.save(); nc.scale(1f, 0.16f, xx + len / 2, yy); nc.drawCircle(xx + len / 2, yy, len / 2, paint); nc.restore()
                paint.alpha = 255
            }
            // a clear day: two soft light shafts from the top
            if (FogWind.clear(wind.code) && !dark && !overlay) {
                val ra = (0.10f * fogK * (0.85f + 0.15f * kotlin.math.sin(om))).coerceIn(0f, 0.3f)
                for (k in 0..1) {
                    val x0 = w * (0.18f + 0.42f * k)
                    paint.shader = android.graphics.LinearGradient(x0, 0f, x0 + w * 0.35f, h * 0.7f,
                        intArrayOf(android.graphics.Color.argb((255 * ra).toInt(), 255, 244, 214), 0), null, android.graphics.Shader.TileMode.CLAMP)
                    shaft.reset(); shaft.moveTo(x0, 0f); shaft.lineTo(x0 + w * 0.16f, 0f); shaft.lineTo(x0 + w * 0.5f, h * 0.75f); shaft.lineTo(x0 + w * 0.22f, h * 0.75f); shaft.close()
                    nc.drawPath(shaft, paint)
                }
            }
            if (overlay) { paint.shader = null; return@Canvas }
            // film grain (static tile, very faint)
            paint.shader = android.graphics.BitmapShader(grain, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            paint.alpha = if (dark) 10 else 14
            nc.drawRect(0f, 0f, w, h, paint)
            paint.alpha = 255
            paint.shader = null
        }
    }
}

/**
 * [Mines-38.9.5 i11] the fog's gradients, made once per colour: unit radial (radius 1 at 0,0) per blob and a unit horizontal
 * streak (0…1), placed each frame with Shader.setLocalMatrix and faded with Paint.alpha (same picture, dithered as before).
 */
private class FogShaders {
    private val blobs = arrayOfNulls<android.graphics.RadialGradient>(4); private val blobCol = IntArray(4)
    private var streak: android.graphics.LinearGradient? = null; private var streakCol = 0
    fun blob(i: Int, c: Int, fall: FloatArray, stops: FloatArray): android.graphics.RadialGradient {
        blobs[i]?.let { if (blobCol[i] == c) return it }
        val arr = IntArray(fall.size) { k -> android.graphics.Color.argb((255 * fall[k]).toInt().coerceIn(0, 255), android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)) }
        return android.graphics.RadialGradient(0f, 0f, 1f, arr, stops, android.graphics.Shader.TileMode.CLAMP).also { blobs[i] = it; blobCol[i] = c }
    }
    fun streak(c: Int): android.graphics.LinearGradient {
        streak?.let { if (streakCol == c) return it }
        val mid = android.graphics.Color.argb(255, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
        return android.graphics.LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0, mid, 0), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            .also { streak = it; streakCol = c }
    }
}

/** [Mines-38.8] 128 px monochrome noise tile for the fog grain. */
private fun fogGrain(): android.graphics.Bitmap {
    val n = 128; val px = IntArray(n * n); val rnd = java.util.Random(7)
    for (i in px.indices) { val v = 96 + rnd.nextInt(64); px[i] = android.graphics.Color.argb(255, v, v, v) }
    return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * [Mines-38.9.1] the wind the fog follows: real wind from the home weather (m/s + direction it blows from + weather code),
 * scaled by 外观调节 › 流动速度 (−1 = 自动 = follow the wind; 0 = still; up to 2×).
 */
object FogWind {
    data class Sample(val speed: Double, val dirFrom: Double, val code: Int)
    @Volatile var sample = Sample(2.0, 270.0, 2)
    /** smoke: a fixed wind (fog-demo: calm / windy / rain); null = real weather */
    @Volatile var forced: Sample? = null
    fun refresh(c: android.content.Context) { forced?.let { sample = it; return }; com.openminis.app.plus.HomeGreeting.windNow(c)?.let { (s, d, k) -> sample = Sample(s, d, k) } }
    fun wet(code: Int) = code in 51..67 || code in 80..82 || code in 95..99 || code in 71..77
    fun clear(code: Int) = code == 0 || code == 1
    /** rain / overcast / mist denser, clear thinner */
    fun density(code: Int): Float = when { wet(code) -> 1.30f; code == 3 || code == 45 || code == 48 -> 1.20f; clear(code) -> 0.85f; else -> 1.05f }
    /** 0 (calm) … 1 (strong wind ≥ 10 m/s) */
    fun windK(s: Sample): Float = (s.speed / 10.0).toFloat().coerceIn(0f, 1f)
    fun flowPxPerSec(s: Sample): Float {
        val f = MinesLook.flow()
        val base = 6f + 26f * windK(s)       // calm ≈ 6 px/s, windy ≈ 32 px/s (at 1080 px width)
        return if (f < 0f) base else 18f * f
    }
}

/** [Mines-38.7] one fog clock for every backdrop, so the fog does not jump when a new page opens. */
object FogClock {
    @Volatile var t: Float = 0f
    /** [Mines-38.9.8] smoke / 自检: the sky scene whose colours the fog uses ("" = fixed fog palette) */
    @Volatile var scene: String = ""
    /** smoke diagnostics: backdrop draws so far and whether the last one was drawn in still mode */
    @Volatile var draws: Long = 0
    @Volatile var stillSeen: Boolean = false
    /** [Mines-38.9.5 i11] the fog holds still until this time (a page is being scrolled) */
    @Volatile var holdUntil: Long = 0L
    /** [38.9.11.3] user 2026-10-21: the sky / fog must never stop — scrolling no longer holds it (kept as a no-op for callers) */
    fun hold(ms: Long = 350L) { WeatherSky.busy(ms) }   // [38.9.11.3] the sky drifts on at a lower frame rate while scrolling
    private var lastMs = 0L
    fun tick(nowMs: Long) {
        if (lastMs != 0L) { val d = (nowMs - lastMs).coerceIn(0L, 100L); t += d / 1000f }
        lastMs = nowMs
    }
}

/** Character id currently picked (reads PlusPrefs). */
@Composable
fun currentCharacter(): String {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    return st.petStyle
}

/**
 * [Mines-38.5] ONE type / spacing / shape scale for every Mines page (user 2026-10-15:
 * 「主题风格不一致，还有些地方字体一些大，下面一堆又小」). Every Mines screen takes its sizes
 * from here; the system font scale multiplies them (the user's phone runs ≈ 1.18).
 */
object MT {
    // [Mines-38.8] user 2026-10-16: one type scale, no mixed big/small for the same role — title 18 Medium, body/labels/buttons 15, caption/meta 13, nav 12 (greeting Display 22 is the one exception)
    val PageTitle = 18.sp      // centred top-bar title (all pages)
    val Display = 22.sp        // home greeting / big in-page title
    val Section = 15.sp        // section label above a card (SemiBold, ink2)
    val Body = 15.sp           // list main text
    val Sub = 13.sp            // list secondary text
    val Caption = 13.sp        // explanations, footers
    val Meta = 13.sp           // times, counts, tags
    val Nav = 12.sp            // bottom bar labels
    val Button = 15.sp         // all buttons
    val BodyLH = 22.sp; val SubLH = 18.sp; val CaptionLH = 18.sp
    // [Mines-38.9.5 i] user 22:24 「一个全局字号」: the few extra roles the sweep needed — still the same scale
    val Tiny = 10.sp           // count badges only
    val BodyTightLH = 20.sp    // dense lists
    val TitleLH = 26.sp
    val Code = 22.sp           // one-time / device codes shown big enough to read
}

/** [Mines-38.9.5 i] the one Mines type scale under its spoken name: title · section · body 15 · caption 13 (letterSpacing 0). */
typealias MinesType = MT

object MS {
    val XS = 4.dp; val S = 8.dp; val M = 12.dp; val L = 16.dp; val XL = 24.dp
    val CardRadius = 20.dp
    val ButtonH = 48.dp
    val PageH = 20.dp          // card inset from the screen edge
}
