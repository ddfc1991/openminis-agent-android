package com.openminis.app.plus.group

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-group-37] adb-only smoke hooks (via SelfTestProvider, uid 2000/0):
 *   content call … --method group --arg 'partners'
 *   … --arg 'custom 名字|http://10.0.2.2:8088/a/v1|key|smoke-model'
 *   … --arg 'make 群名|id1,id2'            → group id
 *   … --arg 'send <gid>|text'              → waits for the round, returns the replies
 *   … --arg 'reset'
 */
object GroupSelfTest {
    /** [Mines-38.9.13 integ] the chat opened by 'i3913 chat' */
    @Volatile private var i3913Sid: String? = null
    /** [Mines-38.9.6 i14] smoke: fake retries per chat ('i396 retryreg') */
    private val fakeRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun skyState(): String = JSONObject().put("scene", com.openminis.app.ui.plus.WeatherSky.lastScene.key)
        .put("mode", com.openminis.app.ui.plus.WeatherSky.lastMode).put("error", com.openminis.app.ui.plus.WeatherSky.shaderError ?: "")
        .put("frames", com.openminis.app.ui.plus.WeatherSky.frames).put("shot", com.openminis.app.ui.plus.WeatherSky.lastShot).put("api", android.os.Build.VERSION.SDK_INT)
        .put("perf", com.openminis.app.ui.plus.WeatherSky.perfLabel()).put("level", com.openminis.app.ui.plus.WeatherSky.level)
        .put("renderer", com.openminis.app.ui.plus.WeatherSky.renderer).put("measured", com.openminis.app.ui.plus.WeatherSky.measuredFrames)
        .put("slow", com.openminis.app.ui.plus.WeatherSky.slowFrames).put("lastFrameMs", com.openminis.app.ui.plus.WeatherSky.lastFrameMs.toDouble())
        .put("wind", com.openminis.app.ui.plus.WeatherSky.windRateLabel())
        .put("keyRenders", com.openminis.app.ui.plus.WeatherSky.keyRenders).put("keyLost", com.openminis.app.ui.plus.WeatherSky.keyLost).put("skies", com.openminis.app.ui.plus.WeatherSky.registry.size())
        .put("liveToken", com.openminis.app.ui.plus.WeatherSky.liveToken.value).toString()

    fun handle(context: Context, arg: String): String = runBlocking {
        GroupStore.init(context)
        val cmd = arg.substringBefore(' ')
        val rest = arg.substringAfter(' ', "")
        val parts = rest.split('|')
        when (cmd) {
            "preset" -> PresetSelfTest.handle(context, rest)  // [Mines-38.8]
            // [Mines-38.9.1] smoke: dynamic identities for 账号关联 ('kcseed' seeds phone / 2 e-mails / Google / GitHub / wallet / Telegram)
            "kcseed" -> {
                listOf(com.openminis.app.plus.IdentityCore.IdType.PHONE to "13800138000", com.openminis.app.plus.IdentityCore.IdType.EMAIL to "smoke@example.com", com.openminis.app.plus.IdentityCore.IdType.EMAIL to "box1@smoke.test",
                    com.openminis.app.plus.IdentityCore.IdType.GOOGLE to "smoke@example.com", com.openminis.app.plus.IdentityCore.IdType.GITHUB to "smokedev", com.openminis.app.plus.IdentityCore.IdType.WALLET to "0x52908400098527886E0F7030069857D2E4169EE7",
                    com.openminis.app.plus.IdentityCore.IdType.TELEGRAM to "@smoke_user").forEach { (t, v) ->
                    com.openminis.app.plus.IdentityStore.addIdent(context, com.openminis.app.plus.IdentityCore.Ident(t, v, source = "smoke", signedIn = t == com.openminis.app.plus.IdentityCore.IdType.GOOGLE))
                }
                JSONArray(com.openminis.app.plus.IdentityStore.current(context).map { it.type.id + ":" + it.value }).toString()
            }
            // [Mines-38.9.3] 账号 page smoke: 'acct393 seed' = an API-key-only OpenRouter (patrol OK) + the stale records the user's
            // phone had (OpenRouter imported from 账号连接 as 过期了, a 豆包 过期了 never confirmed, a mail-sender-named row);
            // 'acct393 state' = what 账号 › 登录 shows (site / status / source) + OpenRouter's 服务 pill
            "acct393" -> {
                val V = com.openminis.app.plus.PlusVault; V.init(context)
                val S = com.openminis.app.plus.IdentityStore; S.init(context)
                val now = System.currentTimeMillis()
                if (rest.trim() == "seed") {
                    val k = V.kind("openrouter") ?: return@runBlocking "no openrouter"
                    V.save(context, k, "smoke", "sk-or-v1-smoke393", false, "key")
                    com.openminis.app.plus.AccountPatrol.smokeSet(context, "openrouter", com.openminis.app.plus.AccountPatrol.Level.OK, "密钥有效")
                    S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("openrouter.ai", "OpenRouter", "", null, com.openminis.app.plus.IdentityCore.Method.KEY,
                        status = com.openminis.app.plus.IdentityCore.EXPIRED, sources = setOf("账号连接"), at = now))
                    S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("doubao.com", "豆包", "13800138000", null, com.openminis.app.plus.IdentityCore.Method.PHONE_SMS,
                        status = com.openminis.app.plus.IdentityCore.EXPIRED, sources = setOf("网页登录"), at = now))
                    com.openminis.app.plus.AutoAccounts.record(context, "Matt from WarpStati.", "warpstatic.com", "smoke@example.com", com.openminis.app.plus.AutoAccounts.LINKED, "邮件")
                }
                S.sync(context, force = true)
                val l = com.openminis.app.plus.Keychain.all(context)
                JSONObject().put("login", JSONArray(l.map { it.site + "|" + it.status.name + "|" + it.source }))
                    .put("openrouterInLogin", l.any { it.host.contains("openrouter") })
                    .put("badNames", JSONArray(l.filter { com.openminis.app.plus.IdentityCore.looksLikeSenderName(it.site) }.map { it.site }))
                    .put("expired", l.count { it.status == com.openminis.app.plus.KeychainCore.Status.EXPIRED })
                    .put("orPill", com.openminis.app.plus.SessionStatus.keyPill(com.openminis.app.plus.AccountPatrol.status.value["openrouter"]?.level?.name).name)
                    .put("auto", JSONArray(com.openminis.app.plus.AutoAccounts.list.value.map { it.site + "|" + it.host + "|" + it.source })).toString()
            }
            // [Mines-38.9.5 i] smoke: 'i395 seed' = accounts like the user's (Google / GitHub signed in, 阿里云 过期了, 163 未检查),
            // task progress on the 5 newest chats, a cloud browser + a server registered through workspace_register, 硅基 忽略;
            // 'i395 accounts' = the agent's accounts(list) text; 'i395 login <site>' = one hidden re-login attempt (truthful reason);
            // 'i395 gw' = the local 免登录 AI gateway (/v1/models over 127.0.0.1) + 模型组 route rows; 'i395 bailian <key>' = endpoint pick
            "i395" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val S = com.openminis.app.plus.IdentityStore; S.init(context)
                val now = System.currentTimeMillis()
                when (sub) {
                    "seed" -> {
                        // IC = IdentityCore (nested types need the type name, not a value)
                        S.addIdent(context, com.openminis.app.plus.IdentityCore.Ident(com.openminis.app.plus.IdentityCore.IdType.GOOGLE, "smoke@example.com", source = "smoke", signedIn = true))
                        S.addIdent(context, com.openminis.app.plus.IdentityCore.Ident(com.openminis.app.plus.IdentityCore.IdType.GITHUB, "smokedev", source = "smoke", signedIn = true))
                        S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("google.com", "Google", "smoke@example.com", com.openminis.app.plus.IdentityCore.IdType.EMAIL, com.openminis.app.plus.IdentityCore.Method.EMAIL_PW, status = com.openminis.app.plus.IdentityCore.LINKED, sources = setOf("网页登录"), at = now))
                        S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("github.com", "GitHub", "smokedev", null, com.openminis.app.plus.IdentityCore.Method.OAUTH, provider = com.openminis.app.plus.IdentityCore.IdType.GOOGLE, status = com.openminis.app.plus.IdentityCore.LINKED, sources = setOf("网页登录"), at = now))
                        S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("aliyun.com", "阿里云", "demo_user", null, com.openminis.app.plus.IdentityCore.Method.USER_PW, status = com.openminis.app.plus.IdentityCore.EXPIRED, sources = setOf("网页登录"), hasPw = true, at = now))
                        S.addRec(context, com.openminis.app.plus.IdentityCore.Rec("mail.163.com", "163 邮箱", "smoke.mines.test@163.com", com.openminis.app.plus.IdentityCore.IdType.EMAIL, com.openminis.app.plus.IdentityCore.Method.EMAIL_PW, status = com.openminis.app.plus.IdentityCore.UNCHECKED, sources = setOf("邮箱"), at = now))
                        S.sync(context, force = true)
                        com.openminis.app.plus.ServiceIgnore.set("siliconflow", true)
                        val app = context.applicationContext as? com.openminis.app.MinisApp
                        // [Mines-38.9.5 i11] five chats so every progress state has a row (titles = the goals)
                        var sids = runCatching { app!!.chatRepository.observeSessions().first().filter { it.parentSessionId == null }.map { it.id } }.getOrDefault(emptyList())
                        if (sids.size < 5) runCatching {
                            val model = app!!.chatRepository.observeSessions().first().firstOrNull()?.modelId ?: "smoke"
                            listOf("检查各账号登录状态", "领取免费云浏览器", "relay 部署", "领取：值得关注", "查看推特分享链接").drop(sids.size).take(5 - sids.size)
                                .forEach { app.chatRepository.createSession(model, title = it) }
                            sids = app.chatRepository.observeSessions().first().filter { it.parentSessionId == null }.map { it.id }
                        }
                        val tp = com.openminis.app.plus.TaskProgress.smoke(context, "seed", sids)
                        val w1 = com.openminis.app.plus.WorkspaceStore.runTool(context, JSONObject().put("type", "cloud_browser").put("host", "https://browser.example.test/s/42")
                            .put("name", "免费云浏览器").put("provider", "Example Cloud").put("account", "smoke@example.com").put("expires", "7天")
                            .put("limits", "每天 2 小时 · 2 核 4 GB").put("cost", "免费").put("vault_ref", "cloudbrowser:smoke").put("source_url", "https://example.test").toString())
                        JSONObject().put("tasks", JSONArray(tp).length()).put("sids", sids.size).put("ws", w1.output)
                            .put("ignored", com.openminis.app.plus.ServiceIgnore.ignored("siliconflow"))
                            .put("login", JSONArray(com.openminis.app.plus.Keychain.all(context).map { it.site + "|" + it.status.name })).toString()
                    }
                    "accounts" -> com.openminis.app.plus.AccountsTool.runTool(context, "smoke", JSONObject().put("tool_title", "账号").put("action", "list").toString()).output
                    "login" -> kotlinx.coroutines.withTimeoutOrNull(120_000) { com.openminis.app.plus.AccountsTool.hiddenLogin(context, arg2.ifBlank { "github.com" }) }
                        ?.let { JSONObject().put("ok", it.ok).put("line", it.line).put("needsUser", it.needsUser).toString() } ?: "timeout"
                    "gw" -> {
                        com.openminis.app.plus.PartnerGateway.ensure(context)
                        val models = runCatching {
                            val u = java.net.URL("http://127.0.0.1:${com.openminis.app.plus.PartnerGateway.PORT}/v1/models").openConnection() as java.net.HttpURLConnection
                            u.connectTimeout = 3000; u.readTimeout = 8000; u.setRequestProperty("Authorization", "Bearer mines-local")
                            u.responseCode.toString() + " " + (runCatching { u.inputStream.bufferedReader().readText() }.getOrDefault("")).take(600)
                        }.getOrElse { "ERR " + it.javaClass.simpleName + " " + it.message }
                        val rows = runCatching { com.openminis.app.plus.PartnerGateway.refreshStatus(context) }.getOrDefault(emptyList())
                        JSONObject().put("models", models).put("added", com.openminis.app.plus.PartnerGateway.providerAdded(context))
                            .put("rows", JSONArray(rows.map { it.name + "|" + it.st.name + "|" + it.reason })).toString()
                    }
                    "bailian" -> com.openminis.app.plus.RouteStatusCore.bailianBase(arg2, null, GroupPrefs.get("bailian_ws"))
                    // [Mines-38.9.5 i] secure login card / quote / greeting shots
                    "card" -> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.plus.LoginCard.demo(arg2 == "refused") }; "shown" }
                    "cardoff" -> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.plus.LoginCard.answer(null) }; "closed" }
                    "chips" -> JSONArray(com.openminis.app.plus.ValueChips.detect(arg2).map { it.kind.name + "|" + it.display + "|" + it.copyable }).toString()
                    else -> "i395 seed | accounts | login <site> | gw | bailian <key>"
                }
            }
            // [Mines-38.9.6 i12] smoke: 'i396 seed' = four chats for the progress / archive shots (a new chat with automatic
            // steps still running, a plain Q&A, a chat idle for 1 day with no table yet = rebuilt from history at home, one idle
            // 5 days = auto-archived); 'i396 state' = every listed chat's table + archive flag; 'i396 archive|restore <sid>';
            // 'i396 selfcheck' = the in-app 自检 (MinesSelfCheck.runAll) as JSON rows (name / ok / warn / detail).
            "i396" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                val TP = com.openminis.app.plus.TaskProgress; TP.init(context)
                val CO = com.openminis.app.plus.ChatOrganizer; CO.init(context)
                fun text(t: String) = JSONArray().put(JSONObject().put("type", "text").put("value", t)).toString()
                fun toolText(tool: String, t: String) = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("name", "shell_execute")
                    .put("input", JSONObject().put("tool_title", tool).put("command", "true").toString()))).put(JSONObject().put("type", "text").put("value", t)).toString()
                when (sub) {
                    "seed" -> {
                        val repo = app.chatRepository
                        val model = repo.observeSessions().first().firstOrNull { it.modelId.isNotEmpty() }?.modelId ?: "smoke"
                        val now = System.currentTimeMillis()
                        val out = JSONObject()
                        // 1) new chat, automatic steps, still running
                        val a = repo.createSession(model, title = "帮我领取免费云浏览器")
                        repo.appendMessage(a.id, "user", text("帮我领取免费云浏览器，注册完告诉我额度"))
                        TP.onTurnStart(a.id, "帮我领取免费云浏览器，注册完告诉我额度")
                        TP.onToolStart(a.id, "打开注册页"); TP.onToolEnd(a.id, "打开注册页", true)
                        TP.onToolStart(a.id, "填写邮箱"); TP.onToolEnd(a.id, "填写邮箱", true)
                        TP.onToolStart(a.id, "等邮件验证码")
                        out.put("auto", a.id)
                        // 2) plain Q&A
                        val b = repo.createSession(model, title = "今天新加坡天气怎么样？")
                        repo.appendMessage(b.id, "user", text("今天新加坡天气怎么样？"))
                        repo.appendMessage(b.id, "assistant", text("多云，31 °C，傍晚可能有阵雨，出门带把伞。"))
                        TP.onTurnStart(b.id, "今天新加坡天气怎么样？"); TP.onTurnEnd(b.id, false, "多云，31 °C，傍晚可能有阵雨，出门带把伞。")
                        out.put("qa", b.id)
                        // 3) idle 1 day, no table yet (built from history on home)
                        val c = repo.createSession(model, title = "整理服务器日志")
                        repo.appendMessage(c.id, "user", text("帮我看看服务器日志有没有报错"))
                        repo.appendMessage(c.id, "assistant", toolText("查看日志", "日志里没有报错，服务正常。"))
                        repo.appendMessage(c.id, "user", text("那再重启一下"))
                        repo.appendMessage(c.id, "assistant", text("已重启，状态 active。"))
                        app.database.chatDao().touchSession(c.id, now - 26 * 3_600_000L)
                        TP.forget(c.id)
                        out.put("idle", c.id)
                        // 4) idle 5 days → auto-archived (3-day rule)
                        val d = repo.createSession(model, title = "上周的旧对话：查快递")
                        repo.appendMessage(d.id, "user", text("帮我查一下快递到哪了"))
                        repo.appendMessage(d.id, "assistant", text("已到新加坡分拣中心，预计周三送达。"))
                        app.database.chatDao().touchSession(d.id, now - 5 * 86_400_000L)
                        TP.forget(d.id)
                        out.put("old", d.id)
                        out.toString()
                    }
                    "state" -> {
                        val all = app.chatRepository.observeSessions().first().filter { it.parentSessionId == null }
                        JSONArray(all.map { s ->
                            val t = TP.get(s.id)
                            JSONObject().put("sid", s.id).put("title", s.title ?: "").put("archived", CO.isArchived(s)).put("manual", CO.isManual(s.id))
                                .put("has", t != null).put("st", t?.st?.label ?: "").put("origin", t?.origin ?: "").put("auto", t?.auto ?: false)
                                .put("steps", JSONArray(t?.steps.orEmpty().map { it.title + "|" + it.st.id })).put("line", t?.let { com.openminis.app.plus.TaskProgressCore.oneLine(it) } ?: "")
                        }).toString()
                    }
                    // after a theme switch (the smoke force-stops the app, which turns 进行中 into 已停止): run the new chat's turn again
                    "relive" -> {
                        val a = app.chatRepository.observeSessions().first().firstOrNull { it.title == "帮我领取免费云浏览器" } ?: return@runBlocking "no chat"
                        TP.forget(a.id)
                        TP.onTurnStart(a.id, "帮我领取免费云浏览器，注册完告诉我额度")
                        TP.onToolStart(a.id, "打开注册页"); TP.onToolEnd(a.id, "打开注册页", true)
                        TP.onToolStart(a.id, "填写邮箱"); TP.onToolEnd(a.id, "填写邮箱", true)
                        TP.onToolStart(a.id, "等邮件验证码")
                        "relived ${a.id}"
                    }
                    "archive" -> { CO.archive(context, arg2); "archived $arg2" }
                    "restore" -> { CO.restore(context, arg2); "restored $arg2" }
                    "backfill" -> {
                        val all = app.chatRepository.observeSessions().first().filter { it.parentSessionId == null }
                        TP.backfill(context, all.map { it.id to it.title }); "ok ${all.size}"
                    }
                    "selfcheck" -> {
                        val rows = kotlinx.coroutines.withTimeoutOrNull(150_000) { com.openminis.app.plus.MinesSelfCheck.runAll(context) }
                        if (rows == null) "timeout" else JSONArray(rows.map {
                            JSONObject().put("name", it.name).put("ok", it.ok).put("warn", it.warn).put("detail", com.openminis.app.plus.AccountLabel.maskText(it.detail).take(140)).put("ms", it.ms)
                        }).toString()
                    }
                    // [Mines-38.9.6 i13] Memoh + proxy checks (fake values only; nothing real is printed)
                    "memohbad" -> {   // what 38.9.5 saved: account 「\u0001\u0001」 on memoh.net, no password, no cookie
                        com.openminis.app.plus.IdentityStore.smokeInject(context, com.openminis.app.plus.IdentityCore.Rec("memoh.net", "Memoh", "\\u0001\\u0001", null,
                            com.openminis.app.plus.IdentityCore.Method.WEB, sources = setOf("网页登录"), at = System.currentTimeMillis()))
                        GroupPrefs.put("memoh_user", "\\u0001\\u0001")
                        "seeded"
                    }
                    "memohcheck" -> {
                        com.openminis.app.plus.IdentityStore.sync(context, force = true)
                        val recs = com.openminis.app.plus.IdentityStore.recs.value.filter { it.site == "memoh.net" }
                        val ks = runCatching { com.openminis.app.plus.Keychain.all(context).filter { it.host == "memoh.net" } }.getOrDefault(emptyList())
                        JSONObject().put("ids", JSONArray(recs.map { it.identity })).put("status", JSONArray(ks.map { it.status.label }))
                            .put("user", PresetAuth.memohUser() ?: "").put("cookie", PresetAuth.memohWebCookie()?.contains("__Host-memoh396") == true).toString()
                    }
                    "memohcookie" -> {
                        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                        val cm = android.webkit.CookieManager.getInstance()
                        val u = "https://app.memoh.net/"
                        when (arg2) {
                            "set" -> {
                                GroupPrefs.put("memoh_base", PresetAuth.memohApiBaseFor(u)); GroupPrefs.put("memoh_web", "1")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    // a __Host- session cookie the way app.memoh.net sets it (host-only, Secure, no Max-Age)
                                    runCatching { cm.setCookie(u, "__Host-memoh396=smoke; Path=/; Secure; HttpOnly; SameSite=Lax") { cm.flush(); done.complete(Unit) } }.onFailure { done.complete(Unit) }
                                }
                                kotlinx.coroutines.withTimeoutOrNull(5_000) { done.await() }
                                com.openminis.app.plus.SessionKeeper.snapshot(context, 0L)
                            }
                            "drop" -> {   // the store loses it (what Chromium does to session cookies) — the snapshot must bring it back
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    runCatching { cm.setCookie(u, com.openminis.app.plus.LoginValueCore.deleteLines("__Host-memoh396", "memoh.net", false).first()) { cm.flush(); done.complete(Unit) } }.onFailure { done.complete(Unit) }
                                }
                                kotlinx.coroutines.withTimeoutOrNull(5_000) { done.await() }
                            }
                        }
                        JSONObject().put("cookie", runCatching { cm.getCookie(u) }.getOrNull()?.contains("__Host-memoh396=smoke") == true)
                            .put("restored", com.openminis.app.plus.SessionKeeper.isRestored()).toString()
                    }
                    "memohexpire" -> {   // self-hosted, saved password refused → the secure login card asks (once)
                        GroupPrefs.put("memoh_base", "http://10.0.2.2:8088/memoh396/api"); GroupPrefs.put("memoh_user", "smoke"); GroupPrefs.put("memoh_web", null)
                        PresetAuth.setKey(PresetRegistry.MEMOH, null)
                        com.openminis.app.plus.PlusVault.setSecretExtra("preset_auth", "memoh_pass", "wrong396")
                        PresetAuth.memohAllowAsk()
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { val t = PresetAuth.memohToken(force = true); GroupPrefs.put("smoke_memoh_tok", if (t != null) "ok" else "none") }
                        "asking"
                    }
                    "memohtok" -> JSONObject().put("tok", GroupPrefs.get("smoke_memoh_tok") ?: "").put("card", com.openminis.app.plus.LoginCard.pending.value != null).toString()
                    "proxyset" -> {   // smoke: the runner's authenticated proxy (fake_proxy.py, smoke/proxy396) as 自己填代理
                        com.openminis.app.plus.SplitProxy.update(context) { it.copy(enabled = true, forSandbox = true, forApp = true, upstreamType = "http",
                            upstreamHost = "10.0.2.2", upstreamPort = 3128, upstreamUser = "smoke", upstreamPass = "proxy396") }
                        runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() }
                        val st = com.openminis.app.plus.SplitProxy.settings.value
                        JSONObject().put("primary", st.primary?.toString() ?: "").put("user", st.upstreamUser).put("pass_saved", st.upstreamPass.isNotEmpty())
                            .put("running", com.openminis.app.plus.SplitProxy.running.value).put("web", com.openminis.app.plus.WebProxy.applied).toString()
                    }
                    "proxyoff" -> {
                        com.openminis.app.plus.SplitProxy.update(context) { it.copy(upstreamHost = "", upstreamPort = 0, upstreamUser = "", upstreamPass = "") }
                        runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() }; "off"
                    }
                    "logincard" -> { com.openminis.app.plus.LoginCard.demo(arg2 == "refused"); "shown" }
                    // [Mines-38.9.6 i14] weather sky: 'i396 sky rain|night|…|auto [static|live]' / 'i396 skystate'
                    "sky" -> {
                        val a = arg2.split(' ').filter { it.isNotBlank() }
                        com.openminis.app.ui.plus.WeatherSky.forced = com.openminis.app.plus.WeatherSkyCore.Scene.of(a.getOrNull(0))
                        com.openminis.app.ui.plus.WeatherSky.forceStatic = a.getOrNull(1) == "static"
                        com.openminis.app.ui.plus.WeatherSky.bump(); kotlinx.coroutines.delay(1500)
                        skyState()
                    }
                    "skystate" -> skyState()
                    // [38.9.11.1] 'i396 localproxy' = probe the local proxy ports now and show what would be exported
                    "localproxy" -> {
                        val lp = com.openminis.app.plus.LocalProxy
                        val before = lp.at
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            lp.probeNow("smoke")
                            var n = 0; while (lp.at == before && n < 200) { Thread.sleep(50); n++ }
                        }
                        val env = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.openminis.app.plus.SandboxNet.proxyEnv(context, "") }.getOrDefault(emptyMap()) }
                        JSONObject().put("summary", lp.summary()).put("best", lp.best ?: -1)
                            .put("probes", JSONArray(lp.probes.map { "${it.port}:${it.result}:${it.ms}" }))
                            .put("http_proxy", env["http_proxy"] ?: "").put("clash_proxy", env["MINES_CLASH_PROXY"] ?: "")
                            .put("source", env["MINES_PROXY_SOURCE"] ?: "").put("status", com.openminis.app.plus.SandboxNet.status.value).toString()
                    }
                    // [38.9.11.1] 'i396 skygpu on|off' (smoke: shader on the emulator's software GPU)
                    "skygpu" -> { com.openminis.app.ui.plus.WeatherSky.forceGpu(context, arg2.trim() != "off"); kotlinx.coroutines.delay(4000); skyState() }
                    // [38.9.11.1] 'i396 skymode smooth|rich|off' = 设置 › 外观调节 › 天空动画
                    "skymode" -> { com.openminis.app.ui.plus.WeatherSky.setMode(context, com.openminis.app.plus.SkyModeCore.parse(arg2.trim()))
                        kotlinx.coroutines.delay(800); skyState() }
                    // [Mines-38.9.9 sky v6] 'i396 skyclock <seconds>' shifts the sky clock (shots minutes apart); 0 = real time
                    "skyclock" -> { com.openminis.app.ui.plus.WeatherSky.clockOffsetMs = (arg2.trim().toLongOrNull() ?: 0L) * 1000L
                        com.openminis.app.ui.plus.WeatherSky.bump(); kotlinx.coroutines.delay(1500); skyState() }
                    // [Mines-38.9.6 i14] 重试 on the card: a fake live chat whose retry counts ('i396 retryreg' on the 「帮我领取…」 chat)
                    "retryreg" -> {
                        val sid = arg2.ifBlank { TP.tasks.value.values.firstOrNull { it.goal.contains("免费云浏览器") || it.steps.any { s -> s.title.contains("免费云浏览器") } }?.sid ?: "" }
                        if (sid.isBlank()) "no chat" else {
                            TP.onManualRetry(sid)
                            TP.register(sid, com.openminis.app.plus.TaskProgress.Actions(
                                retry = { fakeRetries.merge(sid, 1, Int::plus); TP.onManualRetry(sid) }, stop = {}))
                            JSONObject().put("sid", sid).toString()
                        }
                    }
                    "retrycount" -> JSONObject(fakeRetries.mapValues { it.value } as Map<*, *>).put("manual", JSONObject(TP.manualRetries.mapValues { it.value } as Map<*, *>)).toString()
                    // [Mines-38.9.6 i14] overlays: was the page behind blurred for the last one
                    "overlay" -> JSONObject().put("opened", com.openminis.app.ui.plus.GlassBackdrop.opened).put("blurred", com.openminis.app.ui.plus.GlassBackdrop.lastBlurred)
                        .put("depth", com.openminis.app.ui.plus.GlassBackdrop.depth.value).put("result", com.openminis.app.ui.plus.GlassBackdrop.lastResult)
                        .put("ready", com.openminis.app.ui.plus.GlassBackdrop.ready.value).put("ratio", com.openminis.app.ui.plus.GlassBackdrop.lastRatio.toDouble()).toString()
                    // [Mines-38.9.6 i17] three-path server check: 'i396 reach <host> <port>' → verdict JSON
                    "reach" -> {
                        val hp = arg2.split(' ').filter { it.isNotBlank() }
                        val r = com.openminis.app.plus.ReachProbe.check(hp.getOrElse(0) { "10.0.2.2" }, hp.getOrNull(1)?.toIntOrNull() ?: 22)
                        JSONObject().put("kind", r.verdict.kind.name).put("text", r.verdict.text).put("direct", r.paths.direct).put("proxy", r.paths.proxy ?: JSONObject.NULL)
                            .put("outside", "${r.paths.outsideOk}/${r.paths.outsideTotal}").put("down", r.verdict.serverDown).toString()
                    }
                    // [Mines-38.9.6 i17] a fake OpenAI-compatible provider + smoke-model when onboarding never finished (emulator
                    // GMS / FontsProvider death killed the app mid-onboarding in smoke P → 快速测试 / 选择模型 had no model)
                    "seedmodel" -> {
                        val repo = app.providerRepository
                        val have = repo.config.value.modelEntries.firstOrNull { it.baseModel.id == "smoke-model" }
                            ?: repo.config.value.modelEntries.firstOrNull { !it.isHidden }
                        if (have != null) JSONObject().put("seeded", false).put("model", have.baseModel.id).toString() else {
                            val base = arg2.ifBlank { "http://10.0.2.2:8088" }
                            val inst = com.openminis.app.debug.ProviderMutationMethods.instancesCreate(context, JSONObject()
                                .put("providerType", "openAI").put("label", "Smoke").put("customBaseURL", base)
                                .put("seedBuiltInModels", false).put("apiKey", "sk-smoke-000000000000000000000000"))
                            val iid = inst.optJSONObject("instance")?.optString("id").orEmpty()
                            com.openminis.app.debug.ProviderMutationMethods.modelsAdd(context, JSONObject().put("instanceId", iid).put("modelId", "smoke-model"))
                            JSONObject().put("seeded", true).put("instance", iid).put("models", repo.config.value.modelEntries.size).toString()
                        }
                    }
                    // [Mines-38.9.6 i14] model detail page of the first model + its 快速测试 sheet
                    "quicktest" -> {
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden } ?: app.providerRepository.config.value.modelEntries.firstOrNull()
                        if (e == null) "no model" else {
                            com.openminis.app.ui.components.QuickTestAuto.armed = true
                            val route = com.openminis.app.ui.navigation.Routes.modelEntryDetail(e.providerInstanceId, e.id)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(route) }
                            JSONObject().put("route", route).toString()
                        }
                    }
                    else -> "i396 seed | state | archive <sid> | restore <sid> | backfill | selfcheck | memohbad | memohcheck | memohcookie set|drop|get | memohexpire | memohtok | proxyset | proxyoff | logincard [refused] | sky <scene|auto> [static] | skystate | retryreg | retrycount | overlay | quicktest | seedmodel | reach <host> <port>"
                }
            }
            // [Mines-38.9.7] smoke: 'i397 wait' = two chats waiting for the user (greeting 「2 件事等你」 → list);
            // 'i397 handoff' = what the agent would take over; 'i397 agentbrowse' = a fresh chat pool's agent tab (empty pool →
            // adopts the user's page); 'i397 incog' = 无痕 support + last close; 'i397 engineer [on|off]'; 'i397 market' = SkillHub
            // plugin rows as the market shows them (desktop / panel rows must be absent)
            "i397" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                val TP = com.openminis.app.plus.TaskProgress; TP.init(context)
                when (sub) {
                    "wait" -> {
                        val repo = app.chatRepository
                        val model = repo.observeSessions().first().firstOrNull { it.modelId.isNotEmpty() }?.modelId ?: "smoke"
                        val out = JSONArray()
                        for ((title, why) in listOf("注册免费云服务器" to "等你点一下邮件里的验证码", "绑定 GitHub 账号" to "等你在手机上确认登录")) {
                            val a = repo.createSession(model, title = title)
                            TP.onTurnStart(a.id, title); TP.onToolStart(a.id, "打开页面"); TP.onToolEnd(a.id, "打开页面", true)
                            TP.onWaiting(a.id, why); out.put(a.id)
                        }
                        out.toString()
                    }
                    "handoff" -> {
                        val shared = app.sharedBrowserTabPool
                        val last = com.openminis.app.browser.BrowserHandoff.last
                        JSONObject().put("selected", shared.selectedTabId.value).put("tabs", shared.tabs.value.size)
                            .put("url", shared.activeManager?.currentURL?.value ?: "")
                            .put("last", last?.url ?: "").put("session", last?.sessionJson != null).put("desktop", last?.desktop ?: false)
                            .put("adopted", com.openminis.app.browser.BrowserHandoff.adopted).toString()
                    }
                    "agentbrowse" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val pool = com.openminis.app.browser.BrowserTabPool(context).also { it.setSession("smoke397-" + System.currentTimeMillis()) }
                        val before = com.openminis.app.browser.BrowserHandoff.adopted
                        val r = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                            pool.execute(com.openminis.app.browser.BrowserActionInput(action = com.openminis.app.browser.BrowserAction.GET_PAGE_INFO))
                        }
                        kotlinx.coroutines.delay(1500)
                        val url = pool.activeManager?.currentURL?.value ?: ""
                        val o = JSONObject().put("adopted", com.openminis.app.browser.BrowserHandoff.adopted - before).put("url", url)
                            .put("ok", r?.success ?: false).put("text", (r?.text ?: "timeout").take(200))
                        pool.destroyAllTabs()
                        o.toString()
                    }
                    "incog" -> JSONObject().put("supported", com.openminis.app.browser.Incognito.supported())
                        .put("lastClose", com.openminis.app.browser.Incognito.lastClose ?: "").toString()
                    "engineer" -> {
                        if (arg2 == "on" || arg2 == "off") com.openminis.app.tools.AgentToolSwitch.ENGINEER.setEnabled(context, arg2 == "on")
                        JSONObject().put("on", com.openminis.app.tools.AgentToolSwitch.ENGINEER.isEnabled(context)).put("chars", com.openminis.app.agent.EngineerMode.PROMPT.length).toString()
                    }
                    else -> "i397 wait | handoff | agentbrowse | incog | engineer [on|off]"
                }
            }
            // [Mines-38.9.8] smoke: 'i398 seedpicker' = a fake 「免登录 AI（Mines）」 provider with 10 models (incl. the two
            // same-named 「Mines-Test」 扣子 bots) pointed at an in-app server that accepts and never answers (quick test must
            // time out with a real reason); 'i398 picker [modelId]' = open a chat, its 选择模型 sheet and (optionally) the
            // 快速测试 of that model stacked over it
            "i398" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                when (sub) {
                    "seedpicker" -> {
                        val repo = app.providerRepository
                        val label = "免登录 AI（Mines）"
                        val port = I398Silent.start()
                        val old = repo.config.value.modelEntries.firstOrNull { it.baseModel.id == "coze:7696936574432591908" }
                        if (old != null) JSONObject().put("seeded", false).put("instance", old.providerInstanceId).put("port", port).toString() else {
                            val inst = com.openminis.app.debug.ProviderMutationMethods.instancesCreate(context, JSONObject()
                                .put("providerType", "openAI").put("label", label).put("customBaseURL", "http://127.0.0.1:$port/v1")
                                .put("seedBuiltInModels", false).put("apiKey", "sk-mines-local-0000000000000000"))
                            val iid = inst.optJSONObject("instance")?.optString("id").orEmpty()
                            val models = listOf(
                                "coze:7696936574432591908" to "Mines-Test", "coze:7696936160823738402" to "Mines-Test",
                                "web:doubao" to "豆包", "web:yuanbao" to "元宝", "web:kimi" to "Kimi", "web:deepseek" to "DeepSeek",
                                "web:tongyi" to "通义", "web:chatglm" to "智谱清言", "web:gemini" to "Gemini", "web:hark" to "Hark")
                            for ((id, name) in models) com.openminis.app.debug.ProviderMutationMethods.modelsAdd(context,
                                JSONObject().put("instanceId", iid).put("modelId", id).put("displayName", name))
                            JSONObject().put("seeded", true).put("instance", iid).put("port", port).put("models", models.size).toString()
                        }
                    }
                    "picker" -> {
                        I398Silent.start()
                        val repo = app.chatRepository
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { it.baseModel.id == "coze:7696936574432591908" && !it.isHidden }
                            ?: app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden }
                        val s = repo.createSession(e?.baseModel?.id ?: "smoke", title = "选择模型 smoke")
                        com.openminis.app.ui.components.QuickTestAuto.pickerArmed = true
                        com.openminis.app.ui.components.QuickTestAuto.pickerQuickModel = arg2.ifBlank { null }
                        val route = com.openminis.app.ui.navigation.Routes.chat(s.id)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(route) }
                        JSONObject().put("route", route).put("quick", arg2).put("silentAccepted", I398Silent.accepted.get()).toString()
                    }
                    "silent" -> JSONObject().put("port", I398Silent.port).put("accepted", I398Silent.accepted.get()).toString()
                    // [Mines-38.9.9] an OAuth provider (GitHub Copilot, never signed in) with one model → its quick test must say why
                    "seedoauth" -> {
                        val repo = app.providerRepository
                        val old = repo.config.value.instances.firstOrNull { it.providerType == com.openminis.app.data.model.ProviderType.githubCopilot }
                        val iid = old?.id ?: com.openminis.app.debug.ProviderMutationMethods.instancesCreate(context, JSONObject()
                            .put("providerType", "githubCopilot").put("credentialType", "oauth").put("label", "GitHub Copilot"))
                            .optJSONObject("instance")?.optString("id").orEmpty()
                        if (repo.config.value.modelEntries.none { it.providerInstanceId == iid && it.baseModel.id == "gpt-4.1" })
                            runCatching { com.openminis.app.debug.ProviderMutationMethods.modelsAdd(context,
                                JSONObject().put("instanceId", iid).put("modelId", "gpt-4.1").put("displayName", "Copilot GPT-4.1")) }
                        JSONObject().put("instance", iid).put("seeded", old == null).toString()
                    }
                    // 模型详情 of the first visible model (no quick test) — back arrow, bottom inset, backdrop
                    "modeldetail" -> {
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden && it.baseModel.id == "smoke-model" }
                            ?: app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden }
                        if (e == null) "no model" else {
                            val route = com.openminis.app.ui.navigation.Routes.modelEntryDetail(e.providerInstanceId, e.id)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(route) }
                            JSONObject().put("route", route).toString()
                        }
                    }
                    "backdrop" -> JSONObject().put("fogScene", com.openminis.app.ui.plus.FogClock.scene).put("skyOn", com.openminis.app.ui.plus.WeatherSky.enabled(context))
                        .put("skyScene", com.openminis.app.ui.plus.WeatherSky.scene(context).key).put("lastSky", com.openminis.app.ui.plus.WeatherSky.lastScene.key)
                        .put("hour", java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)).toString()
                    "zh" -> JSONObject().put("model", com.openminis.app.plus.MarketZh.lastModel).put("error", com.openminis.app.plus.MarketZh.lastError)
                        .put("tick", com.openminis.app.plus.MarketZh.tick.value).toString()
                    // a market install that fails the way the phone's did (publickey) → the failure card
                    "jobfail" -> {
                        com.openminis.app.plus.MinesJobs.run(context, "安装 DeepSeekBot",
                            "echo 'npm error command git --no-replace-objects ls-remote ssh://git@github.com/BotHarness/DeepSeekBot.git'; echo 'npm error git@github.com: Permission denied (publickey).'; echo 'npm error fatal: Could not read from remote repository.'; exit 128") { }
                        "started"
                    }
                    "classify" -> {
                        val k = com.openminis.app.plus.MarketGitCore.classify("dsh-pilot", arg2.ifBlank { "Desktop automation: hands and eyes on the whole Windows machine - screen_view, desktop_control" }, listOf("dsh-plugin"), "", "verified")
                        JSONObject().put("canTry", k.canTry).put("desktopOnly", k.desktopOnly).put("line", k.line).put("pill", k.pill ?: "").toString()
                    }
                    // [Mines-38.9.9 i26] last swipe-row gesture as the row saw it (start / end off / v / open)
                    "swipeprobe" -> com.openminis.app.ui.components.SwipeRevealCore.probe.ifBlank { "none" }
                    else -> "i398 seedpicker | picker [modelId] | silent | seedoauth | swipeprobe | modeldetail | backdrop | zh | jobfail | classify [desc]"
                }
            }
            // [Mines-38.9.10 i28] smoke: 'i3910 clash' state · 'clashon' / 'clashoff' (switch, as the page does) ·
            // 'proxies h:p[:u:p],h:p…' = these as 自己填代理 (first = primary) then a real check of each · 'heap' / 'gc' (soak) ·
            // 'trim' (MemGuard trim now) · 'oomtest' (throws OutOfMemoryError on a background worker: must be survived)
            "i3910" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val C = com.openminis.app.plus.ClashCore
                fun clashJson() = JSONObject().put("enabled", C.enabled(context)).put("state", C.state.value).put("serviceAlive", C.serviceAlive)
                    .put("running", C.running()).put("bundled", C.bundled(context)).put("lastRestart", C.lastRestart(context))
                    .put("failedRestarts", C.failedRestarts).put("lastExit", C.lastExit).put("pid", android.os.Process.myPid()).toString()
                when (sub) {
                    "clash" -> clashJson()
                    "clashon" -> { C.setEnabled(context, true); kotlinx.coroutines.delay(2500); clashJson() }
                    "clashoff" -> { C.setEnabled(context, false); kotlinx.coroutines.delay(800); clashJson() }
                    "proxies" -> {
                        val ups = arg2.split(',').mapNotNull { e ->
                            val p = e.trim().split(':'); if (p.size < 2) null
                            else com.openminis.app.plus.SplitProxy.Upstream("http", p[0], p[1].toIntOrNull() ?: 0, "", p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
                        }.filter { it.valid }
                        val first = ups.firstOrNull()
                        if (first != null) com.openminis.app.plus.SplitProxy.update(context) { it.copy(enabled = true, upstreamType = "http", upstreamHost = first.host,
                            upstreamPort = first.port, upstreamUser = first.user, upstreamPass = first.pass, backups = ups.drop(1)) }
                        val st = com.openminis.app.plus.SplitProxy.settings.value
                        val arr = JSONArray()
                        for (u in st.all) {
                            val t0 = System.currentTimeMillis(); val h = com.openminis.app.plus.SplitProxy.check(u)
                            arr.put(JSONObject().put("proxy", "${u.host}:${u.port}").put("ok", h.ok).put("ms", h.ms ?: -1).put("error", h.error ?: "")
                                .put("shown", if (h.ok) "响应 ${h.ms} 毫秒" else com.openminis.app.ui.plus.plainProxyReason(h.error)).put("took", System.currentTimeMillis() - t0))
                        }
                        JSONObject().put("listed", st.all.size).put("given", ups.size).put("checks", arr).toString()
                    }
                    "heap" -> com.openminis.app.plus.MemGuard.heapJson()
                    "gc" -> { System.gc(); Runtime.getRuntime().gc(); kotlinx.coroutines.delay(800); com.openminis.app.plus.MemGuard.heapJson() }
                    "trim" -> com.openminis.app.plus.MemGuard.trim("smoke")
                    "oomtest" -> {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { throw OutOfMemoryError("smoke oomtest (simulated)") }
                        kotlinx.coroutines.delay(1500)
                        JSONObject().put("alive", true).put("crashLog", (com.openminis.app.plus.MinesCrash.last(context) ?: "").take(400)).toString()
                    }
                    else -> "i3910 clash | clashon | clashoff | proxies h:p[:u:p],… | heap | gc | trim | oomtest"
                }
            }
            // [Mines-38.9.11 int] smoke: 'i3911 servers' (2 servers + workspace meta like the user's card) · 'i3911 expand <id|first|none>'
            // (open one card on 工作位置) · 'i3911 xpseed' (工作流记录 + 经验 incl. raw envelopes) · 'i3911 shareimgs' (3 images into a
            // new chat's composer, as a system share does) · 'i3911 gemini' / 'apppw' (谷歌账号 page / 应用专用密码向导 step 1) ·
            // 'i3911 apkpath' (this app's own APK path, for the file manager's APK info)
            "i3911" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                suspend fun onMain(block: () -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
                when (sub) {
                    "servers" -> {
                        val P = com.openminis.app.plus.PlusPrefs
                        val now = System.currentTimeMillis()
                        val a = com.openminis.app.plus.VpsServer(id = "smoke-alvps", name = "alvps", host = "10.0.2.2", port = 22, lastLatencyMs = 1238, lastCheckedAt = now, authorized = true)
                        val b = com.openminis.app.plus.VpsServer(id = "smoke-mengyun", name = "梦云", host = "10.0.2.2", port = 22, lastLatencyMs = 3567, lastCheckedAt = now, authorized = true)
                        P.update(context) { cur -> cur.copy(servers = cur.servers.filter { it.id != a.id && it.id != b.id } + listOf(a, b)) }
                        val W = com.openminis.app.plus.WorkspaceStore; W.init(context)
                        W.upsert(com.openminis.app.plus.WorkspaceCore.Meta(a.id, name = a.name, host = a.host, provider = "WispByte", limits = "2GB RAM 实验性 VPS",
                            cost = "已在服务器，无额外费用", geo = "香港 · AS45102 Aliyun Computing Co.LTD",
                            running = listOf("端口 18889 · tinyproxy", "端口 18800 · socat", "端口 443 · sshd", "端口 8443 · python3", "端口 2222 · socat", "端口 80 · nginx", "端口 3001 · new-api"),
                            runningAt = now, notes = listOf("8.217 上新增 memoh 反代 + new-api 供应商资产：memoh2openai(:8794) + new-api(:3001) systemd 托管。"), addedAt = now))
                        W.upsert(com.openminis.app.plus.WorkspaceCore.Meta(b.id, name = b.name, host = b.host, geo = "香港 · AS401696 FOJ IP TECHNOLOGY LIMITED",
                            running = listOf("端口 8788 · python3", "端口 8022 · websocat", "端口 8443 · python3", "端口 3000 · new-api", "端口 8795 · caddy", "容器 steel-proxy（caddy:2-alpine）"),
                            runningAt = now, addedAt = now))
                        W.setPrimary(b.id); W.touch()
                        JSONObject().put("servers", JSONArray(P.state.value.servers.map { it.id })).toString()
                    }
                    "expand" -> {
                        val ids = com.openminis.app.plus.PlusPrefs.state.value.servers.map { it.id }
                        val id = when (arg2) { "", "first" -> ids.firstOrNull(); "none" -> "none"; else -> arg2 }
                        onMain { com.openminis.app.ui.plus.WorkLocationUi.expandId = id }
                        JSONObject().put("expand", id ?: "").put("ids", JSONArray(ids)).toString()
                    }
                    "xpseed" -> {
                        val now = System.currentTimeMillis()
                        val W = com.openminis.app.plus.WorkflowLog
                        fun rec(i: Int, task: String, ok: Boolean, steps: List<com.openminis.app.plus.WorkflowLogCore.Step>, result: String) = W.record(context, com.openminis.app.plus.WorkflowLogCore.Run("smoke-run-$i", "smoke", task, steps,
                            result, if (ok) "done" else "failed", now - i * 3_600_000L - 120_000L, now - i * 3_600_000L))
                        rec(1, "帮我看看梦云上的 new-api 为什么 502，修好后验证一下", true, listOf(com.openminis.app.plus.WorkflowLogCore.Step("shell_execute", "minis-vps run 'systemctl status new-api'"),
                            com.openminis.app.plus.WorkflowLogCore.Step("file_edit", "改 /etc/caddy/Caddyfile 反代端口 3000"), com.openminis.app.plus.WorkflowLogCore.Step("shell_execute", "curl -sI https://api.example.com/v1/models")), "已修好：反代端口写错了，改成 3000 后返回 200。")
                        rec(2, "<scheduled_task name=\"每日简报\" id=\"st_8f3a\">整理今天值得看的新闻</scheduled_task>", true, listOf(com.openminis.app.plus.WorkflowLogCore.Step("web_fetch", "拉取 Hacker News 首页"),
                            com.openminis.app.plus.WorkflowLogCore.Step("model_use", "筛选打分")), "简报已生成：12 条里挑出 5 条。")
                        rec(3, "<scheduled_task name=\"每日简报\" id=\"st_8f3a\">整理今天值得看的新闻</scheduled_task>", true, listOf(com.openminis.app.plus.WorkflowLogCore.Step("web_fetch", "拉取 RSS")), "简报已生成：9 条里挑出 4 条。")
                        rec(4, "登录 grok.com 然后问它今天的天气 code=839201", false, listOf(com.openminis.app.plus.WorkflowLogCore.Step("browser_use", "打开 grok.com"),
                            com.openminis.app.plus.WorkflowLogCore.Step("browser_use", "点击登录", ok = false, error = "Cloudflare 验证没通过")), "没完成：网页要先过安全验证。")
                        rec(5, "把 alvps 上的日志打包下载到手机", true, listOf(com.openminis.app.plus.WorkflowLogCore.Step("shell_execute", "tar czf logs.tgz /var/log/nginx"), com.openminis.app.plus.WorkflowLogCore.Step("file_read", "下载 logs.tgz")), "已保存到 shared/下载/logs.tgz")
                        val E = com.openminis.app.plus.Experience
                        E.add(context, com.openminis.app.agent.ExperienceCore.Kind.HOWTO, "修 VPS 上 502 的反代", "1. systemctl status 看服务在不在\n2. 查 Caddy / nginx 反代端口\n3. 改完 curl -I 验证返回 200", "别直接重启整台机器")
                        E.add(context, com.openminis.app.agent.ExperienceCore.Kind.LESSON, "网页登录遇到 Cloudflare 验证", "先让用户手动点验证，再继续自动操作", "不要把验证页当成打不开反复刷新")
                        E.add(context, com.openminis.app.agent.ExperienceCore.Kind.HOWTO, "每天整理一份新闻简报", "1. 拉取订阅的来源\n2. 去重、打分\n3. 只留值得读的写成中文简报", "")
                        JSONObject().put("runs", W.list(context).size).put("xp", E.list.value.size).toString()
                    }
                    "shareimgs" -> {
                        val dir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context).apply { mkdirs() }
                        val cols = listOf(intArrayOf(0xFF7FA7C9.toInt(), 0xFFF3E3C8.toInt()), intArrayOf(0xFFE3A587.toInt(), 0xFF4E3D5C.toInt()), intArrayOf(0xFF9CC5A1.toInt(), 0xFF2F4F4F.toInt()))
                        val names = cols.mapIndexed { i, c ->
                            val bm = android.graphics.Bitmap.createBitmap(540, 1170, android.graphics.Bitmap.Config.ARGB_8888)
                            val cv = android.graphics.Canvas(bm)
                            cv.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 540f, 1170f, c[0], c[1], android.graphics.Shader.TileMode.CLAMP) })
                            cv.drawText("截图 ${i + 1}", 60f, 200f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; color = android.graphics.Color.WHITE })
                            val n = "smoke-img-${i + 1}.png"
                            java.io.File(dir, n).outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                            n
                        }
                        com.openminis.app.share.SharedShareStore.savePendingShare(context, com.openminis.app.share.PendingShare(
                            names.map { com.openminis.app.share.PendingShare.Item(com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT, it) }, System.currentTimeMillis()))
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { it.baseModel.id == "smoke-model" && !it.isHidden }
                            ?: app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden }
                        val s = app.chatRepository.createSession(e?.baseModel?.id ?: "smoke", title = "发图片 smoke")
                        val route = com.openminis.app.ui.navigation.Routes.chat(s.id)
                        onMain { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(route) }
                        JSONObject().put("route", route).put("images", JSONArray(names)).toString()
                    }
                    "gemini" -> { onMain { com.openminis.app.plus.GeminiAccounts.open(context) }; "ok" }
                    "apppw" -> { onMain { com.openminis.app.ui.plus.openAppPasswordGuide(context, "smoke@example.com") }; "ok" }
                    "apkpath" -> context.applicationInfo.sourceDir ?: ""
                    else -> "i3911 servers | expand <id|first|none> | xpseed | shareimgs | gemini | apppw | apkpath"
                }
            }
            // [Mines-38.9.11.3] smoke: 'i39113 latency <legacy|new> <proxy host:port|none> <baseUrl> [model]' (one streaming request
            // through the real OpenAIProvider: ms until the first chunk or the error; legacy = 38.9.11.2 networking + 60 s deadline) ·
            // 'route <host> [port]' · 'routeforget' · 'skydrift legacy|new' · 'a11yfake <n>' · 'skystate'
            // [Mines-38.9.12] smoke: 'i3912 kbseed' · 'kbstate' · 'autologin' (GitHub-via-Google page, no 台账 password → the
            // provider button is pressed, no password card) · 'autoclean' · 'call' (call screen, silent) · 'pool'
            "i3912" -> {
                val sub = rest.substringBefore(' ').trim()
                when (sub) {
                    "kbseed" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val sp = context.getSharedPreferences("mines_chat_knowledge", android.content.Context.MODE_PRIVATE).edit()
                        val now = System.currentTimeMillis()
                        val seed = listOf(
                            "服务器" to listOf("siyun-vps 部署：代理、订阅、流量上限", "远程浏览器 W1：Caddy WSS 网关", "梦云 SSH 修复记录", "阿里云 ECS 安全组规则", "服务器健康巡检"),
                            "AI 工具" to listOf("模型路由与分流", "Gemini Live 语音", "免登录 AI 渠道"),
                            "安全研究" to listOf("SRC 漏洞复测", "抓包环境"),
                            "资料与链接" to listOf("ARM 服务器跑 Chromium 实测", "stealth-browser-mcp 发布"),
                            "Mines 开发" to listOf("Mines 38.9.11.3.1 ANR 修复", "知识库星图设计", "账号台账设计"),
                            "交易" to listOf("行情复盘"),
                        )
                        var i = 0
                        seed.forEach { (topic, titles) -> titles.forEach { t ->
                            val sid = "smoke-kb-" + (i++)
                            sp.putString("e_$sid", JSONObject().put("sid", sid).put("title", t).put("topic", topic).put("summary", "$t · 已整理").put("facts", JSONArray(listOf("要点 $i")))
                                .put("decisions", JSONArray()).put("links", JSONArray()).put("files", JSONArray()).put("at", now - i * 3_600_000L).put("model", i % 5 != 0)
                                .put("todos", JSONArray()).put("kind", "chat").put("source", "").put("route", "sessions").toString())
                        } }
                        sp.commit()
                        com.openminis.app.plus.ChatKnowledge.load(context)
                        JSONObject().put("seeded", i).toString()
                    }
                    "kbclean" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val p = context.getSharedPreferences("mines_chat_knowledge", android.content.Context.MODE_PRIVATE)
                        val e = p.edit(); p.all.keys.filter { it.startsWith("e_smoke-kb-") }.forEach { e.remove(it) }; e.commit()
                        com.openminis.app.plus.ChatKnowledge.load(context); "cleaned"
                    }
                    "kbselect" -> { com.openminis.app.ui.plus.NexusHudState.request.value = rest.substringAfter(' ', "-1").trim().toIntOrNull() ?: -1; "ok" }
                    "kbstate" -> JSONObject().put("frames", com.openminis.app.ui.plus.NexusHudState.frames).put("selected", com.openminis.app.ui.plus.NexusHudState.selected).toString()
                    "autologin" -> {
                        val host = "login.autologin-smoke.test"; val url = "https://$host/login"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.openminis.app.plus.LoginMemory.record(context, host, "smoke.user@gmail.com", com.openminis.app.plus.LoginMemoryCore.Method.GOOGLE)
                        }
                        val html = "<html><head><meta name='viewport' content='width=device-width'></head><body><h3>Sign in to AutoLogin</h3>" +
                            "<input id='u' type='text' name='login' placeholder='Username or email address'><br><input id='p' type='password' name='password'><br><button>Sign in</button>" +
                            "<p>or</p><button id='g' onclick=\"window.__g=1;return false;\">Continue with Google</button></body></html>"
                        suspend fun <T> um(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
                        val wv = um { android.webkit.WebView(context.applicationContext).also { it.settings.javaScriptEnabled = true
                            it.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                            it.layout(0, 0, 1080, 1920); it.loadDataWithBaseURL(url, html, "text/html", "utf-8", null) } }
                        kotlinx.coroutines.delay(2500)
                        suspend fun ev(js: String): String? {
                            val d = kotlinx.coroutines.CompletableDeferred<String?>()
                            um { wv.evaluateJavascript(js) { r -> d.complete(r) } }
                            val raw = kotlinx.coroutines.withTimeoutOrNull(5000) { d.await() } ?: return null
                            return runCatching { org.json.JSONTokener(raw).nextValue()?.toString() }.getOrNull() ?: raw
                        }
                        val card0 = com.openminis.app.plus.LoginCard.pending.value != null
                        val note = kotlinx.coroutines.withTimeoutOrNull(20_000) { com.openminis.app.plus.AgentLogin.handle(context, url, "smoke-autologin-conv") { js -> ev(js) } }
                        val clicked = ev("(function(){return String(window.__g||0)})()")
                        val pwLen = ev("(function(){return String(document.getElementById('p').value.length)})()")
                        val card = com.openminis.app.plus.LoginCard.pending.value != null
                        if (card && !card0) com.openminis.app.plus.LoginCard.answer(null)
                        um { wv.destroy() }
                        JSONObject().put("note", note ?: "").put("clicked", clicked ?: "").put("pwLen", pwLen ?: "").put("cardShown", card && !card0)
                            .put("log", com.openminis.app.plus.AutoLogin.log.value.firstOrNull()?.let { com.openminis.app.plus.AutoLoginCore.logText(it) } ?: "").toString()
                    }
                    "autoclean" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.openminis.app.plus.LoginMemory.forget(context, "login.autologin-smoke.test"); "cleaned"
                    }
                    "call" -> "removed"   // [Mines-38.9.14.1] 来电 removed
                    "pool" -> JSONObject().put("reads", com.openminis.app.ui.plus.WorkPoolLive.reads).put("nodes", JSONArray(com.openminis.app.ui.plus.WorkPoolLive.nodes.values.map {
                        JSONObject().put("name", it.name).put("role", it.role.name).put("online", it.online).put("mem", it.memPct) })).toString()
                    // [Mines-38.9.12 b] 'mailcodes' (fake mailbox + 2 code mails) · 'codes' (验证码 tab readout) · 'mailoff' · 'attn' · 'reg'
                    "mailcodes" -> { com.openminis.app.plus.MailInbox.fakeCodes(); com.openminis.app.ui.plus.Codes3912.state() }
                    "codes" -> com.openminis.app.ui.plus.Codes3912.state()
                    "mailoff" -> { com.openminis.app.plus.MailInbox.fakeOff(context); "ok" }
                    "attn" -> com.openminis.app.plus.MinesCall.attentionDecision(context)
                    "reg" -> JSONObject().put("rows", com.openminis.app.ui.plus.Register3912.lastRows).put("temp", com.openminis.app.ui.plus.Register3912.lastTemp).toString()
                    else -> "i3912 kbseed | kbclean | kbstate | autologin | autoclean | call | pool | mailcodes | codes | mailoff | attn | reg"
                }
            }
            // [Mines-38.9.12.1] OOM hotfix smoke: 'i39121 seed' (1 long chat of ~300 messages, newest, + 4 more = the 5 paused
            // tasks) · 'slots' (the 5 hold agent slots, as 5 running sessions) · 'shots <n>' (n rounds of browser screenshots in 5
            // pools at once, desktop viewport ≈ 9 Mpx, every 3rd full page) · 'gate' (heap gate readout) · 'oomkill' (an
            // OutOfMemoryError on the MAIN thread in 1.5 s → the real crash path) · 'safe' (safe-start state) · 'clean'
            "i39121" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                val sp = context.getSharedPreferences("mines_smoke_39121", android.content.Context.MODE_PRIVATE)
                fun ids() = (sp.getString("ids", "") ?: "").split(',').filter { it.isNotBlank() }
                when (sub) {
                    "seed" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { it.baseModel.id == "smoke-model" && !it.isHidden }
                            ?: app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden }
                        val model = e?.baseModel?.id ?: "smoke"
                        val out = ArrayList<String>()
                        for (k in 4 downTo 1) out += app.chatRepository.createSession(model, title = "OOM 复现任务 ${k + 1}").id
                        val long = app.chatRepository.createSession(model, title = "OOM 复现长对话（300 条）")
                        val pad = "网页内容 ".repeat(120)
                        for (i in 0 until 150) {
                            app.chatRepository.appendMessage(long.id, "user", JSONArray().put(JSONObject().put("type", "text").put("value", "第 $i 步：继续看下一页 $pad")).toString())
                            val tu = "t$i"
                            app.chatRepository.appendMessage(long.id, "assistant", JSONArray()
                                .put(JSONObject().put("type", "text").put("value", "好的，截图看看。"))
                                .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", tu).put("name", "browser_use").put("input", "{\"action\":\"screenshot\",\"tab_id\":0}")))
                                .put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("toolUseId", tu).put("name", "browser_use").put("success", true).put("output", "Screenshot captured\n  Visible text: $pad")))
                                .toString())
                        }
                        out.add(0, long.id)
                        sp.edit().putString("ids", out.joinToString(",")).commit()
                        JSONObject().put("ids", JSONArray(out)).put("messages", app.chatRepository.messageCount(long.id)).toString()
                    }
                    "slots" -> {
                        val M = com.openminis.app.service.SessionConcurrencyManager
                        for (id in ids()) if (id !in M.runningSessions.value) kotlinx.coroutines.withTimeoutOrNull(3000) { M.acquireSlot(id) }
                        JSONObject().put("slots", M.diagSnapshot()).put("running", M.runningSessions.value.size).toString()
                    }
                    "shots" -> kotlinx.coroutines.coroutineScope {
                        val n = arg2.toIntOrNull() ?: 6
                        val heap0 = com.openminis.app.plus.MemGuard.heapJson()
                        val owners = ids().ifEmpty { List(5) { "smoke-shot-$it" } }.take(5)
                        val html = "document.body.innerHTML='<div style=\"font:28px sans-serif\">'+Array.from({length:400},(x,i)=>'<p style=\"background:hsl('+(i*37%360)+',70%,80%)\">第 '+i+' 行 截图压力测试 Mines OOM</p>').join('')+'</div>';'ok'"
                        val results = owners.mapIndexed { oi, owner -> async(kotlinx.coroutines.Dispatchers.Default) {
                            val pool = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.openminis.app.browser.BrowserTabPool(context).also { it.setSession("smoke39121-$oi") } }
                            val r = JSONObject(); var ok = 0; var err = 0; var maxW = 0; var maxH = 0; var lastErr = ""
                            try {
                                fun inp(j: String) = com.openminis.app.browser.BrowserActionInput.parse(j)!!
                                pool.execute(inp("{\"action\":\"set_user_agent\",\"user_agent\":\"desktop_chrome\"}"), singleTab = true)
                                pool.execute(inp("{\"action\":\"set_viewport\",\"viewport_width\":1400,\"viewport_height\":900}"), singleTab = true)
                                kotlinx.coroutines.withTimeoutOrNull(20_000) { pool.execute(inp("{\"action\":\"navigate\",\"url\":\"https://example.com/\"}"), singleTab = true) }
                                pool.execute(inp(JSONObject().put("action", "execute_js").put("script", html).toString()), singleTab = true)
                                for (k in 0 until n) {
                                    val full = k % 3 == 2
                                    val res = pool.execute(inp("{\"action\":\"screenshot\",\"tab_id\":0,\"full_page\":$full}"), singleTab = true)
                                    val m = Regex("Image: (\\d+)x(\\d+)").find(res.text)
                                    if (res.success && m != null) { ok++; maxW = maxOf(maxW, m.groupValues[1].toInt()); maxH = maxOf(maxH, m.groupValues[2].toInt()) }
                                    else { err++; lastErr = res.text.take(160) }
                                }
                            } catch (t: Throwable) { err++; lastErr = "${t.javaClass.simpleName}: ${t.message}".take(160) }
                            finally {
                                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pool.destroyAllTabs() } }
                                runCatching { com.openminis.app.browser.BrowserTabPoolRegistry.unregister(pool) }
                            }
                            r.put("ok", ok).put("err", err).put("maxW", maxW).put("maxH", maxH).put("lastErr", lastErr)
                        } }.map { it.await() }
                        JSONObject().put("pools", JSONArray(results)).put("heapBefore", JSONObject(heap0)).put("heapAfter", JSONObject(com.openminis.app.plus.MemGuard.heapJson()))
                            .put("gpuDisabled", com.openminis.app.browser.HeadlessRenderHost.disabled ?: "").toString()
                    }
                    "gate" -> {
                        val r = Runtime.getRuntime()
                        JSONObject().put("limit", com.openminis.app.plus.SafeStartCore.slotLimit(r.totalMemory() - r.freeMemory(), r.maxMemory(), 5,
                            com.openminis.app.service.SessionConcurrencyManager.runningSessions.value.size))
                            .put("gated", com.openminis.app.service.SessionConcurrencyManager.heapGated).put("heap", JSONObject(com.openminis.app.plus.MemGuard.heapJson())).toString()
                    }
                    "oomkill" -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ throw OutOfMemoryError("MinesSmokeOomSimulation: Failed to allocate a 35948179 byte allocation (simulated)") }, 1500)
                        JSONObject().put("armed", true).put("running", com.openminis.app.service.SessionConcurrencyManager.runningSessions.value.size).toString()
                    }
                    "safe" -> JSONObject(com.openminis.app.plus.SafeStart.stateJson()).put("ids", JSONArray(ids()))
                        .put("marker", java.io.File(context.filesDir, "mines-safe-start.json").let { if (it.exists()) it.readText().take(600) else "" }).toString()
                    "clean" -> {
                        val M = com.openminis.app.service.SessionConcurrencyManager
                        for (id in ids()) M.releaseSlot(id)
                        com.openminis.app.plus.SafeStart.dismiss(context)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { for (id in ids()) runCatching { app.chatRepository.deleteSession(id) } }
                        sp.edit().clear().commit(); "cleaned"
                    }
                    // ':acra' WebView holder (stale reporter process) · kill only the main process · WebView + proxy readout in main
                    "acrastart" -> {
                        context.getSharedPreferences("mines_acra_probe", android.content.Context.MODE_PRIVATE).edit().clear().commit()
                        context.startService(android.content.Intent(context, com.openminis.app.plus.AcraProbeService::class.java))
                        kotlinx.coroutines.delay(6000)
                        val am = context.getSystemService(android.app.ActivityManager::class.java)
                        val procs = am.runningAppProcesses.orEmpty().filter { it.processName.startsWith(context.packageName) }.map { "${it.processName}(${it.pid})" }
                        val pp = context.getSharedPreferences("mines_acra_probe", android.content.Context.MODE_MULTI_PROCESS)
                        JSONObject().put("procs", JSONArray(procs)).put("probePid", pp.getInt("pid", 0)).put("probeProc", pp.getString("proc", "")).put("err", pp.getString("err", "")).toString()
                    }
                    "killmain" -> { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1200); "killing ${android.os.Process.myPid()}" }
                    "webcheck" -> {
                        val am = context.getSystemService(android.app.ActivityManager::class.java)
                        val procs = am.runningAppProcesses.orEmpty().filter { it.processName.startsWith(context.packageName) }.map { "${it.processName}(${it.pid})" }
                        val d = kotlinx.coroutines.CompletableDeferred<String>()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            runCatching {
                                val wv = android.webkit.WebView(context.applicationContext); wv.settings.javaScriptEnabled = true
                                wv.webViewClient = object : android.webkit.WebViewClient() { override fun onPageFinished(v: android.webkit.WebView, u: String?) { v.evaluateJavascript("document.title") { t -> d.complete(t ?: ""); v.destroy() } } }
                                wv.loadDataWithBaseURL("https://webcheck.test/", "<title>webcheck-ok</title><p>x</p>", "text/html", "utf-8", null)
                            }.onFailure { d.complete("ERR ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        val t = kotlinx.coroutines.withTimeoutOrNull(15_000) { d.await() } ?: "timeout"
                        JSONObject().put("procs", JSONArray(procs)).put("title", t).put("pid", android.os.Process.myPid())
                            .put("othersAtStart", JSONArray(com.openminis.app.plus.ProcessGuard.othersAtStart))
                            .put("proxyError", com.openminis.app.plus.WebProxy.lastError).toString()
                    }
                    "callstate" -> com.openminis.app.ui.plus.CallState.json()
                    else -> "i39121 seed | slots | shots <n> | gate | oomkill | safe | clean | acrastart | killmain | webcheck | callstate"
                }
            }
            // [Mines-38.9.13 integ] smoke: 'i3913 chat <text>' = new chat on smoke-model, opened, <text> sent as the user;
            // 'i3913 say <text>' = send into that chat again; 'i3913 state' = task projects / handoffs / flags
            "i3913" -> {
                val sub = rest.substringBefore(' ').trim(); val text = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                val main = kotlinx.coroutines.Dispatchers.Main
                suspend fun sendTo(sid: String): String {
                    var vm: com.openminis.app.ui.chat.ChatViewModel? = null
                    repeat(40) { if (vm == null) { vm = kotlinx.coroutines.withContext(main) { com.openminis.app.ui.chat.ChatViewModelStore.existing(sid) }; if (vm == null) delay(250) } }
                    val v = vm ?: return JSONObject().put("sid", sid).put("err", "no view model").toString()
                    val out = kotlinx.coroutines.withContext(main) { v.submitPrompt(text) }
                    return JSONObject().put("sid", sid).put("out", out.toString()).toString()
                }
                when (sub) {
                    "chat" -> {
                        val e = app.providerRepository.config.value.modelEntries.firstOrNull { it.baseModel.id.contains("smoke-model") && !it.isHidden }
                            ?: app.providerRepository.config.value.modelEntries.firstOrNull { !it.isHidden }
                        val s = app.chatRepository.createSession(e?.baseModel?.id ?: "smoke", title = null)
                        i3913Sid = s.id
                        kotlinx.coroutines.withContext(main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(com.openminis.app.ui.navigation.Routes.chat(s.id)) }
                        sendTo(s.id)
                    }
                    "say" -> { val sid = i3913Sid; if (sid == null) "no chat" else sendTo(sid) }
                    "state" -> {
                        val tp = com.openminis.app.plus.TaskProjects; tp.init(context)
                        val pj = JSONArray(); tp.projects.value.forEach { p -> pj.put(JSONObject().put("name", p.name).put("site", p.site)
                            .put("accounts", p.accounts.size).put("attempts", JSONArray(p.attempts.map { it.sid }))) }
                        val hs = JSONArray(); com.openminis.app.plus.HandoffBroker.list.value.forEach { h -> hs.put(JSONObject().put("id", h.id)
                            .put("sid", h.sessionId).put("kind", h.kind.name).put("state", h.state.name).put("label", h.taskLabel).put("host", h.host)) }
                        JSONObject().put("sid", i3913Sid ?: "").put("taskName", com.openminis.app.plus.TaskName.of(i3913Sid) ?: "")
                            .put("proposal", tp.proposal.value?.let { JSONObject().put("site", it.site).put("verb", it.verb).put("sid", it.sid) } ?: JSONObject.NULL)
                            .put("projects", pj).put("handoffs", hs)
                            .put("flags", JSONObject().put("vps_page_v2", com.openminis.app.plus.VpsPlan.flagOn(context)).put("task_projects", tp.on.value)
                                .put("memory_v2", com.openminis.app.plus.MemoryV2.isOn(context)).put("handoff_v2", com.openminis.app.plus.HandoffBroker.resumeOn())
                                .put("mines_browser", com.openminis.app.plus.MinesBrowser.on())).toString()
                    }
                    // after 'i3911 servers': alvps (10.0.2.2) = the hub, 梦云 marked for 内网穿透 — emulator-local, no real server touched
                    "tunnelseed" -> {
                        val T = com.openminis.app.plus.Tunnel; T.init(context); T.setOn(context, true)
                        val sv = com.openminis.app.plus.PlusPrefs.state.value.servers
                        sv.firstOrNull { it.id == "smoke-alvps" }?.let { T.setHub(context, it.id) }
                        sv.firstOrNull { it.id == "smoke-mengyun" }?.let { T.mark(context, it, true) }
                        JSONObject().put("hub", T.hub()?.id ?: "").put("state", com.openminis.app.plus.TunnelState.of("梦云") ?: JSONObject.NULL).toString()
                    }
                    // [38.9.13] machine monitor: 梦云 = the runner's own sshd (10.0.2.2:22, emulator-local), 30 days left + 免费 registered,
                    // its card opens expanded; returns the app's public key for the harness to authorise on the runner
                    "sshseed" -> {
                        val P = com.openminis.app.plus.PlusPrefs; val W = com.openminis.app.plus.WorkspaceStore; W.init(context)
                        val now = System.currentTimeMillis()
                        if (P.state.value.servers.none { it.id == "smoke-mengyun" }) P.update(context) { cur ->
                            cur.copy(servers = cur.servers + com.openminis.app.plus.VpsServer(id = "smoke-mengyun", name = "梦云", host = "10.0.2.2", port = 22, lastLatencyMs = 40, lastCheckedAt = now, authorized = true)) }
                        val old = W.meta("smoke-mengyun") ?: com.openminis.app.plus.WorkspaceCore.Meta("smoke-mengyun", name = "梦云", host = "10.0.2.2", addedAt = now)
                        W.upsert(old.copy(expiresAt = now + 30L * 86_400_000L, cost = "免费")); W.touch()
                        com.openminis.app.plus.VpsPlan.openMonitor = com.openminis.app.plus.VpsPlanCore.MENGYUN
                        JSONObject().put("pub", com.openminis.app.plus.VpsSsh.ensureKey(context).second).toString()
                    }
                    else -> "i3913 chat <text> | say <text> | state | tunnelseed | sshseed"
                }
            }
            // [Mines-38.9.14 chat_home] smoke: 'i3914 main' = open 聊天 (main conversation); 'i3914 say <text>' = type <text> into it
            // through the normal send (router decides); 'i3914 state' = flag, main sid, tasks, last decision, main rows (role + tag)
            // [Mines-38.9.15.1] smoke: 'i39151 needseed' = the mock's 任务 page (two 需要你 + one done); 'i39151 state' = tasks count,
            // the 应用 「模型」 line, the LoginCard / AccountPick / handoff pop-up state, the list-pane flags
            "i39151" -> {
                val sub = rest.substringBefore(' ').trim()
                val H = com.openminis.app.plus.ChatHome; H.init(context)
                val CH = com.openminis.app.plus.ChatHomeCore
                when (sub) {
                    "needseed" -> {
                        if (H.mainSid.isBlank()) H.ensureMain(context)
                        val now = System.currentTimeMillis()
                        H.seedForTest(CH.AutoTask("need1", "", "阿里云 QwenPaw 登录", "帮我登录阿里云 QwenPaw", CH.St.WAITING, 3, 6,
                            "短信验证码已发到你手机 138****8658，收到发我", now - 240_000, now - 180_000, "waiting"))
                        H.seedForTest(CH.AutoTask("need2", "", "Railway 注册", "帮我注册 railway.com", CH.St.WAITING, 2, 5,
                            "验证码页要你点一下「我不是机器人」", now - 1_500_000, now - 1_400_000, "waiting"))
                        H.seedForTest(CH.AutoTask("done1", "", "siyun 内存检查", "看看 siyun 内存", CH.St.DONE, 4, 4,
                            "完成 · 4/4 步", now - 4_000_000, now - 3_900_000, "done"))
                        JSONObject().put("seeded", 3).toString()
                    }
                    "state" -> JSONObject()
                        .put("tasks", H.tasks.value.size)
                        .put("ids", JSONArray(H.tasks.value.map { it.id }))
                        .put("waiting", H.tasks.value.count { !it.archived && it.st == CH.St.WAITING })
                        .put("modelLine", com.openminis.app.ui.plus.ChatHomeNav.modelLine.value)
                        .put("loginCard", com.openminis.app.plus.LoginCard.pending.value != null)
                        .put("loginCardOpened", com.openminis.app.plus.LoginCard.opened.value != 0L)
                        .put("accountPick", com.openminis.app.plus.AccountPick.pending.value != null)
                        .put("allShowing", com.openminis.app.ui.plus.ChatHomeNav.allShowing.value)
                        .put("last", H.stateJson().let { runCatching { JSONObject(it).optString("last") }.getOrDefault("") }).toString()
                    else -> "i39151 needseed | state"
                }
            }
            "i3915" -> com.openminis.app.persona.PersonaSmoke.handle(context, rest)   // [Mines-38.9.15 伪人核心] smoke hooks
            "ira" -> com.openminis.app.plus.RegisterAutoSmoke.handle(context, rest)   // [Mines-38.9.15 register_auto] 一键注册 smoke hooks
            "i3914" -> {
                fun rowsOf(a: com.openminis.app.MinisApp, sid: String): JSONArray = JSONArray().apply {
                    kotlinx.coroutines.runBlocking { a.chatRepository.dao.loadMessages(sid) }.takeLast(12).forEach { m ->
                        val t = runCatching { JSONArray(m.partsJson).optJSONObject(0)?.optString("value").orEmpty() }.getOrDefault("")
                        put(JSONObject().put("role", m.role).put("text", t.take(400)))
                    }
                }
                val sub = rest.substringBefore(' ').trim(); val text = rest.substringAfter(' ', "").trim()
                val app = context.applicationContext as com.openminis.app.MinisApp
                val main = kotlinx.coroutines.Dispatchers.Main
                val H = com.openminis.app.plus.ChatHome; H.init(context)
                when (sub) {
                    "main" -> {
                        val sid = H.ensureMain(context) ?: return@runBlocking "no model"
                        kotlinx.coroutines.withContext(main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(com.openminis.app.ui.navigation.Routes.chat(sid)) }
                        JSONObject().put("sid", sid).toString()
                    }
                    "say" -> {
                        val sid = H.mainSid
                        var vm: com.openminis.app.ui.chat.ChatViewModel? = null
                        repeat(40) { if (vm == null) { vm = kotlinx.coroutines.withContext(main) { com.openminis.app.ui.chat.ChatViewModelStore.existing(sid) }; if (vm == null) delay(250) } }
                        val v = vm ?: return@runBlocking JSONObject().put("sid", sid).put("err", "no view model").toString()
                        kotlinx.coroutines.withContext(main) { v.sendMessage(text) }
                        JSONObject().put("sid", sid).put("sent", text).toString()
                    }
                    "state" -> {
                        val rows = JSONArray()
                        if (H.mainSid.isNotBlank()) app.chatRepository.dao.loadMessages(H.mainSid).takeLast(30).forEach { m ->
                            val t = runCatching { JSONArray(m.partsJson).optJSONObject(0)?.optString("value").orEmpty() }.getOrDefault("")
                            rows.put(JSONObject().put("role", m.role).put("tag", com.openminis.app.plus.ChatHomeCore.parseTag(t)?.let { "${it.kind.name}:${it.taskId}" } ?: "")
                                .put("text", t.take(80)))
                        }
                        JSONObject(H.stateJson()).put("rows", rows).put("stalls", com.openminis.app.plus.StallWatch.json()).put("netdrops", com.openminis.app.plus.NetDrop.json()).toString()
                    }
                    // [38.9.14.1] one card per task: a task with 出错 → 等你 → 出错 → 完成 notices (like the user's screenshot)
                    "foldseed" -> {
                        if (H.mainSid.isBlank()) H.ensureMain(context)
                        val now = System.currentTimeMillis()
                        val g = "账号关联测试：那么账号关联这里有没有办法解决？"
                        val t = com.openminis.app.plus.ChatHomeCore.AutoTask("fold3914", "", "账号关联测试", g, com.openminis.app.plus.ChatHomeCore.St.DONE,
                            6, 6, "完成 · 6/6 步", now - 60_000, now, "done")
                        H.seedForTest(t)
                        H.appendToMain(listOf("user" to g, "assistant" to com.openminis.app.plus.ChatHomeCore.tagText(t.id, com.openminis.app.plus.ChatHomeCore.Kind.CREATED, com.openminis.app.plus.ChatHomeCore.createdLine(t.name))))
                        for ((k, b) in listOf(com.openminis.app.plus.ChatHomeCore.Kind.ERROR to "没完成：Software caused connection abort", com.openminis.app.plus.ChatHomeCore.Kind.ASK to "Software caused connection abort",
                                com.openminis.app.plus.ChatHomeCore.Kind.ERROR to "没完成：Software caused connection abort", com.openminis.app.plus.ChatHomeCore.Kind.DONE to "完成 · 6/6 步"))
                            H.appendToMain(listOf("assistant" to com.openminis.app.plus.ChatHomeCore.tagText(t.id, k, b)))
                        // [38.9.14.3] its record, like a real run would have written it
                        fun E(a: Long, st: com.openminis.app.plus.ChatHomeCore.St, x: String) = com.openminis.app.plus.TaskRecordCore.Ev(a, st, x)
                        val Q = com.openminis.app.plus.ChatHomeCore.St.QUEUED; val ER = com.openminis.app.plus.ChatHomeCore.St.ERROR
                        val W = com.openminis.app.plus.ChatHomeCore.St.WAITING; val RN = com.openminis.app.plus.ChatHomeCore.St.RUNNING; val DN = com.openminis.app.plus.ChatHomeCore.St.DONE
                        H.seedLogForTest(t.id, listOf(E(now - 60_000, Q, "建立任务：账号关联测试"), E(now - 50_000, ER, "出错：Software caused connection abort"),
                            E(now - 40_000, W, "等你：Software caused connection abort"), E(now - 30_000, ER, "出错：Software caused connection abort"),
                            E(now - 20_000, RN, "重新开始"), E(now - 1_000, DN, "完成：完成 · 6/6 步")))
                        JSONObject().put("seeded", t.id).toString()
                    }
                    // [38.9.15 task_archive] the user's 任务 page of 2026-10-26 10:13: five 等你 tasks (two of them sign-ups) + one done,
                    // for 归档 by long-press / swipe / the task chat's ⋮ and 撤销
                    "archseed" -> {
                        if (H.mainSid.isBlank()) H.ensureMain(context)
                        val now = System.currentTimeMillis()
                        val W = com.openminis.app.plus.ChatHomeCore.St.WAITING
                        listOf(Triple("arch1", "那么账号关联这里有没有办法解决…", "等你回复"), Triple("arch2", "Moli还是太强了，路很狠：不需", "等你回复"),
                            Triple("arch3", "做一个反代，反代hark你试下", "模型没响应（40 秒没有新输出）"), Triple("arch4", "Qq 注册", "需要手机号收短信，卡在 movil-registro"),
                            Triple("arch5", "Doroai 注册", "等你回复")).forEachIndexed { i, (id, n, l) ->
                            H.seedForTest(com.openminis.app.plus.ChatHomeCore.AutoTask(id, "", n, n, W, i % 3, 5, l, now - 600_000L * (i + 1), now - 60_000L * (i + 1), "waiting"))
                        }
                        JSONObject().put("seeded", 5).toString()
                    }
                    // [38.9.14.2] trip every crash / hang breaker the way the realme did (hang count 3, crash force-home window,
                    // a stall log + several launches without a clean exit) and make the NEXT cold start stall 3.5 s on the
                    // main thread; 'i3914 breakers' reports what they say now
                    "trip" -> {
                        context.getSharedPreferences("hang_detector_prefs", android.content.Context.MODE_PRIVATE).edit()
                            .putInt("hang_count", 3).putLong("last_hang_at_ms", System.currentTimeMillis()).commit()
                        context.getSharedPreferences("crash_freq_prefs", android.content.Context.MODE_PRIVATE).edit()
                            .putLong("force_home_until", System.currentTimeMillis() + 3_600_000L).commit()
                        val logs = java.io.File(context.filesDir, "logs").apply { mkdirs() }
                        java.io.File(logs, "stall-smoke-trip.log").writeText("[smoke] simulated 3.5 s main-thread stall\n")
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
                        val bf = java.io.File(logs, "launch-beacon.log")
                        repeat(6) { i -> bf.appendText("[${fmt.format(java.util.Date(System.currentTimeMillis() - 60_000L + i * 1000L))}] launch pid=1\n") }
                        context.getSharedPreferences("mines_smoke", android.content.Context.MODE_PRIVATE).edit().putBoolean("slow_start", true).commit()
                        JSONObject().put("tripped", true).toString()
                    }
                    "breakers" -> JSONObject()
                        .put("hang", com.openminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(context))
                        .put("crash", com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(context))
                        .put("beacon", com.openminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch())
                        .put("safeLaunch", H.safeLaunch).toString()
                    // [38.9.14.3] 'i3914 talk <taskId>' = the task's own conversation (made once) → its rows; 'i3914 open <sid>' = show a
                    // conversation (e.g. a task's run); 'i3914 rows <sid>' = a conversation's rows
                    "talk" -> {
                        val sid = H.openTalk(context, text) ?: return@runBlocking JSONObject().put("err", "no task $text").toString()
                        JSONObject().put("sid", sid).put("rows", rowsOf(app, sid)).toString()
                    }
                    "open" -> {
                        kotlinx.coroutines.withContext(main) { com.openminis.app.ui.plus.PlusNav.navigate?.invoke(com.openminis.app.ui.navigation.Routes.chat(text)) }
                        JSONObject().put("sid", text).toString()
                    }
                    "rows" -> JSONObject().put("sid", text).put("rows", rowsOf(app, text)).put("barIdle", com.openminis.app.ui.chat.MinesBarProbe.idle).toString()
                    else -> "i3914 main | say <text> | state | foldseed | archseed | trip | breakers | talk <taskId> | open <sid> | rows <sid>"
                }
            }
            "i39113" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                val R = com.openminis.app.plus.ProviderRoutes
                when (sub) {
                    "latency" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val a = arg2.split(' ').filter { it.isNotBlank() }
                        val legacy = a.getOrNull(0) == "legacy"
                        val px = a.getOrNull(1)?.takeIf { it != "none" }?.let { hp ->
                            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(hp.substringBefore(':'), hp.substringAfter(':').toInt())) }
                        val base = a.getOrNull(2) ?: "https://api.deepseek.com/v1"
                        val modelId = a.getOrNull(3) ?: "deepseek-chat"
                        R.legacy = legacy; R.forcedProxy = px; R.forget()
                        val prov = com.openminis.app.provider.openai.OpenAIProvider(apiKey = "sk-mines-smoke-latency", model = com.openminis.app.data.model.LLMModel(modelId, modelId, "openai"), basePath = base)
                        val deadline = if (legacy) com.openminis.app.ui.chat.MINES_FIRST_CHUNK_MS else com.openminis.app.plus.ProviderRouteCore.silentBudgetMs(false)
                        val t0 = System.currentTimeMillis()
                        val phases = JSONArray()
                        val watch = kotlinx.coroutines.GlobalScope.launch { var last = ""; while (true) { val st = R.status.value; val line = "${st.phase}:${st.route ?: ""}"; if (line != last) { phases.put("${System.currentTimeMillis() - t0}ms $line"); last = line }; kotlinx.coroutines.delay(100) } }
                        val outcome = try {
                            kotlinx.coroutines.withTimeout(deadline) {
                                prov.streamMessage(listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = "hi")), null, 16)
                                    .collect { throw RuntimeException("first-chunk") }
                                "completed"
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { "deadline ${deadline / 1000}s (chat gives up)" }
                        catch (e: Throwable) {
                            val m = (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
                            if (m.contains("first-chunk")) "first chunk" else "error: " + m.take(160)
                        }
                        val ms = System.currentTimeMillis() - t0
                        watch.cancel(); R.legacy = false
                        val o = JSONObject().put("mode", if (legacy) "legacy" else "new").put("proxy", px?.toString() ?: "none").put("base", base)
                            .put("ms", ms).put("outcome", outcome).put("phases", phases).put("routes", R.summary()).put("probe", R.lastProbe)
                        R.forcedProxy = null; R.idle()
                        o.toString()
                    }
                    "route" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val host = arg2.substringBefore(' '); val port = arg2.substringAfter(' ', "443").toIntOrNull() ?: 443
                        val t0 = System.currentTimeMillis(); val plan = R.plan(host, port)
                        JSONObject().put("plan", JSONArray(plan.map { it.name })).put("ms", System.currentTimeMillis() - t0).put("probe", R.lastProbe)
                            .put("domestic", com.openminis.app.plus.ProviderRouteCore.isDomestic(host)).put("summary", R.summary()).toString()
                    }
                    "routeforget" -> { R.forget(); R.forcedProxy = null; R.legacy = false; "forgot" }
                    "skydrift" -> {
                        val legacy = arg2 == "legacy"
                        com.openminis.app.ui.plus.SkyFreeze.legacyFreeze = legacy
                        com.openminis.app.ui.plus.WeatherSky.legacyDrift = legacy
                        com.openminis.app.ui.plus.WeatherSky.bump()
                        "drift=" + (if (legacy) "legacy(38.9.11.2)" else "new")
                    }
                    "a11yfake" -> { com.openminis.app.ui.plus.SkyFreeze.fakeOtherServices = arg2.toIntOrNull() ?: -1; "fake=" + com.openminis.app.ui.plus.SkyFreeze.fakeOtherServices }
                    "skystate" -> {
                        val F = com.openminis.app.ui.plus.SkyFreeze; val W = com.openminis.app.ui.plus.WeatherSky
                        JSONObject().put("frozen", F.frozen()).put("reason38_9_11_2", F.lastReason).put("legacyFreeze", F.legacyFreeze)
                            .put("legacyDrift", W.legacyDrift).put("signals", F.signals.toString()).put("shot", W.lastShot).put("drift", W.lastDrift)
                            .put("keyRenders", W.keyRenders).put("keyLost", W.keyLost).put("frames", W.frames).put("level", W.level).put("mode", W.lastMode).put("wind", W.windRateLabel()).toString()
                    }
                    // [38.9.11.3 c] 测试全部密钥: a provider on the runner's fake endpoint with 3 keys, #2 answers 401
                    "keyseed", "keycheck", "keyclean" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val app = context.applicationContext as com.openminis.app.MinisApp
                        val repo = app.providerRepository
                        val base = arg2.ifBlank { "http://10.0.2.2:8088/badkey" }
                        fun mine() = repo.config.value.instances.filter { it.label == "密钥测试" }
                        if (sub == "keyclean") { mine().forEach { repo.removeInstance(it.id) }; listOf("sk-good-1", "sk-bad-2", "sk-good-3").forEach { com.openminis.app.plus.BadKeys.clear(it) } }
                        if (sub == "keyseed" && mine().isEmpty()) {
                            val inst = com.openminis.app.data.model.ProviderInstance(id = java.util.UUID.randomUUID().toString(), label = "密钥测试",
                                providerType = com.openminis.app.data.model.ProviderType.openAI, credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                                customBaseURL = base, isEnabled = true)
                            repo.addInstance(inst); repo.saveApiKey(inst.id, "sk-good-1"); repo.setKeyPool(inst.id, listOf("sk-good-1", "sk-bad-2", "sk-good-3"))
                        }
                        val o = JSONObject().put("count", mine().size)
                        if (sub == "keycheck") {
                            val rows = com.openminis.app.plus.KeyCheck.runAll(repo).filter { it.label == "密钥测试" }
                            o.put("summary", JSONArray(com.openminis.app.plus.KeyCheckCore.summary(rows)))
                            o.put("bad2", com.openminis.app.plus.BadKeys.isBad("sk-bad-2")).put("good1", com.openminis.app.plus.BadKeys.isBad("sk-good-1"))
                            mine().firstOrNull()?.let { i -> o.put("picks", JSONArray((0 until 6).map { repo.usableApiKey(i)?.takeLast(6) })) }
                            o.put("zh401", com.openminis.app.plus.ProviderRouteCore.zhError("Invalid API key: sk-bad", "密钥测试", switched = true))
                            o.put("zhEos", com.openminis.app.plus.ProviderRouteCore.zhError("unexpected end of stream on https://api.lixil.top/...", "AWS中转"))
                        }
                        o.toString()
                    }
                    // [38.9.11.3 c] 台账 auto-fill: two saved accounts for a fake sign-in page; the conversation names the 2nd →
                    // AgentLogin fills it without the card, shows the chip; returns what the page fields hold (password only as length)
                    "ledger" -> {
                        val host = "login.ledger-smoke.test"; val url = "https://$host/signin"; val sid = "smoke-ledger-conv"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.openminis.app.plus.SavedLogins.put(context, com.openminis.app.plus.SavedLogins.Entry("台账测试", "https://ledger-smoke.test/", "main@ledger-smoke.test", "pw-main-111"))
                            com.openminis.app.plus.SavedLogins.put(context, com.openminis.app.plus.SavedLogins.Entry("台账测试", "https://ledger-smoke.test/", "test@ledger-smoke.test", "pw-test-22222"))
                            com.openminis.app.plus.Ledger.setNote(context, host, "test@ledger-smoke.test", "测试")
                        }
                        com.openminis.app.plus.Ledger.noteText(sid, arg2.ifBlank { "用测试号登录一下看看" })
                        val html = "<html><head><meta name='viewport' content='width=device-width'></head><body><h3>登录</h3>" +
                            "<input id='u' type='email' placeholder='邮箱'><br><input id='p' type='password' placeholder='密码'><br><button>登录</button></body></html>"
                        suspend fun <T> um(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
                        val wv = um { android.webkit.WebView(context.applicationContext).also { it.settings.javaScriptEnabled = true
                            it.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                            it.layout(0, 0, 1080, 1920); it.loadDataWithBaseURL(url, html, "text/html", "utf-8", null) } }
                        kotlinx.coroutines.delay(2500)
                        suspend fun ev(js: String): String? {
                            val d = kotlinx.coroutines.CompletableDeferred<String?>()
                            um { wv.evaluateJavascript(js) { r -> d.complete(r) } }
                            val raw = kotlinx.coroutines.withTimeoutOrNull(5000) { d.await() } ?: return null
                            return runCatching { org.json.JSONTokener(raw).nextValue()?.toString() }.getOrNull() ?: raw
                        }
                        val card0 = com.openminis.app.plus.LoginCard.pending.value != null
                        val note = com.openminis.app.plus.AgentLogin.handle(context, url, sid) { js -> ev(js) }
                        val fields = ev("(function(){return JSON.stringify({u:document.getElementById('u').value,pl:document.getElementById('p').value.length})})()")
                        val card = com.openminis.app.plus.LoginCard.pending.value != null
                        um { wv.destroy() }
                        JSONObject().put("note", note ?: "").put("fields", fields ?: "").put("cardShown", card && !card0)
                            .put("chip", com.openminis.app.plus.Ledger.chip.value?.let { com.openminis.app.plus.LedgerCore.chip(com.openminis.app.plus.LedgerCore.Cand("", it.host, it.account)) } ?: "")
                            .put("agent", com.openminis.app.plus.Ledger.agentLines(context, host, sid, null)).toString()
                    }
                    "ledgerclean" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val keep = com.openminis.app.plus.SavedLogins.all(context).filterNot { it.host.endsWith("ledger-smoke.test") }
                        com.openminis.app.plus.SavedLogins.clear(context); keep.forEach { com.openminis.app.plus.SavedLogins.put(context, it) }
                        com.openminis.app.plus.Ledger.clearChip(null); "cleaned"
                    }
                    // [38.9.11.3 c] crash card: a fake crash report with secrets in it → card on screen; returns the copied text's redaction
                    "crashdemo" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val d = java.io.File(context.filesDir, "logs").also { it.mkdirs() }
                        val f = java.io.File(d, "crash-2099-01-01_00-00-00.log")
                        f.writeText("=== Minis Java/Kotlin Crash ===\nReason: ANR\nAuthorization: Bearer sk-smokesecret0123456789abcd\npassword=hunter2 phone 13690688658\n\tat com.openminis.app.X.y(X.kt:1)\n")
                        context.getSharedPreferences("mines_crash_card", android.content.Context.MODE_PRIVATE).edit().putLong("seen", 1L).commit()
                        com.openminis.app.crash.CrashCard.check(context)
                        val off = com.openminis.app.crash.CrashCard.offer.value
                        val t = off?.let { com.openminis.app.crash.CrashCard.text(it) }.orEmpty()
                        f.delete()
                        JSONObject().put("card", off?.title ?: "").put("leak", listOf("sk-smokesecret", "hunter2", "13690688658").filter { it in t })
                            .put("kept", t.contains("X.kt:1")).toString()
                    }
                    // [38.9.11.3 f] smoke only: a real 每日简报 run on three fake feeds (403 / cut connection / ok) → its Chinese note lines,
                    // plus ErrZhCore lines for a TTS model and the chat-capable filter; the user's own sources are restored afterwards
                    "errzh" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val H = com.openminis.app.plus.Horizon
                        val base = arg2.ifBlank { "http://10.0.2.2:8088" }
                        val keep = H.sources(context)
                        H.setSources(context, listOf(
                            com.openminis.app.plus.HorizonCore.Source("smk403", com.openminis.app.plus.HorizonCore.Kind.RSS, "$base/rss403", "Reddit 测试源", "测试"),
                            com.openminis.app.plus.HorizonCore.Source("smkeof", com.openminis.app.plus.HorizonCore.Kind.RSS, "$base/rsseof", "Hacker News 测试源", "测试"),
                            com.openminis.app.plus.HorizonCore.Source("smkok", com.openminis.app.plus.HorizonCore.Kind.RSS, "$base/rssok", "正常测试源", "测试")))
                        val b = runCatching { kotlinx.coroutines.withTimeoutOrNull(120_000) { H.run(context) } }.getOrNull()
                        H.setSources(context, keep)
                        val o = JSONObject().put("notes", org.json.JSONArray(b?.notes ?: emptyList<String>())).put("lastError", H.lastError)
                            .put("items", b?.items?.size ?: -1).put("details", org.json.JSONArray(H.details.takeLast(6)))
                            .put("tts", com.openminis.app.plus.ErrZhCore.model("tts-1", "HTTP 400: This model cannot be used with chat completions"))
                            .put("legacy", com.openminis.app.plus.ErrZhCore.legacyNote("r/LocalLLaMA：HTTP 403"))
                            .put("chatCapable", JSONObject().apply { listOf("tts-1", "text-embedding-3-small", "dall-e-3", "gpt-4o-mini", "deepseek-chat", "whisper-1").forEach { put(it, com.openminis.app.plus.ErrZhCore.chatCapable(it)) } })
                        o.toString()
                    }
                    // [38.9.11.3 g] 远程诊断 smoke: 'diag code <mdiag1:…|none>' · 'diag on|off' · 'diag state' · 'diag shot allow|deny' · 'diag stall <ms>'
                    "diag" -> {
                        val D = com.openminis.app.plus.Diag
                        val a = arg2.split(' ', limit = 2); val v = a.getOrNull(1)?.trim().orEmpty()
                        when (a.getOrNull(0)) {
                            "code" -> JSONObject().put("ok", D.setCode(context, v.takeIf { it != "none" })).toString()
                            "on" -> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { D.setOn(context, true) }; "on" }
                            "off" -> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { D.setOn(context, false) }; "off" }
                            "revert" -> { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { D.revertConfig(v) }; "reverted $v" }
                            "shot" -> { val id = D.shotAsk.value ?: ""; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (id.isNotEmpty()) D.answerShot(id, v == "allow") }; JSONObject().put("id", id).toString() }
                            "stall" -> { val ms = v.toLongOrNull()?.coerceIn(50L, 3000L) ?: 400L
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Thread.sleep(ms) }; "stalled $ms" }
                            else -> JSONObject().put("on", D.on.value).put("status", D.status.value).put("connected", D.isConnected()).put("sid", D.sessionId())
                                .put("notice", D.notice.value ?: "").put("shotAsk", D.shotAsk.value ?: "")
                                .put("changes", JSONArray(D.changes.value.map { "${it.key}=${it.to}" })).put("navAnimOff", D.navAnimOff)
                                .put("fpsCap", com.openminis.app.ui.plus.WeatherSky.fpsCap).put("skyMode", com.openminis.app.ui.plus.WeatherSky.mode(context).key).toString()
                        }
                    }
                    else -> "i39113 latency <legacy|new> <host:port|none> <baseUrl> [model] | route <host> [port] | routeforget | skydrift legacy|new | a11yfake <n> | skystate | keyseed|keycheck|keyclean | ledger [text] | ledgerclean | crashdemo | errzh [base] | diag code|on|off|state|shot|stall"
                }
            }
            // [Mines-38.9.11.2] smoke: 'i39112 incog <url>' (open a 无痕 session) · 'incogstate' · 'incogforce process|auto' ·
            // 'normal <url>' (home browser) · 'skylegacy on|off' (38.9.11.1 sky behaviour for before / after) · 'skylevel <n>' ·
            // 'atomic' (AtomicStore round trip on the device: 3 writes, main corrupted → bak1, all corrupted → notice)
            "i39112" -> {
                val sub = rest.substringBefore(' ').trim(); val arg2 = rest.substringAfter(' ', "").trim()
                suspend fun <T> onMain(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
                val I = com.openminis.app.browser.Incognito
                suspend fun state(): String = onMain {
                    val o = JSONObject().put("supported", I.supported()).put("path", I.path().name).put("lastPath", I.lastPath?.name ?: "")
                        .put("forceProcess", I.forceProcess).put("open", JSONArray(I.openProfiles())).put("marker", I.markerPresent(context))
                        .put("process", com.openminis.app.browser.IncognitoProcess.running(context))
                        .put("dirs", JSONArray(com.openminis.app.browser.IncognitoProcess.dirsPresent(context)))
                        .put("lastClose", I.lastClose ?: "")
                    if (I.supported()) runCatching { o.put("profiles", JSONArray(androidx.webkit.ProfileStore.getInstance().allProfileNames)) }
                    o.toString()
                }
                when (sub) {
                    "incog" -> { onMain { com.openminis.app.ui.plus.PlusNav.openIncognito.value = arg2 }; kotlinx.coroutines.delay(2500); state() }
                    "incogstate" -> state()
                    "incogforce" -> { I.forceProcess = arg2 == "process"; state() }
                    "normal" -> { onMain { com.openminis.app.ui.plus.PlusNav.openBrowser.value = arg2 }; kotlinx.coroutines.delay(2500); "ok" }
                    "skylegacy" -> { com.openminis.app.ui.plus.WeatherSky.legacySky = arg2 == "on"; com.openminis.app.ui.plus.WeatherSky.bump(); "legacy=" + com.openminis.app.ui.plus.WeatherSky.legacySky }
                    // 'conv <sid> <url>' = that conversation's agent pool loads url (off-screen WebView); 'convdel <sid>'; 'convstate'
                    "conv" -> {
                        val sid = arg2.substringBefore(' '); val url = arg2.substringAfter(' ', "")
                        onMain {
                            val pool = convPools.getOrPut(sid) { com.openminis.app.browser.BrowserTabPool(context).also { it.setSession(sid) } }
                            val t = pool.selectOrCreateTabForURL(url + "&r=" + System.nanoTime())
                            JSONObject().put("tab", t != null).put("profile", pool.convProfile ?: "").put("active", com.openminis.app.browser.ConvProfiles.active()).toString()
                        }
                    }
                    "convdel" -> onMain {
                        convPools.remove(arg2)?.destroyAllTabs()
                        com.openminis.app.browser.ConvProfiles.forget(context, arg2); "forgot"
                    }
                    "convstate" -> onMain {
                        val o = JSONObject().put("active", com.openminis.app.browser.ConvProfiles.active())
                            .put("map", JSONObject(com.openminis.app.browser.ConvProfiles.profiles() as Map<*, *>))
                            .put("bindings", com.openminis.app.browser.ConvProfiles.bindings().size)
                        if (com.openminis.app.browser.ConvProfiles.active()) runCatching { o.put("profiles", JSONArray(androidx.webkit.ProfileStore.getInstance().allProfileNames)) }
                        o.toString()
                    }
                    // 'sshprobe host port' = JSch key exchange only (no login): did host-key negotiation pass?
                    "sshprobe" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val host = arg2.substringBefore(' '); val port = arg2.substringAfter(' ', "22").toIntOrNull() ?: 22
                        com.openminis.app.plus.VpsSsh.kexProbe(context, host, port)
                    }
                    "skyfreeze" -> {
                        val F = com.openminis.app.ui.plus.SkyFreeze
                        when (arg2) { "ignore" -> F.ignoreSignals = true; "on" -> F.ignoreSignals = false }
                        JSONObject().put("ignore", F.ignoreSignals).put("frozen", F.frozen()).put("reason", F.lastReason)
                            .put("signals", F.signals.toString()).toString()
                    }
                    // [38.9.11.2] 严格代理 switch from the smoke: 'strict on|off' → the sandbox env the next process gets
                    "strict" -> {
                        com.openminis.app.plus.SandboxNet.setStrict(context, arg2 == "on")
                        val env = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.openminis.app.plus.SandboxNet.proxyEnv(context, "") }.getOrDefault(emptyMap()) }
                        JSONObject().put("strict", com.openminis.app.plus.SandboxNet.strict).put("best", com.openminis.app.plus.LocalProxy.best ?: -1)
                            .put("http_proxy", env["http_proxy"] ?: "").put("source", env["MINES_PROXY_SOURCE"] ?: "")
                            .put("blocked", env["MINES_NET_BLOCKED"] ?: "").put("status", com.openminis.app.plus.SandboxNet.status.value).toString()
                    }
                    // [38.9.11.2] 合并重复供应商: 'mergeseed' (3 providers on one host, 3 keys) · 'mergestate' · 'mergeclean'
                    "mergeseed", "mergestate", "mergeclean" -> {
                        val app = context.applicationContext as com.openminis.app.MinisApp
                        val repo = app.providerRepository
                        val H = "merge-smoke.example"
                        fun mine() = repo.config.value.instances.filter { com.openminis.app.plus.ProviderMergeCore.host(it.customBaseURL) == H }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (sub == "mergeclean") { mine().forEach { repo.removeInstance(it.id) }; repo.dropMergeBackup() }
                            if (sub == "mergeseed" && mine().isEmpty()) {
                                listOf(listOf("m-a"), listOf("m-a", "m-b"), emptyList()).forEachIndexed { i, models ->
                                    val inst = com.openminis.app.data.model.ProviderInstance(id = java.util.UUID.randomUUID().toString(), label = "合并测试 ${i + 1}",
                                        providerType = com.openminis.app.data.model.ProviderType.openAI, credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                                        createdAt = System.currentTimeMillis() + i, customBaseURL = "https://$H/v1", isEnabled = false)
                                    repo.addInstance(inst); repo.saveApiKey(inst.id, "sk-merge-smoke-${i + 1}")
                                    models.forEach { m -> repo.addEntry(com.openminis.app.data.model.ModelEntry(inst.id, com.openminis.app.data.model.LLMModel(m, m, "openai"), isCustom = true)) }
                                }
                            }
                        }
                        val ms = mine()
                        JSONObject().put("count", ms.size).put("labels", JSONArray(ms.map { it.label }))
                            .put("plan", JSONArray(runCatching { repo.mergePlan() }.getOrDefault(emptyList()).filter { it.host == H }.map { com.openminis.app.plus.ProviderMergeCore.line(it) }))
                            .put("pools", JSONArray(ms.map { repo.keyPool(it.id).size }))
                            .put("models", JSONArray(ms.map { i -> repo.config.value.modelEntries.count { it.providerInstanceId == i.id } }))
                            .put("backup", repo.hasMergeBackup()).toString()
                    }
                    "skylevel" -> { com.openminis.app.ui.plus.WeatherSky.setLevel(arg2.toIntOrNull() ?: 0, "smoke"); "level=" + com.openminis.app.ui.plus.WeatherSky.level }
                    "atomic" -> {
                        val d = java.io.File(context.cacheDir, "atomic-smoke"); d.deleteRecursively()
                        val C = com.openminis.app.plus.AtomicStoreCore
                        C.write(d, "t", "v1".toByteArray()); C.write(d, "t", "v2".toByteArray()); C.write(d, "t", "v3".toByteArray())
                        val ok = { b: ByteArray -> String(b).startsWith("v") }
                        val r0 = C.read(d, "t", ok)
                        C.main(d, "t").writeText("garbage")
                        val r1 = C.read(d, "t", ok)
                        C.bak(d, "t", 1).writeText("x"); C.bak(d, "t", 2).writeText("y")
                        val r2 = C.read(d, "t", ok)
                        d.deleteRecursively()
                        JSONObject().put("fresh", r0.bytes?.let { String(it) } + "@" + r0.source).put("mainBad", r1.bytes?.let { String(it) } + "@" + r1.source)
                            .put("allBad", r2.allFailed).toString()
                    }
                    else -> "i39112 incog <url> | incogstate | incogforce process|auto | normal <url> | skylegacy on|off | skylevel <n> | skyfreeze ignore|on|state | conv <sid> <url> | convdel <sid> | convstate | sshprobe <host> <port> | strict on|off | mergeseed | mergestate | mergeclean | atomic"
                }
            }
            // [Mines-38.9.1] 应用代理: 'agents' list (mine + enabled), 'agents clear', 'agents add <id>'
            "agents" -> {
                val A = com.openminis.app.plus.AppAgents; A.init(context)
                when (parts.getOrElse(0) { "" }.substringBefore(' ').ifBlank { rest.substringBefore(' ') }) {
                    "clear" -> A.mine().forEach { A.remove(it.id) }
                    "add" -> A.add(rest.substringAfter("add ").trim())
                }
                JSONObject().put("mine", JSONArray(A.mine().map { JSONObject().put("id", it.id).put("name", it.name).put("on", A.enabled(it.id)) }))
                    .put("catalog", A.catalog().size).toString()
            }
            // [Mines-38.9.1] KeyAgent WebView on a fake page ('keyagent groq|http://10.0.2.2:8787/keydlg'): 账号连接 → that service → 一键接入
            "keyagent" -> {
                val k = parts.getOrElse(0) { "groq" }
                parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { com.openminis.app.plus.KeyAgent.testUrl[k] = it }
                com.openminis.app.ui.plus.PlusNav.openKind = k; com.openminis.app.ui.plus.PlusNav.autoClaim = true
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS) }
                "ok recipe=" + (com.openminis.app.plus.KeyAgent.recipe(k)?.url ?: "")
            }
            // [Mines-38.9.1] open a login page in Mines' in-app browser ('linkrun http://10.0.2.2:8787/linkfake')
            "linkrun" -> {
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.web(rest.trim()) }
                "ok"
            }
            "partners" -> {
                val l = Partners.refresh(context, rescanApps = true)
                JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.spec.name).put("state", it.state.name).put("model", it.model).put("via", it.via) }).toString()
            }
            "custom" -> GroupPrefs.addCustom(parts.getOrElse(0) { "smoke" }, parts.getOrElse(1) { "" }, parts.getOrNull(2), parts.getOrElse(3) { "" })
                .also { Partners.refresh(context) }
            "make" -> {
                Partners.refresh(context)
                val ids = parts.getOrElse(1) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                GroupStore.create(parts.getOrElse(0) { "smoke" }, ids, host = true, sequential = parts.getOrNull(2) != "par").id
            }
            "send" -> {
                val gid = parts.getOrElse(0) { "" }
                val before = GroupStore.messages(gid).value.size
                GroupRunner.send(context, gid, parts.drop(1).joinToString("|"))
                delay(300)
                val t0 = System.currentTimeMillis()
                while (gid in GroupRunner.running.value && System.currentTimeMillis() - t0 < 170_000) delay(250)
                val round = GroupStore.messages(gid).value.drop(before)
                JSONArray(round.map { JSONObject().put("who", it.who).put("name", it.name).put("status", it.status).put("kind", it.kind)
                    .put("model", it.model).put("detail", it.detail.take(160)).put("text", it.text.take(200)) }).toString()
            }
            // [Mines-38.5] simulate 账号连接 having saved a key (as KeyAgent does), then read the partner state the AI 伙伴 page shows
            "vaultkey" -> {
                val kind = parts.getOrElse(0) { "deepseek" }
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { GroupPrefs.put("base_$kind", it) }
                com.openminis.app.plus.PlusVault.init(context)
                val k = com.openminis.app.plus.PlusVault.kind(kind) ?: return@runBlocking "no kind $kind"
                com.openminis.app.plus.PlusVault.save(context, k, "smoke", parts.getOrElse(1) { "sk-smoke" }, false, "key")
                val l = Partners.refresh(context)
                val p = l.firstOrNull { it.id == kind }
                JSONObject().put("state", p?.state?.name).put("via", p?.via).put("model", p?.model).put("reason", p?.reason)
                    .put("dupes", l.count { it.id == kind }).toString()
            }
            // [Mines-38.6] smoke: point a member at the runner's fake service / mark a web app signed in / list 扣子 bots
            "base" -> { GroupPrefs.put("base_${parts.getOrElse(0) { "" }}", parts.getOrNull(1)); Partners.refresh(context); "ok" }
            "weblogin" -> { WebChat.markSignedIn(parts.getOrElse(0) { "" }, parts.getOrNull(1) != "0"); Partners.refresh(context); "ok" }
            "cozesync" -> {
                val key = Partners.vaultKey("coze") ?: return@runBlocking "no coze key"
                val s = CozeBots.sync(GroupPrefs.get("base_coze") ?: "https://api.coze.cn/v3", key, force = true)
                val l = Partners.refresh(context)
                JSONObject().put("bots", JSONArray(s.bots.map { JSONObject().put("id", it.id).put("name", it.name).put("ready", it.ready) }))
                    .put("published", JSONArray(s.published)).put("problem", s.problem ?: "")
                    .put("members", JSONArray(l.filter { it.id.startsWith("coze") }.map { JSONObject().put("id", it.id).put("name", it.spec.name).put("state", it.state.name).put("reason", it.reason ?: "") }))
                    .toString()
            }
            "direct" -> {
                Partners.refresh(context)
                val id = parts.getOrElse(0) { "" }
                GroupStore.direct(id, Partners.find(id)?.spec?.name ?: id).id
            }
            "setmodel" -> { GroupPrefs.setModel(parts.getOrElse(0) { "" }, parts.getOrNull(1)); Partners.refresh(context); "ok" }
            "summary" -> {
                val gid = parts.getOrElse(0) { "" }
                val before = GroupStore.messages(gid).value.size
                GroupRunner.summarize(context, gid)
                delay(300)
                val t0 = System.currentTimeMillis()
                while (gid in GroupRunner.running.value && System.currentTimeMillis() - t0 < 170_000) delay(250)
                val round = GroupStore.messages(gid).value.drop(before)
                JSONArray(round.map { JSONObject().put("who", it.who).put("kind", it.kind).put("status", it.status).put("text", it.text.take(300)) }).toString()
            }
            // ---------------- [Mines-38.7] multi-account web apps (isolation) ----------------
            "jarmode" -> { com.openminis.app.plus.AccountJar.forceSwap = parts.getOrElse(0) { "" } == "swap"; "mode=" + com.openminis.app.plus.AccountJar.mode() }
            "jarinfo" -> JSONObject().put("profilesSupported", com.openminis.app.plus.LoginWeb.profilesSupported())
                .put("mode", com.openminis.app.plus.AccountJar.mode()).toString()
            "webacctadd" -> WebAccounts.add(parts.getOrElse(0) { "web:doubao" }).also { Partners.refresh(context) }
            "webacctreset" -> {
                val site = parts.getOrElse(0) { "web:doubao" }
                for (id in WebAccounts.ids(site)) { AiCatalog.spec(id)?.let { WebChat.signOut(it, id) }; WebAccounts.drop(id); WebChat.markSignedIn(id, null) }
                Partners.refresh(context); "ok"
            }
            "webacctlogin" -> {
                val id = parts.getOrElse(0) { "" }
                val spec = AiCatalog.spec(id) ?: return@runBlocking "no spec $id"
                val mode = WebChat.testLogin(context, spec, id, parts.getOrElse(1) { "" })
                Partners.refresh(context); "mode=$mode"
            }
            "websignout" -> {
                val id = parts.getOrElse(0) { "" }
                val spec = AiCatalog.spec(id) ?: return@runBlocking "no spec $id"
                WebChat.signOut(spec, id); Partners.refresh(context); "ok"
            }
            "webstates" -> {
                val l = Partners.refresh(context)
                JSONArray(l.filter { it.spec.proto == Proto.WEBAPP }.map { JSONObject().put("id", it.id).put("name", it.spec.name).put("state", it.state.name).put("reason", it.reason ?: "") }).toString()
            }
            // real site, never signed in on this device: what the page is (SIGNED_IN / GUEST / LOGIN / VERIFY / LOADING)
            "webcheck" -> {
                val id = parts.getOrElse(0) { "" }
                val spec = AiCatalog.spec(id) ?: return@runBlocking "no spec $id"
                val c = WebChat.check(context, spec, id, (parts.getOrNull(1)?.toLongOrNull() ?: 30) * 1000)
                val p = Partners.refresh(context).firstOrNull { it.id == id }
                JSONObject().put("id", id).put("state", c.state.name).put("url", c.url).put("text", c.text.take(240))
                    .put("partner", p?.state?.name).put("reason", p?.reason ?: "").toString()
            }
            // one real question; "guest" lets a not-signed-in account chat as a visitor where the site allows it
            "webask" -> {
                val id = parts.getOrElse(0) { "" }
                val spec = AiCatalog.spec(id) ?: return@runBlocking "no spec $id"
                val p = Partner(spec, Partner.State.READY, GroupPrefs.get("base_${WebAccounts.site(id)}") ?: spec.webUrl, null, "网页版", "smoke")
                val t0 = System.currentTimeMillis()
                runCatching { WebChat.ask(context, p, parts.getOrElse(1) { "你好" }, 120_000, allowGuest = parts.getOrNull(2) == "guest") }.fold(
                    { r -> JSONObject().put("id", id).put("ok", true).put("model", r.model).put("text", r.text.take(400)).put("ms", System.currentTimeMillis() - t0) },
                    { e -> JSONObject().put("id", id).put("ok", false).put("error", e.message ?: e.javaClass.simpleName).put("ms", System.currentTimeMillis() - t0) }).toString()
            }
            // ---------------- [Mines-38.7] 知识库 auto-organise ----------------
            "kbep" -> { com.openminis.app.plus.ChatKnowledge.setEndpointOverride(context, rest.ifBlank { null }); "ok" }
            "kbseed" -> kbSeed(context, parts.getOrElse(0) { "" })
            "kbadd" -> {
                val gid = parts.getOrElse(0) { "" }
                GroupStore.add(gid, GroupMsg(GroupStore.newId(), "user", "我", "", parts.getOrElse(1) { "补充一句" }))
                GroupStore.add(gid, GroupMsg(GroupStore.newId(), parts.getOrElse(2) { "host" }, "成员A", "", parts.getOrElse(3) { "好的，结论：照这个办。" }))
                "ok"
            }
            // [Mines-38.9] smoke: 'autoacct site|host|account' → 「已记下 X 账号」 toast; 'expadd kind|pattern|steps|pitfalls' → one 经验 entry
            "autoacct" -> com.openminis.app.plus.AutoAccounts.record(context, parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" },
                com.openminis.app.plus.AutoAccounts.LINKED, "smoke") ?: "none"
            "expadd" -> {
                com.openminis.app.plus.Experience.add(context, if (parts.getOrElse(0) { "" } == "lesson") com.openminis.app.agent.ExperienceCore.Kind.LESSON else com.openminis.app.agent.ExperienceCore.Kind.HOWTO,
                    parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" }); "ok"
            }
            "kbrun" -> { val n = com.openminis.app.plus.ChatKnowledge.runNow(context); "organised=$n" }
            "kbstate" -> {
                val l = com.openminis.app.plus.ChatKnowledge.load(context)
                JSONArray(l.map { e -> JSONObject().put("id", e.sessionId).put("kind", e.kind).put("title", e.title).put("topic", e.topic)
                    .put("source", e.source).put("route", e.route).put("model", e.byModel).put("summary", e.summary.take(120))
                    .put("facts", e.facts.size).put("decisions", JSONArray(e.decisions.take(3))).put("todos", JSONArray(e.todos.take(3))).put("chatAt", e.chatAt) }).toString()
            }
            // [Mines-38.7] home greeting with injected time / weather / battery: 'greet {"hour":23,"weather":"rain"}' / 'greet off'
            "greet" -> {
                val o = rest.trim().takeIf { it.startsWith("{") }?.let { JSONObject(it) }
                com.openminis.app.plus.HomeGreeting.applyOverride(o)
                val g = com.openminis.app.plus.HomeGreeting.compute(context, com.openminis.app.plus.HomeGreeting.context(context, 80, false))
                JSONObject().put("title", g.title).put("care", g.care).put("kind", g.kind)
                    .put("weather", com.openminis.app.plus.HomeGreeting.cachedWeather(context)?.let { "${it.code}/${it.temp}" } ?: "none").toString()
            }
            // [Mines-38.9.5 i] varied greeting: one more app open → the phrase shown (never the same twice in a row)
            "greetvar" -> {
                com.openminis.app.plus.HomeGreeting.markOpened(context)
                com.openminis.app.plus.HomeGreeting.variedGreeting(context, com.openminis.app.plus.HomeGreeting.context(context, 80, true), 2, 1, 0, "")
            }
            "greetweather" -> {
                context.getSharedPreferences("mines_greeting", Context.MODE_PRIVATE).edit().remove("w_try").apply()
                com.openminis.app.plus.HomeGreeting.refreshWeather(context)
                com.openminis.app.plus.HomeGreeting.cachedWeather(context)?.toString() ?: "no weather"
            }
            // [Mines-38.7] smoke: point a web app at a test page ('setbase web:kimi http://…/smslogin'; empty = back to the real site)
            "setbase" -> { GroupPrefs.put("base_" + parts.getOrElse(0) { "" }, parts.getOrNull(1)); "ok" }
            // [Mines-38.7] 一键关联 summary inputs: 'linknote <id> 扫码|验证|跳过|' and 'linkmark <id> 1|0|'
            "linknote" -> { com.openminis.app.ui.plus.LinkAll.setNote(parts.getOrElse(0) { "" }, parts.getOrNull(1)); "ok" }
            "linkmark" -> { WebChat.markSignedIn(parts.getOrElse(0) { "" }, when (parts.getOrNull(1)) { "1" -> true; "0" -> false; else -> null }); "ok" }
            "linkstates" -> JSONObject(com.openminis.app.ui.plus.LinkAll.platforms().associateWith { com.openminis.app.ui.plus.LinkAll.state(it).label }).toString()
            // [Mines-38.7] every 一键关联 platform (web AI apps + all of 账号连接) with how it links, for the report table
            "linkinfo" -> {
                com.openminis.app.plus.PlusVault.init(context)
                val L = com.openminis.app.ui.plus.LinkAll
                JSONArray(L.platforms().map { id ->
                    JSONObject().put("id", id).put("name", L.name(id)).put("category", L.category(id)).put("how", L.how(id))
                        .put("auto", L.automatic(id)).put("external", L.externalSync(id)).put("state", L.state(id).label)
                        .put("step", if (L.automatic(id)) "" else L.manualStep(id))
                }).toString()
            }
            // [Mines-38.7] open one platform the way 一键关联 does ('linkopen conn:x' / 'linkopen web:kimi')
            "linkopen" -> {
                val id = parts.getOrElse(0) { "" }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.openminis.app.ui.plus.LinkAll.open(id, { r -> com.openminis.app.ui.plus.PlusNav.go(r) }, auto = false)
                }
                "ok"
            }
            // [Mines-38.7] persistence check without a real account: 'connfake x' puts a session cookie for X into Mines' own
            // cookie store + a 账号连接 entry (as a real in-app sign-in leaves it); 'connfake x|off' removes both
            "connfake" -> {
                com.openminis.app.plus.PlusVault.init(context)
                val k = com.openminis.app.plus.PlusVault.kind(parts.getOrElse(0) { "x" }) ?: return@runBlocking "no kind"
                val host = com.openminis.app.plus.PlusConnect.hostOf(k.loginUrl ?: "")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val cm = com.openminis.app.plus.LoginWeb.cookies(k.id)
                if (parts.getOrNull(1) == "off") {
                    k.doneCookies.forEach { c -> cm.setCookie("https://$host/", "$c=; Max-Age=0; Path=/") }
                    com.openminis.app.plus.LoginWeb.flush()
                    runCatching { com.openminis.app.plus.PlusVault.remove(context, k) }; "off"
                } else {
                    k.doneCookies.forEach { c -> cm.setCookie("https://$host/", "$c=smoke; Max-Age=86400; Path=/; Secure") }
                    com.openminis.app.plus.LoginWeb.flush()
                    com.openminis.app.plus.PlusVault.save(context, k, "smoke", null, share = false, via = "web")
                    "signedIn=" + com.openminis.app.plus.LoginWeb.signedIn(k)
                }
                }
            }
            "linkignore" -> { com.openminis.app.ui.plus.LinkAll.setIgnored(parts.getOrElse(0) { "" }, parts.getOrNull(1) == "1"); "ok" }
            // [Mines-38.7] day ↔ night: 'daynight 18:29:58|18:30' runs a fake clock (crosses the sunset in 2 s); 'daynight off'
            "daynight" -> {
                val a0 = parts.getOrElse(0) { "" }
                if (a0 == "off" || a0.isBlank()) { com.openminis.app.plus.DayNight.simulate(null); "off" } else {
                    val hms = a0.split(':').map { it.toDoubleOrNull() ?: 0.0 }
                    val start = hms.getOrElse(0) { 0.0 } * 60 + hms.getOrElse(1) { 0.0 } + hms.getOrElse(2) { 0.0 } / 60.0
                    val ss = parts.getOrNull(1)?.split(':')?.let { (it[0].toIntOrNull() ?: 18) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 30) }
                    com.openminis.app.plus.DayNight.simulate(start, ss)
                    "sim start=$start sunset=$ss night=" + com.openminis.app.plus.DayNight.tick(context)
                }
            }
            "thememode" -> { com.openminis.app.ui.settings.getAppearancePrefs(context).edit().putInt(com.openminis.app.ui.settings.KEY_THEME_MODE, parts.getOrElse(0) { "3" }.toIntOrNull() ?: 3).apply(); "ok" }
            // [Mines-38.7] open an account's in-app sign-in page from 账号连接 ('openlogin x')
            "openlogin" -> {
                com.openminis.app.ui.plus.PlusNav.openLogin = parts.getOrElse(0) { "x" }
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS) }
                "ok"
            }
            // [Mines-38.7] saved-login import from CSV text in the arg ('savedlogins name,url,username,password\n…' with \n as two chars)
            "savedlogins" -> "imported=" + com.openminis.app.plus.SavedLogins.importCsv(context, rest.replace("\\n", "\n"))
            // [Mines-38.7] 快速线路 code read that hangs: 'vpscodesim' → server id (page then opened by the smoke); 'vpscodesim off'
            "vpscodesim" -> {
                if (parts.getOrElse(0) { "" } == "off") { com.openminis.app.plus.LinkCodeReader.simulateHang = false; com.openminis.app.plus.LinkCodeReader.readLimitMs = 20_000L; "off" } else {
                    com.openminis.app.plus.LinkCodeReader.simulateHang = true
                    com.openminis.app.plus.LinkCodeReader.readLimitMs = 3_000L   // smoke: same flow, short limit
                    com.openminis.app.plus.PlusPrefs.init(context)
                    var sv = com.openminis.app.plus.PlusPrefs.state.value.servers.firstOrNull { it.name == "alvps-sim" }
                    if (sv == null) { sv = com.openminis.app.plus.VpsServer(name = "alvps-sim", host = "10.0.2.2", port = 2222)
                        val nv = sv; com.openminis.app.plus.PlusPrefs.update(context) { st -> st.copy(servers = st.servers + nv) } }
                    com.openminis.app.plus.LinkCodeReader.retryRequested = true
                    sv.id
                }
            }
            // [Mines-38.7] smoke: 'wallpaper off' — the photo wallpaper (set by earlier smoke steps) covers the fog backdrop
            "wallpaper" -> { com.openminis.app.plus.PlusPrefs.init(context)
                if (parts.getOrElse(0) { "" } == "off") com.openminis.app.plus.PlusPrefs.update(context) { it.copy(wallpaper = "") }
                com.openminis.app.plus.PlusPrefs.state.value.wallpaper.ifBlank { "none" } }
            // [Mines-38.9.1] fog-demo: 'fogwind 9|270|61' (m/s | from ° | weather code) or 'fogwind off'; 'lookset 0.55|1.6' (glass light | fog light) or 'lookset reset'
            "fogwind" -> {
                val W = com.openminis.app.ui.plus.FogWind
                W.forced = if (parts.getOrElse(0) { "" } == "off" || rest.isBlank()) null else
                    com.openminis.app.ui.plus.FogWind.Sample(parts.getOrElse(0) { "2" }.toDoubleOrNull() ?: 2.0, parts.getOrElse(1) { "270" }.toDoubleOrNull() ?: 270.0, parts.getOrElse(2) { "2" }.toIntOrNull() ?: 2)
                W.forced?.let { W.sample = it }
                "wind=" + W.sample
            }
            "lookset" -> {
                val L = com.openminis.app.ui.plus.MinesLook; L.init(context)
                val n = if (parts.getOrElse(0) { "" } == "reset") L.DEFAULT else L.look.copy(
                    glassLight = parts.getOrElse(0) { "" }.toFloatOrNull() ?: L.look.glassLight, fogLight = parts.getOrElse(1) { "" }.toFloatOrNull() ?: L.look.fogLight)
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post { L.set(context, n); l.countDown() }
                l.await(3, java.util.concurrent.TimeUnit.SECONDS)
                "look=" + n
            }
            // [Mines-38.9.1] reading-surface check: 'mdview' opens a markdown file (heading, table, code) in the file
            // viewer over whatever page is up; 'mdview legacy' reproduces the old transparent viewer (before shot).
            "mdview" -> {
                val f = java.io.File(context.cacheDir, "smoke-doc.md")
                f.writeText("# 部署说明\n\n这是 **Mines** 文档查看器的测试文件。正文要清楚，背景不能透出聊天。\n\n" +
                    "| 项目 | 状态 | 说明 |\n|---|:---:|---|\n| 网关 | 正常 | 127.0.0.1:8787 |\n| 模型路由 | 3 条 | 商汤 / GLM-5.3 / deepseek |\n| 守护 | 常驻 | 掉了 5 秒自动拉起 |\n\n" +
                    "## 代码\n\n```bash\ncurl -s --max-time 5 http://127.0.0.1:8787/v1/models\n```\n\n> 引用：内容页一律不透明。\n")
                com.openminis.app.ui.sandbox.FilePreviewSelfTest.legacy = parts.getOrElse(0) { "" } == "legacy"
                com.openminis.app.ui.navigation.FilePreviewHolder.currentItem = com.openminis.app.ui.sandbox.FileItem(
                    file = f, name = f.name, isDirectory = false, isSymlink = false, size = f.length(), modifiedMs = f.lastModified())
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go("file_preview"); l.countDown() }
                l.await(3, java.util.concurrent.TimeUnit.SECONDS)
                "mdview " + (if (com.openminis.app.ui.sandbox.FilePreviewSelfTest.legacy) "legacy" else "opaque")
            }
            // [Mines-38.9.1] Skills Hub: 'skillshub sync' (real index), 'skillshub page <query>', 'skillshub install <query>' (first hit), 'skillshub open'
            "skillshub" -> {
                val H = com.openminis.app.plus.SkillsHub
                val sub = rest.substringBefore(' '); val q = rest.substringAfter(' ', "")
                when (sub) {
                    "sync" -> { val t0 = System.currentTimeMillis(); val r = runCatching { H.sync(context, force = q == "force") }
                        JSONObject().put("result", r.getOrElse { "error " + it.message }).put("count", H.count(context)).put("ms", System.currentTimeMillis() - t0).toString() }
                    "page" -> JSONArray(H.page(context, q, null, null, 0, 10).map { it.source + ":" + it.name }).toString()
                    "cat" -> JSONArray(H.page(context, "", q, null, 0, 10).map { it.source + ":" + it.name }).toString()
                    "install" -> { val s = H.page(context, q, null, null, 0, 1).firstOrNull()
                        if (s == null) "no hit" else JSONObject().put("skill", s.source + ":" + s.ident).put("result", runCatching { H.install(context, s) }.getOrElse { "error " + it.message })
                            .put("hasSkillMd", java.io.File(H.skillsDir(context), H.dirName(s) + "/SKILL.md").isFile).toString() }
                    "open" -> { com.openminis.app.ui.plus.MarketNav.tab = 1
                        android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.MARKET) }; "ok" }
                    else -> "count=" + H.count(context)
                }
            }
            // [Mines-38.9.4] smoke 'mkt3894' etc: SkillHub (skillhub.cn) list / search / skill install, Memoh hosted state, a11y wording
            "h394" -> {
                val H = com.openminis.app.plus.SkillHubCn
                val sub = rest.substringBefore(' '); val q = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "list" -> {
                        val p = runCatching { H.plugins(context, "", null, "stars", 1, force = true) }
                        val k = runCatching { H.skills(context, "", null, 1, force = true) }
                        JSONObject().put("plugins", p.getOrNull()?.items?.size ?: -1).put("pluginsTotal", p.getOrNull()?.total ?: -1)
                            .put("skills", k.getOrNull()?.items?.size ?: -1).put("skillsTotal", k.getOrNull()?.total ?: -1)
                            .put("err", (p.exceptionOrNull() ?: k.exceptionOrNull())?.message ?: "").toString()
                    }
                    "search" -> {
                        val p = runCatching { H.plugins(context, q, null, "stars", 1) }.getOrNull()
                        val k = runCatching { H.skills(context, q, null, 1) }.getOrNull()
                        JSONObject().put("plugins", JSONArray(p?.items.orEmpty().take(5).map { it.owner + "/" + it.name }))
                            .put("skills", JSONArray(k?.items.orEmpty().take(5).map { it.slug + "@" + it.version })).toString()
                    }
                    "skill" -> {
                        val all = runCatching { H.skills(context, q, null, 1).items.filter { it.slug == q } }.getOrDefault(emptyList())
                        val hit = all.firstOrNull()
                        if (hit == null) "no hit" else JSONObject().put("result", runCatching { H.installSkill(context, hit) }.getOrElse { "error " + it.message })
                            .put("installed", H.installedSkill(context, q) ?: "")
                            // [Mines-38.9.5 i] every same-slug row's state: exactly one MINE (the installed publisher), the rest OTHER
                            .put("states", JSONArray(all.map { it.namespace + ":" + H.skillState(context, it).name }))
                            .put("other", all.drop(1).firstOrNull()?.let { o -> runCatching { H.installSkill(context, o) }.getOrElse { "error " + it.message } } ?: "").toString()
                    }
                    "plugsrc" -> {
                        val hit = runCatching { H.plugins(context, q.substringAfter('/'), null, "stars", 1).items.firstOrNull { (it.owner + "/" + it.name).equals(q, true) } }.getOrNull()
                        if (hit == null) "no hit" else runCatching { H.installSource(hit) }.getOrElse { "error " + it.message }
                    }
                    "open" -> { com.openminis.app.ui.plus.MarketNav.tab = q.toIntOrNull() ?: 0
                        android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.MARKET) }; "ok" }
                    "memoh" -> {
                        if (q == "fake") {
                            // what SiteLogin stores after a hosted app.memoh.net sign-in (cookie session, no JWT)
                            GroupPrefs.put("memoh_base", PresetAuth.memohApiBaseFor("https://app.memoh.net/"))
                            GroupPrefs.put("memoh_user", "smoke@example.com"); GroupPrefs.put("memoh_web", "1")
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                runCatching { android.webkit.CookieManager.getInstance().setCookie("https://app.memoh.net", "memoh_session=smoke394; Max-Age=86400; Path=/") { done.complete(Unit) } }
                                    .onFailure { done.complete(Unit) }
                            }
                            kotlinx.coroutines.withTimeoutOrNull(5_000) { done.await() }
                        }
                        val l = runCatching { Partners.refresh(context) }.getOrDefault(emptyList()).filter { it.id == "memoh" || it.id.startsWith("memoh:") }
                        JSONObject().put("base", PresetAuth.memohBase() ?: "").put("hosted", PresetAuth.memohHosted())
                            .put("cookie", PresetAuth.memohWebCookie() != null).put("where", PresetAuth.memohWhere() ?: "")
                            .put("members", JSONArray(l.map { it.id + "|" + it.state.name + "|" + (it.reason ?: "") })).toString()
                    }
                    "a11y" -> {
                        val r = com.openminis.app.plus.A11yCheck.check(context)
                        JSONObject().put("ok", r.ok).put("enabled", r.enabled).put("running", r.running).put("restricted", r.restricted).put("detail", r.detail).toString()
                    }
                    else -> "h394 list|search q|skill slug|plugsrc owner/name|open 0/1|memoh [fake]|a11y"
                }
            }
            "fogstate" -> JSONObject().put("t", com.openminis.app.ui.plus.FogClock.t.toDouble()).put("draws", com.openminis.app.ui.plus.FogClock.draws)
                .put("still", com.openminis.app.ui.plus.FogClock.stillSeen)
                .put("scale", android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, -1f).toDouble()).toString()
            "weatherframe" -> JSONObject().put("t", com.openminis.app.ui.plus.AnimeWeather.lastT.toDouble()).toString()
            "reset" -> {
                GroupStore.groups.value.forEach { GroupStore.delete(it.id) }
                GroupPrefs.customs().forEach { GroupPrefs.removeCustom(it.id) }
                "ok"
            }
            else -> "unknown group command"
        }
    }

    /**
     * [Mines-38.7] three conversations for the 知识库 smoke: a normal chat, an AI 群聊 and a 单聊 with a web-app member
     * (multi-account label). [tag] makes a second seed different (so 立即整理 has something new).
     */
    private suspend fun kbSeed(context: Context, tag: String): String {
        val app = context.applicationContext as com.openminis.app.MinisApp
        val dao = app.database.chatDao()
        val now = System.currentTimeMillis()
        val sid = "kbseed-chat"
        dao.insertSession(com.openminis.app.data.db.ChatSessionEntity(id = sid, title = "家里网络升级", modelId = "smoke", createdAt = now - 600_000, updatedAt = now - 1000))
        dao.deleteMessages(sid)
        val lines = listOf(
            "user" to "我想把家里路由器换成 WiFi 7，预算 800 元以内，三室一厅$tag",
            "assistant" to "可以看小米 BE6500 Pro 和 TP-LINK BE7200。三室一厅建议两台组 Mesh。",
            "user" to "那就定了吧",
            "assistant" to "结论：选小米 BE6500 Pro 两台组 Mesh，总价约 760 元。待办：周末下单，记得把旧路由器的宽带账号抄下来。",
        )
        lines.forEachIndexed { i, (role, text) ->
            dao.insertMessage(com.openminis.app.data.db.MessageEntity(id = "$sid-$i", sessionId = sid, role = role,
                partsJson = JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString(), createdAt = now - 600_000 + i * 1000, sortOrder = i))
        }
        GroupStore.groups.value.filter { it.name == "交易复盘群" || it.name.startsWith("豆包") && it.members.size == 1 }.forEach { GroupStore.delete(it.id) }
        val g = GroupStore.create("交易复盘群", listOf("host"), host = true, sequential = true)
        GroupStore.add(g.id, GroupMsg(GroupStore.newId(), "user", "我", "", "这周 BTC 合约亏了 3%，怎么调整仓位？$tag"))
        GroupStore.add(g.id, GroupMsg(GroupStore.newId(), "host", "Mines", "smoke", "建议单笔仓位降到总资金 5% 以内，止损设 2%。"))
        GroupStore.add(g.id, GroupMsg(GroupStore.newId(), "user", "我", "", "好，下周开始执行，记得提醒我复盘"))
        GroupStore.add(g.id, GroupMsg(GroupStore.newId(), "host", "Mines", "smoke", "结论：仓位 5%、止损 2%。待办：下周日晚上复盘一次。"))
        Partners.refresh(context)
        val dn = Partners.find("web:doubao")?.spec?.name ?: "豆包"
        val d = GroupStore.direct("web:doubao", dn)
        GroupStore.clear(d.id)
        GroupStore.add(d.id, GroupMsg(GroupStore.newId(), "user", "我", "", "帮我写一段周末去杭州的行程，两天一夜$tag"))
        GroupStore.add(d.id, GroupMsg(GroupStore.newId(), "web:doubao", dn, "网页版", "第一天西湖、灵隐寺，第二天龙井村。建议住在湖滨。"))
        GroupStore.add(d.id, GroupMsg(GroupStore.newId(), "user", "我", "", "就按这个，记得帮我订周六的酒店"))
        return JSONObject().put("chat", sid).put("group", g.id).put("direct", d.id).put("directName", dn).toString()
    }
}


/** [Mines-38.9.8] smoke: a local HTTP server that accepts every connection and never answers (quick-test timeout path) */
internal object I398Silent {
    @Volatile var port: Int = 0
    val accepted = java.util.concurrent.atomic.AtomicInteger(0)
    private val held = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
    @Synchronized fun start(): Int {
        if (port != 0) return port
        // fixed port first: the provider's base URL must still point here after the app process restarts
        val lo = java.net.InetAddress.getByName("127.0.0.1")
        val ss = runCatching { java.net.ServerSocket(18398, 50, lo) }.getOrElse { java.net.ServerSocket(0, 50, lo) }
        port = ss.localPort
        Thread({
            while (true) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                accepted.incrementAndGet(); held.add(c)
                if (held.size > 64) runCatching { held.removeAt(0).close() }
            }
        }, "i398-silent").apply { isDaemon = true }.start()
        return port
    }
}

/** [Mines-38.9.11.2] smoke: conversation pools opened by 'i39112 conv' */
private val convPools = HashMap<String, com.openminis.app.browser.BrowserTabPool>()
