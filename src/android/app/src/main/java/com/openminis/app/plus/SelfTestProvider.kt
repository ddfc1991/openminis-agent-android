package com.openminis.app.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadServer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * [Mines-selftest-29] Lets the CI emulator smoke test run the same offload
 * commands the agent runs (android-a11y-cli open, android-device storage,
 * android-speech, android-clipboard, android-location …) without the arm64
 * sandbox, which can't boot on the x86_64 emulator:
 *
 *   adb shell content call --uri content://com.openminis.mines.plus.selftest \
 *       --method offload --arg 'android-device storage'
 *
 * Only the adb shell (uid 2000) and root may call it; anything else gets a
 * SecurityException. It adds no capability an app could use.
 */
class SelfTestProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("self-test is adb-only")
        val out = Bundle()
        when (method) {
            "allow" -> {
                // grant the agent gate for one offload tool (BYPASS), e.g. a11y_cli
                OffloadPermissionManager.setLevel(arg ?: "", OffloadPermissionManager.PermissionLevel.BYPASS)
                out.putString("result", "ok")
            }
            "offload" -> {
                val argv = split(arg ?: "")
                val h = NativeOffloadServer.handlerFor(argv.firstOrNull() ?: "")
                if (h == null) { out.putInt("exit", 127); out.putString("result", "no handler"); return out }
                val timeout = extras?.getLong("timeoutMs", 40_000L) ?: 40_000L
                val ex = Executors.newSingleThreadExecutor()
                val t0 = System.currentTimeMillis()
                try {
                    val r = ex.submit(Callable { h.handle(NativeOffloadRequest(Process.myPid(), argv, emptyMap(), "/", null)) })
                        .get(timeout, TimeUnit.MILLISECONDS)
                    out.putInt("exit", r.exitCode); out.putString("result", r.output)
                } catch (e: java.util.concurrent.TimeoutException) {
                    out.putInt("exit", 124); out.putString("result", "TIMEOUT after ${timeout} ms")
                } catch (e: Throwable) {
                    out.putInt("exit", 1); out.putString("result", "${e.javaClass.simpleName}: ${e.message}")
                } finally { ex.shutdownNow() }
                out.putLong("ms", System.currentTimeMillis() - t0)
            }
            "pet" -> {
                // [Mines-pet-32] "show" starts the desk pet; "<frame>" holds that frame 4 s; "peek" plays the peek reaction
                val c = context!!
                when (val a = arg ?: "show") {
                    "show" -> { PlusPrefs.update(c) { it.copy(petEnabled = true) }; DeskPetService.start(c) }
                    else -> if (a.startsWith("mode:")) PetBus.forceMode = a.removePrefix("mode:").uppercase()
                        else { PetBus.forceFrame = a; PetBus.forceUntil = System.currentTimeMillis() + 4000 }
                }
                out.putString("result", "ok")
            }
            "sources" -> {
                // [Mines-sources-33] "fake": put a GitHub + X session cookie in the WebView jar (as a login would),
                // then run the same automatic scan the app runs at start; "list": just report.
                val c = context!!.applicationContext
                val r = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
                    if (arg == "fake") {
                        val cm = android.webkit.CookieManager.getInstance()
                        cm.setCookie("https://github.com/", "logged_in=yes; Domain=.github.com; Path=/; Secure")
                        cm.setCookie("https://github.com/", "user_session=smoke-selftest; Path=/; Secure")
                        cm.setCookie("https://github.com/", "dotcom_user=smoke-selftest; Domain=.github.com; Path=/; Secure")
                        cm.setCookie("https://x.com/", "auth_token=smoke-selftest; Domain=.x.com; Path=/; Secure")
                        cm.flush()
                    }
                    val added = AutoSources.scan(c)
                    if (added.isNotEmpty()) android.widget.Toast.makeText(c, "已自动接入 ${AutoSources.active.value.size} 个来源", android.widget.Toast.LENGTH_LONG).show()
                    org.json.JSONObject()
                        .put("added", org.json.JSONArray(added))
                        .put("active", org.json.JSONArray(AutoSources.active.value.map { it.id }))
                        .put("missing", org.json.JSONArray(AutoSources.missing(c).map { it.first }))
                        .put("vault", org.json.JSONArray(PlusVault.entries.value.keys.toList()))
                }
                out.putString("result", r.toString())
            }
            "mailcode" -> {
                // [Mines-sources-33] simulated code e-mail: arg "<atom feed url>|<page with a code field>".
                // Arms OTP like a page asking for a code, reads the feed through the mailbox path,
                // then fills the page off-screen and reads the field back. adb-only.
                val c = context!!.applicationContext
                val (feed, page) = (arg ?: "").split('|').let { it.getOrElse(0) { "" } to it.getOrNull(1) }
                HumanVerify.init(c)
                HumanVerify.update(c) { it.copy(otpAutofill = true) }
                MailSources.testFeedUrl = feed.ifBlank { null }
                MailSources.invalidate()
                val t0 = System.currentTimeMillis()
                val code = kotlinx.coroutines.runBlocking { HumanVerify.awaitCode(30_000L) }
                val o = org.json.JSONObject().put("code", code ?: "").put("ms", System.currentTimeMillis() - t0)
                if (code != null && !page.isNullOrBlank()) {
                    val js = "(function(){var r=" + HumanVerify.fillJs(code, null) + ";var e=document.querySelector('input');return r+':'+(e?e.value:'');})()"
                    val v = kotlinx.coroutines.runBlocking { HiddenWeb.read(c, page, js, 25_000L, until = { it.startsWith("ok:") }) }
                    o.put("filled", v ?: "")
                }
                MailSources.testFeedUrl = null
                out.putString("result", o.toString())
            }
            "extract" -> out.putString("result", MailParse.extractCode(arg ?: "") ?: "")
            // [Mines-38.1] USE_BIOMETRIC in the manifest + canAuthenticate without a permission exception
            // [Mines-38.1] smoke: greeting name + a loud wallpaper file to prove text never sits on a clear picture
            "uiprep" -> {
                val c = context!!.applicationContext
                com.openminis.app.ui.plus.MinesProfile.setName(c, arg?.substringBefore('|').orEmpty())
                val path = arg?.substringAfter('|', "").orEmpty()
                if (path.isNotBlank()) {
                    val dst = com.openminis.app.ui.plus.wallpaperFile(c)
                    if (path == "gen") runCatching {
                        // a deliberately loud wallpaper (saturated pink/cyan, big shapes, high-contrast stripes)
                        val bm = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
                        val cv = android.graphics.Canvas(bm); val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        pt.shader = android.graphics.LinearGradient(0f, 0f, 1080f, 2400f, intArrayOf(0xFFFF4FA3.toInt(), 0xFFFFD1E8.toInt(), 0xFF00C2FF.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                        cv.drawRect(0f, 0f, 1080f, 2400f, pt); pt.shader = null
                        val cols = intArrayOf(0xFFFF0066.toInt(), 0xFF7A00FF.toInt(), 0xFFFFE600.toInt(), 0xFF00FFB3.toInt(), 0xFF111111.toInt())
                        for (i in 0 until 26) { pt.color = cols[i % cols.size]; cv.drawCircle((i * 397 % 1080).toFloat(), (i * 911 % 2400).toFloat(), 60f + (i * 37 % 160), pt) }
                        pt.color = 0xFF000000.toInt(); pt.textSize = 90f
                        for (y in 200 until 2400 step 260) cv.drawText("WALLPAPER 壁纸 TEST", 40f, y.toFloat(), pt)
                        dst.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    } else runCatching { java.io.File(path).copyTo(dst, true) }
                    com.openminis.app.plus.PlusPrefs.init(c)
                    com.openminis.app.plus.PlusPrefs.update(c) { it.copy(wallpaper = "file:" + System.currentTimeMillis(), wallpaperOnHome = true, wallpaperAlpha = 1f, wallpaperDim = 0f) }
                }
                out.putString("result", org.json.JSONObject().put("name", com.openminis.app.ui.plus.MinesProfile.name(c))
                    .put("wall", com.openminis.app.plus.PlusPrefs.state.value.wallpaper).toString())
            }
            "biometric" -> {
                val c = context!!.applicationContext
                val perms = runCatching { c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() }.getOrNull().orEmpty()
                out.putString("result", org.json.JSONObject().put("manifest", "android.permission.USE_BIOMETRIC" in perms)
                    .put("can", PayCards.biometricSelfTest(c)).toString())
            }
            "degen" -> {
                // [Mines-degen-34] detector on built-in samples: garbage must trip, normal text must not
                val rnd = java.util.Random(7)
                val junk = StringBuilder("让我想想这个推特链接分享的问题。"); val al = "LLLL_나L_yLL//////"
                while (junk.length < 6000) junk.append(al[rnd.nextInt(al.length)])
                val bad = listOf(junk.toString(), "好的。" + "/".repeat(3000), "检查一下。" + "我需要再检查一下这个链接是否安全。".repeat(200))
                val good = listOf(
                    (1..300).joinToString("") { "第${it}步：检查 t.co/${(it * 7919).toString(36)} 的跳转目标，确认是 x.com，再看证书。" },
                    (1..300).joinToString(",", "[", "]") { "{\"id\":$it,\"name\":\"item-$it\",\"price\":${it * 3 % 97}}" },
                )
                fun run(t: String): String? { val d = com.openminis.app.agent.DegenerationDetector(); var i = 0
                    while (i < t.length) { d.feed(t.substring(i, minOf(t.length, i + 40)))?.let { return it }; i += 40 }; return null }
                val br = bad.map { run(it) }; val gr = good.map { run(it) }
                val ok = br.all { it != null } && gr.all { it == null }
                out.putString("result", org.json.JSONObject().put("ok", ok).put("bad", org.json.JSONArray(br.map { it ?: "MISSED" }))
                    .put("good", org.json.JSONArray(gr.map { it ?: "ok" })).toString())
            }
            "failguard" -> {
                // [Mines-loop-34] the CDP sign-up loop from the user's report: stops on the 3rd identical failure
                val g = com.openminis.app.agent.FailurePatternGuard()
                val outs = listOf(
                    "Error: browserType.connectOverCDP: WebSocket error: 400 Bad Request (session 4f1a2b)",
                    "Error: browserType.connectOverCDP: WebSocket error: 400 Bad Request (session 9c3d77)",
                    "Error: browserType.connectOverCDP: WebSocket error: 400 Bad Request (session 11aa02)",
                )
                val r = outs.mapIndexed { i, o -> g.onResult("shell_execute", true, "attempt ${i + 1}\n" + o) }
                val ok = r[0] == null && r[1] == null && r[2] != null
                out.putString("result", org.json.JSONObject().put("ok", ok).put("stop", r[2] ?: "").put("next", g.nextStep()).toString())
            }
            "theme" -> {
                // [Mines-design-34] switch the desk-pet character (→ app accent / wallpaper glow / voice rings)
                val c = context!!
                val id = arg ?: "claude"
                PlusPrefs.update(c) { it.copy(petStyle = id) }
                val t = com.openminis.app.ui.plus.MinesDesign.theme(id)
                out.putString("result", org.json.JSONObject().put("style", PlusPrefs.state.value.petStyle)
                    .put("name", Avatars.name(id)).put("primary", "%08X".format(t.primary.value.toLong() ushr 32)).toString())
            }
            "tts" -> {
                // [Mines-tts-35] engine choice + Sec-MS-GEC v2 known answers (edge-tts 7.3.1); arg "live" also
                // synthesises "你好" over the real Edge service and runs the full fallback chain.
                val c = context!!
                val a = arg ?: ""
                if (a in setOf("edge", "openrouter", "gemini")) ExternalTts.save(c, ExternalTts.settings(c).copy(engine = a))
                val j = org.json.JSONObject().put("engine", ExternalTts.settings(c).engine)
                    .put("gec", ExternalTts.secMsGec(1760400000.0)).put("drm_ok", EdgeDrm.selfCheck())
                if (a == "live") {
                    j.put("edge_samples", runCatching { ExternalTts.edgeSelfTest() }.getOrElse { e -> j.put("edge_error", e.message); -1 })
                    runCatching { ExternalTts.synthChain(c, ExternalTts.settings(c).copy(engine = ExternalTts.ENGINE_EDGE), "测试") }
                        .onSuccess { (pcm, used) -> j.put("chain_used", used).put("chain_samples", pcm.size) }
                        .onFailure { e -> j.put("chain_error", e.message) }
                }
                out.putString("result", j.toString())
            }
            "voiceprint" -> {
                // [Mines-voiceprint-35] arg: "inject:speech" | "inject:silence" | "inject:off" (mic replaced for the
                // enrolment page), "pipeline" (model download + 3 synthetic clips + enrol + score), "clear"
                val c = context!!
                val a = arg ?: ""
                val j = org.json.JSONObject()
                when {
                    a.startsWith("inject:") -> { Voiceprint.inject = a.removePrefix("inject:").takeIf { it != "off" }; j.put("inject", Voiceprint.inject ?: "off") }
                    a == "clear" -> { Voiceprint.clear(c); j.put("cleared", true) }
                    a == "pipeline" -> {
                        val t0 = System.currentTimeMillis()
                        val prev = Voiceprint.inject
                        Voiceprint.inject = "speech"
                        try {
                            kotlinx.coroutines.runBlocking {
                                runCatching { Voiceprint.ensureModel(c) }.onFailure { j.put("model_error", it.message) }
                                j.put("model", Voiceprint.hasModel(c)).put("model_ms", System.currentTimeMillis() - t0)
                                val clips = (0 until 3).map { Voiceprint.recordUtterance().also { cl -> Voiceprint.prepare(c, cl) } }
                                j.put("clip_samples", clips.sumOf { it.size })
                                if (Voiceprint.hasModel(c)) runCatching {
                                    j.put("quality", Voiceprint.saveEnrolment(c, clips).toDouble())
                                    j.put("score_self", (Voiceprint.score(c, clips[0]) ?: -1f).toDouble())
                                }.onFailure { j.put("enrol_error", it.message) }
                                Voiceprint.inject = "silence"
                                j.put("silence_error", runCatching { Voiceprint.recordUtterance(waitSpeechMs = 1500); "none" }.getOrElse { it.message })
                            }
                        } finally { Voiceprint.inject = prev }
                        j.put("enrolled", Voiceprint.settings(c).enrolled).put("ms", System.currentTimeMillis() - t0)
                    }
                    a == "assetsave" -> {
                        // [Mines-voiceprint-35.3] model deleted → save must finish from the bundled asset within 5 s
                        val prev = Voiceprint.inject
                        Voiceprint.inject = "speech"
                        try {
                            kotlinx.coroutines.runBlocking {
                                // [Mines-38.1] the real page flow: model deleted, page opens → warm() in the background
                                // while the three phrases are recorded, then 保存 is timed
                                runCatching { Voiceprint.modelFile(c).delete() }
                                Voiceprint.warm(c)
                                val clips = (0 until 3).map { Voiceprint.recordUtterance().also { cl -> Voiceprint.prepare(c, cl) } }
                                j.put("asset", Voiceprint.hasAsset(c))
                                val t0 = System.currentTimeMillis()
                                runCatching { Voiceprint.awaitWarm(); Voiceprint.ensureModel(c); j.put("quality", Voiceprint.saveEnrolment(c, clips).toDouble()) }
                                    .onFailure { j.put("error", it.message) }
                                j.put("save_ms", System.currentTimeMillis() - t0).put("model", Voiceprint.hasModel(c))
                            }
                        } finally { Voiceprint.inject = prev }
                        j.put("enrolled", Voiceprint.settings(c).enrolled)
                    }
                    else -> j.put("settings", Voiceprint.settings(c).toString())
                }
                out.putString("result", j.toString())
            }
            "models" -> {
                // [Mines-models-35] the wake-word / offline ASR model through ModelFetch (mirrors, resume, sha256)
                val c = context!!
                val t0 = System.currentTimeMillis()
                if (arg == "fresh") runCatching { com.openminis.app.speech.OfflineAsrEngine.modelDir(c, com.openminis.app.speech.OfflineAsrEngine.MODEL_ZH).deleteRecursively() }
                val ok = com.openminis.app.speech.OfflineAsrEngine.ensureModel(c, com.openminis.app.speech.OfflineAsrEngine.MODEL_ZH)
                out.putString("result", org.json.JSONObject().put("vosk_ok", ok).put("ms", System.currentTimeMillis() - t0)
                    .put("error", com.openminis.app.speech.OfflineAsrEngine.lastError ?: "").put("status", com.openminis.app.speech.OfflineAsrEngine.lastStatus ?: "").toString())
            }
            "translate" -> {
                // [Mines-translate-35] page translation end to end on an English page (off-screen WebView)
                val c = context!!
                val j = org.json.JSONObject()
                kotlinx.coroutines.runBlocking {
                    val wv = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.webkit.WebView(c.applicationContext).apply {
                            settings.javaScriptEnabled = true
                            loadDataWithBaseURL("https://example.com/", "<html lang=en><body><h1>Sign in to continue</h1><p>Editorial diagram design for your coding assistant. Free credits every day for new users.</p><a href='#'>Read the documentation</a></body></html>", "text/html", "utf-8", null)
                        }
                    }
                    kotlinx.coroutines.delay(2500)
                    j.put("foreign", com.openminis.app.browser.WebTranslate.detectForeign(wv))
                    runCatching { com.openminis.app.browser.WebTranslate.translate(c, wv) }
                        .onSuccess { j.put("applied", it) }.onFailure { j.put("error", it.message) }
                    val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        kotlinx.coroutines.suspendCancellableCoroutine<String> { cc -> wv.evaluateJavascript("document.body.innerText") { v -> cc.resumeWith(Result.success(v ?: "")) } }
                    }
                    j.put("text", txt.take(200))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { wv.destroy() }
                }
                out.putString("result", j.toString())
            }
            "mcpmerge" -> {
                // [Mines-dsh-35] 「装了又掉」 regression: a server written straight into servers.json by the
                // sandbox (minis-dsh registers "dsh" this way) must survive the app's own saves.
                val c = context!!
                val repo = (c.applicationContext as com.openminis.app.MinisApp).mcpRepository
                val f = java.io.File(c.filesDir, "minis-global/mcp-servers/servers.json")
                f.parentFile?.mkdirs()
                val root = runCatching { org.json.JSONObject(f.readText()) }.getOrDefault(org.json.JSONObject())
                val ms = root.optJSONObject("mcpServers") ?: org.json.JSONObject().also { root.put("mcpServers", it) }
                ms.put("smoke-dsh", org.json.JSONObject().put("command", "/usr/bin/node").put("args", org.json.JSONArray().put("/usr/local/lib/minis-dsh/socket.mjs")).put("note", "smoke"))
                f.writeText(root.toString(2))
                // the app saves from its stale in-memory list (what build-34 did on any MCP toggle)
                repo.add(com.openminis.app.data.repository.MCPRepository.MCPServerConfig(id = "smoke-other", command = "/bin/true"))
                val after = runCatching { org.json.JSONObject(f.readText()).optJSONObject("mcpServers") }.getOrNull()
                val kept = after?.has("smoke-dsh") == true
                repo.delete("smoke-other")
                // clean up our fake entry by editing the file the same way
                runCatching { val r2 = org.json.JSONObject(f.readText()); r2.optJSONObject("mcpServers")?.remove("smoke-dsh"); f.writeText(r2.toString(2)) }
                repo.reloadFromDisk()
                out.putString("result", org.json.JSONObject().put("kept", kept).toString())
            }
            "loginweb" -> {
                // [Mines-web-35] the login / key-fetch WebView setup (LoginWeb.configure) renders real pages:
                // visible text length of [arg] read off-screen, plus the zoom settings it ends up with.
                val c = context!!
                val u = arg ?: "https://example.com/"
                val t0 = System.currentTimeMillis()
                val txt = kotlinx.coroutines.runBlocking { HiddenWeb.text(c, u, 40_000L) }
                out.putString("result", org.json.JSONObject().put("url", u).put("chars", txt?.length ?: -1)
                    .put("head", (txt ?: "").replace(Regex("\\s+"), " ").take(120)).put("ms", System.currentTimeMillis() - t0).toString())
            }
            "zoom" -> {
                // [Mines-zoom-34] in-app browser zoom: settings for the tab profile ("mobile" | "desktop") and
                // that a zoom-locking meta viewport is rewritten to user-scalable=yes / maximum-scale=5.
                // [Mines-render-34] run 20 timed out here: the detached WebView was only referenced from the
                // posted Runnable, so nothing kept it alive and it could be collected (renderer released) before
                // onPageFinished. It is now held in [held] until the check ends, and the stage reached is reported.
                val c = context!!
                val res = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val stage = java.util.concurrent.atomic.AtomicReference("post")
                val latch = java.util.concurrent.CountDownLatch(1)
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                main.post {
                    try {
                        val wv = android.webkit.WebView(c.applicationContext)
                        held = wv
                        stage.set("created")
                        val prof = if (arg == "desktop") com.openminis.app.browser.UserAgentProfile.DESKTOP_CHROME
                            else com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME
                        com.openminis.app.browser.BrowserUseManager.configureWebView(wv, prof)
                        val desc = com.openminis.app.browser.BrowserZoom.describe(wv)
                        wv.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(v: android.webkit.WebView, u: String?, f: android.graphics.Bitmap?) { stage.set("started") }
                            override fun onRenderProcessGone(v: android.webkit.WebView, d: android.webkit.RenderProcessGoneDetail?): Boolean {
                                stage.set("renderer-gone"); res.compareAndSet(null, "{\"ok\":false,\"error\":\"renderer gone\"}"); latch.countDown(); return true
                            }
                            override fun onPageFinished(v: android.webkit.WebView, u: String?) {
                                stage.set("finished")
                                com.openminis.app.browser.BrowserZoom.onPage(v, u, finished = true)
                                main.postDelayed({
                                    v.evaluateJavascript("document.querySelector('meta[name=viewport]').getAttribute('content')") { meta ->
                                        val ok = desc.contains("supportZoom=true builtIn=true display=false wide=true overview=true") &&
                                            (meta ?: "").contains("user-scalable=yes") && (meta ?: "").contains("maximum-scale=5")
                                        res.compareAndSet(null, org.json.JSONObject().put("ok", ok).put("profile", prof.value).put("settings", desc)
                                            .put("meta", meta ?: "").toString())
                                        latch.countDown()
                                    }
                                }, 300)
                            }
                        }
                        wv.loadDataWithBaseURL("https://example.com/",
                            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\"></head><body>zoom</body></html>",
                            "text/html", "utf-8", null)
                        stage.set("loading")
                    } catch (e: Throwable) { res.compareAndSet(null, "{\"ok\":false,\"error\":\"${e.javaClass.simpleName}\"}"); latch.countDown() }
                }
                latch.await(40, TimeUnit.SECONDS)
                main.post { runCatching { held?.destroy() }; held = null }
                out.putString("result", res.get() ?: org.json.JSONObject().put("ok", false).put("error", "timeout").put("stage", stage.get()).toString())
            }
            "webrender" -> {
                // [Mines-render-34] the agent's real browser path (BrowserTabPool → navigate / get_text / screenshot):
                // arg "<url>|mobile" or "<url>|desktop"; ok needs DOM text, a non-blank screenshot and viewport text.
                val c = context!!.applicationContext
                val parts = (arg ?: "").split('|')
                val url = parts.getOrElse(0) { "" }.ifBlank { "https://example.com/" }
                val prof = if (parts.getOrNull(1) == "desktop") com.openminis.app.browser.UserAgentProfile.DESKTOP_CHROME
                    else com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME
                val r = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(100_000L) { com.openminis.app.browser.BrowserRenderCheck.selfTest(c, url, prof) }
                }
                out.putString("result", r ?: "{\"ok\":false,\"error\":\"timeout\"}")
            }
            "voicediag" -> {
                // [Mines-voice-35.2] 测试语音 with the given (fake) key: must say KEY error, never config error
                val c = context!!
                PlusVault.init(c)
                val lines = GeminiRoute.diagnose(c, GeminiLive.DEFAULT_MODEL, arg?.takeIf { it.isNotBlank() } ?: "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUM")
                val all = lines.joinToString("\n")
                val kind = when {
                    all.contains("配置错误") -> "config"
                    all.contains("Key 被拒") || all.contains("API key", true) || all.contains("换一个有效的 Key") -> "key"
                    all.contains("握手成功") -> "ok"
                    else -> "network"
                }
                out.putString("result", org.json.JSONObject().put("kind", kind).put("lines", org.json.JSONArray(lines)).toString())
            }
            "feedinject" -> {
                // [Mines-feed-35.3] two fake cards for the smoke: a 值得关注 offer (no recipe → Mines chat task)
                // and a 账号异常 broken key (fix:siliconflow → 一键修复). arg "clear" removes them.
                val c = context!!
                val now = System.currentTimeMillis()
                val list = if (arg == "clear") emptyList() else listOf(
                    Digest.Item("st:deal353", "网页", "smoke:deal", "SmokeAI 官方", "SmokeAI 新用户免费送 100 万 tokens",
                        "SmokeAI 新用户注册即可免费领取 100 万 tokens 额度，free credits", now - 60_000, url = "https://example.com/smokeai-free")
                        .also { it.headline = "SmokeAI 免费送 100 万 tokens"; it.brief = "新用户注册就送，有效期 30 天。" },
                    Digest.Item("pt:siliconflow:smoke353", "账号巡查", "patrol", "硅基流动", "硅基流动：密钥失效", "SmokeKey 401 Invalid token", now - 120_000,
                        act = "fix:siliconflow").also { it.headline = "硅基流动 SmokeKey 失效"; it.brief = "接口返回 401，密钥不能用了。" },
                ) + (if (arg != "resolved") emptyList() else listOf(
                    // [Mines-38.8] the user's 消息 screenshot: a solved xAI Grok card (→ green 已解决) above two still-broken ones
                    Digest.Item("pt:xai:smoke388ok", "账号巡查", "patrol", "xAI Grok", "xAI Grok：异常", "SmokeKey 403", now - 60_000,
                        act = "fix:xai").also { it.headline = "权限问题已解决：异常已排除"; it.brief = "xAI Grok 检测到异常，经过处理，现在密钥有效，权限正常。" },
                    Digest.Item("pt:xai:smoke388a", "账号巡查", "patrol", "xAI Grok", "xAI Grok：异常", "SmokeKey 403 permission", now - 7_200_000,
                        act = "fix:xai").also { it.headline = "密钥无效或权限不足，请检查设置"; it.brief = "服务器提示密钥无效或权限不足，请检查您的账户设置和权限。" },
                ))
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { Digest.inject(c, list) }; l.countDown() }
                l.await(5, TimeUnit.SECONDS)
                val j = org.json.JSONObject()
                Digest.items.value.filter { it.id.startsWith("st:") || it.id.startsWith("pt:siliconflow:smoke") || it.id.startsWith("pt:xai:smoke") }.forEach { j.put(it.id, it.tab.name + "/" + it.cat.name + "/" + (it.act ?: "")) }
                out.putString("result", j.toString())
            }
            "keyfake" -> {
                // [Mines-keys-35.3] arg "kind|url|key": that kind's key page = a fake page, the key it shows tests OK
                val (k, u, key) = (arg ?: "").split('|').let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
                KeyAgent.testUrl[k] = u; if (key.isNotBlank()) KeyTester.smokeKeys += key
                out.putString("result", org.json.JSONObject().put("kind", k).put("recipe", KeyAgent.recipe(k)?.url ?: "").toString())
            }
            "keystate" -> {
                val c = context!!
                PlusVault.init(c)
                val k = arg ?: ""
                out.putString("result", org.json.JSONObject().put("has", PlusVault.hasSecret(k)).put("tail", PlusVault.secret(k)?.takeLast(8) ?: "").toString())
            }
            "webfit" -> {
                // [Mines-webfit-38.3] arg "open:<url>": open the in-app browser (home ☰ → 打开浏览器) on <url>;
                // "probe": layout of the page on screen (CSS viewport vs screen width, overflow, settings, UA);
                // "secureoff": lift FLAG_SECURE for this self-test session so key pages can be screenshotted.
                val c = context!!
                val a = arg ?: ""
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                when {
                    a == "secureoff" -> { Privacy.secureOffForSelfTest = true; out.putString("result", "{\"ok\":true}") }
                    a.startsWith("layer:") -> {
                        // [Mines-webpaint-38.4] smoke A/B: force the on-screen browser WebView's layer type (none / hardware / software)
                        val l = java.util.concurrent.CountDownLatch(1)
                        main.post {
                            com.openminis.app.browser.WebFit.shown?.get()?.let { w ->
                                w.setLayerType(when (a.removePrefix("layer:")) { "hardware" -> android.view.View.LAYER_TYPE_HARDWARE; "software" -> android.view.View.LAYER_TYPE_SOFTWARE; else -> android.view.View.LAYER_TYPE_NONE }, null)
                                w.invalidate()
                            }
                            l.countDown()
                        }
                        l.await(5, TimeUnit.SECONDS)
                        out.putString("result", "{\"ok\":true}")
                    }
                    a.startsWith("open:") -> {
                        val l = java.util.concurrent.CountDownLatch(1)
                        main.post {
                            com.openminis.app.browser.WebFit.shown = null
                            com.openminis.app.ui.plus.PlusNav.openBrowser.value = a.removePrefix("open:")
                            l.countDown()
                        }
                        l.await(5, TimeUnit.SECONDS)
                        out.putString("result", "{\"ok\":true}")
                    }
                    else -> {
                        val res = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        val l = java.util.concurrent.CountDownLatch(1)
                        main.post {
                            val wv = com.openminis.app.browser.WebFit.shown?.get()
                            if (wv == null) { res.set("{\"ok\":false,\"error\":\"no browser WebView on screen\"}"); l.countDown(); return@post }
                            val dm = c.resources.displayMetrics
                            val base = org.json.JSONObject()
                                .put("viewW", wv.width).put("viewH", wv.height).put("attached", wv.isAttachedToWindow)
                                .put("displayW", dm.widthPixels).put("displayH", dm.heightPixels).put("density", dm.density.toDouble())
                                .put("densityDpi", dm.densityDpi).put("screenWidthDp", c.resources.configuration.screenWidthDp)
                                .put("settings", com.openminis.app.browser.BrowserZoom.describe(wv))
                                .put("settingsUa", wv.settings.userAgentString ?: "").put("scale", wv.scale.toDouble())
                            // [Mines-webpaint-38.4] paint diagnostics: layer type, load progress, what heal did, how much is drawn
                            // on screen (PixelCopy) and in a software draw, and the page's own view of its visible text
                            base.put("layer", com.openminis.app.browser.WebPaintCheck.layerName(wv.layerType)).put("progress", wv.progress)
                                .put("heal", com.openminis.app.browser.WebPaintCheck.lastHeal)
                                .put("softwareInk", com.openminis.app.browser.WebPaintCheck.softwareInk(wv))
                            wv.evaluateJavascript("if(!window.__minesRafOn){window.__minesRafOn=1;window.__minesRaf=0;(function f(){window.__minesRaf++;if(window.__minesRaf<100000)requestAnimationFrame(f)})()}", null)
                            kotlinx.coroutines.MainScope().launch {
                                base.put("screenInk", com.openminis.app.browser.WebPaintCheck.screenInk(wv))
                                kotlinx.coroutines.delay(500)
                                wv.evaluateJavascript(com.openminis.app.browser.WebPaintCheck.TEXT_JS) { tr ->
                                    runCatching { base.put("text", org.json.JSONObject(org.json.JSONTokener(tr ?: "\"\"").nextValue() as String)) }
                            wv.evaluateJavascript(com.openminis.app.browser.WebFit.PROBE_JS) { r ->
                                val page = runCatching { org.json.JSONObject(org.json.JSONTokener(r ?: "\"\"").nextValue() as String) }.getOrNull()
                                base.put("page", page ?: org.json.JSONObject())
                                if (page != null) {
                                    val iw = page.optInt("innerWidth"); val sw = page.optInt("scrollWidth"); val vw = (wv.width / dm.density).toInt()
                                    // fits: no horizontal overflow beyond the CSS viewport, and that viewport (×zoom) spans the view
                                    base.put("cssViewW", vw).put("fits", sw <= iw + 2 && iw > 0)
                                        .put("mobileUa", page.optString("ua").contains("Mobile"))
                                }
                                res.set(base.put("ok", true).toString()); l.countDown()
                            }
                                }
                            }
                        }
                        l.await(10, TimeUnit.SECONDS)
                        out.putString("result", res.get() ?: "{\"ok\":false,\"error\":\"timeout\"}")
                    }
                }
            }
            "route" -> {
                // [Mines-38.5] smoke: open any Mines route (群聊 / AI 伙伴 / 插件 / 知识库 …) the way an in-app tap does
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(arg ?: ""); l.countDown() }
                l.await(5, TimeUnit.SECONDS)
                out.putString("result", "ok")
            }
            "openkind" -> {
                // open 应用 → that service's page (smoke; the mines_route deep link does not navigate on the emulator)
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.openminis.app.ui.plus.PlusNav.openKind = arg?.takeIf { it.isNotBlank() }
                    com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS); l.countDown() }
                l.await(5, TimeUnit.SECONDS)
                out.putString("result", "ok")
            }
            "fakeaccounts" -> {
                // [Mines-privacy-35.3] accounts with a full e-mail / several keys, so the smoke can check the masking
                // and take collapsed / expanded screenshots (FLAG_SECURE is lifted for the self-test only).
                val c = context!!
                PlusVault.init(c)
                Privacy.secureOffForSelfTest = true
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        PlusVault.kind("qqmail")?.let { PlusVault.save(c, it, "selftest.demo@qq.com", "abcdefghijklmnop", share = false, via = "app") }
                        PlusVault.kind("sensenova")?.let { k ->
                            if (!PlusVault.hasSecret("sensenova")) PlusVault.save(c, k, "商汤", "sk-smokesense00000000000001", share = false, via = "key")
                            repeat(2) { n -> val id = PlusVault.newAccountId("sensenova")
                                PlusVault.kind(id)?.let { kk -> PlusVault.save(c, kk, "商汤 ${n + 2}", "sk-smokesense0000000000000${n + 2}", share = false, via = "key") } }
                        }
                    }.onFailure { out.putString("error", it.message) }
                    l.countDown()
                }
                l.await(5, TimeUnit.SECONDS)
                out.putString("result", org.json.JSONObject().put("accounts", PlusVault.entries.value.keys.joinToString(",")).toString())
            }
            "feedstate" -> {
                // [Mines-feed-35.3] last task started from a 消息 button: session id + the text that was sent
                val j = org.json.JSONObject().put("session", com.openminis.app.ui.plus.FeedTasks.lastSession ?: "")
                    .put("prompt", (com.openminis.app.ui.plus.FeedTasks.lastPrompt ?: "").take(300))
                    .put("running", com.openminis.app.ui.plus.FeedTasks.running.value.joinToString(","))
                    .put("failed", org.json.JSONObject(com.openminis.app.ui.plus.FeedTasks.failed.value as Map<*, *>))
                out.putString("result", j.toString())
            }
            "voiceintent" -> {
                // [Mines-voice-35.2] arg = what the user said; a match opens the page (声纹录入 on the voice screen)
                val c = context!!
                val text = arg ?: "设定好我的声音录入"
                val page = VoiceIntents.match(text)
                val j = org.json.JSONObject().put("page", page ?: "").put("promise", VoiceIntents.isPromise("好，我马上为您设置录入声音"))
                if (page != null) {
                    val r = java.util.concurrent.atomic.AtomicReference<String>()
                    val l = java.util.concurrent.CountDownLatch(1)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { r.set(runCatching { VoiceIntents.open(c, page) }.getOrElse { "ERR " + it.message }); l.countDown() }
                    l.await(5, TimeUnit.SECONDS)
                    j.put("opened", r.get())
                }
                out.putString("result", j.toString())
            }
            "cardfill" -> {
                // [Mines-cards-35.1] 填卡 JS on a local payment form (test card 4242…), OCR parsing, Luhn, and
                // the per-account WebView profile (多账号隔离). Never uses a real card.
                val c = context!!
                val res = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val latch = java.util.concurrent.CountDownLatch(1)
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val scan = PayCards.parseOcr("VISA\n4242 4242 4242 4242\nVALID THRU 08/29\nJOHN APPLESEED\n")
                var profileOk = "unsupported"
                main.post {
                    try {
                        if (LoginWeb.profilesSupported()) {
                            val pw = android.webkit.WebView(c.applicationContext)
                            LoginWeb.configure(pw, "selftest#2")
                            profileOk = if (LoginWeb.lastShared) "shared" else androidx.webkit.WebViewCompat.getProfile(pw).name
                            pw.destroy()
                        }
                        val wv = android.webkit.WebView(c.applicationContext)
                        held = wv
                        wv.settings.javaScriptEnabled = true
                        PayCards.install(wv)
                        wv.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(v: android.webkit.WebView, u: String?) {
                                v.evaluateJavascript(PayCards.DETECT_JS) { det ->
                                    PayCards.fill(v, PayCards.Card(number = "4242424242424242", expMonth = "08", expYear = "29", cvc = "123", name = "JOHN APPLESEED", postal = "94107")) { n ->
                                        v.evaluateJavascript("[document.getElementById('n').value,document.getElementById('e').value,document.getElementById('c').value,document.getElementById('h').value,document.getElementById('z').value,String(window.__submitted||0)].join('|')") { vals ->
                                            val locked = PayCards.lockedFor(v, v.url)
                                            res.compareAndSet(null, org.json.JSONObject().put("detect", det ?: "").put("filled", n).put("values", vals ?: "")
                                                .put("locked", locked).put("ocr_number", scan.number ?: "").put("ocr_exp", "${scan.expMonth}/${scan.expYear}")
                                                .put("ocr_name", scan.name ?: "").put("luhn_bad", PayCards.luhn("4242424242424241")).put("profile", profileOk).toString())
                                            latch.countDown()
                                        }
                                    }
                                }
                            }
                        }
                        wv.loadDataWithBaseURL("https://shop.example/",
                            "<html><body><form onsubmit=\"window.__submitted=1;return false\"><input id=n autocomplete=cc-number><input id=e autocomplete=cc-exp><input id=c autocomplete=cc-csc>" +
                                "<input id=h autocomplete=cc-name><input id=z autocomplete=postal-code><button>Pay</button></form></body></html>", "text/html", "utf-8", null)
                    } catch (e: Throwable) { res.compareAndSet(null, "{\"ok\":false,\"error\":\"${e.javaClass.simpleName}: ${e.message?.take(80)}\"}"); latch.countDown() }
                }
                latch.await(30, TimeUnit.SECONDS)
                main.post { runCatching { held?.destroy() }; held = null }
                out.putString("result", res.get() ?: "{\"ok\":false,\"error\":\"timeout\"}")
            }
            "group" -> out.putString("result", com.openminis.app.plus.group.GroupSelfTest.handle(context!!, arg ?: "")) // [Mines-group-37]
            // [Mines-38.8] 消息 → 邮箱: fake inbox for screenshots, plain-IMAP fake server (runner) for the real add/read path
            "mailbox" -> {
                val c = context!!.applicationContext
                val a = arg ?: ""
                val j = org.json.JSONObject()
                when {
                    a == "fake" -> { MailInbox.fakeFill(); j.put("ok", true) }
                    a == "off" -> { MailInbox.fakeOff(c); j.put("ok", true) }
                    a.startsWith("plain ") -> { MailImap.plainOverride = a.removePrefix("plain ").trim().ifBlank { null }; j.put("plain", MailImap.plainOverride ?: "") }
                    a.startsWith("add ") -> {
                        val p = a.removePrefix("add ").split('|')
                        j.put("error", kotlinx.coroutines.runBlocking { MailInbox.verifyAndAdd(c, p.getOrElse(0) { "" }, p.getOrElse(1) { "" }) } ?: "")
                    }
                    a == "refresh" -> kotlinx.coroutines.runBlocking { MailInbox.refresh(c, force = true) }
                    a == "open" -> {
                        val l = java.util.concurrent.CountDownLatch(1)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            com.openminis.app.ui.plus.PlusNav.digestTab = com.openminis.app.ui.plus.MAIL_TAB
                            com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.DIGEST); l.countDown() }
                        l.await(5, TimeUnit.SECONDS); j.put("ok", true)
                    }
                }
                j.put("accounts", org.json.JSONArray(MailInbox.accounts.value.map { it.address }))
                j.put("states", org.json.JSONObject(MailInbox.states.value.mapValues { (_, v) -> v.st.name + ":" + v.msg.take(60) + ":" + v.unread }))
                j.put("mails", MailInbox.mails.value.size)
                j.put("subjects", org.json.JSONArray(MailInbox.mails.value.take(5).map { it.subject.take(40) + (if (it.unread) " *" else "") }))
                out.putString("result", j.toString())
            }
            // [Mines-38.9.2] 消息 pull-to-refresh: a fake source that brings one new card per read, a 163 box with a wrong
            // 授权码 on the runner's fake IMAP (the real IMAP path → 「163 授权码失效」), open 消息, state for the smoke
            "msgfeed" -> {
                val c = context!!.applicationContext
                val a = arg ?: ""
                val j = org.json.JSONObject()
                when {
                    a == "seed" -> {
                        Digest.resetTestReads()
                        Digest.testSource = { n ->
                            val now = System.currentTimeMillis()
                            (1..n).map { i ->
                                Digest.Item("st:msg$i", "Smoke", "smoke:msg", "Smoke 来源", "Smoke 新消息 #$i", "第 $i 次读取送来的消息", now - (n - i) * 1000L)
                                    .also { it.headline = "Smoke 新消息 #$i"; it.brief = "第 $i 次读取送来的消息" }
                            }
                        }
                        j.put("ok", true)
                    }
                    a.startsWith("badbox ") -> {
                        MailImap.plainOverride = a.removePrefix("badbox ").trim().ifBlank { null }
                        MailSources.testBoxes = listOf(Triple("163", "smoke|smoke163@smoke.test", "wrongcode"))
                        MailSources.invalidate()
                        j.put("ok", true)
                    }
                    a == "off" -> { Digest.testSource = null; MailSources.testBoxes = emptyList(); MailSources.invalidate(); j.put("ok", true) }
                    a == "pull" -> {
                        val o = kotlinx.coroutines.runBlocking { Digest.refresh(c, FeedRefresh.Mode.PULL) }
                        j.put("line", FeedRefresh.line(o)).put("fresh", org.json.JSONArray(o?.fresh ?: emptyList<String>()))
                    }
                    a == "open" -> {
                        val l = java.util.concurrent.CountDownLatch(1)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            com.openminis.app.ui.plus.PlusNav.digestTab = "FOR_YOU"
                            com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.DIGEST); l.countDown() }
                        l.await(5, TimeUnit.SECONDS); j.put("ok", true)
                    }
                }
                j.put("items", Digest.items.value.size)
                j.put("smoke", org.json.JSONArray(Digest.items.value.filter { it.id.startsWith("st:msg") }.map { it.id }))
                j.put("top", org.json.JSONArray(Digest.items.value.take(3).map { it.id }))
                j.put("fresh", j.opt("fresh") ?: org.json.JSONArray(Digest.fresh.value.toList()))
                j.put("lastError", Digest.lastError ?: "")
                j.put("lastRefreshAgoMs", if (Digest.lastRefreshAt == 0L) -1 else System.currentTimeMillis() - Digest.lastRefreshAt)
                j.put("busy", Digest.busy.value)
                out.putString("result", j.toString())
            }
            // [Mines-38.6] F: one real read of 微博 / B站 / 知乎 (own session + a public endpoint each)
            "sourcecheck" -> out.putString("result", kotlinx.coroutines.runBlocking {
                org.json.JSONArray(SourceAccounts.check(context!!.applicationContext).map { (own, pub) ->
                    org.json.JSONObject().put("kind", own.kind).put("own", own.text).put("own_http", own.http).put("own_ok", own.ok).put("own_detail", own.detail.take(100))
                        .put("public", pub.text).put("public_http", pub.http).put("public_count", pub.count).put("public_detail", pub.detail.take(100))
                }).toString()
            })
            else -> out.putString("result", "unknown method")
        }
        return out
    }

    /** [Mines-render-34] Keeps the zoom self-test's detached WebView reachable until the check ends. */
    @Volatile private var held: android.webkit.WebView? = null

    private fun split(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null; var any = false
        for (c in s) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' -> { q = c; any = true }
                c == ' ' -> { if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false } }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
