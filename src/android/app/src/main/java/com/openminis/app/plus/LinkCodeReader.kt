package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Mines-38.7] 快速线路 connection code over SSH, never stuck (user 2026-10-15: 「正在通过 SSH 读取服务器上的连接码…」 sat
 * there all afternoon with no retry and no warning). Every read has a 20 s hard limit; 3 tries with back-off (2 s,
 * 4 s); after that one 「自动配置」 (install, 5 min limit); still nothing → a red 「快速线路未就绪：原因」 and a system
 * notification with 「重试」 (also when the app is in the background).
 */
object LinkCodeReader {
    sealed class Result { data class Code(val code: String) : Result(); data class NotInstalled(val why: String) : Result(); data class Failed(val why: String) : Result() }

    /** smoke: every SSH read "hangs" (exceeds the 20 s limit) */
    @Volatile var simulateHang = false
    @Volatile var readLimitMs = 20_000L

    private suspend fun readOnce(c: Context, s: VpsServer): Result = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(readLimitMs) {
            if (simulateHang) { delay(Long.MAX_VALUE) }
            runCatching { VpsSsh.run(c, s.copy(linkUrl = ""), "cat /etc/mines-link/phone.txt 2>/dev/null", 15_000, 1).first.output.trim() }
        } ?: return@withContext Result.Failed("读取连接码超时（${readLimitMs / 1000} 秒没回应）")
        r.fold({ out -> out.lines().firstOrNull { it.startsWith("mines-link://") }?.let { Result.Code(it) } ?: Result.NotInstalled("服务器上还没装快速线路") },
            { e -> Result.Failed("SSH 连不上：" + VpsSsh.reason(e)) })
    }

    private suspend fun install(c: Context, s: VpsServer): Result = withContext(Dispatchers.IO) {
        if (simulateHang) { delay(readLimitMs); return@withContext Result.Failed("自动配置也超时了") }
        val r = withTimeoutOrNull(300_000L) {
            runCatching {
                val bundle = c.assets.open("mines-link/install-bundle.sh").use { it.readBytes() }
                val (sess, _) = VpsSsh.session(c, s.copy(linkUrl = ""))
                val sudo = if (s.user == "root") "" else "sudo -n "
                VpsSsh.exec(sess, "${sudo}sh -s -- --port 8443", 290_000, bundle).output
            }
        } ?: return@withContext Result.Failed("自动配置超时（5 分钟）")
        r.fold({ out -> out.lines().firstOrNull { it.startsWith("mines-link://") }?.let { Result.Code(it) } ?: Result.Failed("自动配置没成功：" + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)) },
            { e -> Result.Failed("SSH 连不上，没法自动配置：" + VpsSsh.reason(e)) })
    }

    /** Reads the code with retries, then auto-configures once; [onStep] gets plain status lines. */
    suspend fun obtain(c: Context, s: VpsServer, onStep: (String) -> Unit): Result {
        var last: Result = Result.Failed("没有回应")
        for (i in 0 until 3) {
            onStep(if (i == 0) "正在通过 SSH 读取服务器上的连接码…" else "第 ${i + 1} 次重试读取连接码…")
            last = readOnce(c, s)
            if (last is Result.Code) return last
            if (last is Result.NotInstalled) break
            delay(2000L shl i)
        }
        onStep("读不到连接码，正在自动配置快速线路（约 1 分钟）…")
        val ins = install(c, s)
        if (ins is Result.Code) return ins
        val why = (ins as? Result.Failed)?.why ?: (last as? Result.Failed)?.why ?: "没有回应"
        notifyNotReady(c, s, why)
        return Result.Failed(why)
    }

    const val CHANNEL = "mines_vps_link"

    fun notifyNotReady(c: Context, s: VpsServer, why: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "服务器快速线路", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(c, com.openminis.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("mines_route", "plus_server/" + s.id).putExtra("mines_link_retry", true)
        val pi = PendingIntent.getActivity(c, 7390 + (s.id.hashCode() and 0xff), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(73900 + (s.id.hashCode() and 0xff), android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
                .setContentTitle("快速线路未就绪：${s.name.ifBlank { s.host }}")
                .setContentText(why)
                .setStyle(android.app.Notification.BigTextStyle().bigText(why))
                .setContentIntent(pi)
                .addAction(android.app.Notification.Action.Builder(null, "重试", pi).build())
                .setAutoCancel(true).build())
        }
    }

    /** set when the notification's 「重试」 opened the server page */
    @Volatile var retryRequested = false
}
