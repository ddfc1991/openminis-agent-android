package com.openminis.app.ui.plus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * [Mines-38.7] 设置 →「手机权限」 (user 2026-10-15 「要提高它对手机权限的使用」): every permission Mines uses, whether it is
 * on, and what it is for in one plain line. Tapping one that is off opens ONE step in big type (系统弹框 → 点「允许」, or
 * the settings page to open and what to switch on). The list re-checks when the user comes back.
 */
object PermGuide {
    const val ROUTE = "plus_permissions_guide"

    enum class How { RUNTIME, OVERLAY, NOTIF_LISTENER, BATTERY }

    class Item(val title: String, val use: String, val how: How, val perms: List<String> = emptyList())

    fun items(): List<Item> = buildList {
        add(Item("短信验证码", "收到验证码短信时，自动填进登录页", How.RUNTIME, listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)))
        add(Item("本机号码", "登录页自动填上你的手机号", How.RUNTIME, listOf(Manifest.permission.READ_PHONE_NUMBERS)))
        add(Item("麦克风", "语音聊天、叫醒 Mines", How.RUNTIME, listOf(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= 33) add(Item("通知", "任务做完、提醒、验证码到了告诉你", How.RUNTIME, listOf(Manifest.permission.POST_NOTIFICATIONS)))
        add(Item("位置", "首页显示你那里的天气", How.RUNTIME, listOf(Manifest.permission.ACCESS_COARSE_LOCATION)))
        add(Item("通讯录", "你说「给某某…」时找到这个人", How.RUNTIME, listOf(Manifest.permission.READ_CONTACTS)))
        add(Item("相册", "选壁纸、发图片给 AI", How.RUNTIME, listOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)))
        add(Item("读取通知", "从通知里读验证码（比如 App 推送的验证码）", How.NOTIF_LISTENER))
        add(Item("悬浮窗", "桌宠和语音小窗浮在别的 App 上面", How.OVERLAY))
        add(Item("后台不被关", "长任务、语音在后台不被系统杀掉", How.BATTERY))
    }

    fun granted(c: Context, it: Item): Boolean = when (it.how) {
        How.RUNTIME -> it.perms.all { p -> ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED }
        How.OVERLAY -> Settings.canDrawOverlays(c)
        How.NOTIF_LISTENER -> androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        How.BATTERY -> runCatching { c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(false)
    }

    fun settingsIntent(c: Context, it: Item): Intent = when (it.how) {
        How.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName))
        How.NOTIF_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        How.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
        How.RUNTIME -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
    }

    fun stepText(it: Item, denied: Boolean): String = when (it.how) {
        How.RUNTIME -> if (denied) "系统不再弹框了。点下面「去设置」，进「权限」，找到「${it.title}」，选「允许」，再按返回键回来。"
                       else "点下面「打开」。系统会弹一个框，点「允许」就好。"
        How.OVERLAY -> "点下面「去设置」，在打开的页面把「Mines」的开关打开，再按返回键回来。"
        How.NOTIF_LISTENER -> "点下面「去设置」，在列表里找到「Mines」，把开关打开，再按返回键回来。"
        How.BATTERY -> "点下面「去设置」，系统问要不要让 Mines 在后台运行，点「允许」。"
    }
}

@Composable
fun PermGuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<PermGuide.Item?>(null) }
    var denied by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        tick++
        val it = open
        if (it != null && PermGuide.granted(ctx, it)) open = null else denied = true
    }
    val items = remember(tick) { PermGuide.items().map { it to PermGuide.granted(ctx, it) } }
    PlusPage("手机权限", onBack, subtitle = "开了哪些、用来做什么；没开的，点一下我一步步带你开") {
        val off = items.count { !it.second }
        Text(if (off == 0) "全部都开好了" else "还有 $off 项没开", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        PlusSection(null) {
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                items.forEach { (it, ok) ->
                    PlusRow(null, it.title, it.use, trailing = { StatusPill(if (ok) "已开" else "没开", if (ok) true else false) },
                        onClick = if (ok) null else ({ open = it; denied = false }))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    open?.let { it ->
        com.openminis.app.ui.glass.AlertDialog(onDismissRequest = { open = null },
            title = { Text("开「${it.title}」", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold) },
            text = { Text(it.use + "\n\n" + PermGuide.stepText(it, denied), fontSize = MT.PageTitle, lineHeight = MT.TitleLH) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (it.how == PermGuide.How.RUNTIME && !denied) launcher.launch(it.perms.toTypedArray())
                    else { runCatching { ctx.startActivity(PermGuide.settingsIntent(ctx, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        open = null }
                }) { Text(if (it.how == PermGuide.How.RUNTIME && !denied) "打开" else "去设置", fontSize = MT.PageTitle) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { open = null }) { Text("先不开", fontSize = MT.PageTitle) } })
    }
}
