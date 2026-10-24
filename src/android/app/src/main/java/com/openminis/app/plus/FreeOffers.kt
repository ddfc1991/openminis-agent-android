package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [Mines-offers-32] 免费活动 / 额度. Zero model cost: a curated JSON list
 * (bundled in assets/mines/free-offers.json, refreshed at most once a day from
 * hark-build/free-offers.json in the public Mines repo) matched by rules
 * against what the user has connected. Offers for services the user has not
 * connected yet are shown on 「应用」 and notified once each
 * ("智谱新用户送 … 要我帮你申请吗？"). Tapping 「帮我领」 opens that service's
 * page: KeyAgent when a recipe exists, else the official page in a Custom Tab
 * (the key is picked up from the clipboard on return). Mines never pays,
 * never picks a paid plan, and stops for SMS / CAPTCHA / real-name steps.
 */
object FreeOffers {
    data class Offer(
        val id: String, val kind: String, val title: String, val detail: String,
        val url: String, val source: String, val expires: String, val needs: List<String>,
    )

    private const val REMOTE = "https://raw.githubusercontent.com/ddfc1991/openminis-agent-android/hark-minis-build/hark-build/free-offers.json"
    private const val PREFS = "mines_free_offers"
    private const val CHANNEL = "mines_offers"

    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()

    private fun parse(raw: String): List<Offer> = runCatching {
        val a = JSONObject(raw).getJSONArray("offers")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val needs = o.optJSONArray("needs")?.let { n -> (0 until n.length()).map { n.getString(it) } } ?: emptyList()
            Offer(o.getString("id"), o.getString("kind"), o.getString("title"), o.optString("detail"),
                o.optString("url"), o.optString("source"), o.optString("expires"), needs)
        }
    }.getOrDefault(emptyList())

    private fun cacheFile(c: Context) = File(c.filesDir, "free-offers.json")

    /** Load cached/bundled list now; refresh from the repo at most once a day. Never blocks the UI (call on IO). */
    suspend fun load(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        val cached = cacheFile(c).takeIf { it.exists() }?.readText()
        val bundled = runCatching { c.assets.open("mines/free-offers.json").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val first = parse(cached ?: bundled ?: "{\"offers\":[]}")
        if (_offers.value.isEmpty()) _offers.value = first.filterNot { expired(it) }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (force || System.currentTimeMillis() - p.getLong("fetched", 0) > 24 * 3600_000L) {
            runCatching {
                val conn = URL(REMOTE).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val list = parse(body)
                    if (list.isNotEmpty()) { cacheFile(c).writeText(body); _offers.value = list.filterNot { expired(it) } }
                }
                p.edit().putLong("fetched", System.currentTimeMillis()).apply()
            }
        }
    }

    private fun expired(o: Offer): Boolean = o.expires.isNotBlank() && runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(o.expires)!!.time + 86_400_000L < System.currentTimeMillis()
    }.getOrDefault(false)

    fun dismissed(c: Context): Set<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("dismissed", emptySet()) ?: emptySet()
    fun dismiss(c: Context, id: String) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet("dismissed", dismissed(c) + id).apply()
        _offers.value = _offers.value.toList()
    }

    /** Offers worth showing: service not connected yet, not dismissed. */
    fun relevant(c: Context): List<Offer> {
        PlusVault.init(c)
        val connected = PlusVault.entries.value.keys.map { PlusVault.baseId(it) }.toSet()
        val d = dismissed(c)
        return _offers.value.filter { it.kind !in connected && it.id !in d }
    }

    fun forKind(c: Context, kindId: String): Offer? = _offers.value.firstOrNull { it.kind == PlusVault.baseId(kindId) && it.id !in dismissed(c) }

    /** One-line hint for the steps Mines cannot do for the user. */
    fun needsText(o: Offer): String {
        val n = o.needs.mapNotNull { when (it) { "sms" -> "短信验证码"; "realname" -> "实名认证"; "captcha" -> "人机验证"; else -> null } }
        return if (n.isEmpty()) "" else "需要你自己做一步：" + n.joinToString("、")
    }

    /** Notify each new relevant offer once (max 1 notification per day). */
    fun notifyNew(context: Context) {
        val c = context.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("notified_at", 0) < 24 * 3600_000L) return
        val seen = p.getStringSet("notified", emptySet()) ?: emptySet()
        val o = relevant(c).firstOrNull { it.id !in seen } ?: return
        p.edit().putStringSet("notified", seen + o.id).putLong("notified_at", System.currentTimeMillis()).apply()
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "免费额度提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 7310 + (o.id.hashCode() and 0xff),
            Intent(c, com.openminis.app.MainActivity::class.java)
                .putExtra("mines_route", com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS)
                .putExtra("mines_open_kind", o.kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(73120, android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
                .setContentTitle(o.title)
                .setContentText("要我帮你申请吗？点一下就开始")
                .setStyle(android.app.Notification.BigTextStyle().bigText(o.detail + "\n要我帮你申请吗？点一下就开始。"))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
