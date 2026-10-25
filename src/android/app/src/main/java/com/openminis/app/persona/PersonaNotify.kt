package com.openminis.app.persona

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * [Mines-38.9.15 伪人核心] The persistent notice while the persona may act, with 全部停止 on it: the kill switch is
 * always one tap away, wherever the user is. The receiver is the only other place that may flip it, and only towards
 * STOP (a tighter limit never needs the device lock; loosening always happens in the app).
 */
object PersonaNotify {
    private const val CHANNEL = "mines_persona"
    private const val ID = 0x9E15
    const val ACTION_KILL = "com.openminis.app.persona.KILL"
    @Volatile private var app: Context? = null

    fun attach(c: Context) { app = c.applicationContext; refresh() }

    fun refresh() {
        val c = app ?: return
        Persona.init(c)
        val nm = NotificationManagerCompat.from(c)
        if (!Persona.on.value || Persona.profile.value.accounts.none { it.allowed }) { runCatching { nm.cancel(ID) }; return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val sys = c.getSystemService(NotificationManager::class.java)
            if (sys?.getNotificationChannel(CHANNEL) == null)
                sys?.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "伪人核心", NotificationManager.IMPORTANCE_LOW))
        }
        val killed = Persona.limits.value.killed
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val open = android.app.PendingIntent.getActivity(c, 1, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (killed) "伪人核心已停止" else "伪人核心运行中")
            .setContentText(if (killed) "我不会再用你的账号做任何事。要恢复，到 应用 → 伪人核心" else "我可以用你允许的账号做事，发出去的还要你点头")
            .setOngoing(!killed).setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(open)
        // the notification can only STOP; turning it back on is done in the app (with the device lock), never here
        if (!killed) b.addAction(0, "全部停止", android.app.PendingIntent.getBroadcast(
            c, 2, Intent(c, KillReceiver::class.java).setAction(ACTION_KILL),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        val n = b.build()
        runCatching { nm.notify(ID, n) }
    }

    class KillReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_KILL) return
            // the user pressed it themselves, on their own phone's notification
            Persona.userSetKill(context, LimitGuardCore.USER_TAP, true)   // stop only
            attach(context); refresh()
        }
    }
}
