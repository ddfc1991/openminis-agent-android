package com.openminis.app.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.openminis.app.MainActivity
import com.openminis.app.R

/**
 * [Mines-38.9.10 i28] user 2026-10-19 15:09: 「内置 Clash 死机/重启后不会自启」. The core used to live only inside the app
 * process (started from MinisApp.onCreate): when the system killed the process, or after a phone reboot, nothing brought it
 * back until the user opened Mines and toggled it. Now, while the switch is on, a foreground service owns the core:
 *  - START_STICKY: after a crash / process kill the system restarts the service, which restarts the core;
 *  - the core's watchdog restarts a dead or hung mihomo (2 s … 30 s backoff, controller health every 30 s);
 *  - [ClashBootReceiver]: BOOT_COMPLETED / MY_PACKAGE_REPLACED start it again;
 *  - an orphaned mihomo left from a killed process (port still bound) is killed before the new one starts;
 *  - the notification carries the one-line status, including why a restart failed.
 */
class ClashService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ClashCore.serviceAlive = true
        ClashCore.onState = { runCatching { notifyState(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first, always: a service started with startForegroundService that stops without it crashes the app
        val fg = runCatching { goForeground(ClashCore.state.value) }
        if (intent?.action == ACTION_STOP || !ClashCore.enabled(this)) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }; stopSelf(); return START_NOT_STICKY
        }
        if (fg.isFailure) {
            // the system refused the foreground start: run the core in-process (it lives as long as the app does)
            Log.w(TAG, "startForeground: ${fg.exceptionOrNull()?.javaClass?.simpleName}")
            ClashCore.noteRestart("前台服务被系统拒绝，改在 App 里运行", this)
            Thread({ runCatching { ClashCore.start(applicationContext) } }, "mines-clash-svc").start()
            stopSelf(); return START_NOT_STICKY
        }
        // restarted by the system after a kill (null intent) or a crash → note it, then bring the core up
        if (intent == null) ClashCore.noteRestart("App 被系统结束后自动重启", this)
        Thread({ runCatching { ClashCore.start(applicationContext) } }, "mines-clash-svc").start()
        return START_STICKY
    }

    override fun onDestroy() {
        ClashCore.serviceAlive = false
        ClashCore.onState = null
        super.onDestroy()
    }

    private fun notifyState(line: String) {
        if (!ClashCore.serviceAlive) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build(line))
    }

    private fun goForeground(line: String) {
        val n = build(line)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun build(line: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "内置 Clash", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("内置 Clash")
            .setContentText(line.take(80))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MinesClashSvc"
        const val CHANNEL = "mines_clash"
        const val NOTIF_ID = 7898
        const val ACTION_STOP = "com.openminis.mines.CLASH_STOP"

        /** starts (or stops) the service for the current switch state; false when the system refused a foreground start */
        fun sync(c: Context): Boolean {
            val app = c.applicationContext
            return if (ClashCore.enabled(app)) runCatching {
                androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ClashService::class.java)); true
            }.getOrElse {
                // Android 12+: no foreground start from the background (e.g. a cold start without UI) → run in-process now,
                // the service takes over the next time Mines is opened
                Log.w(TAG, "fg start refused: ${it.javaClass.simpleName}")
                ClashCore.noteRestart("后台不能启动服务，先在 App 里运行", app)
                false
            } else { runCatching { app.stopService(Intent(app, ClashService::class.java)) }; true }
        }
    }
}

/** [Mines-38.9.10 i28] phone reboot / app update → the Clash service comes back when the switch was on */
class ClashBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ClashCore.enabled(context)) return
        ClashCore.noteRestart(if (a == Intent.ACTION_MY_PACKAGE_REPLACED) "更新后自动启动" else "开机自动启动", context)
        if (!ClashService.sync(context)) Thread({ runCatching { ClashCore.start(context.applicationContext) } }, "mines-clash-boot").start()
    }
}
