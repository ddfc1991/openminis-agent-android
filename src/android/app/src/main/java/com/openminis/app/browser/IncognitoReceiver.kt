package com.openminis.app.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [Mines-38.9.11.2] main-process end of the ":incog" process (not exported; explicit intents from this app only):
 *  - ACTION_SAVE: the user tapped 「保存到密码库」 in a 无痕 page → SavedLogins (the only way a 无痕 login is ever saved);
 *  - ACTION_CLOSED: the 无痕 process wiped itself → after it is gone, delete its data dirs here too and clear the marker.
 */
class IncognitoReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SAVE = "com.openminis.mines.incog.SAVE"
        const val ACTION_CLOSED = "com.openminis.mines.incog.CLOSED"
    }

    override fun onReceive(c: Context, i: Intent) {
        if (IncognitoProcess.inProcess) return
        when (i.action) {
            ACTION_SAVE -> {
                val url = i.getStringExtra("url").orEmpty(); val user = i.getStringExtra("user").orEmpty(); val pass = i.getStringExtra("pass").orEmpty()
                if (!IncognitoCore.savesLogin(incognito = true, userTapped = true) || pass.isEmpty() || !url.startsWith("http")) return
                runCatching {
                    val host = android.net.Uri.parse(url).host.orEmpty()
                    com.openminis.app.plus.SavedLogins.put(c, com.openminis.app.plus.SavedLogins.Entry(
                        com.openminis.app.plus.AutoAccounts.siteOf(host).ifBlank { host }, url, user, pass))
                }
            }
            ACTION_CLOSED -> {
                val pr = goAsync()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching {
                        if (!IncognitoProcess.running(c)) { IncognitoProcess.wipeDirs(c); Incognito.markOpen(c, false) }
                        Incognito.noteProcessClosed()
                    }
                    pr.finish()
                }, 1500)
            }
        }
    }
}
