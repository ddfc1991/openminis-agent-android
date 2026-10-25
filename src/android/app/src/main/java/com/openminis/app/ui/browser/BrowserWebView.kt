package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable wrapper around Android WebView, mirroring iOS BrowserWebView.
 *
 * [T-android-browser-blank] Hosts the WebView inside a FrameLayout container
 * and swaps it in `update` instead of returning the WebView from `factory`.
 * AndroidView's factory runs ONCE per node — with the old direct-return
 * approach, switching tabs (or the pool recreating a WebView after idle
 * eviction) recomposed with a NEW WebView instance but the screen kept
 * showing the stale one: the sheet header/URL bar (StateFlows from the
 * current tab manager) looked correct while the content area rendered a
 * dead/blank WebView — the reported black/white screen. iOS avoids this via
 * `.id(pool.selectedTabId)` remounting; the container+update pattern is the
 * Android equivalent.
 *
 * Ask all ancestor views (including the host ModalBottomSheet) to stop
 * intercepting touches as soon as the user puts a finger down on the
 * WebView, so page scroll gestures never drag the sheet.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                // [Mines-webpaint-38.4] the tab we leave goes back to its off-screen hardware layer
                (mounted as? WebView)?.let { runCatching { it.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null) } }
                // The WebView may still be parented to a previous container
                // (sheet re-open racing the old node's disposal) — detach
                // first or addView throws "child already has a parent".
                (webView.parent as? ViewGroup)?.removeView(webView)
                // [Mines-38.9.15] takeover: drags (sliders / human checks) go to the page, never to a nested-scroll parent
                webView.isNestedScrollingEnabled = false
                webView.isFocusableInTouchMode = true
                webView.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && !v.hasFocus()) v.requestFocus()
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                // [Mines-webfit-38.3] the page the user is looking at, for the fit-to-screen self-test
                com.openminis.app.browser.WebFit.shown = java.lang.ref.WeakReference(webView)
                // [Mines-webpaint-38.4] on screen the page draws directly (no hardware layer): smoke 44 showed
                // coze.cn's background only — the layer never got the content tiles. WebPaintCheck.heal switches
                // back to a layer if this device draws nothing without one. Restored on release (headless capture).
                if (webView.layerType != android.view.View.LAYER_TYPE_NONE) webView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                webView.onResume()
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container ->
            // Free the WebView so a later mount (same or different sheet)
            // can re-parent it without hitting the has-a-parent guard.
            val mounted = container.getChildAt(0) as? WebView
            container.removeAllViews()
            // [Mines-webpaint-38.4] off screen it goes back to the hardware layer BrowserUseManager configured
            mounted?.let { runCatching { it.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null) } }
        },
        modifier = modifier,
    )
    // [Mines-webpaint-38.4] page loaded but nothing drawn on screen → switch the layer type until it shows
    LaunchedEffect(webView) { com.openminis.app.browser.WebPaintCheck.heal(webView) }
}
