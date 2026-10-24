package com.openminis.app.ui.plus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.plus.GlassAuto
import com.openminis.app.plus.PlusPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.4] 外观调节 › 玻璃 = 自动 (default): the glass alpha comes from what is behind it ([GlassAuto]) — the fog at
 * its current 浓淡 / weather density, or the photo wallpaper — per screen band. [Mines-38.9.5 i] thin glass (light 30–40 %,
 * dark 35–45 %); readability from a darker ink2 + a halo behind text rows on the worst bands (GlassAuto.plan).
 */
object GlassAutoState {
    private val fogCache = ConcurrentHashMap<String, GlassAuto.Stats>()
    /** photo stats by "<mtime>:<alpha>:<dark>" (state map: cards recompose when a photo has been measured) */
    val photo = mutableStateMapOf<String, GlassAuto.Stats>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    // [Mines-38.9.5 i] 外观调节 over a photo: every glass card re-ran the solver (6 bands × ~80 pow() steps), a disk stat and
    // string formatting on EVERY recomposition, and each slider step re-decoded the photo. Now: thumbnail decoded once per
    // file version, stats per (version, alpha in 0.05 steps), solver results memoised per (mode, tint, ink, stats).
    private val thumbs = ConcurrentHashMap<Long, IntArray>()
    private data class SolveKey(val dark: Boolean, val tint: Int, val ink2: Int, val ink: Int, val stats: Int)
    private val solved = ConcurrentHashMap<SolveKey, GlassAuto.Plan>()
    fun planCached(dark: Boolean, tint: Int, ink2: Int, ink: Int, s: GlassAuto.Stats): GlassAuto.Plan {
        if (solved.size > 64) solved.clear()
        return solved.getOrPut(SolveKey(dark, tint, ink2, ink, System.identityHashCode(s))) { GlassAuto.plan(dark, tint, ink2, ink, s) }
    }
    /** [Mines-38.9.5 i] the readable secondary text for the current backdrop (null = not measured yet → palette ink2) and how
     *  far it was pulled toward the primary ink; snapshot state, so [MinesLook.apply] / the theme recompose when it changes. */
    var ink2Light by androidx.compose.runtime.mutableStateOf<Int?>(null)
    var ink2Dark by androidx.compose.runtime.mutableStateOf<Int?>(null)
    var mixLight by androidx.compose.runtime.mutableStateOf(0f)
    var mixDark by androidx.compose.runtime.mutableStateOf(0f)
    /** halo behind text rows per screen band (0 = none) for the current backdrop */
    var haloLight by androidx.compose.runtime.mutableStateOf<List<Float>>(emptyList())
    var haloDark by androidx.compose.runtime.mutableStateOf<List<Float>>(emptyList())
    fun q(v: Float): Float = (Math.round(v * 20f) / 20f)
    /** last screen-wide auto alpha per mode (feeds [MinesLook.glassScale] for the palette glass tokens) */
    @Volatile var lastLight = MinesLook.NOMINAL_LIGHT
    @Volatile var lastDark = MinesLook.NOMINAL_DARK

    fun fog(dark: Boolean, fogK: Float, dens: Float): GlassAuto.Stats {
        val fk = q(fogK); val dk = q(dens)
        val k = "$dark:$fk:$dk"
        return fogCache.getOrPut(k) { GlassAuto.fogStats(dark, fk, dk) }
    }

    suspend fun loadPhoto(c: android.content.Context, key: String, mtime: Long, alpha: Float, page: Int) {
        if (photo.containsKey(key) || !loading.add(key)) return
        try {
            val st = withContext(Dispatchers.IO) {
                runCatching {
                    val px = thumbs[mtime] ?: run {
                        val f = wallpaperFile(c)
                        if (!f.exists()) return@runCatching null
                        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
                        val b = BitmapFactory.decodeFile(f.absolutePath, o) ?: return@runCatching null
                        val s = Bitmap.createScaledBitmap(b, 12, 24, true)   // heavy averaging = the blur under the glass
                        IntArray(12 * 24).also { s.getPixels(it, 0, 12, 0, 0, 12, 24); thumbs.clear(); thumbs[mtime] = it }
                    }
                    GlassAuto.photoStats(px, 12, 24, alpha, page)
                }.getOrNull()
            }
            if (st != null) photo[key] = st
        } finally { loading.remove(key) }
    }
}

/** Auto alphas for the current backdrop: (per band, screen-wide). */
@Composable
fun glassAutoAlphas(dark: Boolean, tint: Color, ink: Color): Pair<List<Float>, Float> {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val photoOn = st.wallpaper.isNotEmpty() && st.wallpaperOnHome && st.wallpaper.startsWith("file:")
    val stats: GlassAuto.Stats = if (photoOn) {
        val mtime = androidx.compose.runtime.remember(st.wallpaper) { wallpaperFile(ctx).lastModified() }   // no disk stat per frame
        val page = (if (dark) Color(0xFF161114) else Color(0xFFF4EAE4)).toArgb()
        val qa = GlassAutoState.q(st.wallpaperAlpha)
        val key = "$mtime:$qa:$dark"
        LaunchedEffect(key) { GlassAutoState.loadPhoto(ctx, key, mtime, qa, page) }
        GlassAutoState.photo[key] ?: GlassAutoState.fog(dark, MinesLook.fog(dark), FogWind.density(FogWind.sample.code))
    } else GlassAutoState.fog(dark, MinesLook.fog(dark), FogWind.density(FogWind.sample.code))
    // the BASE palette inks (not mines().ink2, which this very plan may have darkened → no feedback loop)
    val ink2 = (if (dark) MinesDesign.Ink2Dark else MinesDesign.Ink2).toArgb()
    val ink1 = (if (dark) MinesDesign.InkDark else MinesDesign.Ink).toArgb()
    val p = GlassAutoState.planCached(dark, tint.toArgb(), ink2, ink1, stats)
    if (dark) GlassAutoState.lastDark = p.all else GlassAutoState.lastLight = p.all
    androidx.compose.runtime.SideEffect { if (dark) {
        if (GlassAutoState.ink2Dark != p.ink2) { GlassAutoState.ink2Dark = p.ink2; GlassAutoState.mixDark = p.inkMix }
        if (GlassAutoState.haloDark != p.halo) GlassAutoState.haloDark = p.halo
    } else {
        if (GlassAutoState.ink2Light != p.ink2) { GlassAutoState.ink2Light = p.ink2; GlassAutoState.mixLight = p.inkMix }
        if (GlassAutoState.haloLight != p.halo) GlassAutoState.haloLight = p.halo
    } }
    return p.per to p.all
}

/** [Mines-38.9.5 i] halo behind text on the given screen bands (null = worst on screen); 0 when the glass is manual. */
fun glassAutoHalo(dark: Boolean, bands: IntRange?): Float {
    if (!MinesLook.glassAuto(dark)) return 0f
    val h = if (dark) GlassAutoState.haloDark else GlassAutoState.haloLight
    if (h.isEmpty()) return 0f
    return bands?.let { r -> r.mapNotNull { h.getOrNull(it) }.maxOrNull() } ?: h.max()
}
