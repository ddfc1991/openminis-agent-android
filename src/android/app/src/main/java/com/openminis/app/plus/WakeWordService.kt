package com.openminis.app.plus

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.speech.OfflineAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * [Mines-wake-25] Offline wake word: listens with Vosk's small Chinese model
 * (no network, no key; Porcupine not used) and, on the user's phrase, opens
 * the live voice screen and makes the desk pet wave / talk.
 *
 * Free-form recognition with fuzzy matching against the phrase, not a fixed
 * grammar: a grammar forces every sound into the phrase and fires on noise.
 * The mic is released while a voice call is open (PetBus.inCall) and taken
 * back afterwards. Costs battery while on — the settings say so.
 */
class WakeWordService : Service() {
    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL = "mines_wake"
        private const val NOTIF_ID = 7302
        private const val RATE = 16000
        const val ACTION_STOP = "com.openminis.mines.WAKE_STOP"

        fun prepare(context: Context): Boolean =
            OfflineAsrEngine.modelReady(context, OfflineAsrEngine.MODEL_ZH) ||
                OfflineAsrEngine.ensureModel(context, OfflineAsrEngine.MODEL_ZH)

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            if (!OfflineAsrEngine.modelReady(context, OfflineAsrEngine.MODEL_ZH)) return
            runCatching { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
                .onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }

        /** Loose match: the phrase's characters appear in order, allowing one miss for 4+ chars. */
        fun matches(heard: String, phrase: String): Boolean {
            val h = heard.replace(" ", "")
            val p = phrase.replace(" ", "").trim()
            if (p.isEmpty() || h.isEmpty()) return false
            if (h.contains(p)) return true
            var i = 0; var hit = 0
            for (c in p) { val j = h.indexOf(c, i); if (j >= 0) { hit++; i = j + 1 } }
            return p.length >= 4 && hit >= p.length - 1 && h.length <= p.length + 6
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var lastWake = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PlusPrefs.update(this) { it.copy(wakeWord = false) }
            stopSelf(); return START_NOT_STICKY
        }
        PlusPrefs.init(this)
        foreground()
        if (loop?.isActive != true) loop = scope.launch { listen() }
        return START_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "唤醒词", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val stop = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("在听唤醒词「${PlusPrefs.state.value.wakeText}」")
            .setContentText("完全离线。会多耗一点电。")
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "关闭唤醒词", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen() {
        val model = runCatching { Model(OfflineAsrEngine.modelDir(this, OfflineAsrEngine.MODEL_ZH).absolutePath) }
            .getOrElse { Log.w(TAG, "model: ${it.message}"); stopSelf(); return }
        try {
            while (scope.isActive) {
                // Give the mic to the voice call while it is open.
                if (PetBus.inCall.value || PetBus.micExclusive.value) { delay(500); continue }
                val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 3200) * 2)
                }.getOrNull()
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); delay(5000); continue }
                val recognizer = Recognizer(model, RATE.toFloat())
                val buf = ByteArray(3200)
                try {
                    rec.startRecording()
                    PetBus.wakeMicHeld.value = true
                    while (scope.isActive && !PetBus.inCall.value && !PetBus.micExclusive.value) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { delay(50); continue }
                        val final = recognizer.acceptWaveForm(buf, n)
                        val text = runCatching {
                            if (final) JSONObject(recognizer.result).optString("text")
                            else JSONObject(recognizer.partialResult).optString("partial")
                        }.getOrDefault("")
                        if (text.isNotBlank() && matches(text, PlusPrefs.state.value.wakeText) &&
                            System.currentTimeMillis() - lastWake > 4000) {
                            lastWake = System.currentTimeMillis()
                            recognizer.reset()
                            onWake()
                            break
                        }
                        if (final) recognizer.reset()
                    }
                } finally {
                    runCatching { rec.stop() }; rec.release(); recognizer.close()
                    PetBus.wakeMicHeld.value = false
                }
                delay(800)
            }
        } finally { model.close() }
    }

    private fun onWake() {
        Log.i(TAG, "wake word heard")
        PetBus.wake.value = System.currentTimeMillis()
        // [Mines-voice-31] With the desk pet on, the call starts right where the user is
        // (transcript next to the pet) instead of switching to the voice page.
        if (VoiceSession.active) return
        if (android.provider.Settings.canDrawOverlays(this) && PlusPrefs.state.value.petEnabled && PlusPrefs.state.value.petTapVoice &&
            runCatching { startActivity(Intent(this, VoiceTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }.isSuccess) return
        com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.VOICE)
        // Allowed from the background because the app holds "display over other
        // apps" (the desk pet); without it the notification below opens it.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        if (!android.provider.Settings.canDrawOverlays(this)) runCatching {
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID + 1, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("我在！点这里开始语音聊天")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
