package com.openminis.app.ui.plus

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.brand.MinesIcon

/**
 * [Mines-38.9] One place for the look (user 2026-10-16): glass transparency, fog strength and the accent colour, set
 * separately for light and dark, live preview, 恢复默认. Everything flows through the MinesDesign tokens
 * ([minesColors], [minesGlassFill], [glassVeil], [PorcelainBackdrop]) — no page has its own copy.
 */
object MinesLook {
    data class Accent(val id: String, val name: String, val primary: Color, val text: Color, val darkText: Color)

    /** White text on every [Accent.primary] is ≥ 4.5:1; [Accent.text] ≥ 4.5:1 on porcelain; [Accent.darkText] on the dark page. */
    val accents = listOf(
        Accent("claude", "陶土", Color(0xFFB5532F), Color(0xFFA84B2A), Color(0xFFE08A6B)),
        Accent("rose", "玫瑰", Color(0xFFA83C64), Color(0xFF9C365C), Color(0xFFF08AB0)),
        Accent("violet", "紫藤", Color(0xFF6A4FB4), Color(0xFF6046A8), Color(0xFFB9A4F0)),
        Accent("sea", "海蓝", Color(0xFF2C6AB0), Color(0xFF2861A3), Color(0xFF8DB6EE)),
        Accent("teal", "青碧", Color(0xFF1C7069), Color(0xFF19665F), Color(0xFF7CCFC6)),
        Accent("pine", "松绿", Color(0xFF3B7337), Color(0xFF356832), Color(0xFF93CF8B)),
        Accent("ink", "墨灰", Color(0xFF5A534E), Color(0xFF504A46), Color(0xFFC9C0B8)),
    )

    data class Look(
        /** [Mines-38.9.4] −1 = 自动 (default: the tint follows the background, GlassAuto); else the manual alpha 0.25…0.92 */
        val glassLight: Float = -1f, val glassDark: Float = -1f,
        val fogLight: Float = 1.25f, val fogDark: Float = 1.2f,   // [Mines-38.9.1] user: 「雾气还要明显点」 (was 1.0)
        val accentLight: String = "claude", val accentDark: String = "claude",
        /** 流动速度: −1 = 自动（跟着风）, else 0…2 */
        val flow: Float = -1f,
    )
    val DEFAULT = Look()
    /** the frosted alphas of the approved mock-27 (what the palette glass tokens are drawn at) */
    const val NOMINAL_LIGHT = 0.58f; const val NOMINAL_DARK = 0.50f

    /** Snapshot state: every composable (and [minesColors] inside MinisTheme) that reads it recomposes on change. */
    var look by mutableStateOf(DEFAULT)
        private set
    @Volatile private var loaded = false

    fun init(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("mines_look", Context.MODE_PRIVATE)
        // [Mines-38.9.4] new keys: the glass starts on 自动 for everyone (user 16:59 「不用户自己调节」); a manual value set after this is kept
        // [Mines-38.9.6] a manual glass below the new floor (0.35) is raised to it
        fun cl(v: Float) = if (v < 0f) v else v.coerceIn(GlassFloor.SLIDER_MIN, 0.92f)
        look = Look(cl(p.getFloat("gl3", DEFAULT.glassLight)), cl(p.getFloat("gd3", DEFAULT.glassDark)), p.getFloat("fl", DEFAULT.fogLight),
            p.getFloat("fd", DEFAULT.fogDark), p.getString("al", null) ?: DEFAULT.accentLight, p.getString("ad", null) ?: DEFAULT.accentDark, p.getFloat("flow", DEFAULT.flow))
        // a fog strength the user never moved follows the new, stronger default
        if (!p.contains("fl")) look = look.copy(fogLight = DEFAULT.fogLight)
        if (!p.contains("fd")) look = look.copy(fogDark = DEFAULT.fogDark)
    }

    fun set(c: Context, l: Look) {
        look = l
        c.getSharedPreferences("mines_look", Context.MODE_PRIVATE).edit().putFloat("gl3", l.glassLight).putFloat("gd3", l.glassDark)
            .putFloat("fl", l.fogLight).putFloat("fd", l.fogDark).putString("al", l.accentLight).putString("ad", l.accentDark).putFloat("flow", l.flow).apply()
    }

    fun accent(dark: Boolean): Accent = accents.firstOrNull { it.id == (if (dark) look.accentDark else look.accentLight) } ?: accents[0]
    fun glass(dark: Boolean) = if (dark) look.glassDark else look.glassLight
    fun glassAuto(dark: Boolean) = glass(dark) < 0f
    /** what the glass is at right now (auto → the last measured screen-wide alpha) */
    fun glassNow(dark: Boolean): Float = if (glassAuto(dark)) (if (dark) GlassAutoState.lastDark else GlassAutoState.lastLight) else glass(dark)
    fun fog(dark: Boolean) = if (dark) look.fogDark else look.fogLight
    fun flow(): Float = look.flow
    /** chosen glass alpha ÷ the default one (1 = unchanged); every glass alpha in the app is multiplied by it */
    fun glassScale(dark: Boolean): Float = glassNow(dark) / (if (dark) NOMINAL_DARK else NOMINAL_LIGHT)

    /** Applies the chosen accent + glass to the computed palette (no-op at the defaults). */
    fun apply(m: MinesColors): MinesColors {
        val a = accent(m.dark)
        val k = glassScale(m.dark)
        // [Mines-38.9.5 i] glass 自动 keeps the card thin; secondary text is pulled toward the primary ink as far as the
        // backdrop needs (GlassAuto.plan) instead — ink3 by the same amount.
        val m = if (glassAuto(m.dark)) {
            val i2 = if (m.dark) GlassAutoState.ink2Dark else GlassAutoState.ink2Light
            val t = if (m.dark) GlassAutoState.mixDark else GlassAutoState.mixLight
            if (i2 == null || t <= 0f) m else m.copy(ink2 = Color(i2),
                ink3 = Color(com.openminis.app.plus.GlassAuto.mixInk(m.ink3.toArgb(), m.ink.toArgb(), t)))
        } else m
        val withGlass = if (k == 1f) m else m.copy(glass = m.glass.copy(alpha = (m.glass.alpha * k).coerceIn(GlassFloor.card(m.dark), 0.98f)),
            glassStrong = m.glassStrong.copy(alpha = (m.glassStrong.alpha * k).coerceIn(GlassFloor.card(m.dark) + 0.06f, 0.98f)))
        if (a.id == "claude") return withGlass
        val cont = androidx.compose.ui.graphics.lerp(m.bg, a.primary, if (m.dark) 0.32f else 0.13f)
        return withGlass.copy(primary = a.primary, primaryText = if (m.dark) a.darkText else a.text, icon = if (m.dark) a.darkText else a.primary,
            container = cont, onContainer = if (m.dark) a.darkText else a.text, ring = a.darkText)
    }
}

@Composable
fun LookScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { MinesLook.init(ctx); 0 }
    val cur = MinesLook.look
    val dark0 = mines().dark
    var dark by remember { mutableStateOf(dark0) }
    PlusPage("外观调节", onBack, subtitle = "浅色和深色分开调，改了马上生效") {
        // 浅色 / 深色 switch
        Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "浅色", true to "深色").forEach { (d, t) ->
                val on = dark == d
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(if (on) mines().primary else minesGlassFill())
                    .clickable { dark = d }.semantics { contentDescription = "调$t" }, contentAlignment = Alignment.Center) {
                    Text(t, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else mines().ink)
                }
            }
        }
        // live preview in the chosen mode
        val pm = minesColors("claude", dark)
        CompositionLocalProvider(LocalMinesColors provides pm) {
            Box(Modifier.fillMaxWidth().padding(horizontal = MS.PageH, vertical = 12.dp).height(220.dp).clip(RoundedCornerShape(MS.CardRadius))
                .semantics { contentDescription = "外观预览" }) {
                PorcelainBackdrop(Modifier.fillMaxSize(), animate = false)   // [Mines-38.9.5 i11] one still frame: the page fog behind already moves
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val wnd = FogWind.sample
                    Text("现在：" + com.openminis.app.plus.HomeGreeting.describe(wnd.code, 12).second + " · 风 " + "%.1f".format(wnd.speed) + " m/s " +
                        listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[(((wnd.dirFrom + 22.5) % 360) / 45).toInt().coerceIn(0, 7)] + "风" +
                        if (cur.flow < 0f) " · 雾跟着风走" else "", fontSize = MT.Meta, color = pm.ink, fontWeight = FontWeight.Medium)
                    MinesCard(Modifier.fillMaxWidth()) {
                        PlusRow(com.openminis.app.ui.brand.MinesIcons.named("sparkles"), "预览：玻璃卡片", "文字在雾上也要看得清", chevron = false)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("主按钮") {}
                        Text("链接文字", fontSize = MT.Body, color = pm.primaryText, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        // [38.9.11.1] 天空动画 (user on 38.9.11: 「雾好卡」): 流畅 = slow, light key frames (default) · 精美 = more detail · 关闭 = a still picture
        PlusSection("天空动画") {
            var sm by remember { mutableStateOf(WeatherSky.mode(ctx)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.openminis.app.plus.SkyModeCore.Mode.values().forEach { md ->
                    val on = sm == md
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(50)).background(if (on) mines().primary else minesGlassFill())
                        .clickable { sm = md; WeatherSky.setMode(ctx, md) }
                        .semantics { contentDescription = "天空动画 ${md.label}" + if (on) "（正在用）" else "" }, contentAlignment = Alignment.Center) {
                        Text(md.label, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else mines().ink)
                    }
                }
            }
            Text(when (sm) {
                com.openminis.app.plus.SkyModeCore.Mode.SMOOTH -> "云和雾慢慢飘，省电不卡（推荐）"
                com.openminis.app.plus.SkyModeCore.Mode.RICH -> "细节更多，更耗电"
                com.openminis.app.plus.SkyModeCore.Mode.OFF -> "静止的天空，每分钟换一次"
            } + "。省电模式下自动静止。", fontSize = MT.Caption, color = mines().ink2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        PlusSection(if (dark) "深色" else "浅色") {
            val g = MinesLook.glass(dark); val f = MinesLook.fog(dark)
            // [Mines-38.9.4] user 16:59 「不用户自己调节」: 自动 by default — the glass measures what is behind it (fog or photo)
            // and picks the most transparent tint that keeps text ≥ 4.5:1; the slider is only an optional override
            val gAuto = g < 0f
            val now = (MinesLook.glassNow(dark) * 100).toInt()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("玻璃透明度", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = mines().ink, modifier = Modifier.weight(1f))
                Text(if (gAuto) "自动（按背景算，现在 $now%）" else "手动", fontSize = MT.Meta, color = mines().ink2)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(gAuto, { on ->
                    val v = if (on) -1f else MinesLook.glassNow(dark).coerceIn(GlassFloor.SLIDER_MIN, 0.92f)
                    MinesLook.set(ctx, if (dark) cur.copy(glassDark = v) else cur.copy(glassLight = v))
                }, modifier = Modifier.semantics { contentDescription = "玻璃透明度自动" })
            }
            if (!gAuto) LookSlider("玻璃透明度（手动）", "越往右越不透明", g.coerceIn(GlassFloor.SLIDER_MIN, 0.92f), GlassFloor.SLIDER_MIN..0.92f) { v ->
                MinesLook.set(ctx, if (dark) cur.copy(glassDark = v) else cur.copy(glassLight = v))
            }
            LookSlider("雾气浓淡", "0 = 不要雾，只留底色", f, 0f..2f) { v ->
                MinesLook.set(ctx, if (dark) cur.copy(fogDark = v) else cur.copy(fogLight = v))
            }
            // [Mines-38.9.1] 流动速度: 自动 = follow the real wind
            val auto = cur.flow < 0f
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("流动速度", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = mines().ink, modifier = Modifier.weight(1f))
                Text(if (auto) "自动（跟着风）" else "手动", fontSize = MT.Meta, color = mines().ink2)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(auto, { on -> MinesLook.set(ctx, cur.copy(flow = if (on) -1f else 1f)) },
                    modifier = Modifier.semantics { contentDescription = "流动速度跟着风" })
            }
            if (!auto) LookSlider("流动速度（手动）", "0 = 不动，越往右越快", cur.flow, 0f..2f) { v -> MinesLook.set(ctx, cur.copy(flow = v)) }
            Text("主题色", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = mines().ink, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                MinesLook.accents.forEach { a ->
                    val on = MinesLook.accent(dark).id == a.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(a.primary)
                            .then(if (on) Modifier.border(2.dp, mines().ink, CircleShape) else Modifier)
                            .clickable { MinesLook.set(ctx, if (dark) cur.copy(accentDark = a.id) else cur.copy(accentLight = a.id)) }
                            .semantics { contentDescription = "主题色 ${a.name}" + if (on) "（正在用）" else "" }, contentAlignment = Alignment.Center) {
                            if (on) MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("check")!!, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(a.name, fontSize = MT.Meta, color = mines().ink2)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            PillButton("恢复默认", Modifier.fillMaxWidth(), primary = false) {
                MinesLook.set(ctx, if (dark) cur.copy(glassDark = MinesLook.DEFAULT.glassDark, fogDark = MinesLook.DEFAULT.fogDark, accentDark = "claude", flow = -1f)
                    else cur.copy(glassLight = MinesLook.DEFAULT.glassLight, fogLight = MinesLook.DEFAULT.fogLight, accentLight = "claude"))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LookSlider(title: String, hint: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val m = mines()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
            Text("${(v * 100).toInt()}%", fontSize = MT.Meta, color = m.ink2)
        }
        Slider(v, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = title },
            colors = SliderDefaults.colors(thumbColor = m.primary, activeTrackColor = m.primary, inactiveTrackColor = m.ink3.copy(alpha = 0.25f)))
        Text(hint, fontSize = MT.Caption, color = m.ink2)
    }
    Spacer(Modifier.width(1.dp))
}
