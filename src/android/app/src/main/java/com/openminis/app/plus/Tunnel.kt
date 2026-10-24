package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * [Mines-38.9.13 tunnel_v1] 内网穿透 via the AWS hub (specs/tunnel.md).
 * Part 1: machines without a reachable port keep a reverse SSH tunnel to the hub; Mines reaches them through a
 * direct-tcpip channel of its hub session. Part 2 ([PhonePort]): a phone port published on the hub while switched on.
 * Flag off → no route, no UI, [TunnelState.of] = null.
 */
object Tunnel {
    /** One marked machine. Ports are on the hub. */
    data class Entry(
        val sshPort: Int,
        val localSsh: Int = 22,
        val webLocal: Int = 0,
        val webPort: Int = 0,
        val webPublic: Boolean = false,
        val pubKey: String = "",
    )

    private const val PREFS = "mines_tunnel"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile var app: Context? = null; private set

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries
    private val _hubId = MutableStateFlow("")
    val hubId: StateFlow<String> = _hubId
    /** serverId → (listening on hub, probed at) */
    private val _probe = MutableStateFlow<Map<String, Pair<Boolean, Long>>>(emptyMap())
    val probe: StateFlow<Map<String, Pair<Boolean, Long>>> = _probe
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(c: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            app = c.applicationContext
            _on.value = p.getBoolean("on", true)
            _hubId.value = p.getString("hub", "") ?: ""
            _entries.value = runCatching {
                val o = JSONObject(p.getString("entries", "{}") ?: "{}")
                o.keys().asSequence().associateWith { k ->
                    val e = o.getJSONObject(k)
                    Entry(e.getInt("ssh"), e.optInt("lssh", 22), e.optInt("lweb"), e.optInt("web"), e.optBoolean("pub"), e.optString("key"))
                }
            }.getOrDefault(emptyMap())
            prefs = p
        }
    }

    private fun save() {
        val o = JSONObject()
        _entries.value.forEach { (k, e) ->
            o.put(k, JSONObject().put("ssh", e.sshPort).put("lssh", e.localSsh).put("lweb", e.webLocal).put("web", e.webPort)
                .put("pub", e.webPublic).put("key", e.pubKey))
        }
        prefs?.edit()?.putString("entries", o.toString())?.putString("hub", _hubId.value)?.apply()
    }

    fun setOn(c: Context, v: Boolean) { init(c); _on.value = v; prefs?.edit()?.putBoolean("on", v)?.apply(); runCatching { PlusVps.syncToSandbox(c) } }
    fun setHub(c: Context, id: String) { init(c); _hubId.value = id; save(); runCatching { PlusVps.syncToSandbox(c) } }

    /** The hub: the chosen server, else the AWS one (18.143.139.29 / name with aws or siyun). */
    fun hub(): VpsServer? {
        val servers = PlusPrefs.state.value.servers.filter { it.host.isNotBlank() }
        servers.firstOrNull { it.id == _hubId.value }?.let { return it }
        return servers.firstOrNull { it.host == "18.143.139.29" }
            ?: servers.firstOrNull { it.name.contains("aws", true) || it.name.contains("siyun", true) }
    }

    fun entry(serverId: String): Entry? = if (_on.value) _entries.value[serverId] else null

    /** Mark a machine 「经 AWS 穿透」 (assigns its ports) or unmark it. */
    fun mark(c: Context, s: VpsServer, marked: Boolean) {
        init(c)
        val cur = _entries.value
        _entries.value = if (!marked) cur - s.id else if (s.id in cur) cur else {
            val port = TunnelCore.assign(cur.values.map { it.sshPort }, TunnelCore.SSH_PORTS) ?: return
            cur + (s.id to Entry(port, localSsh = s.port.takeIf { it > 0 } ?: 22))
        }
        save(); runCatching { PlusVps.syncToSandbox(c) }
    }

    /** Set the machine's web port (0 = none) and whether it is public; reassigns the hub port from the right range. */
    fun setWeb(c: Context, serverId: String, localPort: Int, public: Boolean) {
        init(c)
        val cur = _entries.value; val e = cur[serverId] ?: return
        val others = cur.filterKeys { it != serverId }.values
        val webPort = if (localPort <= 0) 0 else {
            val range = if (public) TunnelCore.PUBLIC_PORTS else TunnelCore.WEB_PORTS
            if (e.webPort in range && e.webPublic == public) e.webPort
            else TunnelCore.assign(others.map { it.webPort } + PhonePort.usedPorts(), range) ?: return
        }
        _entries.value = cur + (serverId to e.copy(webLocal = localPort.coerceAtLeast(0), webPort = webPort, webPublic = public && webPort > 0))
        save()
    }

    /** The route [VpsSsh.endpoints] adds for a marked machine (never for the hub itself). */
    fun endpoint(s: VpsServer): VpsSsh.Endpoint? {
        val e = entry(s.id) ?: return null
        val h = hub() ?: return null
        if (h.id == s.id) return null
        return VpsSsh.Endpoint("经 AWS 穿透", "127.0.0.1", e.sshPort, viaHub = true)
    }

    fun sandboxEntry(s: VpsServer): String? {
        val e = entry(s.id) ?: return null
        val h = hub()?.takeIf { it.id != s.id } ?: return null
        return TunnelCore.sandboxEntry(s.user, e.sshPort, h.user, VpsSsh.preferIpv4(h.host), h.port)
    }

    /** JSch proxy: the target's SSH stream is a direct-tcpip channel of the cached hub session (no local listener). */
    class HubProxy(private val hub: Session) : com.jcraft.jsch.Proxy {
        private var ch: ChannelDirectTCPIP? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
            val c = hub.openChannel("direct-tcpip") as ChannelDirectTCPIP
            c.setHost(host); c.setPort(port)
            input = c.inputStream; output = c.outputStream
            c.connect(if (timeout > 0) timeout else 15_000)
            ch = c
        }
        override fun getInputStream(): InputStream = input!!
        override fun getOutputStream(): OutputStream = output!!
        override fun getSocket(): Socket? = null
        override fun close() { runCatching { ch?.disconnect() } }
    }

    fun hubProxy(c: Context): com.jcraft.jsch.Proxy {
        val h = hub() ?: throw java.io.IOException("没有设置 AWS 中转服务器")
        // a hub problem (no key, changed fingerprint) must not stop the machine's other routes
        val sess = try { VpsSsh.session(c, h).first } catch (t: Throwable) { throw java.io.IOException("AWS 中转：" + VpsSsh.reason(t)) }
        return HubProxy(sess)
    }

    // -- install / apply / probe --------------------------------------------------------------------------------

    fun script(c: Context, s: VpsServer): String {
        val e = _entries.value[s.id] ?: throw IllegalStateException("先打开「经 AWS 穿透」")
        val h = hub() ?: throw IllegalStateException("没有设置 AWS 中转服务器")
        val tpl = c.assets.open("mines-tunnel/install.sh").use { it.readBytes().toString(Charsets.UTF_8) }
        return TunnelCore.installScript(tpl, h.user, VpsSsh.preferIpv4(h.host), h.port,
            TunnelCore.forwards(e.sshPort, e.localSsh, e.webPort, e.webLocal, e.webPublic))
    }

    /** The machine's key pasted by the user (install run by hand in a web console). */
    fun setPubKey(c: Context, serverId: String, pub: String) {
        init(c); val e = _entries.value[serverId] ?: return
        _entries.value = _entries.value + (serverId to e.copy(pubKey = pub.trim())); save()
    }

    fun keyLine(serverId: String): String? = _entries.value[serverId]?.takeIf { it.pubKey.isNotBlank() }?.let {
        TunnelCore.keyLine(it.pubKey, it.sshPort, it.webPort, it.webPublic)
    }

    /** [script] run as root when possible (root / passwordless sudo), else as the login user (installs a user loop). */
    private fun rootCmd(script: String): String {
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        return "T=\$(mktemp); echo $b64 | base64 -d > \$T; if [ \"\$(id -u)\" = 0 ]; then sh \$T; " +
            "elif sudo -n true 2>/dev/null; then sudo -n sh \$T; else sh \$T; fi; rm -f \$T"
    }

    // stop after the reply is out (Mines is often connected through the tunnel being stopped)
    private const val STOP = "echo MINES_STOPPED\n(sleep 2; N=mines-tunnel; systemctl disable --now \$N; rm -f /etc/systemd/system/\$N.service; " +
        "rc-service \$N stop; rc-update del \$N; rm -f /etc/init.d/\$N; pkill -f '[m]ines-tunnel/loop.sh'; pkill -f '[m]ines-tunnel/key'; " +
        "crontab -l | grep -v \$N/loop.sh | crontab -) >/dev/null 2>&1 </dev/null &\n"

    /**
     * 「开启穿透」: install the tunnel on [s] over the connection Mines already has (快速线路 / SSH / 中转). Re-running it
     * over the tunnel itself restarts the tunnel and may cut the reply; the key from the first run is kept.
     */
    suspend fun install(c: Context, s: VpsServer): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val out = VpsSsh.run(c, s, rootCmd(script(c, s)), 120_000, 1).first.output
            val pub = TunnelCore.pubFrom(out) ?: throw java.io.IOException(
                out.lines().lastOrNull { it.startsWith("MINES_ERR") }?.removePrefix("MINES_ERR ") ?: ("安装没成功：" + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120)))
            _entries.value[s.id]?.let { _entries.value = _entries.value + (s.id to it.copy(pubKey = pub)); save() }
            "已在这台机器上装好穿透（${out.lines().firstOrNull { it.startsWith("MINES_SVC") }?.removePrefix("MINES_SVC ") ?: "自启"}）。下一步：「应用到 AWS」"
        }
    }

    /** 「关闭穿透」: stop and remove the service on [s] (keeps its key, so 开启 again needs no new AWS line). */
    suspend fun uninstall(c: Context, s: VpsServer): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val r = VpsSsh.run(c, s, rootCmd(STOP), 30_000, 1).first
            if (!r.output.contains("MINES_STOPPED")) throw java.io.IOException(r.output.take(120))
            "已关闭这台机器上的穿透"
        }
    }

    /** 「应用到 AWS」: put the restricted line into the hub's authorized_keys (replacing an older line for the same key). */
    suspend fun applyToHub(c: Context, serverId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val line = keyLine(serverId) ?: throw IllegalStateException("先点「开启穿透」")
            val h = hub() ?: throw IllegalStateException("没有设置 AWS 中转服务器")
            val cmd = "umask 077; mkdir -p ~/.ssh; L=\$(cat); B=\$(printf '%s\\n' \"\$L\" | grep -o 'ssh-[a-z0-9-]* [A-Za-z0-9+/=]*'); " +
                "[ -n \"\$B\" ] || { echo MINES_ERR; exit 4; }; touch ~/.ssh/authorized_keys; " +
                "{ grep -vF \"\$B\" ~/.ssh/authorized_keys; printf '%s\\n' \"\$L\"; } > ~/.ssh/.mines-ak && " +
                "cat ~/.ssh/.mines-ak > ~/.ssh/authorized_keys && rm -f ~/.ssh/.mines-ak && echo MINES_OK"
            val (sess, _) = VpsSsh.session(c, h)
            val out = VpsSsh.exec(sess, cmd, 20_000, (line + "\n").toByteArray()).output
            if (!out.contains("MINES_OK")) throw java.io.IOException("写入 AWS 失败：" + out.take(120))
            "已加到 ${h.name.ifBlank { h.host }}，约 10 秒内连上"
        }
    }

    /** Real probe: is the machine's ssh port listening on the hub? Updates [probe] for every marked machine. */
    suspend fun probeAll(c: Context) = withContext(Dispatchers.IO) {
        init(c); PlusPrefs.init(c)
        if (!_on.value || _entries.value.isEmpty()) return@withContext
        val h = hub() ?: return@withContext
        val out = runCatching { VpsSsh.run(c, h, "ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null", 15_000, 1).first.output }.getOrNull()
        val now = System.currentTimeMillis()
        _probe.value = _entries.value.mapValues { (_, e) -> (out != null && TunnelCore.listening(out, e.sshPort)) to now }
    }

    @Volatile private var lastKick = 0L
    /** Background re-probe at most once a minute (used by [TunnelState.of]). */
    fun kick() {
        val c = app ?: return
        val now = System.currentTimeMillis()
        if (now - lastKick < 60_000) return
        lastKick = now
        scope.launch { runCatching { probeAll(c) } }
    }

    fun status(serverId: String): String {
        val p = _probe.value[serverId]
        return TunnelCore.status(entry(serverId) != null, p != null, p?.first == true)
    }

    // -- 「打开网页」 -----------------------------------------------------------------------------------------------

    private val webFwd = HashMap<String, Pair<Session, Int>>()

    /** Phone 127.0.0.1:L → hub 127.0.0.1:webPort over the hub session; returns the URL to open in Mines. */
    suspend fun webUrl(c: Context, serverId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val e = entry(serverId)?.takeIf { it.webPort > 0 } ?: throw IllegalStateException("先填网页端口并重新「开启穿透」")
            val h = hub() ?: throw IllegalStateException("没有设置 AWS 中转服务器")
            val sess = VpsSsh.session(c, h).first
            val local = synchronized(webFwd) {
                webFwd[serverId]?.takeIf { it.first === sess && sess.isConnected }?.second
                    ?: sess.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", e.webPort).also { webFwd[serverId] = sess to it }
            }
            "http://127.0.0.1:$local/"
        }
    }

    fun publicWebUrl(serverId: String): String? {
        val e = entry(serverId)?.takeIf { it.webPort > 0 && it.webPublic } ?: return null
        return "http://${hub()?.host ?: return null}:${e.webPort}/"
    }

    // -- Part 2: 「把手机端口公开」 --------------------------------------------------------------------------------

    object PhonePort {
        data class Pub(val localPort: Int, val remotePort: Int, val address: String)
        private val _state = MutableStateFlow<Pub?>(null)
        val state: StateFlow<Pub?> = _state
        private val _note = MutableStateFlow<String?>(null)
        val note: StateFlow<String?> = _note
        @Volatile private var sess: Session? = null

        fun usedPorts(): List<Int> = listOfNotNull(_state.value?.remotePort)

        suspend fun start(c: Context, localPort: Int): Result<Pub> = withContext(Dispatchers.IO) {
            runCatching {
                require(localPort in 1..65535) { "端口要在 1–65535" }
                stop(null)
                val h = hub() ?: throw IllegalStateException("没有设置 AWS 中转服务器")
                val used = _entries.value.values.filter { it.webPublic }.map { it.webPort }
                val remote = TunnelCore.assign(used, TunnelCore.PUBLIC_PORTS) ?: throw IllegalStateException("公开端口用完了")
                val s = VpsSsh.openAny(c, h)
                try { s.setPortForwardingR("0.0.0.0", remote, "127.0.0.1", localPort) } catch (t: Throwable) {
                    runCatching { s.disconnect() }; throw java.io.IOException("AWS 不让开这个端口（需要 GatewayPorts）：${t.message?.take(80)}")
                }
                sess = s
                val p = Pub(localPort, remote, "http://${h.host}:$remote/")
                _state.value = p; _note.value = null
                scope.launch { while (sess === s) { if (!s.isConnected) { stop("连接断了，已停止公开"); break }; delay(5_000) } }
                p
            }
        }

        fun stop(why: String? = null) {
            val s = sess; sess = null
            runCatching { s?.disconnect() }
            _state.value = null; _note.value = why
        }
    }
}
