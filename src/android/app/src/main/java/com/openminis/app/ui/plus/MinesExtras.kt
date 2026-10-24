package com.openminis.app.ui.plus

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.plus.ChatOrganizer
import com.openminis.app.plus.DshLlmBridge
import com.openminis.app.plus.HumanVerify
import com.openminis.app.plus.LocalGateway
import com.openminis.app.plus.MinesUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── a simple card used on the home screen ──────────────────────────────

@Composable
private fun HomeCard(title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** 「发现本机模型网关 → 添加」, one tap. Used on the home screen and the plugin page. */
@Composable
fun LocalGatewayCards(scanOnShow: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val found by LocalGateway.found.collectAsState()
    var keys by remember { mutableStateOf(mapOf<Int, String>()) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scanOnShow) { if (scanOnShow) LocalGateway.scan(ctx) }
    result?.let { r ->
        HomeCard("本机模型网关", r) { TextButton(onClick = { result = null }) { Text("好的") } }
    }
    found.forEach { f ->
        HomeCard(
            "发现本机模型网关 · ${f.title}",
            "127.0.0.1:${f.port}" + if (f.needsKey) " · 需要密钥" else " · ${f.models.size} 个模型，可以直接用",
        ) {
            if (f.needsKey) OutlinedTextField(
                keys[f.port] ?: "", { v -> keys = keys + (f.port to v.trim()) },
                label = { Text("网关的密钥") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !f.needsKey || !keys[f.port].isNullOrBlank(),
                    onClick = { scope.launch { result = LocalGateway.add(ctx, f, keys[f.port]) } },
                    modifier = Modifier.weight(1f),
                ) { Text("添加") }
                OutlinedButton(onClick = { LocalGateway.dismiss(ctx, f.port) }) { Text("不用") }
            }
        }
    }
}

/** Update card on the home screen (only when a newer build exists). */
@Composable
fun UpdateHomeCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val st by MinesUpdate.state.collectAsState()
    LaunchedEffect(Unit) { delay(4000); runCatching { MinesUpdate.checkDaily(ctx) } }
    val av = st as? MinesUpdate.State.Available ?: return
    if (MinesUpdate.dismissedBuild(ctx) >= av.release.build) return
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    HomeCard("Mines 有新版本 build-${av.release.build}", av.release.notes.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: "点开看看更新了什么") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("看看") }
            OutlinedButton(onClick = { MinesUpdate.dismiss(ctx, av.release.build); hidden = true }) { Text("以后") }
        }
    }
}

/**
 * [Mines-38.9.12.1] safe start after an out-of-memory exit: 「上次内存不足退出，已暂停 N 个任务」, one row per paused task with
 * 继续 (opens that chat; nothing resumed by itself), 知道了 clears it.
 */
@Composable
fun SafeStartHomeCard() {
    val ctx = LocalContext.current
    val card by com.openminis.app.plus.SafeStart.card.collectAsState()
    val c = card ?: return
    var titles by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(c.sessions) {
        titles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val repo = runCatching { (ctx.applicationContext as com.openminis.app.MinisApp).chatRepository }.getOrNull()
            c.sessions.associateWith { sid -> runCatching { repo?.getSession(sid)?.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未命名对话" }
        }
    }
    HomeCard(c.title, c.body) {
        c.sessions.take(6).forEach { sid ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(titles[sid] ?: "…", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    com.openminis.app.plus.SafeStart.continued(ctx, sid)
                    PlusNav.go(com.openminis.app.ui.navigation.Routes.chat(sid))
                }) { Text("继续") }
            }
        }
        if (c.sessions.size > 6) Text("还有 ${c.sessions.size - 6} 个，在对话列表里找", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { com.openminis.app.plus.SafeStart.dismiss(ctx) }) { Text("知道了") }
        }
    }
}

/** Starts the plugin-model gateway once per app run (no-op without model plugins). */
@Composable
fun DshModelsAutoStart() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (autoStarted) return@LaunchedEffect
        autoStarted = true
        delay(3000)
        runCatching { DshLlmBridge.ensure(ctx) }
    }
}
private var autoStarted = false

/** Topic / archive chips above the conversation list. */
@Composable
fun ChatFilterChips(all: List<ChatSessionEntity>) {
    val ctx = LocalContext.current
    ChatOrganizer.init(ctx)
    val settings by ChatOrganizer.settings.collectAsState()
    val filter by ChatOrganizer.filter.collectAsState()
    val restored by ChatOrganizer.restored.collectAsState()
    val manual by ChatOrganizer.manual.collectAsState()
    val chips = remember(all, settings, restored, manual) { if (settings.topicChips) ChatOrganizer.chipCounts(all) else emptyList() }
    val archived = remember(all, settings, restored, manual) { ChatOrganizer.archivedCount(all) }
    if (chips.isEmpty() && archived == 0 && filter == ChatOrganizer.FILTER_ALL) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = filter == ChatOrganizer.FILTER_ALL, onClick = { ChatOrganizer.setFilter(ChatOrganizer.FILTER_ALL) }, label = { Text("全部") })
        chips.forEach { (name, n) ->
            FilterChip(selected = filter == name, onClick = { ChatOrganizer.setFilter(if (filter == name) ChatOrganizer.FILTER_ALL else name) }, label = { Text("$name $n") })
        }
        if (archived > 0 || filter == ChatOrganizer.FILTER_ARCHIVED) {
            FilterChip(selected = filter == ChatOrganizer.FILTER_ARCHIVED, onClick = {
                ChatOrganizer.setFilter(if (filter == ChatOrganizer.FILTER_ARCHIVED) ChatOrganizer.FILTER_ALL else ChatOrganizer.FILTER_ARCHIVED)
            }, label = { Text("已归档 $archived") })
        }
    }
    if (filter == ChatOrganizer.FILTER_ARCHIVED) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("超过 ${settings.days} 天没动的对话。点开就会回到列表。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { ChatOrganizer.restoreAll(ctx, all.filter { ChatOrganizer.isArchived(it) }.map { it.id }) }) { Text("全部恢复") }
        }
    }
}

// ── 对话整理 settings ──────────────────────────────────────────────────

@Composable
fun TidyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    ChatOrganizer.init(ctx)
    val s by ChatOrganizer.settings.collectAsState()
    PlusPage("对话整理", onBack) {
        PlusSection("自动归档", footer = "置顶、进行中和等你回复的对话不会被自动归档。也可以在对话的 ⋯ 里一键归档。归档只是从列表里收起来，内容都在；在首页点「已归档」可以找回，点开就恢复。") {
            SwitchRow("自动归档不活跃的对话", "超过 ${s.days} 天没有新消息就收起来", s.autoArchive) { on -> ChatOrganizer.update(ctx) { it.copy(autoArchive = on) } }
            if (s.autoArchive) {
                PlusDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${s.days} 天", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = s.days.toFloat(), valueRange = 1f..60f, steps = 58,
                        onValueChange = { v -> ChatOrganizer.update(ctx) { it.copy(days = v.toInt()) } },
                    )
                }
            }
        }
        PlusSection("按话题分组", footer = "根据标题和最后一条消息里的关键词，把对话分到 " +
            ChatOrganizer.topics.joinToString("、") { it.name } + "，在首页顶部点一下就只看这一类。全部在手机上完成，不调用模型。") {
            SwitchRow("首页显示话题标签", null, s.topicChips) { on -> ChatOrganizer.update(ctx) { it.copy(topicChips = on) } }
        }
    }
}

// ── 浏览器验证 settings ────────────────────────────────────────────────

@Composable
fun VerifyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    HumanVerify.init(ctx)
    val s by HumanVerify.settings.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val listenerOn = remember(tick) { com.openminis.app.offload.MinisNotificationListenerService.isEnabled(ctx) }
    PlusPage("浏览器验证", onBack) {
        PlusSection("人机验证", footer = "AI 用浏览器时遇到 Cloudflare、hCaptcha、reCAPTCHA、阿里滑块、极验、腾讯验证码，会停下来通知你「需要你点一下验证」，打开那个网页交给你，你点完就自动继续。AI 不会自己去破解验证码。") {
            SwitchRow("遇到验证时叫我", null, s.challenges) { on -> HumanVerify.update(ctx) { it.copy(challenges = on) } }
        }
        PlusSection("短信 / 邮件验证码", footer = "网页要验证码时，2 分钟内收到的短信或邮件通知里的验证码（4–8 位数字）会自动填进去。验证码只在内存里用一次，不记录、不保存，也不发给 AI。需要「通知使用权」。") {
            SwitchRow("自动填验证码", null, s.otpAutofill) { on -> HumanVerify.update(ctx) { it.copy(otpAutofill = on) } }
            PlusDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (listenerOn) "通知使用权：已打开" else "通知使用权：未打开", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!listenerOn) Button(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(com.openminis.app.offload.MinisNotificationListenerService.SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("去打开") }
            }
        }
        PlusSection("登录状态") {
            Text("各网站的登录状态（Cookie）分网站保存在手机上，验证通过后立刻存盘，重启后还在。", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp))
        }
        HandoffFlagsSection()   // [Mines-38.9.13 handoff_v2]
    }
}

// ── 检查更新 ───────────────────────────────────────────────────────────

@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by MinesUpdate.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val rollback = remember(tick) { MinesUpdate.rollbackApk(ctx) }
    var confirmRollback by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (st is MinesUpdate.State.Idle || st is MinesUpdate.State.Failed) MinesUpdate.check(ctx) }
    PlusPage("检查更新", onBack) {
        PlusSection("当前版本") {
            Text(
                if (MinesUpdate.currentBuild > 0) "Mines build-${MinesUpdate.currentBuild}" else "Mines（本地构建，无编号）",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp),
            )
        }
        PlusSection("新版本", footer = "从 GitHub 下载（${MinesUpdate.OWNER}/${MinesUpdate.REPO}）。装之前会先把现在这版存一份，装坏了可以一键回去。") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = st) {
                    MinesUpdate.State.Idle, MinesUpdate.State.Checking -> Text("正在检查…", style = MaterialTheme.typography.bodyLarge)
                    is MinesUpdate.State.UpToDate -> Text("已经是最新（最新是 build-${s.latest}）", style = MaterialTheme.typography.bodyLarge)
                    is MinesUpdate.State.Failed -> Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    is MinesUpdate.State.Available -> {
                        ReleaseInfo(s.release)
                        Button(onClick = { scope.launch { MinesUpdate.download(ctx, s.release) } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("下载并安装（${"%.1f".format(s.release.apkSize / 1048576.0)} MB）")
                        }
                    }
                    is MinesUpdate.State.Downloading -> {
                        ReleaseInfo(s.release)
                        Text("正在下载 ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is MinesUpdate.State.Ready -> {
                        ReleaseInfo(s.release)
                        Button(onClick = { MinesUpdate.install(ctx, s.apk); tick++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("安装 build-${s.release.build}") }
                        Text("如果提示「允许安装未知应用」，打开后回来再点一次。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (st !is MinesUpdate.State.Downloading) OutlinedButton(onClick = { scope.launch { MinesUpdate.check(ctx) } }, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
            }
        }
        if (rollback != null) PlusSection("回到上一版", footer = "安装保存下来的 build-${rollback.first}。数据不会丢。") {
            ActionRow(Icons.Outlined.Restore, "回到 build-${rollback.first}") { confirmRollback = true }
        }
    }
    if (confirmRollback && rollback != null) com.openminis.app.ui.glass.AlertDialog(
        onDismissRequest = { confirmRollback = false },
        title = { Text("回到 build-${rollback.first}？") },
        text = { Text("会打开系统安装界面，装回上一个版本。") },
        confirmButton = { Button(onClick = { confirmRollback = false; MinesUpdate.install(ctx, rollback.second) }) { Text("回去") } },
        dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("取消") } },
    )
}

@Composable
private fun ReleaseInfo(r: MinesUpdate.Release) {
    Text("build-${r.build}", style = MaterialTheme.typography.titleLarge)
    val date = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(r.publishedAt)
    }.getOrNull()
    if (date != null) Text("发布于 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(date), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (r.notes.isNotBlank()) Text(r.notes.take(1500), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.width(0.dp))
}

@Suppress("unused")
private fun fmt(t: Long) = SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(t))
