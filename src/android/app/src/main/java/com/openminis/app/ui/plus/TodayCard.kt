@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.openminis.app.ui.plus

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openminis.app.ui.brand.MinesIcon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.PlusState
import com.openminis.app.plus.PlusVault
import com.openminis.app.plus.PlusVps
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "Today" header on the conversation list: greeting with the avatar, and
 * one-tap chips for where work runs, servers, and connected accounts.
 */
@Composable
fun TodayCard(onOpen: (String) -> Unit, modifier: Modifier = Modifier, onSearch: () -> Unit = {},
              all: List<com.openminis.app.data.db.ChatSessionEntity> = emptyList(), showAvatar: Boolean = true,
              searching: Boolean = false, query: String = "", onQuery: (String) -> Unit = {}, onCloseSearch: () -> Unit = {}, resultCount: Int = 0) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    PlusVault.init(context)
    val st by PlusPrefs.state.collectAsState()
    val vault by PlusVault.entries.collectAsState()
    LaunchedEffect(Unit) {
        PlusVps.startMonitor(context)
        launch { runCatching { com.openminis.app.plus.Digest.refresh(context) } }
        com.openminis.app.plus.SandboxCare.startAuto(context)
        if (PlusPrefs.state.value.petEnabled && com.openminis.app.plus.DeskPetService.canShow(context)) {
            com.openminis.app.plus.DeskPetService.start(context)
        }
        // [Mines-wake-25] Wake word resumes with the app (a mic service may only start while the app is in front).
        if (PlusPrefs.state.value.wakeWord) com.openminis.app.plus.WakeWordService.start(context)
        // [Mines-dsh-35] plugin widget survives app restarts / process kills
        com.openminis.app.plus.PluginWidgetService.resumeIfWanted(context)
    }

    var switcher by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (switcher) AvatarSwitcherSheet(onDismiss = { switcher = false })
    LaunchedEffect(Unit) { com.openminis.app.plus.PlusConnect.rearm(context) }

    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    // [Mines-38.7] greeting with care: 8 time slots + weather (open-meteo, no key) + battery + late night + weekend /
    // holiday + 好久不见, several lines per case, no sentence twice a day (HomeGreeting)
    val gTick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    // [Mines-38.8] weather re-checked on every app resume (refetched only when the cache is > 1 h old or the phone moved > 5 km)
    val wOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(wOwner) {
        wOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { runCatching { com.openminis.app.plus.HomeGreeting.refreshWeather(context) } }
    }
    // [Mines-38.8] no location permission and no remembered place: ask once, in plain words, for the rough location
    val askLoc by com.openminis.app.plus.HomeGreeting.askLocation.collectAsState()
    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        com.openminis.app.plus.HomeGreeting.locationAsked(context)
        if (granted) runCatching { context.getSharedPreferences("mines_greeting", android.content.Context.MODE_PRIVATE).edit().putLong("w_loc_try", 0L).putLong("w_try", 0L).apply() }
    }
    val wScope = androidx.compose.runtime.rememberCoroutineScope()
    // [Mines-38.9.5 i] user 22:34/22:35: ask the system once for coarse location (no Mines explanation text); the weather
    // stays hidden until the phone (or a city the user set) says where it is — never the VPN's IP
    LaunchedEffect(askLoc) {
        if (askLoc) {
            locLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            wScope.launch { kotlinx.coroutines.delay(1500); runCatching { com.openminis.app.plus.HomeGreeting.refreshWeather(context) } }
        }
    }
    val greeting = androidx.compose.runtime.remember(gTick, hour) {
        val snap = DeviceSnap.read(context)
        val charging = runCatching { context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)
        com.openminis.app.plus.HomeGreeting.compute(context, com.openminis.app.plus.HomeGreeting.context(context, snap.batteryPct, charging))
    }
    val greet = greeting.title
    @Suppress("UNUSED_VARIABLE") val weekday = SimpleDateFormat("EEEE", Locale.CHINA).format(cal.time).replace("星期", "周")
    // [Mines-warm-36] design36warm 01/02: home = 对话 only. Face + name plate, greeting, one status line,
    // then 最近对话. No message card / bar here (user 13:01 「对话就对话，要处理放在消息嘛」); the
    // count of things to handle is the red badge on the 消息 tab of the bottom bar.
    val m = mines()
    val feed by com.openminis.app.plus.Digest.items.collectAsState()
    val todo = feed.filter { it.tab == com.openminis.app.plus.Digest.Tab.ACCOUNT }
    val running by FeedTasks.running.collectAsState()
    var shown by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(280), label = "appear")
    val eff = PlusVps.effectiveMode(st)
    val vpsDown = st.isVps && eff == PlusState.MODE_LOCAL
    // [Mines-38.1] board 01: no 「N 件要处理」 here (only the red badge on 消息); one green line when all is well.
    @Suppress("UNUSED_VARIABLE") val todoCount = todo.size
    val (statusText, statusColor) = when {
        vpsDown -> "服务器连不上，先在本机跑" to (if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning)
        else -> "都在正常跑" to (if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success)
    }
    val who = androidx.compose.runtime.remember { MinesProfile.name(context) }
    Column(
        modifier.fillMaxWidth().padding(vertical = 2.dp)
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 8f },
    ) {
        // [Mines-38.5] user 2026-10-15 「虚拟形象放太低了，设备状况也搞没了」: the character is the visual centre
        // (104 dp, nothing pressed over it), name + status on one line under it, then greeting + search,
        // the 设备状况 chips (电量 · 网络 · 存储 · 运行), then 最近对话.
        // [Mines-38.6] user 2026-10-15 「再上一点，得接近两侧边的两个图标高度」: on the home list the face sits in the
        // top bar between ☰ and ⚙ (HomeAvatar, drawn by SessionListScreen); this card starts at the name line.
        // [Mines-38.7] design387 board: the face lives in the top bar (weather pill above it); here the greeting is centred,
        // big, with one line of care under it (no name/status line, the search button moved next to 「最近对话」)
        if (showAvatar) Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val name = com.openminis.app.plus.Avatars.name(currentCharacter())
            Box(Modifier.size(112.dp).clip(CircleShape)
                .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(m.container.copy(alpha = 0.9f), m.container.copy(alpha = 0f))))
                .clickable { switcher = true }.semantics { contentDescription = "换角色：$name" },
                contentAlignment = Alignment.Center) { PetAvatar(size = 100.dp) }
        }
        // [Mines-38.8] user 2026-10-16 13:42 — build-36 sheet 01: left-aligned 「下午好，<name>」 (22 sp) with a round frosted
        // search button on its right, one small dot line under it (green 「都在正常跑」, or the first warning: red 「电量 11%」,
        // 「没有网络」, amber 「存储只剩 2.1 GB」, 「服务器连不上，先在本机跑」). The four 设备状况 tiles are gone.
        // Searching turns the greeting row into the search field IN PLACE (nothing floats over the header, no font change).
        val snap by androidx.compose.runtime.produceState(initialValue = DeviceSnap.read(context)) {
            while (true) { kotlinx.coroutines.delay(30_000); value = DeviceSnap.read(context) }
        }
        val okC = if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success
        val warnC = if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning
        val badC = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger
        val (lineText, lineColor) = when {
            snap.batteryPct in 0..15 -> "电量 ${snap.batteryPct}%，记得充电" to badC
            !snap.online -> "没有网络" to badC
            vpsDown -> statusText to statusColor
            snap.freeGb < 3 -> "存储只剩 ${"%.1f".format(snap.freeGb)} GB" to warnC
            snap.batteryPct in 16..20 -> "电量 ${snap.batteryPct}%" to warnC
            else -> "都在正常跑" to okC
        }
        @Suppress("UNUSED_VARIABLE") val okUnused = okC
        // [Mines-38.9.5 i] user 22:34 「晚上好主人太单调了」: varied greeting — time of day · weather (rain soon → 带伞) ·
        // weekday / holiday · task status, from a pool, never the same phrase on two opens in a row; name optional
        val tasksAll by com.openminis.app.plus.TaskProgress.tasks.collectAsState()
        // [Mines-38.9.7] user 21:15 「2 件事等你」 — which two? Same filter as the summary line (chats on the list), and the
        // greeting's number is the size of the list the tap opens
        val visHome by com.openminis.app.plus.TaskProgress.visible.collectAsState()
        val tSum = androidx.compose.runtime.remember(tasksAll, visHome) { com.openminis.app.plus.TaskProgressCore.summary(tasksAll.values.filter { t -> visHome?.contains(t.sid) ?: true }, System.currentTimeMillis()) }
        val waitList = androidx.compose.runtime.remember(tasksAll, visHome, all) {
            com.openminis.app.plus.HomeFixCore.waiting(tasksAll.values, visHome, System.currentTimeMillis(), all.associate { it.id to (it.title ?: "") })
        }
        var waitOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        if (waitOpen) WaitingSheet(waitList, onOpen = { sid -> waitOpen = false; onOpen(com.openminis.app.ui.navigation.Routes.chat(sid)) }, onDismiss = { waitOpen = false })
        // [Mines-38.9.8] user 2026-10-19: a marker for unfinished work (进行中 / 等你 / 出错 / 已停止, last 7 days) → the list
        val openList = androidx.compose.runtime.remember(tasksAll, visHome, all) {
            com.openminis.app.plus.HomeFixCore.unfinished(tasksAll.values, visHome, System.currentTimeMillis(), all.associate { it.id to (it.title ?: "") })
        }
        var openOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        if (openOpen) UnfinishedSheet(openList, onOpen = { sid -> openOpen = false; onOpen(com.openminis.app.ui.navigation.Routes.chat(sid)) }, onDismiss = { openOpen = false })
        val hello = androidx.compose.runtime.remember(gTick, hour, tSum.running, waitList.size, tSum.error) {
            runCatching {
                com.openminis.app.plus.HomeGreeting.variedGreeting(context, com.openminis.app.plus.HomeGreeting.context(context, 80, true),
                    tSum.running, waitList.size, tSum.error, who)
            }.getOrDefault(greet)
        }
        // [Mines-38.9.1] user: 「晚上好这里高一点」 — the greeting moves up into the space the plate's status line left
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 0.dp).offset(y = (-8).dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!searching) {
                Text(hello, fontSize = MT.PageTitle, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, style = fogGlow(),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                        .then(if (waitList.isNotEmpty()) Modifier.clip(RoundedCornerShape(12.dp)).clickable { waitOpen = true } else Modifier)
                        .semantics { contentDescription = "$hello。$lineText" + if (waitList.isNotEmpty()) "。点按看是哪 ${waitList.size} 件" else "" })
                Spacer(Modifier.width(8.dp))
                if (openList.isNotEmpty()) {
                    UnfinishedBadge(openList.size) { openOpen = true }
                    Spacer(Modifier.width(8.dp))
                }
                WarmRoundButton(com.openminis.app.ui.brand.MinesIcons.named("search")!!, "搜索对话", size = 42.dp, onClick = onSearch)
            } else {
                val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                val shape = RoundedCornerShape(24.dp)
                Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(shape).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, shape)
                    .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("search")!!, null, tint = m.ink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索对话…", fontSize = MT.Body, color = m.ink3, maxLines = 1)
                        // [Mines-38.8 v5d] own TextFieldValue: the cursor stays where the user types even when the parent's
                        // query state lags a frame behind (a String field resets the selection to 0 → 「Mines」 came out 「sM」)
                        var tfv by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))) }
                        // the only outside change is a clear (清空 / 取消); a lagging echo of what was typed is ignored
                        androidx.compose.runtime.LaunchedEffect(query) { if (query.isEmpty() && tfv.text.isNotEmpty()) tfv = androidx.compose.ui.text.input.TextFieldValue("") }
                        androidx.compose.foundation.text.BasicTextField(tfv, { v -> tfv = v; if (v.text != query) onQuery(v.text) }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body, color = m.ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(m.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "搜索对话" })
                    }
                    if (query.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape).clickable { onQuery("") }.semantics { contentDescription = "清空" },
                        contentAlignment = Alignment.Center) {
                        MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("x")!!, null, tint = m.ink3, modifier = Modifier.size(16.dp))
                    }
                    Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = m.primaryText, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onCloseSearch).padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp).offset(y = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
            // [Mines-38.9.5 i] user 19:26: 「都在正常跑」 becomes the conversation summary (进行中 N · 等你 N · 出错 N · 完成 N);
            // device warnings (电量 / 网络 / 存储 / 服务器) still come first
            val summarised = lineText == "都在正常跑" && TaskSummaryLine()
            if (!summarised) {
                BreathingDot(lineColor)
                Spacer(Modifier.width(6.dp))
                Text(lineText, fontSize = 13.sp, lineHeight = 18.sp, style = fogGlow(),
                    color = if (lineColor == okC) m.ink2 else lineColor, maxLines = 1, softWrap = false)
            }
            // [Mines-38.9.5 i] user 22:34 「首页天气加回去」: small weather at the end of this line (icon · temp · condition · city,
            // or the rain-soon hint in amber); tap → detail. Gone when there is no device location / city.
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            HomeWeatherMini()
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 0.dp, bottom = 0.dp).offset(y = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (searching && query.isNotBlank()) "找到 $resultCount 个对话 · 按最近" else "最近对话", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = m.ink2, style = fogGlow(), modifier = Modifier.weight(1f))
            if (!searching) ChatFilterMenu(all)
        }
    }
}

/**
 * [Mines-38.6] The home character face (100 dp, same glow and 换角色 tap as before), placed by the home top bar
 * between ☰ and ⚙ so its upper half lines up with those two buttons. The 112 dp glow box is pulled up by its
 * 6 dp glow margin, so the face itself starts right under the status bar and the bar reports 106 dp.
 */
@Composable
fun HomeAvatar(modifier: Modifier = Modifier) {
    val m = mines()
    var switcher by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (switcher) AvatarSwitcherSheet(onDismiss = { switcher = false })
    val name = com.openminis.app.plus.Avatars.name(currentCharacter())
    val wx = homeWeather()
    // [Mines-38.7] user 2026-10-15 「太阳能不能围着头像转，点一下然后一个大太阳回归头顶」: a small weather sprite circles the
    // face; tap the face (or the sprite) → it flies up and grows into the big weather picture above the head with the
    // temperature pill and 「晴 26 度」 read aloud; after 4 s / another tap it shrinks back into its orbit. Long-press = 换角色.
    val orbit = HomeOrbit
    val appCtx = LocalContext.current
    val stillAnim = remember { AnimeWeather.still(appCtx) }
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val toggle: () -> Unit = {
        if (wx == null) { switcher = true } else { scope.launch { orbit.toggle(stillAnim); if (orbit.open) view.announceForAccessibility(wx.spoken) } }
        Unit
    }
    Box(modifier
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val lift = 6.dp.roundToPx()
            layout(p.width, (p.height - lift).coerceAtLeast(0)) { p.place(0, -lift) }
        }
        .size(112.dp), contentAlignment = Alignment.Center) {
        if (wx != null) WeatherOrbit(orbit, wx, front = false, onTap = toggle)
        Box(Modifier.size(112.dp).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(m.container.copy(alpha = 0.9f), m.container.copy(alpha = 0f))))
            .combinedClickable(onClick = toggle, onLongClick = { switcher = true })
            .semantics { contentDescription = "换角色：$name，长按" + if (wx != null) "；点一下看天气" else "" },
            contentAlignment = Alignment.Center) { PetAvatar(size = 100.dp) }
        if (wx != null) WeatherOrbit(orbit, wx, front = true, onTap = toggle)
    }
}

/**
 * [Mines-38.8] user 2026-10-16 「首页只改头像高度，跟消息页一样」: the home top bar is as compact as the 消息 page's
 * (WarmHeader): ☰ · 56 dp face with the glass name plate under its chin · ⚙. The weather sprite still circles the face
 * (scaled orbit); a tap shows the 「26° 晴」 pill under the plate (no big picture above the head, so no extra room up top);
 * long-press = 换角色. Everything below (greeting, 设备状况, 最近对话, search) is unchanged 38.7.
 */
@Composable
fun HomeCompactHeader(modifier: Modifier = Modifier, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    val m = mines()
    val context = LocalContext.current
    var switcher by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (switcher) AvatarSwitcherSheet(onDismiss = { switcher = false })
    val name = com.openminis.app.plus.Avatars.name(currentCharacter())
    val wx = homeWeather()
    val orbit = HomeOrbit
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val toggle: () -> Unit = {
        if (wx == null) { switcher = true } else { scope.launch { orbit.toggle(true); if (orbit.open) view.announceForAccessibility(wx.spoken) } }
        Unit
    }
    val running by FeedTasks.running.collectAsState()
    // [Mines-38.8] sheet 01: the plate says what the character is doing; device / server health is the line under the greeting
    val status = if (running.isNotEmpty()) "后台在办 ${running.size} 件事" else "在线，随时叫我"
    val face = 70.dp   // [Mines-38.8] user 13:42: ~70 dp face between ☰ and ⚙ (build-36 sheet 01)
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(face), contentAlignment = Alignment.Center) {
                if (wx != null) WeatherOrbit(orbit, wx, front = false, onTap = toggle, k = 0.72f)
                Box(Modifier.size(face).clip(CircleShape)
                    .combinedClickable(onClick = toggle, onLongClick = { switcher = true })
                    .semantics { contentDescription = "换角色：$name，长按" + if (wx != null) "；点一下看天气" else "" },
                    contentAlignment = Alignment.Center) { PetAvatar(size = face) }
                if (wx != null) WeatherOrbit(orbit, wx, front = true, onTap = toggle, k = 0.72f)
            }
            Column(
                Modifier.offset(y = (-10).dp).widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(minesGlassFill(strong = true))
                    .border(1.dp, m.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 3.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                // [Mines-38.9.1] user 2026-10-16: 「在线，随时叫我」 removed; the plate only says something when work runs in the background
                if (running.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(m.icon))
                    Spacer(Modifier.width(5.dp))
                    Text(status, fontSize = 13.sp, lineHeight = 16.sp, color = m.primaryText, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** [Mines-38.7] the home weather as the sprite / big picture / pill need it; null = no weather (nothing shown). */
class HomeWx(val icon: String, val words: String, val temp: Int, val extra: String, val night: Boolean) {
    val spoken get() = "$words $temp 度"
}

@Composable
fun homeWeather(): HomeWx? {
    val context = LocalContext.current
    val tick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    LaunchedEffect(Unit) { runCatching { com.openminis.app.plus.HomeGreeting.refreshWeather(context) } }
    val w = remember(tick) { com.openminis.app.plus.HomeGreeting.weatherNow(context) } ?: return null
    // [Mines-38.7] night = after the real sunset (DayNight), unless the smoke injected an hour
    val sunNight by com.openminis.app.plus.DayNight.night.collectAsState()
    val forced = com.openminis.app.plus.HomeGreeting.override?.optInt("hour", -1)?.takeIf { it >= 0 }
    val night = if (forced != null) forced >= 19 || forced < 6 else sunNight
    val (icon, words) = com.openminis.app.plus.HomeGreeting.describe(w.code, if (night) 23 else 12)
    // [Mines-38.8] rain later today is a separate short hint (「傍晚可能有雨」); the word stays the current condition
    val extra = w.later.takeIf { it.isNotBlank() } ?: w.place.takeIf { it.isNotBlank() && it.length <= 8 } ?: if (night) "夜里" else ""
    return HomeWx(icon, words, Math.round(w.temp).toInt(), extra, night)
}

/**
 * [Mines-38.7] orbit board (design387/orbit.jpg): when the big weather picture is up, the 「26° 晴 · 新加坡」 pill pops up
 * under the face and pushes the greeting down a little; it folds away when the picture goes back into its orbit.
 */
@Composable
fun HomeWeatherPill() {
    val wx = homeWeather() ?: return
    val m = mines()
    androidx.compose.animation.AnimatedVisibility(HomeOrbit.open,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
        val fill = minesGlassFill(strong = true)  // [Mines-38.9.5 i] one glass in both modes  // [Mines-38.8 v5d] light: glass, not white
        Row(Modifier.padding(top = 10.dp, bottom = 2.dp).heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50)).background(fill).border(1.dp, m.hairline.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "天气：" + wx.spoken },
            verticalAlignment = Alignment.CenterVertically) {
            Text("${wx.temp}°", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = m.ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
            Text(if (wx.extra.isNotBlank()) "${wx.words} · ${wx.extra}" else wx.words, fontSize = 15.sp, lineHeight = 18.sp, color = m.ink2, maxLines = 1, softWrap = false)
        }
    }
}

/** Room above the face for the big weather picture (gone when there is no weather). */
@Composable
fun HomeWeatherSpace() { if (homeWeather() != null) Spacer(Modifier.height(WeatherOrbitState.TOP_SPACE)) }

/** [Mines-38.7] one orbit state for the home face (the pill under the face reads it too). */
val HomeOrbit = WeatherOrbitState()

class WeatherOrbitState {
    companion object {
        val TOP_SPACE = 58.dp
        const val PERIOD = 12f
        /** smoke: true while the big picture is up */
        @Volatile var shownNow = false
    }
    var open by androidx.compose.runtime.mutableStateOf(false)
    val fly = androidx.compose.animation.core.Animatable(0f)
    /** orbit angle frozen while open (radians) */
    var angle by androidx.compose.runtime.mutableFloatStateOf(-0.6f)
    suspend fun toggle(still: Boolean = false) {
        if (still) { open = !open; shownNow = open; return }
        if (open) close() else {
            open = true; shownNow = true
            fly.animateTo(1f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    suspend fun close() {
        if (fly.value == 0f) { open = false; shownNow = false; return }
        fly.animateTo(0f, androidx.compose.animation.core.tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        open = false; shownNow = false
    }
}

/**
 * One layer of the orbit: [front] draws the half of the path in front of the face (lower arc, under the chin, full size),
 * the other call draws the back half (upper arc, smaller and faded, hidden behind the face). The open state (big picture
 * above the head + pill) is drawn by the front layer only. Path: ellipse ±74 dp × ±50 dp centred 8 dp below the face
 * centre, so it never crosses the face in front and stays clear of ☰ / ⚙. Still (animations off): sprite parked at the
 * face's upper right, a tap shows the pill.
 */
@Composable
private fun WeatherOrbit(st: WeatherOrbitState, wx: HomeWx, front: Boolean, onTap: () -> Unit, k: Float = 1f) {
    // [Mines-38.8] k < 1: the same orbit around the compact 56 dp home face (path, sprite and still spot scaled; no flight)
    val ctx = LocalContext.current
    val m = mines()
    val still = remember { AnimeWeather.still(ctx) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var t by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(owner, still, st.open) {
        if (!front || still || st.open) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var prev = androidx.compose.runtime.withFrameMillis { it }
            while (true) { MotionGate.awaitRun(); androidx.compose.runtime.withFrameMillis { now ->
                st.angle = (st.angle + (now - prev) / 1000f * (2f * Math.PI.toFloat() / WeatherOrbitState.PERIOD)) % (2f * Math.PI.toFloat())
                prev = now; t = now / 1000f
            } }
        }
    }
    LaunchedEffect(st.open) { if (front && st.open) { kotlinx.coroutines.delay(4000); if (st.open) st.close() } }
    var prevIcon by remember { androidx.compose.runtime.mutableStateOf(wx.icon) }
    var old by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val swap = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(wx.icon) {
        if (wx.icon == prevIcon) return@LaunchedEffect
        old = prevIcon; prevIcon = wx.icon
        if (still) swap.snapTo(1f) else { swap.snapTo(0f); swap.animateTo(1f, androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        old = null
    }
    val a = st.angle
    val f = st.fly.value
    val rx = 74f * k; val ry = 50f * k; val cy = 8f * k
    val ox = if (still) 52f * k else rx * kotlin.math.cos(a)
    val oy = if (still) -44f * k else cy + ry * kotlin.math.sin(a)
    // faint dashed orbit line (back layer, while circling)
    if (!front && !still && f < 0.02f) {
        androidx.compose.foundation.Canvas(Modifier.size(1.dp)) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f + cy.dp.toPx())
            drawOval(Color(0xFFE8B98A).copy(alpha = 0.45f), topLeft = androidx.compose.ui.geometry.Offset(c.x - rx.dp.toPx(), c.y - ry.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(2 * rx.dp.toPx(), 2 * ry.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))))
        }
    }
    val inFront = still || kotlin.math.sin(a) >= 0f || f > 0.02f
    if (front != inFront && f <= 0.02f) return
    if (!front && f > 0.02f) return
    // depth: front 1.0, back 0.68 (scale) / 0.55 (alpha)
    val depth = if (still) 1f else (0.5f + 0.5f * kotlin.math.sin(a))
    val sc0 = 0.68f + 0.32f * depth
    val al0 = 0.55f + 0.45f * depth
    // flight to the top of the head: arc with a little bulge, grows 26 → 60 dp
    val tx = 0f; val ty = -82f
    val x = ox + (tx - ox) * f
    val y = oy + (ty - oy) * f - kotlin.math.sin(Math.PI.toFloat() * f) * 22f
    val sizeDp = (30f + (62f - 30f) * f) * (if (k < 1f) 0.74f else 1f)
    val scale = sc0 + (1f - sc0) * f
    val alpha = al0 + (1f - al0) * f
    // faint trail of light dots behind the sprite while it circles
    if (!still && f < 0.02f) {
        for (k in 1..3) {
            val ak = a - k * 0.16f
            val sk = kotlin.math.sin(ak)
            if ((sk >= 0f) != front) continue
            val dx = rx * kotlin.math.cos(ak); val dy = cy + ry * sk
            Box(Modifier.offset(dx.dp, dy.dp).size(4.dp).graphicsLayer { this.alpha = (0.45f - k * 0.12f) * (0.55f + 0.45f * (0.5f + 0.5f * sk)) }
                .clip(CircleShape).background(Color(0xFFFFE3B0)))
        }
    }
    Box(Modifier.offset(x.dp, y.dp).size(sizeDp.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onTap)
        // [Mines-38.7 v11c] screen reader: the closed sprite says what it is and that a tap opens it
        .semantics { contentDescription = if (st.open) "天气：${wx.spoken}，点一下收起" else "天气：${wx.words} ${wx.temp}°，点开查看" }) {
        // [Mines-38.7] day ↔ night: the old picture sets along an arc (down + sideways, fading), the new one rises
        val s = swap.value
        val o = old
        if (o != null && s < 1f) Box(Modifier.size(sizeDp.dp).graphicsLayer {
            translationY = s * 64.dp.toPx(); translationX = s * 28.dp.toPx(); this.alpha = 1f - s }) { AnimeWeatherIcon(o, sizeDp.dp) }
        Box(Modifier.size(sizeDp.dp).graphicsLayer {
            if (o != null && s < 1f) { translationY = (1f - s) * 64.dp.toPx(); translationX = -(1f - s) * 28.dp.toPx(); this.alpha = s } }) { AnimeWeatherIcon(wx.icon, sizeDp.dp) }
    }
}

/** [Mines-38.5] 设备状况: battery, network, free storage and where work runs, refreshed every 30 s. */
@Composable
fun DeviceStatusRow(statusText: String, statusColor: Color, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val snap by androidx.compose.runtime.produceState(initialValue = DeviceSnap.read(ctx)) {
        while (true) { kotlinx.coroutines.delay(30_000); value = DeviceSnap.read(ctx) }
    }
    val ok = if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success
    val warn = if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning
    val bad = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusTile(Modifier.weight(1f), "battery-full", snap.battery, "电量",
            when { snap.batteryPct in 0..15 -> bad; snap.batteryPct in 16..30 -> warn; else -> ok }) {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        StatusTile(Modifier.weight(1f), "wifi", snap.net, "网络", if (snap.online) ok else bad) {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        StatusTile(Modifier.weight(1f), "hard-drive", snap.storage, "存储", if (snap.freeGb < 3) warn else ok) { onOpen(PlusRoutes.SANDBOX) }
        StatusTile(Modifier.weight(1f), "gauge", if (statusText == "都在正常跑") "正常" else "本机跑", "运行", statusColor) { onOpen(PlusRoutes.WORK) }
    }
}

@Composable
private fun StatusTile(modifier: Modifier, icon: String, value: String, label: String, dot: Color, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, shape).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label：$value" }
            .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(com.openminis.app.ui.brand.MinesIcons.named(icon)!!, null, tint = m.icon, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = MT.Meta, color = m.ink2, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        }
        Text(value, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Snapshot of the phone's state for the 设备状况 chips (cheap, no permissions beyond ACCESS_NETWORK_STATE). */
data class DeviceSnap(val battery: String, val batteryPct: Int, val net: String, val online: Boolean, val storage: String, val freeGb: Double) {
    companion object {
        fun read(ctx: android.content.Context): DeviceSnap {
            val bi = runCatching { ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val lvl = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
            val st = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
            val battery = if (pct < 0) "—" else "$pct%" + if (charging) " ⚡" else ""
            var net = "离线"; var online = false
            runCatching {
                val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    online = true
                    net = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                        else -> "已连网"
                    }
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) net = "$net·VPN"
                }
            }
            val free = runCatching { android.os.StatFs(ctx.filesDir.absolutePath).availableBytes / 1e9 }.getOrDefault(-1.0)
            val storage = if (free < 0) "—" else if (free >= 100) "剩 ${free.toInt()}G" else "剩 ${"%.1f".format(free)}G"
            return DeviceSnap(battery, pct, net, online, storage, if (free < 0) 99.0 else free)
        }
    }
}

@Composable
private fun HeaderCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val m = mines()
    Box(Modifier.size(44.dp).clip(CircleShape).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, CircleShape)
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MinesIcon(icon, null, tint = m.ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FeedRow(item: com.openminis.app.plus.Digest.Item) {
    val it = item
    val m = mines()
    val (icon, tone) = when (it.cat) {
        com.openminis.app.plus.Digest.Cat.ACT -> "message-square-warning" to Tone.DANGER
        com.openminis.app.plus.Digest.Cat.DEAL -> "wallet" to Tone.SUCCESS
        com.openminis.app.plus.Digest.Cat.LEARN -> "book-open" to Tone.INFO
        com.openminis.app.plus.Digest.Cat.HOT -> "chart-column" to Tone.WARNING
        else -> "mail" to Tone.NEUTRAL
    }
    val time = runCatching {
        val d = System.currentTimeMillis() - it.at
        if (d < 86_400_000L) SimpleDateFormat("HH:mm", Locale.CHINA).format(java.util.Date(it.at)) else "${d / 86_400_000L} 天前"
    }.getOrDefault("")
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ToneBubble(com.openminis.app.ui.brand.MinesIcons.named(icon)!!, tone)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text((it.headline ?: it.title).ifBlank { it.summary }, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = m.ink,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val sub = it.summary.takeIf { s -> s.isNotBlank() && s != it.headline } ?: it.source
            Text(sub, fontSize = 15.sp, lineHeight = 20.sp, color = m.ink2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (time.isNotEmpty()) Text(time, fontSize = 15.sp, color = m.ink3, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Chip(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, dot: Color, onClick: () -> Unit) {
    // [Mines-a11y-26] explicit, contrast-checked colours. [Mines-compact-29] slim
    // pill: one line, label spoken via the description and shown as a 2-char hint.
    GlassCard(modifier.semantics(mergeDescendants = true) { contentDescription = "$label：$value" },
        shape = RoundedCornerShape(14.dp), elevation = 2.dp, onClick = onClick) {
        val fill = MaterialTheme.colorScheme.surfaceContainerHigh
        val bg = LocalCardSurface.current ?: fill.compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
        val fg = cardTextColor(fill)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(icon, null, tint = readableSecondary(fg, bg), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(6.dp).background(dot, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg,
                maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessagesBar(count: Int, onClick: () -> Unit) {
    val m = mines()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(minesGlassFill(strong = false))
            .border(1.dp, m.glassBorder, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "消息" + (if (count > 0) "，$count 条要处理" else "") + "，点按打开消息页" }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("mail")!!, null, tint = m.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("消息", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f), maxLines = 1)
        if (count > 0) {
            Box(Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(50)).background(MinesDesign.DangerFill).padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center) {
                Text(if (count > 99) "99+" else count.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("›", fontSize = 20.sp, color = m.ink3)
    }
}


/**
 * [Mines-38.7] user 2026-10-15 「最好有天气在头像上方」: one small weather line above the home face — icon, temperature,
 * two words (「☀ 26° 晴」), large and high-contrast, read aloud as 「天气：晴，26 度」. Same source as the greeting
 * (open-meteo, no key); when there is no weather the whole line is gone (no empty pill, no reserved space).
 */
@Composable
fun HomeWeatherChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val m = mines()
    val tick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    LaunchedEffect(Unit) { runCatching { com.openminis.app.plus.HomeGreeting.refreshWeather(context) } }
    val w = androidx.compose.runtime.remember(tick) { com.openminis.app.plus.HomeGreeting.weatherNow(context) } ?: return
    val hour = (com.openminis.app.plus.HomeGreeting.override?.optInt("hour", -1)?.takeIf { it >= 0 }) ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val (icon, words) = com.openminis.app.plus.HomeGreeting.describe(w.code, hour)
    val night = hour >= 19 || hour < 6
    val extra = w.later.takeIf { it.isNotBlank() } ?: w.place.takeIf { it.isNotBlank() && it.length <= 8 } ?: if (night) "夜里" else ""
    val t = Math.round(w.temp).toInt()
    // design387 board: white pill (dark: deep porcelain), line icon, bold temperature, small 「晴 · 新加坡」
    val fill = minesGlassFill(strong = true)  // [Mines-38.9.5 i] one glass in both modes  // [Mines-38.8 v5d] light: glass, not white
    Row(modifier.padding(top = 4.dp, bottom = 6.dp).clip(RoundedCornerShape(50)).background(fill)
        .border(1.dp, m.hairline.copy(alpha = 0.18f), RoundedCornerShape(50))
        .padding(start = 8.dp, end = 16.dp, top = 3.dp, bottom = 3.dp)
        .semantics(mergeDescendants = true) { contentDescription = "天气：$words，$t 度" + if (extra.isNotBlank() && !night) "，$extra" else "" },
        verticalAlignment = Alignment.CenterVertically) {
        // [Mines-38.7] anime-style animated sticker (board2: blushing sun / round rain cloud / winking moon)
        AnimeWeatherIcon(icon, 32.dp)
        Spacer(Modifier.width(6.dp))
        Text("$t°", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = m.ink, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(if (extra.isNotBlank()) "$words · $extra" else words, fontSize = 15.sp, lineHeight = 18.sp, color = m.ink2, maxLines = 1)
    }
}


/** [Mines-38.8] liquid-glass v2: soft light halo (dark halo at night) behind text that sits straight on the fog (≥ 4.5:1). */
@Composable
fun fogGlow(): androidx.compose.ui.text.TextStyle {
    val dark = mines().dark
    return androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
        color = if (dark) Color(0xD9140E10) else Color(0xF2FFF8F4), offset = androidx.compose.ui.geometry.Offset.Zero, blurRadius = 14f))
}


/**
 * [Mines-38.9.1] 「都在正常跑」 as a breathing light: a soft halo that swells and fades every ~2.5 s around a 7 dp dot in the
 * status colour (green ok / amber warning / red error). Still dot when animations are off (battery saver / animator 0).
 */
@Composable
fun BreathingDot(color: Color) {
    val ctx = LocalContext.current
    val still = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) ||
        runCatching { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false) }
    val t = if (still) 0.5f else {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath")
        val v by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
        v
    }
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            drawCircle(color.copy(alpha = 0.10f + 0.28f * (1f - t)), radius = size.minDimension / 2f * (0.55f + 0.45f * t))
            drawCircle(color.copy(alpha = 0.75f + 0.25f * t), radius = 3.5.dp.toPx())
        }
    }
}


/** [Mines-38.9.5 i] the home's small weather (status line, right): 「☁ 24° 多云 · 新加坡」 or 「☂ 24° · 1 小时后有雨」; tap → [HomeWeatherSheet]. */
@Composable
fun HomeWeatherMini() {
    val context = LocalContext.current
    val m = mines()
    val tick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    // [Mines-38.9.7] re-checked every 10 min while home is shown (was once: after 1 h the weather vanished)
    LaunchedEffect(Unit) { while (true) { runCatching { com.openminis.app.plus.HomeGreeting.refreshWeather(context) }; kotlinx.coroutines.delay(10 * 60_000L) } }
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val w = androidx.compose.runtime.remember(tick) { com.openminis.app.plus.HomeGreeting.weatherNow(context) }
    if (w == null) {
        // [Mines-38.9.7] never nothing: 「设置城市」 / 「天气获取中…」, tap → city
        val why = androidx.compose.runtime.remember(tick) { com.openminis.app.plus.HomeGreeting.weatherWhy(context) }
        val label = com.openminis.app.plus.HomeFixCore.weatherChip(why)
        Row(Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "天气：" + why.line + "。点按设置城市" }, verticalAlignment = Alignment.CenterVertically) {
            AnimeWeatherIcon("cloud", 18.dp); Spacer(Modifier.width(4.dp))
            Text(label, fontSize = MT.Meta, lineHeight = MT.SubLH, color = m.ink2, maxLines = 1, softWrap = false, style = fogGlow())
        }
        if (open) HomeWeatherSheet(null, why.line, onDismiss = { open = false })
        return
    }
    val hour = (com.openminis.app.plus.HomeGreeting.override?.optInt("hour", -1)?.takeIf { it >= 0 }) ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val (icon0, words) = com.openminis.app.plus.HomeGreeting.describe(w.code, hour)
    val t = Math.round(w.temp).toInt()
    val soon = w.soon.takeIf { it.isNotBlank() && it != "正在下雨" }
    val icon = if (soon != null) "cloud-rain" else icon0
    val tail = soon ?: w.place.takeIf { it.isNotBlank() && it.length <= 6 }
    val warnC = if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).clickable { open = true }.padding(horizontal = 6.dp, vertical = 2.dp)
        .semantics(mergeDescendants = true) { contentDescription = "天气：$words，$t 度" + (w.place.takeIf { it.isNotBlank() }?.let { "，$it" } ?: "") + (soon?.let { "，$it" } ?: "") + "。点按看详情" },
        verticalAlignment = Alignment.CenterVertically) {
        AnimeWeatherIcon(icon, 18.dp)
        Spacer(Modifier.width(4.dp))
        Text("$t° $words", fontSize = MT.Meta, lineHeight = MT.SubLH, color = m.ink2, maxLines = 1, softWrap = false, style = fogGlow())
        if (tail != null) {
            Text(" · $tail", fontSize = MT.Meta, lineHeight = MT.SubLH, color = if (soon != null) warnC else m.ink2, fontWeight = if (soon != null) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, softWrap = false, style = fogGlow())
        }
    }
    if (open) HomeWeatherSheet(w, words, onDismiss = { open = false })
}

@Composable
private fun HomeWeatherSheet(w: com.openminis.app.plus.HomeGreeting.Weather?, words: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val m = mines()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(w == null) }
    var city by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.openminis.app.plus.HomeGreeting.manualCity(context)?.third.orEmpty()) }
    var err by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val src = com.openminis.app.plus.HomeGreeting.placeSource(context)
    MinesDialog(w?.place?.ifBlank { null } ?: "天气", onDismiss = onDismiss,
        confirm = if (editing) "保存" else null,
        onConfirm = if (editing) ({
            scope.launch {
                val r = com.openminis.app.plus.HomeGreeting.setManualCity(context, city)
                if (city.isBlank() || r != null) { editing = false; err = null; onDismiss() } else err = "没找到这个城市"
            }
        }) else null, dismiss = if (editing) "取消" else "关闭") {
        @Composable fun line(k: String, v: String, c: Color = m.ink) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(k, fontSize = MT.Body, color = m.ink2, modifier = Modifier.width(72.dp)); Text(v, fontSize = MT.Body, color = c)
        }
        if (w == null) line("现在", words)
        if (w != null) line("现在", "${Math.round(w.temp)}° $words")
        if (w != null) line("今天", "${Math.round(w.min)}° ~ ${Math.round(w.max)}° · 降水 ${w.rainProb}%")
        val rainLine = if (w == null) "" else w.soon.ifBlank { w.later }
        if (rainLine.isNotBlank()) line("下雨", rainLine, if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning)
        if (src.isNotBlank()) line("位置", src)
        if (!editing) Text("设置城市", fontSize = MT.Body, color = m.primaryText, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { editing = true }.padding(horizontal = 4.dp, vertical = 8.dp))
        else {
            androidx.compose.material3.OutlinedTextField(city, { city = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body, color = m.ink), placeholder = { Text("城市（留空 = 用手机定位）", fontSize = MT.Body, color = m.ink3) })
            err?.let { Text(it, fontSize = MT.Caption, color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** [Mines-38.9.8] home marker: list icon + count badge of unfinished tasks; tap → [UnfinishedSheet]. */
@Composable
private fun UnfinishedBadge(n: Int, onClick: () -> Unit) {
    val m = mines()
    Box(Modifier.size(42.dp).semantics { contentDescription = "未完成的事 $n 件，点按查看" }) {
        WarmRoundButton(com.openminis.app.ui.brand.MinesIcons.named("list-checks")!!, "未完成的事 $n 件", size = 42.dp, onClick = onClick)
        Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
            .clip(RoundedCornerShape(50)).background(if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center) {
            Text(com.openminis.app.plus.HomeFixCore.badge(n), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
    }
}

/** [Mines-38.9.8] every unfinished task (等你 first, then 出错, 进行中, 已停止), each opens its chat. */
@Composable
private fun UnfinishedSheet(items: List<com.openminis.app.plus.HomeFixCore.Open>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val m = mines()
    MinesDialog("未完成的事（${items.size}）", onDismiss = onDismiss, dismiss = "关闭") {
        if (items.isEmpty()) Text("都做完了", fontSize = MT.Body, color = m.ink2)
        androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            items.forEach { w ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(w.sid) }.padding(horizontal = 6.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${w.title}，${w.line}。点按打开" }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(ProgressColors.of(w.st, m.dark)))
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(w.title, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(w.line, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("chevron-right")!!, null, tint = m.ink3, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** [Mines-38.9.7] 「N 件事等你」 → exactly those N chats, each opens its chat. */
@Composable
private fun WaitingSheet(items: List<com.openminis.app.plus.HomeFixCore.Waiting>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val m = mines()
    MinesDialog("${items.size} 件事等你", onDismiss = onDismiss, dismiss = "关闭") {
        if (items.isEmpty()) Text("现在没有等你的事", fontSize = MT.Body, color = m.ink2)
        items.forEach { w ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(w.sid) }.padding(horizontal = 6.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${w.title}，${w.why}。点按打开" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ProgressColors.of(com.openminis.app.plus.TaskProgressCore.St.WAITING, m.dark)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(w.why, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("chevron-right")!!, null, tint = m.ink3, modifier = Modifier.size(16.dp))
            }
        }
    }
}
