package com.openminis.app.plus

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [Mines-voice-31] The live voice call, app-wide.
 *
 * build-30 created GeminiLive inside VoiceLiveScreen, so leaving the page hung
 * up. The call now lives here, kept alive by [VoiceCallService] (foreground,
 * type microphone). VoiceLiveScreen is only a view onto it; the desk pet shows
 * the running transcript and starts / stops the call with one tap.
 */
object VoiceSession {
    private val _live = MutableStateFlow<GeminiLive?>(null)
    val live: StateFlow<GeminiLive?> = _live.asStateFlow()
    /** Last user line and last answer line, for the desk pet bubble. */
    val lastUser = MutableStateFlow("")
    val lastAnswer = MutableStateFlow("")
    /** Bumps on every transcript update (pet refreshes its bubble). */
    val transcriptTick = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJobs: List<Job> = emptyList()

    /**
     * [Mines-voice-35.2] Backup channel of the same call (phone speech recognition → Mines agent
     * → external / Edge voice). Started by itself when Gemini rejects the setup, model or key or
     * no route works, so the user is never left on an error; or by the user (普通语音模式).
     */
    private val _backup = MutableStateFlow<PlainVoice?>(null)
    val backup: StateFlow<PlainVoice?> = _backup.asStateFlow()

    val active: Boolean get() = _live.value != null || _backup.value != null

    /** Moves the call to the backup channel; [why] is shown in small text (null = user's choice). */
    fun startBackup(context: Context, why: String?) {
        val c = context.applicationContext
        val g = _live.value
        _live.value = null
        watchJobs.forEach { it.cancel() }; watchJobs = emptyList()
        g?.stop()
        _backup.value?.let { it.setNote(why); it.start(); return }
        val p = PlainVoice(c, why?.let { "备用通道（手机识别 + Mines + 朗读）· $it" })
        _backup.value = p
        PetBus.inCall.value = true
        runCatching { ContextCompat.startForegroundService(c, Intent(c, VoiceCallService::class.java)) }
        watchJobs = listOf(
            scope.launch { p.phase.collect { PetBus.talking.value = it == PlainVoice.Phase.SPEAKING } },
            scope.launch {
                p.lines.collect { ls ->
                    ls.lastOrNull { it.user }?.let { lastUser.value = it.text.trim() }
                    ls.lastOrNull { !it.user }?.let { lastAnswer.value = it.text.trim() }
                    transcriptTick.value = System.currentTimeMillis()
                }
            },
        )
        if (hasMic(c)) p.start()
    }

    fun hasMic(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    enum class StartResult { OK, NO_KEY, NO_MIC, ALREADY }

    /** Must be called while Mines is in the foreground (an Activity is visible): Android only lets a
     *  microphone foreground service start then. The pet goes through [VoiceTrampolineActivity]. */
    fun start(context: Context): StartResult {
        val c = context.applicationContext
        if (_live.value != null) return StartResult.ALREADY
        _backup.value?.let { it.stop(); _backup.value = null }
        PlusVault.init(c)
        VoiceDiag.init(c)
        // [Mines-38.1] the current character's own voice (Kimi → Kimi's, Claude → Claude's)
        runCatching { CharacterVoices.apply(c) }
        val key = GeminiRoute.apiKey() ?: return StartResult.NO_KEY
        if (!hasMic(c)) return StartResult.NO_MIC
        val userVoice = PlusVault.extra("gemini", "voice").takeIf { PlusVault.extra("gemini", "voice_user") == "1" }
        val g = GeminiLive(
            key, PlusVault.extra("gemini", "model") ?: GeminiLive.DEFAULT_MODEL,
            userVoice ?: GeminiLive.DEFAULT_VOICE,
            // [Mines-voice-33] speed/pitch only apply to the external (cloned) voice; Gemini's own audio plays as sent
            // [Mines-tts-34] external voice (OpenRouter → Edge → phone TTS) or the cloned voice: Gemini audio muted,
            // speed / pitch applied on playback. Gemini's own voice plays as sent.
            speed = when { VoiceClone.settings(c).enabled -> PlusVault.extra("gemini", "speed")?.toFloatOrNull() ?: 1f
                ExternalTts.active(c) -> ExternalTts.settings(c).speed; else -> 1f },
            pitch = when { VoiceClone.settings(c).enabled -> PlusVault.extra("gemini", "pitch")?.toFloatOrNull() ?: 1f
                ExternalTts.active(c) -> ExternalTts.settings(c).pitch; else -> 1f },
            appContext = if (PlusVault.extra("gemini", "phone") != "0") c else null,
            cloneVoice = VoiceClone.speaker(c) ?: ExternalTts.speaker(c),
            systemPrompt = MinesVoiceBridge.persona(c),
            bridgeContext = c.applicationContext,
        )
        _live.value = g
        lastUser.value = ""; lastAnswer.value = ""
        PetBus.inCall.value = true
        runCatching { ContextCompat.startForegroundService(c, Intent(c, VoiceCallService::class.java)) }
        watchJobs.forEach { it.cancel() }
        watchJobs = listOf(
            scope.launch { g.phase.collect { PetBus.talking.value = it == GeminiLive.Phase.SPEAKING } },
            // [Mines-voice-35.2] Gemini can't carry the call → continue on the backup channel by itself
            scope.launch {
                g.fallback.collect { why ->
                    if (why != null && _live.value === g) {
                        android.util.Log.i("MinesVoice", "fallback to backup channel: $why")
                        startBackup(c, why)
                    }
                }
            },
            scope.launch {
                g.lines.collect { ls ->
                    ls.lastOrNull { it.user }?.let { lastUser.value = it.text.trim() }
                    ls.lastOrNull { !it.user }?.let { lastAnswer.value = it.text.trim() }
                    transcriptTick.value = System.currentTimeMillis()
                }
            },
        )
        g.start()
        return StartResult.OK
    }

    fun stop(context: Context) {
        val g = _live.value
        _live.value = null
        _backup.value?.stop(); _backup.value = null
        watchJobs.forEach { it.cancel() }; watchJobs = emptyList()
        g?.stop()
        PetBus.inCall.value = false
        PetBus.talking.value = false
        transcriptTick.value = System.currentTimeMillis()
        runCatching { context.applicationContext.stopService(Intent(context.applicationContext, VoiceCallService::class.java)) }
    }

    /** Settings changed: hang up and call again with the new settings. */
    fun restart(context: Context) {
        if (_live.value == null) return
        stop(context); start(context)
    }

    fun toggle(context: Context): StartResult =
        if (active) { stop(context); StartResult.ALREADY } else start(context)
}

/** [Mines-voice-31] Keeps the call (and the mic) alive when Mines is not on screen. */
class VoiceCallService : Service() {
    companion object {
        private const val CHANNEL = "mines_voice_call"
        private const val NOTIF_ID = 7311
        const val ACTION_HANGUP = "com.openminis.app.plus.VOICE_HANGUP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            VoiceSession.stop(this); stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "语音通话", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("mines_route", com.openminis.app.ui.plus.PlusRoutes.VOICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hang = PendingIntent.getService(this, 12, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("语音通话中")
            .setContentText("离开页面也在听。点桌宠或这里的「挂断」结束。")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "挂断", hang).build())
            .setOngoing(true)
            .build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        }
        if (ok.isFailure) {
            // Android refused a background mic service: the call still runs while Mines is open.
            android.util.Log.w("VoiceCallService", "startForeground refused", ok.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        if (!VoiceSession.active) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }
}

/**
 * [Mines-voice-31] Invisible one-frame activity the desk pet opens on tap: it
 * brings Mines to the foreground for an instant so Android allows the mic
 * service, asks for the mic permission when needed, toggles the call, and
 * closes. One tap for the user, nothing to navigate.
 */
class VoiceTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VoiceSession.active && !VoiceSession.hasMic(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        act()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) act()
        else { Toast.makeText(this, "需要麦克风权限才能语音聊天", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun act() {
        when (VoiceSession.toggle(this)) {
            VoiceSession.StartResult.OK -> Toast.makeText(this, "语音已开始，直接说话", Toast.LENGTH_SHORT).show()
            VoiceSession.StartResult.ALREADY -> Toast.makeText(this, "语音已结束", Toast.LENGTH_SHORT).show()
            VoiceSession.StartResult.NO_KEY -> {
                // No Gemini key yet: open the voice page, which offers 账号连接 / 普通语音模式.
                com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.VOICE)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            VoiceSession.StartResult.NO_MIC -> Toast.makeText(this, "需要麦克风权限才能语音聊天", Toast.LENGTH_LONG).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
