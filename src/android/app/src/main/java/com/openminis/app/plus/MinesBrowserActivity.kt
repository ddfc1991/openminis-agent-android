package com.openminis.app.plus

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoView

/**
 * [Mines-38.9.13 mines_browser] Shows the task's live page from Mines' own browser to the user — only for what Mines may
 * not do itself (payments, approvals on the phone). One step: do it on the page, then tap 「好了，交还给 Mines」.
 */
class MinesBrowserActivity : Activity() {
    private var view: GeckoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = MinesBrowser.sessionForUser()
        if (session == null) { finish(); return }
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val head = TextView(this).apply {
            text = MinesBrowser.handoverHead + "\n" + MinesBrowser.handoverUrl()
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(10f))
        }
        val gv = GeckoView(this)
        gv.setSession(session)
        view = gv
        val done = Button(this).apply {
            text = "好了，交还给 Mines"
            isAllCaps = false   // [38.9.13] the Button theme upper-cased it to 「MINES」
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(gv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).apply { setMargins(dp(16f), dp(8f), dp(16f), dp(16f)) })
        setContentView(root)
    }

    override fun onDestroy() {
        runCatching { view?.releaseSession() }
        view = null
        MinesBrowser.userDone()
        super.onDestroy()
    }
}
