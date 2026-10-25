package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * [Mines-38.9.12] Mines 来电 (design ws2 ⑤, free, in-app — no telephony): when a task finishes (or fails) while Mines is in
 * the background and 「任务完成来电提醒」 is on, a high-priority call-style notification with a full-screen intent opens
 * [com.openminis.app.ui.plus.MinesCallActivity]: ringing (system ringtone, ≤ 30 s) + 接听 (voice chat) / 转文字 (open the
 * conversation) / 挂断. Off by default.
 */
object MinesCall {
    const val CHANNEL = "mines_call_v1"
    private const val PREFS = "mines_call"
    const val NOTIF_ID = 0x4D43   // "MC"
    @Volatile var lastRungAt: Long = 0L

    /** [Mines-38.9.14.1] the 来电 feature is removed (user 2026-10-25 「要么修好，要么不要」): never on, never rings */
    @Suppress("UNUSED_PARAMETER") fun enabled(c: Context) = false

    /** [Mines-38.9.14.1] clean up what earlier builds left: the old switch, a ringing notification, the 「Mines 来电」 channel */
    fun retire(c: Context) {
        val app = c.applicationContext
        runCatching { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).apply() }
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIF_ID) }
        runCatching { app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(CHANNEL) }
    }
    fun setEnabled(c: Context, on: Boolean) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mines 来电", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务完成或需要你处理时，像来电一样提醒"
            setSound(null, null)          // the call screen plays the ringtone itself (stops on 接听 / 挂断)
            enableVibration(false)
        })
    }

    /** ring now: [title] e.g. 「任务完成」, [body] the last reply line, [sessionId] for 转文字 */
    fun ring(c: Context, title: String, body: String, sessionId: String?) {
        if (!enabled(c)) return   // [Mines-38.9.14.1] removed: MinesCallActivity is no longer in the manifest
        val app = c.applicationContext
        channel(app)
        lastRungAt = System.currentTimeMillis()
        val i = Intent(app, com.openminis.app.ui.plus.MinesCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("title", title); putExtra("body", body.take(240)); putExtra("sid", sessionId)
        }
        val pi = PendingIntent.getActivity(app, NOTIF_ID, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Mines 来电 · $title")
            .setContentText(body.take(120))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true).setAutoCancel(true)
            .setTimeoutAfter(45_000L)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        val nm = NotificationManagerCompat.from(app)
        val canNotify = Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()
        if (canNotify) runCatching { nm.notify(NOTIF_ID, n) }
        // no notification permission / full-screen intent refused while the screen is on → open the call screen directly
        val fsOk = Build.VERSION.SDK_INT < 34 || runCatching { app.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }.getOrDefault(false)
        if (!canNotify || !fsOk) runCatching { app.startActivity(i) }
    }

    /** [Mines-38.9.12 b] also ring for needs-attention events (sign-in card, human check, payment confirm); on by default once 来电 is on */
    fun attention(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("attn", true)
    fun setAttention(c: Context, on: Boolean) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("attn", on).apply()

    private fun inForeground(): Boolean = runCatching {
        val i = android.app.ActivityManager.RunningAppProcessInfo(); android.app.ActivityManager.getMyMemoryState(i)
        i.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    /** self-test: would a needs-attention event ring right now (no ring) */
    fun attentionDecision(c: Context): String = "on=${enabled(c)} attn=${attention(c)} fg=${inForeground()} ring=${enabled(c) && attention(c) && !inForeground()}"

    /** a task is waiting on the user (sign-in, human check, payment) while Mines is in the background → ring (≤ once a minute) */
    fun onNeedsAttention(c: Context, title: String, body: String, sessionId: String? = null, force: Boolean = false) {
        if (!force && (!enabled(c) || !attention(c) || inForeground())) return
        if (!force && System.currentTimeMillis() - lastRungAt < 60_000L) return
        ring(c, title, body, sessionId)
    }

    fun cancel(c: Context) { runCatching { NotificationManagerCompat.from(c.applicationContext).cancel(NOTIF_ID) } }

    /** SessionActivityTracker completion hook (only when on, in the background, at most once a minute) */
    fun onTaskFinished(c: Context, sessionId: String, isError: Boolean, foreground: Boolean, title: String?, lastReply: String?) {
        if (!enabled(c) || foreground) return
        if (System.currentTimeMillis() - lastRungAt < 60_000L) return
        ring(c, if (isError) "任务出错，需要你看一下" else "任务完成", (lastReply ?: title).orEmpty().ifBlank { title.orEmpty() }, sessionId)
    }
}
