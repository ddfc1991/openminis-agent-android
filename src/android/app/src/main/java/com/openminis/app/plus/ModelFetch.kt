package com.openminis.app.plus

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * [Mines-models-35] One robust downloader for every on-device model (声纹 CAM++, 唤醒词 / 离线识别 Vosk).
 *
 * Why (user, 2026-10-14, realme on a China network with a VPN): the wake word said 「离线模型下载失败」 and
 * 声纹录入 hung — both models came from a single overseas host each (alphacephei.com, GitHub releases),
 * through one HTTP call with no resume, no checksum and no time limit, and the JVM's default proxy
 * selector could send them into Mines' 分流代理 even when its upstream was dead.
 *
 * Now each model is split into ≤ 8 MB parts mirrored on the Mines repo's `hark-models` branch and fetched
 * through several CDNs (jsDelivr cdn/fastly/gcore — reachable in mainland China — then GitHub raw and
 * ghfast). Every part: try direct first, then through the system/split proxy; resume with HTTP Range;
 * SHA-256 checked; 3 rounds over all sources. If every mirror fails, the original single-file URL is
 * tried. Progress is reported in %, and the final error names the cause in plain Chinese.
 */
object ModelFetch {
    private const val TAG = "ModelFetch"
    private const val REPO = "ddfc1991/openminis-agent-android"
    private const val BRANCH = "hark-models"

    data class Model(val id: String, val size: Long, val sha256: String, val parts: List<Pair<String, String>>, val originals: List<String>)

    val CAMPP = Model("campplus", 28281138, "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11", listOf(
        "part-00" to "5d705855e024caa5baa0169f7557834be8dc1ee5deddd9762f853984f0866d4e", "part-01" to "67a75659b7797f98958b1be2f5db5f0495068da3d3a9bf9b94de47e311b9e3c0",
        "part-02" to "339b1c38498adcb29e75f84b2cc660b874b51efc5b6928d87033308c197803bd", "part-03" to "a49678b86b87b12fec65fe98a2e360d121b5d9c22d15b1fe819e89e47e9b4126"),
        listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            "https://hf-mirror.com/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"))

    val VOSK_CN = Model("vosk-small-cn-0.22", 43898754, "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba", listOf(
        "part-00" to "ffd87e52fc8efded5d3bad31dcee4d8fe094fdd8eec096538e20571e9f8cdd12", "part-01" to "0fc396a9f36517541752fe89ccedf0b0f4c975eb326ad9be8f1f8c5e75f13888",
        "part-02" to "5750763cd9c59f2dd6badeedc74b4b39f9b4f0a7c90dc76e484e721a8518231d", "part-03" to "c1424c7233f39f9ef05bd1d9dd62a80e11ec31ac66f98ec3503a60e265bce26f",
        "part-04" to "745ec7dec3744d059c438de674cab545ae2dee31f29dccae983bf9d904ca8da7", "part-05" to "674c8f50b6f8616067855e69ce62e613ec5badb279e48aee240eb98930e23a9a"),
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"))

    private fun hosts(model: String, part: String): List<String> {
        val path = "models/$model/$part"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/$path",
            "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/$path",
            "https://gcore.jsdelivr.net/gh/$REPO@$BRANCH/$path",
            "https://raw.githubusercontent.com/$REPO/$BRANCH/$path",
            "https://ghfast.top/https://raw.githubusercontent.com/$REPO/$BRANCH/$path",
        )
    }

    /**
     * [Mines-models-35.3] user 2026-10-14 (VPN on): 声纹 sat on 「首次使用：下载声纹模型…」 with no number at all.
     * OkHttp's connect/read timeouts do not cover DNS (InetAddress blocks), so one dead source could hold an
     * attempt until the 180 s call timeout — ×5 hosts ×2 routes ×3 rounds ×4 parts with nothing on screen.
     * Now: DNS has its own 8 s limit, a watchdog cancels any attempt that receives no byte for 15 s and moves to the
     * next source, two rounds only, and progress always names the source plus speed.
     */
    private object TimedDns : okhttp3.Dns {
        private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mines-dns").apply { isDaemon = true } }
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val f = pool.submit<List<java.net.InetAddress>> { okhttp3.Dns.SYSTEM.lookup(hostname) }
            return try { f.get(8, TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
                f.cancel(true); throw java.net.UnknownHostException("DNS timeout: $hostname")
            } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? java.io.IOException) ?: java.net.UnknownHostException(hostname) }
        }
    }
    private val direct = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(TimedDns).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS).build()
    private val viaProxy = OkHttpClient.Builder().dns(TimedDns).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS).build()
    /** no byte for this long → the attempt is cancelled and the next source is tried */
    @Volatile var stallMs = 15_000L
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mines-fetch-wd").apply { isDaemon = true } }

    class FetchError(msg: String) : Exception(msg)

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(256 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads [url] into [f], resuming from what [f] already holds. Returns bytes total in [f]. */
    private fun get(client: OkHttpClient, url: String, f: File, onBytes: (Long) -> Unit) {
        val have = if (f.exists()) f.length() else 0L
        val rb = Request.Builder().url(url).header("User-Agent", "Mines/35 (Android)")
        if (have > 0) rb.header("Range", "bytes=$have-")
        val call = client.newCall(rb.build())
        val lastByte = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val wd = watchdog.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - lastByte.get() > stallMs) { stalled.set(true); call.cancel() }
        }, 1, 1, TimeUnit.SECONDS)
        try { getInner(call, have, f) { b -> lastByte.set(System.currentTimeMillis()); onBytes(b) } }
        catch (e: Throwable) { if (stalled.get()) throw FetchError("${stallMs / 1000} 秒没有收到数据（timeout）"); throw e }
        finally { wd.cancel(false) }
    }

    private fun getInner(call: okhttp3.Call, have: Long, f: File, onBytes: (Long) -> Unit) {
        call.execute().use { r ->
            if (r.code == 416) return
            if (!r.isSuccessful) throw FetchError("HTTP ${r.code}")
            val append = have > 0 && r.code == 206
            val body = r.body ?: throw FetchError("空的")
            RandomAccessFile(f, "rw").use { raf ->
                if (append) raf.seek(have) else raf.setLength(0)
                val inp = body.byteStream(); val buf = ByteArray(64 * 1024)
                var got = if (append) have else 0L
                while (true) { val n = inp.read(buf); if (n < 0) break; raf.write(buf, 0, n); got += n; onBytes(got) }
            }
        }
    }

    private fun plain(e: Throwable?): String {
        val m = (e?.message ?: e?.javaClass?.simpleName ?: "").lowercase()
        return when {
            m.contains("unable to resolve") || m.contains("unknownhost") -> "没有网络或 DNS 不通"
            m.contains("timeout") || m.contains("timed out") -> "网络太慢或被拦截（超时）"
            m.contains("http 403") || m.contains("http 451") -> "下载源拒绝访问"
            m.contains("http 404") -> "下载源上找不到文件"
            m.contains("校验") -> "下载的文件不完整（校验没通过）"
            m.contains("refused") || m.contains("reset") || m.contains("closed") || m.contains("eof") -> "连接被断开（网络或代理问题）"
            m.contains("space") -> "手机存储空间不够"
            else -> e?.message?.take(40) ?: "未知原因"
        }
    }

    /**
     * Makes sure [dest] holds [model] (SHA-256 checked). Blocking; call on IO.
     * [progress] gets (fraction 0..1, plain status line).
     */
    fun fetch(model: Model, dest: File, work: File, progress: (Float, String) -> Unit = { _, _ -> }) {
        if (dest.exists() && dest.length() == model.size) return
        work.mkdirs()
        var last: Throwable? = null
        val done = LongArray(model.parts.size)
        var t0 = System.currentTimeMillis(); var b0 = -1L; var speed = ""
        fun report(i: Int, b: Long, how: String) {
            done[i] = b
            val sum = done.sum()
            val now = System.currentTimeMillis()
            if (b0 < 0) { b0 = sum; t0 = now } else if (now - t0 >= 1000) {
                val bps = (sum - b0) * 1000.0 / (now - t0); b0 = sum; t0 = now
                speed = if (bps >= 1_000_000) " · %.1f MB/s".format(bps / 1_000_000) else " · %d KB/s".format((bps / 1000).toInt())
            }
            val f = (sum.toFloat() / model.size).coerceIn(0f, 1f)
            progress(f, "下载中 ${(f * 100).toInt()}%$speed（$how）")
        }
        fun src(url: String) = when {
            url.contains("cdn.jsdelivr") -> "jsDelivr"; url.contains("fastly.jsdelivr") -> "jsDelivr fastly"; url.contains("gcore.jsdelivr") -> "jsDelivr gcore"
            url.contains("ghfast") -> "ghfast"; url.contains("raw.githubusercontent") -> "GitHub"; url.contains("hf-mirror") -> "hf-mirror"; url.contains("github.com") -> "GitHub 发布页"
            else -> "备用源"
        }
        var allParts = true
        for ((i, p) in model.parts.withIndex()) {
            val (name, sha) = p
            val pf = File(work, "${model.id}.$name")
            if (pf.exists() && runCatching { sha256(pf) }.getOrNull() == sha) { report(i, pf.length(), "已有"); continue }
            var ok = false
            round@ for (round in 0 until 2) {
                for (url in hosts(model.id, name)) {
                    for ((client, how) in listOf(direct to "直连", viaProxy to "经代理")) {
                        try {
                            progress((done.sum().toFloat() / model.size).coerceIn(0f, 1f), "正在连接 ${src(url)}（$how）… 已下载 ${(done.sum() * 100 / model.size)}%")
                            get(client, url, pf) { b -> report(i, b, "${src(url)} $how") }
                            if (sha256(pf) == sha) { ok = true; break@round }
                            pf.delete(); throw FetchError("校验没通过")
                        } catch (e: Throwable) {
                            last = e; Log.w(TAG, "${model.id}/$name $how $url: ${e.message}")
                        }
                    }
                }
                Thread.sleep(1500L)
            }
            if (!ok) { allParts = false; break }
        }
        val tmp = File(work, "${model.id}.joined")
        if (allParts) {
            tmp.outputStream().use { out -> for ((name, _) in model.parts) File(work, "${model.id}.$name").inputStream().use { it.copyTo(out) } }
            if (sha256(tmp) == model.sha256) {
                dest.parentFile?.mkdirs(); dest.delete()
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                for ((name, _) in model.parts) File(work, "${model.id}.$name").delete()
                progress(1f, "下载完成"); return
            }
            tmp.delete(); last = FetchError("校验没通过")
        }
        // last resort: the original single file (resumable too)
        for (url in model.originals) for ((client, how) in listOf(direct to "直连", viaProxy to "经代理")) {
            try {
                progress(0f, "正在连接 ${src(url)}（$how）…")
                get(client, url, tmp) { b -> progress((b.toFloat() / model.size).coerceIn(0f, 1f), "下载中 ${(b * 100 / model.size)}%（${src(url)}，$how）") }
                if (tmp.length() == model.size && sha256(tmp) == model.sha256) {
                    dest.parentFile?.mkdirs(); dest.delete()
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                    progress(1f, "下载完成"); return
                }
                tmp.delete(); last = FetchError("校验没通过")
            } catch (e: Throwable) { last = e; Log.w(TAG, "${model.id} original $how $url: ${e.message}") }
        }
        throw FetchError("模型下载失败：" + plain(last) + "。已下载的部分会保留，联网后点「重试」接着下")
    }
}
