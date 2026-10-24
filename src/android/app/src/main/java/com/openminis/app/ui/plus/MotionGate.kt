package com.openminis.app.ui.plus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first

/**
 * [Mines-38.9.9 i27] one switch for the page's ambient frame loops (weather sky, fog, animated weather icons, Today orbit).
 * While a glass overlay (sheet / menu / ☰ / dialog) is open, the page under the root blur is parked: no frame requests, so
 * nothing behind the blur is redrawn and re-blurred every frame (smoke 39197873667: picker / quick test / ☰ dumps came back
 * empty — the app was drawing 1.2–1.7 s frames non-stop under the sheet and uiautomator never saw it idle).
 */
object MotionGate {
    /** suspends while an overlay is open; returns how long it was parked (ms, 0 = not parked) */
    suspend fun awaitRun(): Long {
        if (!GlassBackdrop.active.value) return 0L
        val t0 = System.currentTimeMillis()
        GlassBackdrop.active.first { !it }
        return System.currentTimeMillis() - t0
    }

    private fun isSaver(ctx: android.content.Context): Boolean = runCatching {
        (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
    }.getOrDefault(false)

    /** battery saver, following the system switch live */
    @Composable
    fun powerSave(ctx: android.content.Context): State<Boolean> = produceState(isSaver(ctx), ctx) {
        val app = ctx.applicationContext
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { value = isSaver(app) }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(app, r,
                android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        awaitDispose { runCatching { app.unregisterReceiver(r) } }
    }
}
