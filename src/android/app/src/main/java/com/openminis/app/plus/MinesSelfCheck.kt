package com.openminis.app.plus

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [Mines-report-31] 一键冒烟测试 + 上报.
 *
 * Runs on-device self-checks, bundles the failures with a redacted log
 * excerpt and files a GitHub issue (label `mines-bug`) on the Mines repo
 * with the user's connected GitHub login, so Hark can read it through the
 * GitHub connector. The issue body is structured: build, device, a check
 * table, log excerpt. Keys / tokens are removed, e-mails masked.
 */
object MinesSelfCheck {
    const val REPO = "ddfc1991/openminis-agent-android"
    const val LABEL = "mines-bug"

    data class Check(val name: String, val ok: Boolean, val detail: String, val ms: Long = 0, val warn: Boolean = false)

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    private inline fun timed(name: String, block: () -> Pair<Boolean, String>): Check {
        val t0 = System.currentTimeMillis()
        return runCatching { block() }.fold(
            { (ok, d) -> Check(name, ok, d, System.currentTimeMillis() - t0) },
            { e -> Check(name, false, "${e.javaClass.simpleName}: ${e.message?.take(160)}", System.currentTimeMillis() - t0) },
        )
    }

    /** Run every check (≈20–60 s). [progress] gets the name of the check being run. */
    suspend fun runAll(context: Context, progress: (String) -> Unit = {}): List<Check> = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        PlusVault.init(c)
        val out = ArrayList<Check>()

        progress("模型服务")
        val repo = runCatching { (c as? com.openminis.app.MinisApp)?.providerRepositoryOrNull }.getOrNull()
        val insts = runCatching { repo?.instances?.filter { it.isEnabled } }.getOrNull().orEmpty()
        if (insts.isEmpty()) out += Check("模型服务", false, "没有启用的模型服务", warn = true)
        insts.take(8).forEach { inst ->
            out += timed("模型 · ${inst.label}") {
                val base = inst.effectiveBaseURL ?: return@timed true to "官方地址（不测）"
                val url = base.trimEnd('/') + "/models"
                val code = http.newCall(Request.Builder().url(url).build()).execute().use { it.code }
                (code in 200..499) to "HTTP $code（能连上）"
            }
        }

        progress("实时语音握手")
        out += timed("Gemini 实时语音握手") {
            val key = GeminiRoute.apiKey() ?: return@timed false to "没有 Gemini Key"
            val r = GeminiRoute.routes(c, false).firstOrNull { GeminiRoute.proxyPortUp(it) } ?: return@timed false to "没有可用线路"
            val p = GeminiRoute.probe(r, key)
            p.ok to "线路 ${r.label}：${p.note.take(120)}"
        }

        progress("账号密钥")
        PlusVault.entries.value.values.filter { it.via == "key" || it.via == "token" }.take(10).forEach { e ->
            out += timed("密钥 · ${PlusVault.kind(e.kindId)?.name ?: e.kindId}") {
                val k = PlusVault.secret(e.kindId) ?: return@timed false to "没有保存密钥"
                val r = KeyTester.test(PlusVault.baseId(e.kindId), k)
                r.ok to (if (!r.ok && netError(r.message)) "网络/代理问题，不是密钥问题（${proxyHint(r.message)}）：" + r.message.removePrefix("测试时连不上服务器：").take(100) else r.message)
            }.let { ch -> if (!ch.ok && ch.detail.startsWith("网络/代理问题")) ch.copy(warn = true) else ch }
        }

        progress("权限")
        // [Mines-38.9.4] issue #2 Bug 4: 「已开启」 was read from the switch / a stale instance while android-a11y-cli got
        // PERM_DENIED / SERVICE_NOT_RUNNING (Android 13+ 受限设置 for a sideloaded APK). Now: is the service bound AND does it
        // answer (a live window root)? If the switch is on but the service is not running, say why and how to fix it.
        out += A11yCheck.check(c).let { Check("无障碍", it.ok, it.detail, warn = !it.ok && !it.restricted) }
        out += timed("Shizuku") {
            val ok = com.openminis.app.sandbox.offload.FastUi.shizukuReady()
            ok to (if (ok) "已授权" else "未运行或未授权")
        }
        out += Check("悬浮窗（桌宠）", Settings.canDrawOverlays(c), if (Settings.canDrawOverlays(c)) "已允许" else "未允许")
        out += Check("桌宠", !PlusPrefs.state.value.petEnabled || Settings.canDrawOverlays(c),
            if (PlusPrefs.state.value.petEnabled) "已开启" else "未开启", warn = !PlusPrefs.state.value.petEnabled)

        progress("沙箱")
        out += timed("沙箱命令") {
            val (code, text) = MinesJobs.runSync(c, "echo mines-ok; uname -m; df -h / | tail -1", 30_000)
            (code == 0 && text.contains("mines-ok")) to text.replace("\n", " ").take(160)
        }
        // [Mines-38.9.6 i13] 沙箱代理出口 / Gmail 可达: the sandbox inherits the proxy env (local split proxy → upstream with
        // Proxy-Authorization), so the exit IP seen from inside must be the proxy's. Nothing here prints credentials.
        progress("沙箱代理出口")
        out += timed("沙箱代理出口") {
            val st = SplitProxy.settings.value
            val up = st.all.firstOrNull { it.key == SplitProxy.current.value } ?: st.primary
            val (code, text) = MinesJobs.runSync(c, "command -v curl >/dev/null || timeout 90 apk add --no-cache curl >/dev/null 2>&1; echo \"via=\${HTTPS_PROXY:-direct}\"; curl -sS -m 15 https://api.ipify.org 2>&1 | head -c 200", 120_000)
            val ip = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""").findAll(text.substringAfter('\n', text)).lastOrNull()?.value
            val via = text.lineSequence().firstOrNull { it.startsWith("via=") }?.removePrefix("via=").orEmpty()
            if (ip == null) return@timed false to "沙箱里查不到出口 IP（退出码 $code）：" + text.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
            if (up == null) return@timed true to "出口 $ip（没设代理服务器，直连）"
            val want = runCatching { java.net.InetAddress.getAllByName(up.host).map { it.hostAddress } }.getOrDefault(listOf(up.host))
            (ip in want) to (if (ip in want) "出口 $ip = 代理 ${up.host}（沙箱经 ${via.ifBlank { "直连" }}）"
                else "出口 $ip，不是代理 ${up.host}：沙箱流量没走代理（${if (via == "direct" || via.isBlank()) "沙箱没拿到代理变量，检查 分流 › 用于沙箱" else "经 $via"}）")
        }.let { ch -> if (!ch.ok && (SplitProxy.settings.value.primary == null || ch.detail.contains("沙箱还没启动"))) ch.copy(warn = true) else ch }
        progress("Gmail 可达")
        out += timed("Gmail 可达") {
            val (code, text) = MinesJobs.runSync(c, "command -v curl >/dev/null || timeout 90 apk add --no-cache curl >/dev/null 2>&1; curl -sS -m 20 -o /dev/null -w 'http=%{http_code}' https://mail.google.com/mail/ 2>&1 | tail -c 200; echo; curl -sS -m 20 -o /dev/null -w 'acct=%{http_code}' https://accounts.google.com/ServiceLogin 2>&1 | tail -c 200", 140_000)
            val gm = Regex("http=(\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ac = Regex("acct=(\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val app = runCatching { http.newCall(Request.Builder().url("https://mail.google.com/mail/").head().build()).execute().use { it.code } }.getOrDefault(0)
            val ok = gm in 200..399 && ac in 200..399
            ok to "沙箱 mail.google.com ${if (gm > 0) "HTTP $gm" else "连不上"} · 登录页 ${if (ac > 0) "HTTP $ac" else "连不上"} · App ${if (app > 0) "HTTP $app" else "连不上"}" +
                (if (code != 0 && !ok) "（${text.lines().lastOrNull { it.isNotBlank() && !it.contains("=") }.orEmpty().take(80)}）" else "") +
                " · 网页登录${if (WebProxy.applied) "走代理" else "未走代理"}"
        }
        // [Mines-38.9.7] 天气皮肤 / 首页天气 / 网页登录 / DSH 工具 — what is active and why (phone 21:15–21:30)
        out += timed("天气皮肤") {
            val cal = java.util.Calendar.getInstance()
            com.openminis.app.plus.PlusPrefs.init(context)
            val st = com.openminis.app.plus.PlusPrefs.state.value
            val on = com.openminis.app.ui.plus.WeatherSky.enabled(context)
            val photo = st.wallpaper.isNotEmpty() && st.wallpaperOnHome
            val code = runCatching { HomeGreeting.weatherNow(context)?.code }.getOrNull()
            val sc = runCatching { com.openminis.app.ui.plus.WeatherSky.scene(context) }.getOrDefault(com.openminis.app.ui.plus.WeatherSky.lastScene)
            (on && !photo) to HomeFixCore.skyWhy(sc.zh, com.openminis.app.ui.plus.WeatherSky.lastMode.ifBlank { "shader" }, on, photo, code,
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }
        // [Mines-38.9.11 i32] wind → cloud / fog drift speed
        out += timed("云速随风") { true to com.openminis.app.ui.plus.WeatherSky.windRateLabel() }
        out += timed("首页天气") {
            val why = HomeGreeting.weatherWhy(context)
            (why == HomeFixCore.WxWhy.OK || why == HomeFixCore.WxWhy.STALE) to (why.line + (if (why == HomeFixCore.WxWhy.OK || why == HomeFixCore.WxWhy.STALE) "" else " → 首页显示「${HomeFixCore.weatherChip(why)}」"))
        }
        out += timed("网页登录诊断") { true to WebLoginDiag.lines() }
        out += timed("字号与缩放") {
            val sys = com.openminis.app.ui.theme.MinesDensity.system; val ap = com.openminis.app.ui.theme.MinesDensity.applied
            val fs = context.resources.configuration.fontScale
            true to "系统 ${"%.2f".format(sys)}x → 用 ${"%.2f".format(ap)}x（屏宽至少 ${HomeFixCore.MIN_WIDTH_DP.toInt()} dp）· 系统字体 ${"%.2f".format(fs)} → 用 ${"%.2f".format(minOf(fs, 1f))}"
        }
        out += timed("DSH 插件调用") {
            val f = java.io.File(DshWebHost.home(context), "status.json")
            if (!f.exists()) true to "还没装 DSH 插件（装了以后工具经 MCP「dsh」给对话调用）"
            else {
                val o = org.json.JSONObject(f.readText())
                val names = o.keys().asSequence().toList()
                val tools = names.sumOf { o.optJSONObject(it)?.optJSONArray("tools")?.length() ?: 0 }
                val okN = names.count { o.optJSONObject(it)?.optBoolean("ok") == true }
                (okN == names.size) to "$okN/${names.size} 个插件加载成功 · $tools 个工具可被对话调用（MCP: dsh）"
            }
        }
        // [Mines-38.9.7] 浏览器接管 / 无痕标签 / 高级工程模式
        out += timed("浏览器接管") {
            val shared = runCatching { (context.applicationContext as com.openminis.app.MinisApp).sharedBrowserTabPool }.getOrNull()
            val sel = shared?.selectedTabId?.value
            val lite = shared?.tabs?.value.orEmpty().map { com.openminis.app.browser.BrowserHandoffCore.TabLite(it.id, it.inUse, true) }
            val pick = com.openminis.app.browser.BrowserHandoffCore.pickAgentTab(sel, lite)
            val last = com.openminis.app.browser.BrowserHandoff.last
            val age = last?.let { (System.currentTimeMillis() - it.at) / 60_000 }
            val lastLine = if (last == null) "还没记下你打开的网页"
                else "记下了 ${last.origin}（${age} 分钟前${if (last.sessionJson != null) "，含会话存储" else ""}${if (last.desktop) "，电脑版" else ""}）"
            val pickLine = if (lite.isEmpty()) "首页浏览器还没开标签" else if (pick == sel) "AI 接手会用你正在看的标签 $sel" else "AI 接手用标签 $pick（你看的 $sel 正忙）"
            true to "首页浏览器 = 共享标签池 · $pickLine · $lastLine · 已接手 ${com.openminis.app.browser.BrowserHandoff.adopted} 次"
        }
        out += timed("无痕标签") {
            com.openminis.app.browser.IncognitoCore.selfCheck(com.openminis.app.browser.Incognito.supported(), com.openminis.app.browser.Incognito.lastClose)
        }
        out += timed("高级工程模式") {
            val on = com.openminis.app.tools.AgentToolSwitch.ENGINEER.isEnabled(context)
            true to (if (on) "已开（计划 → 先读再改 → 验证 → 不盲目重试），${com.openminis.app.agent.EngineerMode.PROMPT.length} 字规则加在系统提示末尾" else "已关（设置 › Agent 工具 › 高级工程模式）")
        }
        // [Mines-38.9.6 i17] tool-call arguments wrapped as {"arguments":{…}} / JSON strings are unwrapped before dispatch
        out += timed("工具参数解包") {
            val T = com.openminis.app.agent.ToolArgsCore
            val cases = listOf("""{"arguments":{"command":"ls"}}""", """{"args":"{\"command\":\"ls\"}"}""", """{"input":{"parameters":{"command":"ls"}}}""", "\"{\\\"command\\\":\\\"ls\\\"}\"")
            val ok = cases.count { T.missing("shell_execute", T.normalize(it).first).isEmpty() }
            (ok == cases.size) to "$ok/${cases.size} 种包法能解开（arguments / args 字符串 / 两层 / 整段字符串）"
        }
        // [Mines-38.9.6 i17] each server: direct / through the proxy / outside nodes — only all three failing is 「服务器挂了」
        progress("服务器连通")
        out += timed("服务器连通（直连/代理/外部）") {
            PlusPrefs.init(c)
            val servers = PlusPrefs.state.value.servers.filter { it.enabled }.take(3)
            if (servers.isEmpty()) return@timed true to "没有添加服务器"
            val rs = servers.map { sv -> sv to PlusVps.reachFor(sv, maxAgeMs = 0L) }
            rs.all { it.second.verdict.reachable } to rs.joinToString("；") { (sv, r) -> "${sv.name.ifBlank { sv.host }} ${sv.port}：${r.verdict.text}" }.take(400)
        }.let { ch -> if (!ch.ok && !ch.detail.contains("服务器挂了")) ch.copy(warn = true) else ch }
        // [Mines-38.9.6 i14] embedded Clash (mihomo): process up + controller answers; exit IP through its mixed port
        progress("Clash 内核")
        out += timed("Clash 内核运行") {
            if (!ClashCore.bundled(c)) return@timed false to "这个版本没有带 Clash 内核"
            if (!ClashCore.enabled(c)) return@timed true to "没开启（分流代理 › 内置 Clash 内核）"
            val (code, body) = ClashCore.api(c, "/version")
            (ClashCore.running() && code == 200) to (if (code == 200) "运行中 · ${Regex("\"version\"\\s*:\\s*\"([^\"]+)").find(body)?.groupValues?.get(1) ?: "mihomo"} · 控制接口 127.0.0.1 已加密钥"
                else "没在运行（${ClashCore.state.value}${ClashCore.lastExit.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}）")
        }.let { ch -> if (!ClashCore.enabled(c) && ClashCore.bundled(c)) ch.copy(warn = true) else ch }
        if (ClashCore.enabled(c)) out += timed("Clash 出口 IP") {
            val ip = ClashCore.exitIp()
            (ip != null) to (ip?.let { "出口 $it（经 Clash 127.0.0.1:${ClashConfigCore.MIXED_PORT}）" } ?: "经 Clash 查不到出口 IP：节点都连不上？")
        }
        // [Mines-38.9.8] Clash subscription source: what is imported, the phone's own Clash controller, the last download line
        out += timed("Clash 订阅") {
            val kind = ClashCore.importedKind(c)
            val phone = runCatching { ClashCore.phoneClash() }.getOrDefault("")
            val n = ClashCore.note.value
            (!n.startsWith("订阅下载失败")) to listOf("节点来源：$kind", n.takeIf { it.isNotBlank() }, phone, "没有 root：读不到 Clash App 的私有文件").filterNotNull().joinToString(" · ").take(400)
        }
        // [Mines-38.9.8] 免登录 AI（Mines）: every signed-in route is called for real (≤ 45 s each); dead ones are hidden from 模型组
        progress("免登录 AI 实测")
        out += timed("免登录 AI 实测") { FreeRoutes.testAll(c) }
        // [Mines-38.9.8] 自我地图 / 技能匹配 / 工作流记录
        out += timed("自我地图") {
            val snap = SelfMap.snapshot(c, refresh = false)
            PlusPrefs.init(c)
            PlusPrefs.state.value.servers.filter { it.enabled && it.host.isNotBlank() }.take(4).forEach { SelfMap.probeNow(c, it.id) }
            val snap2 = SelfMap.snapshot(c, refresh = false)
            val ports = snap2.servers.sumOf { it.ports.size }; val open = snap2.servers.sumOf { sv -> sv.ports.count { it.open } }
            true to "${snap.providers.size} 个供应商 · ${snap.groups.size} 个模型组 · ${snap2.servers.size} 台服务器（实测 $ports 个端口，通 $open 个）· 提示里 ${SelfMap.promptBlock(c).length} 字"
        }
        out += timed("技能匹配") {
            val on = com.openminis.app.tools.AgentToolSwitch.SKILL_FIRST.isEnabled(c)
            val cands = SkillMatch.candidates(c, "")
            true to (if (on) "已开：开工前自动找技能 / 工作流（现有 ${cands.count { it.kind == SkillMatchCore.Kind.SKILL }} 个技能、${cands.count { it.kind == SkillMatchCore.Kind.WORKFLOW }} 条可复用工作流），匹配到会在对话里显示「用技能：…」"
                else "已关（设置 › Agent 工具 › 技能优先）")
        }
        out += timed("工作流记录") {
            val l = WorkflowLog.list(c)
            true to (if (l.isEmpty()) "还没有记录（用工具做完一件事就会记一条）" else "${l.size} 条 · 最近：${l.first().task.lineSequence().firstOrNull().orEmpty().take(30)}（${WorkflowLogCore.statusLabel(l.first().status)}）· 已存成技能 ${l.count { it.skillId.isNotBlank() }} 条")
        }
        progress("MCP 服务器")
        // [Mines-38.9.4] issue #2 Bug 3: 「alive:false」 only means the stdio server is not running right now (they start on
        // demand). The check now really starts each enabled server (ping), makes sure Node.js / npx is in the sandbox first
        // (stdio servers from the 市场 run with npx), and shows each one's real error.
        out += timed("MCP 服务器") {
            val (code, text) = MinesJobs.runSync(c, "command -v minis-mcp-cli >/dev/null && timeout 25 minis-mcp-cli list 2>&1 | head -c 20000", 35_000)
            val servers = runCatching { org.json.JSONObject(text.substring(text.indexOf('{'))).optJSONArray("servers") }.getOrNull()
            if (servers == null) return@timed (code == 0) to (if (text.isBlank()) "没有输出（退出码 $code）" else text.lines().take(4).joinToString("；").take(300))
            val enabled = (0 until servers.length()).mapNotNull { servers.optJSONObject(it) }.filter { it.optBoolean("enabled", true) }
            if (enabled.isEmpty()) return@timed true to "没有启用的 MCP 服务器"
            val needNpx = enabled.any { it.optString("target") == "npx" || it.optString("target").endsWith("/npx") }
            val pre = if (needNpx) MinesJobs.runSync(c, "command -v npx >/dev/null && echo has-npx || (timeout 150 apk add --no-cache nodejs npm >/dev/null 2>&1 && command -v npx >/dev/null && echo installed-npx) || echo no-npx", 170_000).second else ""
            if (needNpx && pre.contains("no-npx")) return@timed false to "沙箱里装不上 Node.js（npx），MCP 服务器起不来：检查网络或镜像（设置 › 网络）"
            val lines = enabled.take(4).map { sv ->
                val name = sv.optString("name")
                if (!name.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) return@map Triple(name, false, "名字不合法")
                val (pc, pt) = MinesJobs.runSync(c, "timeout 60 minis-mcp-cli ping $name 2>&1 | head -c 4000", 70_000)
                val j = runCatching { org.json.JSONObject(pt.substring(pt.indexOf('{'))) }.getOrNull()
                val ok = pc == 0 && j != null && j.optString("error").isBlank() && j.optBoolean("ok", true)
                val why = j?.optString("error")?.ifBlank { null } ?: j?.optString("message")?.ifBlank { null } ?: pt.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                Triple(name, ok, if (ok) "能启动" else "起不来：" + why.take(120))
            }
            lines.all { it.second } to ((if (pre.contains("installed-npx")) "已装好 Node.js · " else "") + lines.joinToString("；") { "${it.first} ${it.third}" }).take(400)
        }
        out
    }

    /**
     * [Mines-38.9.4] issue #2: 「密钥 · OpenRouter」 failed after 9 s connecting to 127.12.175.112 (a fake-ip of the user's
     * proxy/VPN app) — a network / proxy failure, not a bad key: shown as a warning with the reason, never as 密钥失效.
     */
    fun netError(m: String): Boolean {
        val l = m.lowercase()
        return m.startsWith("测试时连不上服务器") || l.contains("failed to connect") || l.contains("timeout") || l.contains("timed out") ||
            l.contains("unable to resolve") || l.contains("connection reset") || l.contains("connection refused") || l.contains("ssl") || l.contains("handshake")
    }

    /** 127.x (not .0.0.1) / 198.18–19.x = fake-ip from a proxy app; 127.0.0.1 = local proxy port. */
    fun proxyHint(m: String): String {
        val ip = Regex("""/?(\d{1,3}(?:\.\d{1,3}){3})""").find(m)?.groupValues?.get(1).orEmpty()
        return when {
            ip.startsWith("198.18.") || ip.startsWith("198.19.") || (ip.startsWith("127.") && ip != "127.0.0.1") -> "代理 App 的 fake-ip $ip 没接通，检查代理或分流"
            ip == "127.0.0.1" -> "本机代理端口没接通"
            else -> "连不上服务器"
        }
    }

    // ---- redaction ----

    private val secretPatterns = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{12,}"""), Regex("""AIza[0-9A-Za-z_\-]{30,}"""), Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""), Regex("""hf_[A-Za-z0-9]{20,}"""), Regex("""pplx-[A-Za-z0-9]{20,}"""),
        Regex("""dop_v1_[a-f0-9]{20,}"""), Regex("""xai-[A-Za-z0-9]{20,}"""), Regex("""(?i)(bearer|token|key|password|passwd|secret)[=: ]+[^\s"',;]{8,}"""),
        Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"""), Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b"""),
    )
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    fun redact(s: String): String {
        var t = s
        secretPatterns.forEach { p -> t = p.replace(t) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "=[已隐去]" else "[已隐去]" } }
        t = email.replace(t) { AccountLabel.maskEmail(it.value) }
        PlusVault.entries.value.values.forEach { e -> PlusVault.secret(e.kindId)?.takeIf { it.length >= 8 }?.let { t = t.replace(it, "[已隐去]") } }
        return t
    }

    fun recentLogs(context: Context, lines: Int = 200): String {
        val sb = StringBuilder()
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "--pid=${android.os.Process.myPid()}", "*:W").redirectErrorStream(true).start()
            sb.append(p.inputStream.bufferedReader().readText().takeLast(12_000))
            p.waitFor(3, TimeUnit.SECONDS)
        }
        runCatching {
            File(context.filesDir, "logs").listFiles()?.maxByOrNull { it.lastModified() }?.let { f ->
                sb.append("\n--- app log ").append(f.name).append(" ---\n").append(f.readText().takeLast(6_000))
            }
        }
        runCatching { File(context.filesDir, "mines-last-crash.txt").takeIf { it.exists() }?.let { sb.append("\n--- last crash ---\n").append(it.readText().take(4000)) } }
        return redact(sb.toString())
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun issueBody(context: Context, checks: List<Check>, description: String?, screenshotUrl: String?): String {
        val sb = StringBuilder()
        sb.append("<!-- mines-report v1 -->\n")
        sb.append("**Build:** build-").append(com.openminis.app.BuildConfig.MINES_BUILD).append("  \n")
        sb.append("**Device:** ").append(device()).append("  \n")
        sb.append("**Time:** ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())).append("\n\n")
        if (!description.isNullOrBlank()) sb.append("### 用户描述\n").append(redact(description)).append("\n\n")
        if (screenshotUrl != null) sb.append("### 截图\n![screenshot](").append(screenshotUrl).append(")\n\n")
        if (checks.isNotEmpty()) {
            val fails = checks.count { !it.ok && !it.warn }
            sb.append("### 检查结果（").append(checks.size).append(" 项，失败 ").append(fails).append("）\n\n")
            sb.append("| 状态 | 检查 | 耗时 | 详情 |\n|---|---|---|---|\n")
            checks.forEach { ch ->
                sb.append("| ").append(if (ch.ok) "✅" else if (ch.warn) "⚠️" else "❌").append(" | ").append(ch.name.replace("|", "/"))
                    .append(" | ").append(ch.ms).append(" ms | ").append(redact(ch.detail).replace("|", "/").replace("\n", " ").take(200)).append(" |\n")
            }
            sb.append('\n')
        }
        sb.append("### 日志（已隐去密钥和邮箱）\n<details><summary>展开</summary>\n\n```\n")
        sb.append(recentLogs(context).takeLast(15_000).replace("```", "'''"))
        sb.append("\n```\n</details>\n")
        return sb.toString()
    }

    private fun ghToken(): String? = (PlusVault.defaultAccount("github")?.let { PlusVault.secret(it) } ?: PlusVault.secret("github"))?.takeIf { it.isNotBlank() }

    data class Filed(val ok: Boolean, val message: String, val url: String? = null)

    /** Optional screenshot → pushed to branch `mines-reports` (never the build branch, so no CI runs), linked in the issue. */
    private fun uploadScreenshot(token: String, png: ByteArray): String? = runCatching {
        fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
        val branch = "mines-reports"
        val has = http.newCall(req("https://api.github.com/repos/$REPO/git/ref/heads/$branch").build()).execute().use { it.code == 200 }
        if (!has) {
            val repoInfo = http.newCall(req("https://api.github.com/repos/$REPO").build()).execute().use { JSONObject(it.body!!.string()) }
            val def = repoInfo.optString("default_branch", "main")
            val sha = http.newCall(req("https://api.github.com/repos/$REPO/git/ref/heads/$def").build()).execute()
                .use { JSONObject(it.body!!.string()).getJSONObject("object").getString("sha") }
            http.newCall(req("https://api.github.com/repos/$REPO/git/refs").post(JSONObject().put("ref", "refs/heads/$branch").put("sha", sha).toString()
                .toRequestBody("application/json".toMediaType())).build()).execute().close()
        }
        val path = "reports/" + System.currentTimeMillis() + ".png"
        val body = JSONObject().put("message", "Mines bug report screenshot").put("branch", branch)
            .put("content", Base64.encodeToString(png, Base64.NO_WRAP))
        val ok = http.newCall(req("https://api.github.com/repos/$REPO/contents/$path").put(body.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.isSuccessful }
        if (ok) "https://github.com/$REPO/blob/$branch/$path?raw=true" else null
    }.getOrNull()

    suspend fun file(context: Context, title: String, checks: List<Check>, description: String? = null, screenshot: ByteArray? = null): Filed =
        withContext(Dispatchers.IO) {
            val token = ghToken() ?: return@withContext Filed(false, "还没连接 GitHub：去 账号连接 → GitHub 点一下「连接」")
            val shot = screenshot?.let { uploadScreenshot(token, it) }
            val body = issueBody(context, checks, description, shot)
            val json = JSONObject().put("title", title.take(120)).put("body", body.take(60_000)).put("labels", JSONArray().put(LABEL))
            runCatching {
                http.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/issues")
                    .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (r.isSuccessful) Filed(true, "已上报", JSONObject(t).optString("html_url"))
                    else Filed(false, "GitHub 拒绝了（HTTP ${r.code}）：" + t.take(160))
                }
            }.getOrElse { Filed(false, "上报失败：${it.message}") }
        }
}

/** [Mines-report-31] Agent tool: run the self-check, optionally file it to GitHub for Hark. */
object MinesSelfCheckTool {
    const val TOOL_NAME = "mines_selfcheck"

    fun definition() = com.openminis.app.data.model.AgentToolDefinition(
        name = TOOL_NAME,
        description = "Run Mines' on-device self-check (model providers, Gemini Live handshake, saved API keys, accessibility/Shizuku/overlay permissions, sandbox shell, MCP servers). " +
            "Set report=true to file the results with a redacted log excerpt as a GitHub issue (label mines-bug) for the developer, e.g. when the user says something is broken. " +
            "Use description for what the user reported.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "report" to com.openminis.app.data.model.AgentToolParam("boolean", "true = also file a GitHub issue with the results"),
            "description" to com.openminis.app.data.model.AgentToolParam("string", "Optional: the problem in the user's words"),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "report", "description"),
    )

    suspend fun run(ctx: Context, argsJson: String): com.openminis.app.tools.ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        val checks = MinesSelfCheck.runAll(ctx)
        val sb = StringBuilder()
        checks.forEach { sb.append(if (it.ok) "✓ " else if (it.warn) "! " else "✗ ").append(it.name).append(": ").append(MinesSelfCheck.redact(it.detail)).append('\n') }
        if (a.optBoolean("report", false)) {
            val fails = checks.filter { !it.ok && !it.warn }
            val f = MinesSelfCheck.file(ctx, "[AI 自检] " + (a.optString("description").take(50).ifBlank { "${fails.size} 项失败" }), checks, a.optString("description").ifBlank { null })
            sb.append(if (f.ok) "Filed: ${f.url}" else "Report failed: ${f.message}")
        }
        com.openminis.app.tools.ToolExecutionResult(sb.toString(), true)
    }.getOrElse { com.openminis.app.tools.ToolExecutionResult("Error: ${it.message}", false) }
}
