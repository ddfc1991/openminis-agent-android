package com.openminis.app.plus

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.MainActivity
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Desk pet: the Mines avatar floating over other apps.
 *
 * [Mines-pet-25] Reworked after build-24 feedback ("挡住内容", "只有 3 个表情"):
 *  - Animation states drawn from the chibi art with Android transforms:
 *    idle breathing + blink-dip, wave, walk along the screen edge, sleep after
 *    3 min idle (Zz), happy bounce, thinking sway (while jobs run), talking
 *    (mouth flap between expressions during a voice call), drag squash/tilt.
 *  - Stays out of the way: snaps to the nearest edge, tucks half behind it
 *    when idle, fades + shrinks while you touch or type in another app,
 *    size slider, and a pass-through mode (touches go to the app below;
 *    toggled from the notification).
 *  - Useful: tap → status panel with running Mines tasks, VPS jobs, model
 *    balances (DeepSeek / 硅基流动 / Kimi / OpenRouter), and quick actions
 *    (voice chat, open Mines, plugin widget, pass-through, tuck away).
 *    Task finished / failed → speech bubble.
 *  - Voice: 「语音聊天」 in the panel or the offline wake word opens the live
 *    voice session; the pet shows the talking animation while it speaks.
 * Needs "display over other apps".
 */
class DeskPetService : Service() {

    companion object {
        private const val CHANNEL = "minis_plus_pet"
        private const val NOTIF_ID = 7301
        const val ACTION_STOP = "com.openminis.app.plus.PET_STOP"
        const val ACTION_PASS = "com.openminis.app.plus.PET_PASS"
        const val ACTION_REFRESH = "com.openminis.app.plus.PET_REFRESH"

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canShow(context)) return
            runCatching { context.startForegroundService(Intent(context, DeskPetService::class.java)) }
        }

        /** Re-read size / behaviour settings in a running pet. */
        fun refresh(context: Context) {
            if (!canShow(context)) return
            runCatching { context.startService(Intent(context, DeskPetService::class.java).setAction(ACTION_REFRESH)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DeskPetService::class.java))
        }
    }

    private var wm: WindowManager? = null
    private var petView: PetView? = null
    private var petLp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var switcher: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null
    private var bubbleJob: Job? = null
    private var fadeJob: Job? = null
    private var snapAnim: ValueAnimator? = null
    private var tucked = false
    private var lastTouch = System.currentTimeMillis()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    // [Mines-pet-26] Real window size (follows rotation / foldables), and the area
    // outside status bar, navigation bar and display cutout.
    private val screenW: Int get() = if (Build.VERSION.SDK_INT >= 30) runCatching { wm!!.currentWindowMetrics.bounds.width() }.getOrDefault(resources.displayMetrics.widthPixels) else resources.displayMetrics.widthPixels
    private val screenH: Int get() = if (Build.VERSION.SDK_INT >= 30) runCatching { wm!!.currentWindowMetrics.bounds.height() }.getOrDefault(resources.displayMetrics.heightPixels) else resources.displayMetrics.heightPixels
    private fun safe(): android.graphics.Rect {
        val r = android.graphics.Rect(0, dp(24), screenW, screenH - dp(24))
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val m = wm!!.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            r.set(ins.left, ins.top, m.bounds.width() - ins.right, m.bounds.height() - ins.bottom)
        }
        return r
    }
    private val st get() = PlusPrefs.state.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PlusPrefs.update(this) { it.copy(petEnabled = false) }
                stopSelf(); return START_NOT_STICKY
            }
            ACTION_PASS -> {
                PlusPrefs.update(this) { it.copy(petPassThrough = !it.petPassThrough) }
                applyFlags(); goForeground()
                return START_STICKY
            }
            ACTION_REFRESH -> { applySize(); applyFlags(); goForeground(); return START_STICKY }
        }
        goForeground()
        if (petView == null && canShow(this)) addPet()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "桌宠", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun svc(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, DeskPetService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pass = st.petPassThrough
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (pass) "桌宠：穿透模式（点不到它）" else "Mines 桌宠在桌面上")
            .setContentText(if (pass) "点「关闭穿透」恢复点按" else "点它看任务和余额，双击打开 Mines，长按更多")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, if (pass) "关闭穿透" else "穿透模式", svc(2, ACTION_PASS)).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "收起桌宠", svc(1, ACTION_STOP)).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    /**
     * [Mines-pet-26] Size follows the screen: the slider's default (96) is 16 % of
     * the shorter side (≈ 60–70 dp on a phone, was a 125 dp window); 56…180 on the
     * slider = ~9 %…30 %. The window is the sprite plus a little headroom for
     * jumps — no big transparent rectangle around it to catch touches.
     */
    private fun petPx(): Int {
        // [Mines-pet-32] user (partially sighted): far bigger. 150 = 1/3 of the shorter side,
        // slider 70…340 (max ≈ 2.5× build-31's max); never below 96 dp so it stays easy to hit.
        val minDim = min(screenW, screenH)
        val frac = 0.33f * (st.petSizeDp.coerceIn(70, 340) / 150f)
        return (minDim * frac).toInt().coerceIn(dp(96), (minDim * 0.85f).toInt().coerceAtLeast(dp(96)))
    }
    private fun windowW() = petPx()
    private fun windowH() = (petPx() * 1.16f).toInt()

    /** Keep the pet inside the safe area (half-tucked at the side edges is allowed). */
    private fun clampIntoSafe() {
        val lp = petLp ?: return; val v = petView ?: return
        val r = safe()
        // [Mines-pet-32] half-hidden at the edge, but at least 72 dp stays visible / tappable
        val hide = tuckHide(lp.width)
        val minX = if (tucked) r.left - hide else r.left - dp(6)
        val maxX = if (tucked) r.right - lp.width + hide else r.right - lp.width + dp(6)
        lp.x = lp.x.coerceIn(minX, max(minX, maxX))
        lp.y = lp.y.coerceIn(r.top, max(r.top, r.bottom - lp.height))
        v.edge = when { lp.x <= r.left + dp(2) -> -1; lp.x >= r.right - lp.width - dp(2) -> 1; else -> 0 }
        Overlays.update(wm, v, lp); moveBubble()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applySize()
        clampIntoSafe()
        if (st.petEdgeSnap && !tucked) snapToEdge()
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (st.petAutoFade) f = f or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (st.petPassThrough) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun applyFlags() {
        val lp = petLp ?: return; val v = petView ?: return
        lp.flags = baseFlags()
        v.alpha = if (st.petPassThrough) 0.55f else 1f
        Overlays.update(wm, v, lp)
    }

    private fun applySize() {
        val lp = petLp ?: return; val v = petView ?: return
        val w = windowW()
        lp.width = w; lp.height = windowH()
        lp.x = lp.x.coerceIn(-w / 2, screenW - w / 2)
        Overlays.update(wm, v, lp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPet() {
        PlusPrefs.init(this)
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val w = windowW()
        val lp = WindowManager.LayoutParams(
            w, windowH(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = safe().right - w + dp(6)
            y = (screenH * 0.62f).toInt()
        }
        petLp = lp
        val view = PetView(this)
        petView = view
        if (PetBus.voicePageOpen.value) { view.visibility = android.view.View.GONE; lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
        view.alpha = if (st.petPassThrough) 0.55f else 1f

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        var lastMoveX = 0f; var lastMoveT = 0L
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tucked) { untuck(); return true }
                view.react(PetView.Mode.JUMP, 650)
                // [Mines-voice-31] One tap = start / stop the live voice call.
                if (st.petTapVoice) { closePanel(); toggleVoice(); return true }
                if (st.petPanel) togglePanel() else say(PetView.LINES.random())
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                view.react(PetView.Mode.HAPPY, 1500)
                if (st.petTapVoice && st.petPanel) togglePanel() else openApp(null)
                return true
            }
            override fun onLongPress(e: MotionEvent) { if (!dragging) showSwitcher() }
        })
        // [Mines-pet-32] pinch on the pet itself to resize it
        var pinching = false
        val scaler = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pinching = true; return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val next = (st.petSizeDp * d.scaleFactor).toInt().coerceIn(70, 340)
                if (next != st.petSizeDp) { PlusPrefs.update(this@DeskPetService) { it.copy(petSizeDp = next) }; applySize(); clampIntoSafe() }
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { pinching = false }
        })
        view.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutsideTouch(); return@setOnTouchListener false }
            scaler.onTouchEvent(ev)
            if (pinching || ev.pointerCount > 1) { dragging = false; return@setOnTouchListener true }
            lastTouch = System.currentTimeMillis()
            view.wakeUp()
            restore()
            // [Mines-pet-29] Single taps were being lost: the pet's window
            // moves under a still finger (untuck/restore on touch, walking,
            // snap/peek animations), so the view-local x/y of the touch
            // changed and GestureDetector read it as a drag past its slop.
            // Feed it screen coordinates; only a real finger move counts.
            val gev = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
            gestures.onTouchEvent(gev)
            gev.recycle()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel(); hideJob?.cancel()
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; dragging = false
                    lastMoveX = ev.rawX; lastMoveT = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (dragging || abs(dx) > 12 * density || abs(dy) > 12 * density) {
                        if (!dragging) { dragging = true; closePanel() }
                        tucked = false
                        val now = System.currentTimeMillis()
                        val vx = (ev.rawX - lastMoveX) / max(1L, now - lastMoveT)
                        lastMoveX = ev.rawX; lastMoveT = now
                        view.drag(vx)
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt().coerceIn(safe().top, max(safe().top, safe().bottom - lp.height))
                        Overlays.update(manager, view, lp)
                        moveBubble()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        view.release(); snapToEdge()
                        // [Mines-pet-26] After a move nothing may stay behind at the old spot.
                        if (Overlays.audit().isNotEmpty()) Overlays.repair()
                    }
                    dragging = false
                }
            }
            true
        }
        if (!Overlays.add(manager, "pet", view, lp)) { stopSelf(); return }
        view.react(PetView.Mode.WAVE, 1800)
        scheduleTuck()
        watchEvents()
        walkLoop()
        idleLoop()
    }

    /**
     * [Mines-pet-26] Life when nobody touches it: fades to see-through after
     * 15 s, now and then stretches / yawns / looks around / spins, and when
     * half-hidden at the edge it peeks out and slides back.
     */
    private fun idleLoop() = scope.launch {
        var nextAct = System.currentTimeMillis() + 20_000L
        var nextPeek = System.currentTimeMillis() + 30_000L
        while (isActive) {
            delay(2500)
            val v = petView ?: continue; val lp = petLp ?: continue
            val now = System.currentTimeMillis()
            val idle = now - lastTouch > 15_000 && panel == null && switcher == null && !v.held
            if (idle && fadeJob?.isActive != true) {
                val target = if (st.petPassThrough) 0.4f else if (v.busy) 0.75f else 0.55f
                if (abs(v.alpha - target) > 0.02f) v.animate().alpha(target).setDuration(600).start()
            }
            if (v.sleeping || st.petPassThrough || panel != null) continue
            if (tucked && now > nextPeek) {
                nextPeek = now + (25_000L..50_000L).random()
                val r = safe(); val w = lp.width
                val home = lp.x
                val out = if (home < (r.left + r.right) / 2) home + (w * 0.35f).toInt() else home - (w * 0.35f).toInt()
                v.react(PetView.Mode.PEEK, 2200)
                animateX(out) { scope.launch { delay(1400); if (tucked) animateX(home) } }
                continue
            }
            if (now > nextAct && !v.busy) {
                nextAct = now + (22_000L..45_000L).random()
                val m = listOf(PetView.Mode.STRETCH, PetView.Mode.YAWN, PetView.Mode.LOOK, PetView.Mode.LOOK, PetView.Mode.SPIN, PetView.Mode.JUMP).random()
                v.react(m, when (m) { PetView.Mode.STRETCH -> 1600; PetView.Mode.YAWN -> 1800; PetView.Mode.LOOK -> 2600; PetView.Mode.SPIN -> 900; else -> 650 })
            }
        }
    }

    // ---- staying out of the way --------------------------------------

    private fun onOutsideTouch() {
        if (!st.petAutoFade) return
        val v = petView ?: return
        closePanel()
        v.animate().alpha(0.3f).scaleX(0.72f).scaleY(0.72f).setDuration(160).start()
        fadeJob?.cancel()
        fadeJob = scope.launch { delay(3500); restore() }
    }

    private fun restore() {
        fadeJob?.cancel()
        val v = petView ?: return
        v.animate().alpha(if (st.petPassThrough) 0.55f else 1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun snapToEdge() {
        val lp = petLp ?: return; val v = petView ?: return
        if (!st.petEdgeSnap) { scheduleTuck(); return }
        val w = lp.width
        val r = safe()
        val left = lp.x + w / 2 < (r.left + r.right) / 2
        val target = if (left) r.left - dp(4) else r.right - w + dp(4)
        animateX(target) { scheduleTuck() }
        v.facingLeft = !left
        v.edge = if (left) -1 else 1
    }

    private fun animateX(target: Int, end: () -> Unit = {}) {
        val lp = petLp ?: return; val v = petView ?: return
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 260; interpolator = DecelerateInterpolator()
            addUpdateListener { a -> lp.x = a.animatedValue as Int; Overlays.update(wm, v, lp); moveBubble() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { end() }
            })
            start()
        }
    }

    private fun scheduleTuck() {
        hideJob?.cancel()
        if (!st.petHalfHide) return
        hideJob = scope.launch {
            delay(6000)
            if (panel != null || bubble != null) return@launch
            val lp = petLp ?: return@launch
            val w = lp.width
            val r = safe()
            when {
                lp.x <= r.left -> { tucked = true; animateX(r.left - tuckHide(w)) }
                lp.x >= r.right - w -> { tucked = true; animateX(r.right - w + tuckHide(w)) }
            }
            petView?.animate()?.alpha(0.8f)?.setDuration(200)?.start()
        }
    }

    private fun untuck() {
        val lp = petLp ?: return
        tucked = false
        val w = lp.width
        val r = safe()
        animateX(if (lp.x < (r.left + r.right) / 2) r.left - dp(4) else r.right - w + dp(4)) { scheduleTuck() }
        petView?.react(PetView.Mode.WAVE, 1200)
        restore()
    }

    /** Now and then walk a short way along the edge it sits on. */
    private fun walkLoop() = scope.launch {
        while (isActive) {
            delay((45_000L..90_000L).random())
            val v = petView ?: continue; val lp = petLp ?: continue
            if (!st.petWalk || st.petPassThrough || tucked || panel != null || v.sleeping) continue
            if (System.currentTimeMillis() - lastTouch < 20_000) continue
            val from = lp.y
            val r = safe()
            val to = (from + (if ((0..1).random() == 0) -1 else 1) * dp((60..160).random())).coerceIn(r.top + dp(40), max(r.top + dp(40), r.bottom - lp.height - dp(40)))
            v.walking = true
            val a = ValueAnimator.ofInt(from, to).apply {
                duration = (abs(to - from) * 9L).coerceIn(800L, 2600L)
                addUpdateListener { lp.y = it.animatedValue as Int; Overlays.update(wm, v, lp) }
                start()
            }
            delay(a.duration + 50)
            v.walking = false
            scheduleTuck()
        }
    }

    // ---- reacting to the app ------------------------------------------

    private fun watchEvents() {
        scope.launch {
            MinesJobs.finished.collect { j ->
                if (j.silent) return@collect
                if (j.ok) { petView?.react(PetView.Mode.CELEBRATE, 2600); say("✓ " + j.successText) }
                else { petView?.react(PetView.Mode.SAD, 2500); say("✗ ${j.title}：${j.reason}") }
            }
        }
        scope.launch { MinesJobs.jobs.collect { petView?.thinking = it.any { j -> j.running && !j.silent } } }
        scope.launch { PetBus.talking.collect { petView?.talking = it } }
        // [Mines-voice-35.2] the call page shows the character full size: hide the pet (and its bubble)
        // while it is open so there are never two of them on screen; back when the page closes.
        scope.launch {
            PetBus.voicePageOpen.collect { open ->
                applyVoicePageHide(open)
            }
        }
        // [Mines-pet-32] charging → happy stretch then sits; late night → yawns now and then
        scope.launch {
            var wasCharging = false
            while (true) {
                val bm = getSystemService(android.os.BatteryManager::class.java)
                val charging = runCatching { bm?.isCharging == true }.getOrDefault(false)
                if (charging && !wasCharging) { petView?.react(PetView.Mode.STRETCH, 1600); say("充上电啦，我歇一会儿～") }
                wasCharging = charging
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if ((h >= 23 || h < 6) && petView?.busy == false && Math.random() < 0.25) petView?.react(PetView.Mode.YAWN, 1800)
                delay(60_000)
            }
        }
        // [Mines-voice-31] Transcript bubble during a live call; "通话结束" when it ends.
        scope.launch {
            var lastShown = 0L
            VoiceSession.transcriptTick.collect {
                if (!VoiceSession.active) { if (lastShown > 0) { lastShown = 0; hideBubble() }; return@collect }
                if (PetBus.voicePageOpen.value) return@collect
                val now = System.currentTimeMillis()
                if (now - lastShown < 350) delay(350 - (now - lastShown))
                lastShown = System.currentTimeMillis()
                showTranscript()
            }
        }
        // [Mines-pet-26] Typing pose while the agent works; celebrate when a task ends.
        scope.launch { com.openminis.app.service.SessionActivityTracker.activeSessions.collect { petView?.typing = it.isNotEmpty() } }
        scope.launch {
            var first = true
            com.openminis.app.service.SessionActivityTracker.lastTaskFinishedAtMs.collect { t ->
                if (first) { first = false; return@collect }
                if (t != null) { petView?.react(PetView.Mode.CELEBRATE, 2600); restore() }
            }
        }
        // [Mines-pet-32] Peek out when a new 要处理 message lands in 消息汇总.
        scope.launch {
            val seen = HashSet<String>()
            var first = true
            Digest.items.collect { list ->
                val fresh = list.filter { it.cat == Digest.Cat.ACT && seen.add(it.id) }
                if (first) { first = false; return@collect }
                val top = fresh.firstOrNull() ?: return@collect
                if (petView?.busy == true) return@collect
                if (tucked) untuck()
                petView?.react(PetView.Mode.PEEK, 1600)
                say("新消息：" + (top.headline ?: top.title).take(16))
            }
        }
        scope.launch {
            PetBus.wake.collectLatest { t ->
                if (t == 0L) return@collectLatest
                if (tucked) untuck()
                petView?.react(PetView.Mode.WAVE, 1500)
                say("我在！")
            }
        }
    }

    /** [Mines-voice-31] Through an invisible activity: Android only allows a mic service from the foreground. */
    private fun toggleVoice() {
        say(if (VoiceSession.active) "好，挂断了" else "我在听…", 1500)
        runCatching {
            startActivity(Intent(this, VoiceTrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { say("开不了语音：${it.message}") }
    }

    /** [Mines-voice-31] Live transcript next to the pet: your last line + the answer, newest part only. */
    private fun showTranscript() {
        if (!VoiceSession.active) return
        fun tail(t: String, n: Int) = if (t.length <= n) t else "…" + t.takeLast(n)
        val u = VoiceSession.lastUser.value; val a = VoiceSession.lastAnswer.value
        if (u.isBlank() && a.isBlank()) return
        val text = buildString {
            if (u.isNotBlank()) append("你：").append(tail(u, 36))
            if (a.isNotBlank()) { if (isNotEmpty()) append('\n'); append("答：").append(tail(a, 60)) }
        }
        say(text, 9000)
    }

    private fun say(text: String, ms: Long = 3500) {
        val manager = wm ?: return
        val lp0 = petLp ?: return
        val tv = bubble ?: TextView(this).apply {
            textSize = 17f; setTextColor(Color.parseColor("#111111"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (screenW * 0.6f).toInt()
            maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.START
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * density; setStroke(dp(1), Color.parseColor("#22000000")) }
            elevation = 6 * density
        }.also { v ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = lp0.x; y = lp0.y - dp(44) }
            bubbleLp = lp; bubble = v
            if (!Overlays.add(manager, "pet-bubble", v, lp)) bubble = null
        }
        tv.text = text
        tv.contentDescription = "桌宠：$text"
        petView?.say(null)
        moveBubble()
        bubbleJob?.cancel()
        bubbleJob = scope.launch { delay(ms); hideBubble() }
    }

    private fun moveBubble() {
        val tv = bubble ?: return; val lp = bubbleLp ?: return; val p = petLp ?: return
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = tv.measuredWidth
        lp.x = if (p.x + p.width / 2 > screenW / 2) (p.x - bw + p.width / 3).coerceAtLeast(dp(4)) else (p.x + p.width * 2 / 3).coerceAtMost(screenW - bw - dp(4))
        lp.y = (p.y - maxOf(dp(30), tv.measuredHeight - dp(10))).coerceAtLeast(dp(24))
        Overlays.update(wm, tv, lp)
    }

    /** [Mines-voice-35.2] Hidden = invisible AND not touchable, so the empty window can't eat taps on the call page. */
    private fun applyVoicePageHide(hide: Boolean) {
        val v = petView ?: return; val lp = petLp ?: return
        v.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        val f = if (hide) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else if (st.petPassThrough) lp.flags else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (f != lp.flags) { lp.flags = f; runCatching { Overlays.update(wm, v, lp) } }
        if (hide) { hideBubble(); runCatching { closePanel() } }
    }

    private fun hideBubble() {
        bubble?.let { v -> Overlays.remove(wm, v) }
        bubble = null; bubbleLp = null
        scheduleTuck()
    }

    // ---- status panel ---------------------------------------------------

    private fun closePanel() {
        panel?.let { v -> Overlays.remove(wm, v) }
        panel = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun togglePanel() {
        if (panel != null) { closePanel(); return }
        val manager = wm ?: return
        petView?.react(PetView.Mode.HAPPY, 900)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F7F4EE")); cornerRadius = 22 * density }
            elevation = 12 * density
        }
        fun label(t0: String, size: Float = 15f, color: String = "#2B2522", bold: Boolean = false) = TextView(this).apply {
            val t = AccountLabel.maskText(t0)  // [Mines-privacy-35.3] no e-mail / phone / key on the desk-pet panel
            text = t; textSize = size; setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        root.addView(label(Avatars.name(st.petStyle) + " · 状态", 18f, bold = true))
        // Tasks
        root.addView(label("任务", 13f, "#8A8079"))
        val tasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tasks)
        fun fillTasks() {
            tasks.removeAllViews()
            val run = MinesJobs.jobs.value.filter { it.running }
            val recent = MinesJobs.jobs.value.filter { !it.running }.takeLast(2)
            if (run.isEmpty() && recent.isEmpty()) tasks.addView(label("现在没有在跑的任务", 14f, "#6B625C"))
            run.forEach { tasks.addView(label("⏳ " + it.title + "  " + it.lastLine.take(40), 14f)) }
            recent.forEach { tasks.addView(label((if (it.ok) "✓ " + it.successText else "✗ " + it.title + "：" + it.reason), 14f, if (it.ok) "#1F7A3F" else "#B3261E")) }
        }
        fillTasks()
        // VPS jobs
        val vpsHead = label("服务器任务", 13f, "#8A8079")
        val vps = label("读取中…", 14f, "#6B625C")
        val servers = st.servers.filter { it.enabled && it.authorized }
        if (servers.isNotEmpty()) { root.addView(vpsHead); root.addView(vps) }
        // Balances
        root.addView(label("余额", 13f, "#8A8079"))
        val bal = label("读取中…", 14f, "#6B625C")
        root.addView(bal)
        // [Mines-digest-31] 消息汇总 top 3
        val top = Digest.top(3)
        if (top.isNotEmpty()) {
            root.addView(label("消息（点开看全部）", 13f, "#8A8079"))
            top.forEach { m ->
                root.addView(label("· " + m.cat.label + "｜" + m.summary.ifBlank { m.title }.take(36), 15f).apply {
                    setOnClickListener { closePanel(); openApp(com.openminis.app.ui.plus.PlusRoutes.DIGEST) }
                })
            }
        }
        // Quick actions
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun chip(t: String, bg: String = "#E9E2D6", fg: String = "#2B2522", onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(fg))
            minHeight = dp(46)
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = 14 * density }
            setOnClickListener { onClick() }
        }
        val clp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) } }
        row1.addView(chip(if (VoiceSession.active) "挂断语音" else "🎙 语音聊天", "#D97757", "#FFFFFF") { closePanel(); toggleVoice() }, clp())
        row1.addView(chip("打开 Mines") { closePanel(); openApp(null) }, clp())
        row2.addView(chip("插件挂件") {
            closePanel()
            scope.launch {
                val ok = withContext(Dispatchers.IO) { DshWebHost.ensure(this@DeskPetService) }
                if (ok && PluginWidgetService.canShow(this@DeskPetService)) PluginWidgetService.start(this@DeskPetService)
                else say("挂件没起来，去 Mines → 插件 看看")
            }
        }, clp())
        row2.addView(chip("穿透") { closePanel(); PlusPrefs.update(this) { it.copy(petPassThrough = true) }; applyFlags(); goForeground(); say("现在点不到我啦，通知栏里可以关掉穿透") }, clp())
        row2.addView(chip("收起") { closePanel(); untuckOrTuck() }, clp())
        root.addView(row1); root.addView(row2)

        val lp = WindowManager.LayoutParams(
            min((screenW * 0.92f).toInt(), dp(420)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val p = petLp
            y = if (p != null && p.y > screenH / 2) (p.y - dp(330)).coerceAtLeast(dp(40)) else ((p?.y ?: 0) + (p?.height ?: 0) + dp(8)).coerceAtMost(screenH - dp(360))
        }
        root.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false }
        panel = root
        if (!Overlays.add(manager, "pet-panel", root, lp)) { panel = null; return }
        hideJob?.cancel()
        scope.launch {
            val items = Balances.refresh(this@DeskPetService)
            if (panel === root) bal.text = if (items.isEmpty()) "在 账号连接 里填 DeepSeek / 硅基流动 / Kimi / OpenRouter 的 Key 就能看到余额"
                else items.joinToString("\n") { (if (it.low) "⚠ " else "") + it.name + "  " + it.text }
        }
        if (servers.isNotEmpty()) scope.launch {
            val text = withContext(Dispatchers.IO) {
                servers.take(3).joinToString("\n") { s ->
                    runCatching {
                        val cmd = "cd ~/minis-jobs 2>/dev/null || exit 0; for p in *.pid; do [ -f \"\$p\" ] || continue; n=\${p%.pid}; " +
                            "if [ -f \"\$n.rc\" ]; then st=\"完成(\$(cat \$n.rc))\"; elif kill -0 \$(cat \"\$p\") 2>/dev/null; then st=运行中; else st=已结束; fi; echo \"\$n \$st\"; done | tail -4"
                        val out = VpsSsh.run(this@DeskPetService, s, cmd, timeoutMs = 8000, attempts = 1).first.output.trim()
                        s.name + "：" + out.ifBlank { "没有任务" }.replace("\n", "；")
                    }.getOrElse { s.name + "：连不上" }
                }
            }
            if (panel === root) vps.text = text
        }
        scope.launch { while (panel === root) { fillTasks(); delay(1500) } }
    }

    /** [Mines-pet-32] how much of the pet may hide behind the edge: half, but ≥ 72 dp stays visible. */
    private fun tuckHide(w: Int) = min(w / 2, w - dp(72)).coerceAtLeast(0)

    private fun untuckOrTuck() {
        val lp = petLp ?: return
        if (tucked) { untuck(); return }
        tucked = true
        animateX(if (lp.x < screenW / 2) -tuckHide(lp.width) else screenW - lp.width + tuckHide(lp.width))
    }

    private fun openApp(route: String?) {
        if (route != null) com.openminis.app.ui.plus.PlusNav.go(route)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Long-press panel: pick another character, size, behaviour, or tuck the pet away. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showSwitcher() {
        if (switcher != null) return
        closePanel()
        val manager = wm ?: return
        val d = density
        val panelV = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F4F0E8")); cornerRadius = 24 * d }
            elevation = 12 * d
        }
        panelV.addView(TextView(this).apply {
            text = "换个形象"; textSize = 20f; setTextColor(Color.parseColor("#2B2522"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        fun close() { switcher?.let { v -> Overlays.remove(manager, v) }; switcher = null }
        val grid = android.widget.GridLayout(this).apply { columnCount = 4; setPadding(0, dp(10), 0, dp(6)) }
        val choices = Avatars.builtins.map { it.id to it.name } +
            (if (Avatars.hasCustom(this)) listOf(Avatars.CUSTOM to "我的") else emptyList())
        choices.forEach { (id, name) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(8))
                setOnClickListener {
                    PlusPrefs.update(this@DeskPetService) { it.copy(petStyle = id) }
                    petView?.invalidate(); petView?.react(PetView.Mode.WAVE, 1500); close()
                }
            }
            cell.addView(AvatarView(this).apply { style = id; face = true }, LinearLayout.LayoutParams(dp(60), dp(60)))
            cell.addView(TextView(this).apply {
                text = name; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#2B2522"))
                if (st.petStyle == id) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            grid.addView(cell)
        }
        panelV.addView(grid)
        // Size
        val sizeLabel = TextView(this).apply { textSize = 17f; setTextColor(Color.parseColor("#2B2522")); text = "大小 ${st.petSizeDp}" }
        panelV.addView(sizeLabel)
        panelV.addView(android.widget.SeekBar(this).apply {
            max = 270; progress = st.petSizeDp - 70
            contentDescription = "桌宠大小"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    PlusPrefs.update(this@DeskPetService) { it.copy(petSizeDp = 70 + p) }
                    sizeLabel.text = "大小 ${56 + p}"; applySize()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        })
        fun button(label: String, bg: String, fg: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(fg))
            minHeight = dp(50)
            background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = 14 * d }
            setOnClickListener { onClick() }
        }
        val lpBtn = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        panelV.addView(button("更多桌宠设置 / 从相册选图…", "#D97757", "#FFFFFF") {
            close(); openApp(com.openminis.app.ui.plus.PlusRoutes.PERSONALIZE)
        }, lpBtn)
        panelV.addView(button("收起桌宠", "#E7E1D6", "#2B2522") {
            close(); PlusPrefs.update(this@DeskPetService) { it.copy(petEnabled = false) }; stopSelf()
        }, lpBtn)
        panelV.addView(button("关闭", "#00000000", "#6B625C") { close() }, lpBtn)

        val lp = WindowManager.LayoutParams(
            (screenW * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        panelV.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { close(); true } else false }
        switcher = panelV
        if (!Overlays.add(manager, "pet-switcher", panelV, lp)) switcher = null
    }

    override fun onDestroy() {
        scope.cancel()
        snapAnim?.cancel()
        listOf(switcher, panel, bubble, petView).forEach { v -> v?.let { Overlays.remove(wm, it) } }
        switcher = null; panel = null; bubble = null; petView = null
        // [Mines-pet-26] Nothing of the pet may stay behind on screen.
        listOf("pet", "pet-bubble", "pet-panel", "pet-switcher").forEach { Overlays.removeAll(it) }
        super.onDestroy()
    }

    /**
     * The pet itself. One chibi bitmap per expression, animated with canvas
     * transforms: breathing, blink-dip, wave (rocking from the feet), walk
     * (step bob + lean), sleep (slow breath, tilt, Zz), happy (bounce),
     * sad (shake), thinking (sway + dots), talking (expression flap), drag
     * (squash / stretch and lean with the finger's speed).
     */
    class PetView(context: Context) : View(context) {
        enum class Mode { IDLE, WAVE, HAPPY, SAD, JUMP, STRETCH, YAWN, LOOK, SPIN, CELEBRATE, PEEK }
        companion object {
            val LINES = listOf("我在这儿陪你～", "今天也要加油！", "有事双击我", "长按我可以换形象", "服务器我盯着呢", "喝口水吧", "摸摸头 (•ᴗ•)")
            private const val SLEEP_AFTER_MS = 180_000L
        }
        private val renderer = AvatarRenderer(context).also { r ->
            r.callback = object : android.graphics.drawable.Drawable.Callback {
                override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = invalidate()
                override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {
                    postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
                }
                override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { removeCallbacks(what) }
            }
        }
        private val start = System.currentTimeMillis()
        private var mode = Mode.IDLE
        private var modeUntil = 0L
        private var modeStart = 0L
        private var lastActive = System.currentTimeMillis()
        @Volatile var thinking = false
        @Volatile var talking = false
        @Volatile var walking = false
        /** [Mines-pet-26] The agent is working: typing pose. */
        @Volatile var typing = false
        /** Edge it sits against: -1 left, 1 right, 0 free (sitting pose). */
        @Volatile var edge = 0
        var facingLeft = true
        private var dragVx = 0f
        var held = false
            private set
        val busy: Boolean get() = held || walking || talking || typing || thinking || mode != Mode.IDLE
        private var releaseAt = 0L
        private var line: String? = null
        private var lineUntil = 0L
        val sleeping get() = !held && mode == Mode.IDLE && !talking && !thinking && System.currentTimeMillis() - lastActive > SLEEP_AFTER_MS
        private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7A8CA8"); isFakeBoldText = true }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D97757") }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }
        private val confetti = Paint(Paint.ANTI_ALIAS_FLAG)
        private val kbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 60, 52, 48) }
        private val CONFETTI = intArrayOf(Color.parseColor("#D97757"), Color.parseColor("#F4C542"), Color.parseColor("#5AC8FA"), Color.parseColor("#34C759"), Color.parseColor("#AF52DE"))
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        // [Mines-pet-32] speech text large (≥ 16 sp) and dark on white for the partially sighted user
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111"); textSize = 17f * context.resources.displayMetrics.scaledDensity; isFakeBoldText = true
        }
        // [Mines-pet-32] frame animation state
        private val sprite = PetSprite()
        private var lastFrameNs = 0L
        private var nextBlinkAt = System.currentTimeMillis() + 2500
        private var blinkUntil = 0L
        private var exprAlpha = 0f
        private var exprShown = Avatars.NEUTRAL
        private val exprBmp = HashMap<String, android.graphics.Bitmap>()
        private var frames = 0; private var janks = 0; private var statAt = System.currentTimeMillis()
        private val choreo = android.view.Choreographer.getInstance()
        private var vsyncPending = false
        private var skip = false
        private val frameCb = android.view.Choreographer.FrameCallback { vsyncPending = false; invalidate() }

        init { contentDescription = "Mines 桌宠。单击看状态，双击打开 Mines，长按更多" }

        fun react(m: Mode, ms: Long) { mode = m; modeStart = System.currentTimeMillis(); modeUntil = modeStart + ms; wakeUp() }
        fun wakeUp() { lastActive = System.currentTimeMillis(); invalidate() }
        fun drag(vx: Float) { held = true; dragVx = dragVx * 0.6f + vx * 0.4f; wakeUp() }
        fun release() { held = false; releaseAt = System.currentTimeMillis(); dragVx = 0f; wakeUp() }
        /** In-view one-liner (used when the status panel is off); null clears it. */
        fun say(text: String?) { line = text; lineUntil = System.currentTimeMillis() + 3000; wakeUp() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val st = PlusPrefs.state.value
            val now = System.currentTimeMillis()
            if (now > modeUntil && mode != Mode.IDLE) mode = Mode.IDLE
            PetBus.forceMode?.let { fm -> PetBus.forceMode = null; runCatching { react(Mode.valueOf(fm), 3500) } }
            val t = (now - start) / 1000f
            val mt = (now - modeStart) / 1000f
            val pet = min(width.toFloat(), height / 1.16f)
            val left = (width - pet) / 2f
            val top = height - pet - pet * 0.03f
            var dy = 0f
            val sleep = sleeping

            var bob = sin(t * (if (sleep) 0.9f else 1.6f)) * (if (sleep) 0.03f else 0.04f)
            var sway = sin(t * 0.9f + 1.1f) * 0.015f
            var sx = 1f; var sy = 1f; var rot = 0f
            // blink-dip every ~4 s
            if (!sleep && ((t * 1000f) % 4200f) < 110f) sy *= 0.96f
            var expression = Avatars.NEUTRAL
            // [Mines-pet-34] articulated body pose (head about the neck, torso about the feet, upper-body turn, nod)
            var pTilt = if (sleep) 0f else sin(t * 0.7f + 0.4f) * 3.5f     // idle: slow head tilt
            var pLean = if (sleep) 0f else sin(t * 0.45f) * 1.6f           // idle: weight shift
            var pTurn = 0f; var pNod = 0f
            when {
                held -> {
                    rot = (dragVx * 18f).coerceIn(-22f, 22f)
                    sx = 1f + min(abs(dragVx) * 0.08f, 0.12f); sy = 1f / sx
                    bob = -0.05f; expression = Avatars.HAPPY
                }
                now - releaseAt < 450 -> {
                    val k = (now - releaseAt) / 450f
                    sy = 1f - 0.12f * sin(k * Math.PI.toFloat()); sx = 1f + 0.08f * sin(k * Math.PI.toFloat())
                    expression = Avatars.HAPPY
                }
                mode == Mode.WAVE -> { pLean = sin(mt * 7f) * 8f; pTilt = -sin(mt * 7f) * 7f; pTurn = sin(mt * 3.5f) * 0.25f; expression = Avatars.HAPPY }
                mode == Mode.HAPPY -> { bob = -abs(sin(mt * 9f)) * 0.12f; pTilt = sin(mt * 4.5f) * 8f; pLean = sin(mt * 4.5f) * 3f; expression = Avatars.LAUGH }
                mode == Mode.SAD -> { sway = sin(mt * 30f) * 0.03f; rot = -6f; expression = Avatars.NEUTRAL }
                mode == Mode.JUMP -> {
                    val k = (mt / 0.65f).coerceIn(0f, 1f)
                    dy = -sin(k * Math.PI.toFloat()) * pet * 0.14f
                    if (k < 0.15f || k > 0.85f) { sy = 0.9f; sx = 1.06f }
                    expression = Avatars.LAUGH
                }
                mode == Mode.STRETCH -> {
                    val k = sin((mt / 1.6f).coerceIn(0f, 1f) * Math.PI.toFloat())
                    sy = 1f + 0.12f * k; sx = 1f - 0.05f * k; expression = Avatars.HAPPY
                }
                mode == Mode.YAWN -> {
                    val k = sin((mt / 1.8f).coerceIn(0f, 1f) * Math.PI.toFloat())
                    sy = 1f + 0.05f * k; rot = (if (facingLeft) -4f else 4f) * k
                    expression = if (k > 0.4f) Avatars.LAUGH else Avatars.NEUTRAL
                }
                mode == Mode.LOOK -> {
                    sway = sin(mt * 2.6f) * 0.03f; pTurn = sin(mt * 1.3f); pTilt = sin(mt * 1.3f) * 9f
                    facingLeft = sin(mt * 1.3f) < 0f
                }
                mode == Mode.SPIN -> {
                    val k = (mt / 0.9f).coerceIn(0f, 1f)
                    sx = kotlin.math.cos(k * 2f * Math.PI.toFloat()).let { if (abs(it) < 0.08f) 0.08f * kotlin.math.sign(it + 0.0001f) else it }
                    expression = Avatars.HAPPY
                }
                mode == Mode.CELEBRATE -> {
                    dy = -abs(sin(mt * 7f)) * pet * 0.1f; rot = sin(mt * 7f) * 6f; expression = Avatars.LAUGH
                }
                mode == Mode.PEEK -> { rot = if (edge < 0) 12f else -12f; sway = sin(mt * 3f) * 0.02f; expression = Avatars.HAPPY }
                talking -> { expression = if (((t * 6f).toInt() % 2) == 0) Avatars.HAPPY else Avatars.LAUGH; bob = sin(t * 8f) * 0.02f; pNod = (0.5f + 0.5f * sin(t * 5.5f)) * 0.7f; pTilt = sin(t * 1.7f) * 5f }
                walking -> { bob = -abs(sin(t * 10f)) * 0.07f; rot = if (facingLeft) -2f else 2f; pLean = (if (facingLeft) -5f else 5f) + sin(t * 10f) * 1.5f; pTurn = if (facingLeft) -0.6f else 0.6f; expression = Avatars.HAPPY }
                typing -> { bob = sin(t * 14f) * 0.012f; sy *= 0.97f; expression = if (((t * 0.7f).toInt() % 2) == 0) Avatars.NEUTRAL else Avatars.HAPPY }
                thinking -> { sway = sin(t * 2.2f) * 0.02f; pTilt = 11f + sin(t * 1.1f) * 3f; pLean = -2f }
                sleep -> { rot = if (facingLeft) -10f else 10f }
                line != null && now < lineUntil -> expression = Avatars.HAPPY
                edge != 0 -> { rot = edge * 5f; sy *= 0.96f }   // sitting, leaning on the edge
            }
            // ground shadow
            canvas.drawOval(RectF(left + pet * 0.2f, height - pet * 0.08f, left + pet * 0.8f, height - pet * 0.01f), shadow)
            canvas.save()
            canvas.translate(0f, dy)
            canvas.rotate(rot, left + pet / 2f, top + pet)
            canvas.scale(sx, sy, left + pet / 2f, top + pet)
            canvas.translate(left, top)
            // [Mines-pet-32] real frames when this character has them: mesh breathing + crossfaded blink / visemes / think
            val set = PetFrames.load(context, st.petStyle, st.avatarVersion)
            val nowNs = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0.016f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.25f)
            if (lastFrameNs != 0L) {
                frames++; if (dt > 0.025f && (held || talking || mode != Mode.IDLE)) janks++
                if (now - statAt > 10_000) {
                    android.util.Log.i("MinesPet", "fps=${frames * 1000 / (now - statAt).coerceAtLeast(1)} jank=${if (frames > 0) janks * 100 / frames else 0}% frames=${if (set != null) set.frames.keys.joinToString(",") else "none"}")
                    frames = 0; janks = 0; statAt = now
                }
            }
            lastFrameNs = nowNs
            if (set != null) {
                if (now >= nextBlinkAt) { blinkUntil = now + 130; nextBlinkAt = now + 2000 + (Math.random() * 4000).toLong() }
                val speaking = talking && now - PetBus.levelAt < 300
                val lv = if (speaking) PetBus.level else if (talking) (0.35f + 0.35f * sin(t * 13f) * sin(t * 5.3f)).coerceIn(0f, 1f) else 0f
                val narrow = speaking && PetBus.zcr > 0.12f
                val targets = HashMap<String, Float>()
                targets[PetFrames.BLINK] = if (sleep || now < blinkUntil || mode == Mode.YAWN) 1f else 0f
                targets[PetFrames.MOUTH_A] = if (!narrow) ((lv - 0.25f) / 0.45f).coerceIn(0f, 1f) else 0f
                targets[PetFrames.MOUTH_O] = if (narrow) ((lv - 0.15f) / 0.4f).coerceIn(0f, 1f) else ((lv - 0.08f) / 0.25f).coerceIn(0f, 1f) * (1f - ((lv - 0.25f) / 0.45f).coerceIn(0f, 1f))
                targets[PetFrames.THINK] = if ((thinking || mode == Mode.LOOK) && !talking) 1f else 0f
                // [Mines-pet-34] laugh = open mouth on the full-body frames (no chibi overlay → no ghost image on release)
                if (expression == Avatars.LAUGH && !talking) targets[PetFrames.MOUTH_A] = maxOf(targets[PetFrames.MOUTH_A] ?: 0f, 0.8f)
                val ff = PetBus.forceFrame
                if (ff != null && now < PetBus.forceUntil) { for (k in PetFrames.names) targets[k] = if (k == ff) 1f else 0f }
                sprite.step(targets, dt, if (talking) 0.05f else 0.08f)
                // expression crossfade (happy / laugh art) instead of a hard swap
                // [Mines-pet-34] the chibi expression art has a different framing from the full-body frames;
                // crossfading it on top showed a second, offset figure (ghosting after a drag). Off for frame sets.
                val want = 0f
                if (expression != Avatars.NEUTRAL) exprShown = expression
                exprAlpha += (want - exprAlpha) * (dt / 0.18f).coerceIn(0f, 1f)
                val dst = RectF(0f, 0f, pet, pet)
                val ease = (dt / 0.12f).coerceIn(0f, 1f)
                sprite.headTilt += (pTilt - sprite.headTilt) * ease
                sprite.lean += (pLean - sprite.lean) * ease
                sprite.turn += (pTurn - sprite.turn) * ease
                sprite.nod += (pNod - sprite.nod) * ease
                canvas.translate(sway * pet, bob * pet)
                sprite.draw(canvas, set, dst, t, if (held) 0.3f else if (sleep) 0.6f else 1f,
                    if (st.petOutline) Color.WHITE else null, resources.displayMetrics.density)
                if (exprAlpha > 0.02f) {
                    val b = Avatars.builtins.firstOrNull { it.id == st.petStyle }
                    val eb = if (b == null) null else exprBmp.getOrPut(exprShown) { android.graphics.BitmapFactory.decodeResource(resources, b.chibi(exprShown)) }
                    if (eb != null) {
                        val ar = eb.width.toFloat() / eb.height
                        val dw = if (ar <= 1f) pet * ar else pet; val dh = if (ar <= 1f) pet else pet / ar
                        val ep = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (exprAlpha * 255).toInt() }
                        canvas.drawBitmap(eb, null, RectF((pet - dw) / 2f, pet - dh, (pet + dw) / 2f, pet), ep)
                    }
                }
            } else renderer.draw(canvas, pet, pet, st.petStyle, face = false,
                ringColor = if (st.petStyle == Avatars.CUSTOM) st.petColor else null, bob = bob,
                sway = sway, squash = 1f,
                crop = AvatarCrop(st.avatarScale, st.avatarOffX, st.avatarOffY),
                version = st.avatarVersion, noBg = st.avatarRemoveBg, expression = expression)
            canvas.restore()

            if (sleep) {
                // drifting Zz
                for (i in 0 until 3) {
                    val k = ((t * 0.5f + i / 3f) % 1f)
                    zPaint.alpha = (255 * (1f - k)).toInt()
                    zPaint.textSize = pet * (0.12f + 0.06f * i)
                    canvas.drawText("z", left + pet * (0.72f + 0.1f * i), top + pet * (0.3f - 0.25f * k), zPaint)
                }
            } else if (mode == Mode.CELEBRATE) {
                // confetti
                for (i in 0 until 10) {
                    val k = ((mt * 0.8f + i * 0.13f) % 1f)
                    confetti.color = CONFETTI[i % CONFETTI.size]
                    val cx = left + pet * (0.08f + 0.84f * ((i * 37 % 10) / 10f))
                    canvas.drawCircle(cx, top + pet * (0.05f + 0.6f * k), pet * 0.025f, confetti)
                }
            } else if (typing && !talking && mode == Mode.IDLE && !held) {
                // little keyboard + keys lighting up
                val kb = RectF(left + pet * 0.22f, top + pet * 0.86f, left + pet * 0.78f, top + pet * 0.97f)
                canvas.drawRoundRect(kb, pet * 0.03f, pet * 0.03f, kbPaint)
                for (i in 0 until 4) {
                    dotPaint.alpha = if (((t * 8f).toInt() % 4) == i) 255 else 70
                    canvas.drawCircle(kb.left + kb.width() * (0.2f + 0.2f * i), kb.centerY(), pet * 0.02f, dotPaint)
                }
            } else if (thinking && !talking && mode == Mode.IDLE && !held) {
                for (i in 0 until 3) {
                    dotPaint.alpha = if (((t * 3f).toInt() % 3) == i) 255 else 90
                    canvas.drawCircle(left + pet * (0.66f + i * 0.1f), top + pet * 0.1f, pet * 0.035f, dotPaint)
                }
            }
            val l = line
            if (l != null) {
                if (now > lineUntil) line = null
                else {
                    val pad = 8f * resources.displayMetrics.density
                    val tw = textPaint.measureText(l).coerceAtMost(width - pad * 3)
                    val bh = textPaint.textSize + pad * 1.4f
                    val bx = ((width - tw) / 2f - pad).coerceAtLeast(0f)
                    bubblePaint.color = Color.WHITE
                    canvas.drawRoundRect(RectF(bx, 0f, bx + tw + pad * 2, bh), bh / 2, bh / 2, bubblePaint)
                    canvas.drawText(l, bx + pad, bh / 2 + textPaint.textSize / 3f, textPaint)
                }
            }
            val busy = held || walking || talking || typing || thinking || mode != Mode.IDLE || now - releaseAt < 450 || line != null ||
                sprite.alphaOf(PetFrames.BLINK) > 0.02f || now < blinkUntil + 200
            // [Mines-pet-32] vsync-driven: every frame (60 fps) while anything moves, every 2nd vsync
            // (30 fps) for idle breathing, ~8 fps asleep (battery).
            if (sleep) postInvalidateDelayed(125)
            else if (!vsyncPending && isAttachedToWindow) {
                vsyncPending = true
                if (busy) choreo.postFrameCallback(frameCb)
                else choreo.postFrameCallbackDelayed(frameCb, 16)
            }
        }
    }
}
