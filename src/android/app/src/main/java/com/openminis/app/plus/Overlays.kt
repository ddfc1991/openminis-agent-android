package com.openminis.app.plus

import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * [Mines-pet-26] Every floating window Mines puts on the screen (desk pet,
 * its bubble / panel / switcher, the plugin-widget overlay) goes through
 * here, so one set of rules holds for all of them:
 *
 *  - a window that shows nothing (GONE / INVISIBLE / fully transparent)
 *    never takes touches: FLAG_NOT_TOUCHABLE is forced on;
 *  - hiding a window = GONE + NOT_TOUCHABLE, never "invisible but there";
 *  - [audit] lists leftovers: windows still registered whose view is no
 *    longer attached, or that are hidden yet touchable. The desk pet runs
 *    it after every drag and on destroy and removes what it finds.
 *
 * build-25 bug this guards against: the widget overlay was a fixed
 * 300×380 dp window with a transparent WebView; after the pet moved away
 * the window (with its 「⋯ 挂件」 handle) stayed where it was and its empty
 * area swallowed every touch.
 *
 * CI checks that no other file calls WindowManager.addView directly.
 */
object Overlays {
    private const val TAG = "Overlays"

    private class Entry(val owner: String, val view: View, val lp: WindowManager.LayoutParams, val wm: WindowManager)
    private val live = ArrayList<Entry>()

    private fun harden(view: View, lp: WindowManager.LayoutParams) {
        val showsNothing = view.visibility != View.VISIBLE || view.alpha <= 0.01f
        lp.flags = if (showsNothing) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags
    }

    @Synchronized
    fun add(wm: WindowManager, owner: String, view: View, lp: WindowManager.LayoutParams): Boolean {
        harden(view, lp)
        return runCatching { wm.addView(view, lp) }.onSuccess { live += Entry(owner, view, lp, wm) }
            .onFailure { Log.w(TAG, "add $owner failed: ${it.message}") }.isSuccess
    }

    @Synchronized
    fun update(wm: WindowManager?, view: View?, lp: WindowManager.LayoutParams?) {
        if (wm == null || view == null || lp == null) return
        harden(view, lp)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    @Synchronized
    fun remove(wm: WindowManager?, view: View?) {
        if (view == null) return
        live.removeAll { it.view === view }
        runCatching { (wm ?: return).removeViewImmediate(view) }.onFailure { runCatching { wm?.removeView(view) } }
    }

    /** Hide without removing: GONE and NOT_TOUCHABLE; show restores the caller's flags. */
    @Synchronized
    fun setHidden(wm: WindowManager?, view: View?, lp: WindowManager.LayoutParams?, hidden: Boolean, touchableWhenShown: Boolean = true) {
        if (wm == null || view == null || lp == null) return
        view.visibility = if (hidden) View.GONE else View.VISIBLE
        lp.flags = if (hidden || !touchableWhenShown) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /** Problems found, one line each (empty = clean). */
    @Synchronized
    fun audit(): List<String> = live.mapNotNull { e ->
        val touchable = e.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        when {
            !e.view.isAttachedToWindow -> "${e.owner}: registered but not attached (leftover)"
            touchable && (e.view.visibility != View.VISIBLE || e.view.alpha <= 0.01f) -> "${e.owner}: hidden but takes touches"
            touchable && (e.lp.width == 0 || e.lp.height == 0) -> "${e.owner}: zero-size touchable window"
            else -> null
        }
    }

    /** Fix what [audit] finds: drop dead entries, make hidden ones untouchable. */
    @Synchronized
    fun repair(): Int {
        var n = 0
        val it = live.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.view.isAttachedToWindow) { it.remove(); n++; continue }
            val touchable = e.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
            if (touchable && (e.view.visibility != View.VISIBLE || e.view.alpha <= 0.01f)) {
                e.lp.flags = e.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { e.wm.updateViewLayout(e.view, e.lp) }; n++
            }
        }
        if (n > 0) Log.w(TAG, "repaired $n overlay window(s)")
        return n
    }

    @Synchronized
    fun removeAll(owner: String) {
        live.filter { it.owner == owner }.forEach { e -> runCatching { e.wm.removeViewImmediate(e.view) } }
        live.removeAll { it.owner == owner }
    }

    @Synchronized
    fun count(owner: String? = null): Int = live.count { owner == null || it.owner == owner }
}
