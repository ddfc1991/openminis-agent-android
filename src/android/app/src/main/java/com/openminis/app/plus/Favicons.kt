package com.openminis.app.plus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9] Real site logos for 账号关联 / 应用代理 (user 2026-10-16: 「很多图标都没有，要把图标加上去」). Each site's icon
 * is fetched once and kept on disk (files/favicons/<domain>.png): Google's favicon service (128 px), DuckDuckGo's, then
 * the site's own apple-touch-icon / favicon.ico. A miss is remembered for 3 days; the UI shows a letter tile meanwhile.
 */
object Favicons {
    private val mem = android.util.LruCache<String, Bitmap>(80)
    private val locks = HashMap<String, Mutex>()
    private val http by lazy { OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build() }

    fun domainOf(host: String): String = Keychain.root(host)

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "favicons").also { it.mkdirs() }

    fun cached(host: String): Bitmap? = domainOf(host).takeIf { it.isNotBlank() }?.let { mem.get(it) }

    suspend fun get(c: Context, host: String): Bitmap? = withContext(Dispatchers.IO) {
        val d = domainOf(host); if (d.isBlank() || !d.contains('.')) return@withContext null
        mem.get(d)?.let { return@withContext it }
        val lock = synchronized(locks) { locks.getOrPut(d) { Mutex() } }
        lock.withLock {
            mem.get(d)?.let { return@withLock it }
            val f = File(dir(c), "$d.png"); val miss = File(dir(c), "$d.none")
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { mem.put(d, it); return@withLock it }
            if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 3 * 86_400_000L) return@withLock null
            val urls = listOf("https://www.google.com/s2/favicons?domain=$d&sz=128", "https://icons.duckduckgo.com/ip3/$d.ico",
                "https://$d/apple-touch-icon.png", "https://www.$d/apple-touch-icon.png", "https://$d/favicon.ico")
            for (u in urls) {
                val bmp = runCatching {
                    http.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mines").build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }
                    }
                }.getOrNull()
                if (bmp != null && bmp.width >= 16 && bmp.height >= 16) {
                    runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    mem.put(d, bmp); miss.delete(); return@withLock bmp
                }
            }
            runCatching { miss.writeText("") ; miss.setLastModified(System.currentTimeMillis()) }
            null
        }
    }
}
