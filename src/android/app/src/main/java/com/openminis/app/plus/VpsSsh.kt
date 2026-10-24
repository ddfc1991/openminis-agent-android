package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.openminis.app.sandbox.RootfsManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Mines-vps-link] The app's own SSH client (JSch), used for everything the
 * app does with a server itself: the first-login key install, health checks
 * that really authenticate, the server card / detail stats, and the relay
 * fallback. The agent's commands still go through `minis-vps` in the sandbox,
 * which reads the same key (written to ~/.ssh/minis_vps_app_key) and the same
 * relay settings.
 *
 * Link hardening, all applied here:
 *  - IPv4 first: the host is resolved and an IPv4 address is preferred (a
 *    phone on a broken IPv6 path otherwise waits out a connect timeout).
 *  - TCP keepalive + no-delay on the socket, SSH keepalive every 10 s with 3
 *    misses allowed, so a dead path is noticed in ~30 s instead of hanging.
 *  - One cached session per server, reused while alive, reopened with backoff
 *    when it drops.
 *  - Direct first, then the server's relay (WebSocket bridge, jump host,
 *    HTTP CONNECT or SOCKS5 proxy) when the direct path fails.
 *  - Host key pinned on first contact (TOFU); a changed key refuses to connect.
 */
object VpsSsh {
    private const val TAG = "VpsSsh"
    private const val PREFS = "mines_ssh_keys"
    private const val K_PRIV = "priv"
    private const val K_PUB = "pub"
    const val KEY_COMMENT = "mines@phone"

    class NoKeyException : Exception("还没有授权：点“授权”，输一次服务器密码")
    // [Mines-38.9.3] user 2026-10-17: after a server reinstall Mines only said 「java.io.IOException: End of…」 then
    // 「Auth fail for methods publickey,password」 — the pin was compared AFTER login, and the login (with the old key the
    // reinstalled box no longer has) failed first. Now the key is compared during key exchange ([PinRepo]) and a changed
    // key is reported as such, with one button 「信任新指纹」 ([trustNewHostKey]).
    class HostKeyChangedException(val fp: String) :
        Exception("服务器重装过，指纹变了（$fp）。确认是你重装的，点「信任新指纹」")

    @Volatile private var prefs: SharedPreferences? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val jumps = ConcurrentHashMap<String, Session>()

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)
                .also { prefs = it }
        }

    fun hasKey(context: Context): Boolean = prefs(context).getString(K_PRIV, null) != null

    fun publicKey(context: Context): String? = prefs(context).getString(K_PUB, null)

    /** The app's key pair (ECDSA P-256, generated once, stored encrypted). */
    @Synchronized
    fun ensureKey(context: Context): Pair<String, String> {
        val p = prefs(context)
        val priv = p.getString(K_PRIV, null)
        val pub = p.getString(K_PUB, null)
        if (priv != null && pub != null) return priv to pub
        // ECDSA rather than ed25519: Ed25519 in JSch needs Java 15 EdDSA or
        // BouncyCastle, neither of which every Android version has. P-256 is
        // in every Android JCA and OpenSSH accepts it everywhere.
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val privOut = ByteArrayOutputStream(); kp.writePrivateKey(privOut)
        val pubOut = ByteArrayOutputStream(); kp.writePublicKey(pubOut, KEY_COMMENT)
        kp.dispose()
        val a = privOut.toString("UTF-8"); val b = pubOut.toString("UTF-8").trim()
        p.edit().putString(K_PRIV, a).putString(K_PUB, b).apply()
        materializeKey(context)
        return a to b
    }

    /**
     * Write the key where the sandbox's `ssh` finds it (mode 600). The
     * encrypted copy in the app stays the source of truth; this file is
     * rewritten whenever the server list is published.
     */
    fun materializeKey(context: Context) {
        val p = prefs(context)
        val priv = p.getString(K_PRIV, null) ?: return
        val pub = p.getString(K_PUB, null) ?: return
        runCatching {
            val dir = File(RootfsManager.getInstance(context).rootfsDir, "root/.ssh")
            if (!dir.isDirectory) dir.mkdirs()
            runCatching { android.system.Os.chmod(dir.absolutePath, 448) } // 0700
            val f = File(dir, "minis_vps_app_key")
            if (!f.exists() || f.readText() != priv) f.writeText(priv)
            runCatching { android.system.Os.chmod(f.absolutePath, 384) }    // 0600
            File(dir, "minis_vps_app_key.pub").writeText(pub + "\n")
        }.onFailure { Log.w(TAG, "materializeKey: ${it.message}") }
    }

    /** IPv4 literal for [host] when it has one, else [host] unchanged. */
    fun preferIpv4(host: String): String = runCatching {
        InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull() ?: host

    /** Plain-words reason for a connection failure. */
    fun reason(t: Throwable): String {
        // [Mines-vps-31] a route summary from openAny is already in plain words (keep the VPN hint whole)
        t.message?.let { m -> if (m.contains("检测到代理") || m.startsWith("直连 ")) return m }
        var c: Throwable? = t
        while (c != null) {
            val m = (c.message ?: "").lowercase()
            when {
                c is NoKeyException || c is HostKeyChangedException -> return c.message ?: ""
                c is SocketTimeoutException || m.contains("timed out") || m.contains("timeout") ->
                    return "连接超时（端口可能被网络拦截）"
                c is UnknownHostException -> return "域名解析失败"
                m.contains("refused") -> return "端口拒绝连接（sshd 没开或端口不对）"
                m.contains("unreachable") -> return "网络不通"
                m.contains("reset") -> return "连接被重置（可能被网络干扰）"
                m.contains("auth fail") || m.contains("auth cancel") -> return "钥匙或密码不对"
                m.contains("banner") || m.contains("connection is closed") || m.contains("closed by foreign host") ->
                    return "握手被中断（可能被网络干扰）"
            }
            c = c.cause
        }
        return (t.message ?: t.javaClass.simpleName).take(80)
    }

    /** [38.9.11.2] for the direct-route socket factory (set on the first open) */
    @Volatile private var appCtx: Context? = null
    @Volatile var lastSocketRoute: String = ""; private set

    /** [38.9.11.2] JSch algorithm lists + Bouncy Castle ed25519 / curve25519 (SshNetCore.GLOBAL), once */
    private val algos by lazy { SshNetCore.GLOBAL.forEach { (k, v) -> runCatching { JSch.setConfig(k, v) } }; true }

    private fun underlying(c: Context): android.net.Network? = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            val nc = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrNull()

    /**
     * [38.9.11.2] SSH sockets go direct (never the app's proxy / split proxy): bound to the real network when a VPN app is
     * the default route, then the default route, and only if both fail a passing local proxy (SOCKS on its mixed port).
     */
    private object KeepAliveSockets : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val c = appCtx
            val net = c?.let { if (vpnActive(it)) underlying(it) else null }
            val routes = SshNetCore.routes(net != null, net != null, LocalProxy.best)
            var last: Throwable? = null
            for (r in routes) {
                val s = when (r) {
                    SshNetCore.Route.LOCAL_PROXY -> Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LocalProxy.best ?: continue)))
                    else -> Socket(java.net.Proxy.NO_PROXY)
                }
                try {
                    s.keepAlive = true; s.tcpNoDelay = true
                    if (r == SshNetCore.Route.UNDERLYING) net!!.bindSocket(s)
                    val addr = if (r == SshNetCore.Route.LOCAL_PROXY) InetSocketAddress.createUnresolved(host, port)
                        else if (r == SshNetCore.Route.UNDERLYING) InetSocketAddress(net!!.getAllByName(host).firstOrNull { it is Inet4Address } ?: net.getByName(host), port)
                        else InetSocketAddress(preferIpv4(host), port)
                    s.connect(addr, if (r == SshNetCore.Route.UNDERLYING) 8_000 else 15_000)
                    lastSocketRoute = r.label
                    return s
                } catch (t: Throwable) { last = t; runCatching { s.close() }; Log.w("VpsSsh", "ssh route ${r.name} failed: ${t.javaClass.simpleName}") }
            }
            throw (last as? IOException) ?: IOException(last?.message ?: "连不上")
        }
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private class Prompter(private val password: String?) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = password != null
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? = if (password == null) null else Array(prompt?.size ?: 0) { password }
    }

    /**
     * SSH over a WebSocket (e.g. `websocat --binary ws-l:0.0.0.0:8022
     * tcp:127.0.0.1:22`, which `minis-vps relay-setup` installs). Looks like
     * ordinary web traffic, works through HTTP-only paths and Cloudflare.
     */
    private class WsProxy(private val url: String) : Proxy {
        private var ws: okhttp3.WebSocket? = null
        private val inPipe = PipedInputStream(256 * 1024)
        private val inFeed = PipedOutputStream(inPipe)
        private val out = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val w = ws ?: throw IOException("relay closed")
                if (!w.send(okio.ByteString.of(*b.copyOfRange(off, off + len)))) throw IOException("relay closed")
            }
        }

        override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) {
            val opened = CountDownLatch(1)
            var failure: Throwable? = null
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
            ws = client.newWebSocket(okhttp3.Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { opened.countDown() }
                override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                    runCatching { inFeed.write(bytes.toByteArray()); inFeed.flush() }
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    runCatching { inFeed.write(text.toByteArray()); inFeed.flush() }
                }
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { runCatching { inFeed.close() } }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    failure = t; opened.countDown(); runCatching { inFeed.close() }
                }
            })
            if (!opened.await((if (timeout > 0) timeout else 15_000).toLong(), TimeUnit.MILLISECONDS)) {
                close(); throw SocketTimeoutException("relay timeout")
            }
            failure?.let { throw IOException("relay: ${it.message}", it) }
        }
        override fun getInputStream(): InputStream = inPipe
        override fun getOutputStream(): OutputStream = out
        override fun getSocket(): Socket? = null
        override fun close() { runCatching { ws?.close(1000, null) }; runCatching { inFeed.close() } }
    }

    private fun userPass(uri: URI): Pair<String?, String?> {
        val ui = uri.userInfo ?: return null to null
        return ui.substringBefore(':') to ui.substringAfter(':', "").ifEmpty { null }
    }

    private fun newJsch(context: Context, withKey: Boolean): JSch = JSch().apply {
        if (withKey) {
            val (priv, pub) = if (hasKey(context)) ensureKey(context) else throw NoKeyException()
            addIdentity("mines", priv.toByteArray(), pub.toByteArray(), null)
        }
    }

    /**
     * Open a fresh session to [s]. [viaRelay] false = direct, true = through
     * the server's relay. [password] non-null = password login (first-time
     * authorisation), else the app's key.
     */
    /** [Mines-vps-31] One way to reach the box: public IP + port, backup port, Tailscale name. */
    data class Endpoint(val label: String, val host: String, val port: Int, val ws: String? = null,
        /** [Mines-38.9.13 tunnel_v1] reach host:port through a direct-tcpip channel of the AWS hub session */ val viaHub: Boolean = false)

    /** Routes tried in order: main port → backup port (443 by default when main is 22) → Tailscale → (relay). */
    fun endpoints(s: VpsServer): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        Tunnel.endpoint(s)?.let { out += it }   // [Mines-38.9.13 tunnel_v1] marked 「经 AWS 穿透」: tried first
        // Cloudflare Tunnel (cloudflared ingress ssh://localhost:22): plain WebSocket, binary frames, on 443.
        cfUrl(s)?.let { out += Endpoint("Cloudflare 隧道", s.host, s.port, it) }
        out += Endpoint("直连 ${s.port}", s.host, s.port)
        val alt = if (s.altPort > 0) s.altPort else if (s.port == 22) 443 else 0
        if (alt > 0 && alt != s.port) out += Endpoint("备用端口 $alt", s.host, alt)
        // [Mines-vps-32] On the tailnet sshd listens on 22; 443 on a tailscale IP is
        // tailscaled itself (alvps showed 「其他」), so the Tailscale route always uses 22.
        if (s.tailscaleHost.isNotBlank()) out += Endpoint("Tailscale", s.tailscaleHost.trim(), 22)
        return out
    }

    fun cfUrl(s: VpsServer): String? = s.cfHost.trim().takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("wss://") || it.startsWith("ws://")) it else "wss://" + it.removePrefix("https://").trimEnd('/') + "/"
    }

    /** Which endpoint worked last per server (tried first next time). */
    private val goodRoute = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun lastRoute(serverId: String): String? = goodRoute[serverId]

    /** A system VPN (Clash / v2ray / …) is the phone's default route. */
    fun vpnActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    private fun preBanner(t: Throwable): Boolean {
        val r = reason(t)
        return r.startsWith("握手被中断") || r.startsWith("连接被重置") || r.startsWith("连接超时")
    }

    /** The one plain hint when a proxy app cuts SSH before the banner. */
    fun vpnHint(s: VpsServer) = "检测到代理，SSH 被切断：请在代理 App 里把 Mines 或 ${s.host} 设为直连"

    /**
     * [Mines-vps-31] Open over the first endpoint that answers. Fails over on
     * pre-banner closes / resets / timeouts (what a VPN cutting SSH looks
     * like); a wrong key / password or a changed host key stops at once.
     */
    fun openAny(context: Context, s: VpsServer, password: String? = null, timeoutMs: Int = 12_000): Session {
        Tunnel.init(context)   // [Mines-38.9.13 tunnel_v1]
        val eps = endpoints(s).let { l -> val g = goodRoute[s.id]; if (g == null) l else l.sortedBy { if (it.label == g) 0 else 1 } }
        val errs = ArrayList<String>()
        var anyPreBanner = false
        for (ep in eps) {
            try {
                val sess = open(context, s, false, password, timeoutMs, ep)
                goodRoute[s.id] = ep.label
                sess.setConfig("mines.route", ep.label)
                return sess
            } catch (e: NoKeyException) { throw e
            } catch (e: HostKeyChangedException) { throw e
            } catch (t: Throwable) {
                val r = reason(t)
                if (r.startsWith("钥匙或密码不对")) throw t
                if (preBanner(t)) anyPreBanner = true
                errs += "${ep.label}：$r"
            }
        }
        val hint = if (anyPreBanner && vpnActive(context)) "\n" + vpnHint(s) else ""
        throw IOException(errs.joinToString("；") + hint)
    }

    fun open(context: Context, s: VpsServer, viaRelay: Boolean, password: String? = null, timeoutMs: Int = 15_000, ep: Endpoint? = null): Session {
        appCtx = context.applicationContext; algos
        val jsch = newJsch(context, withKey = password == null)
        var host = preferIpv4(ep?.host ?: s.host)
        var port = ep?.port ?: s.port
        var proxy: Proxy? = if (ep?.viaHub == true) Tunnel.hubProxy(context) else if (ep?.ws != null) WsProxy(ep.ws) else null
        if (viaRelay) {
            val r = s.relay.trim()
            require(r.isNotEmpty()) { "没有设置中转" }
            val uri = URI(r)
            when (uri.scheme?.lowercase()) {
                "ws", "wss" -> proxy = WsProxy(r)
                "http" -> proxy = ProxyHTTP(uri.host, if (uri.port > 0) uri.port else 8080).apply {
                    val (u, p) = userPass(uri); if (u != null) setUserPasswd(u, p ?: "")
                }
                "socks5" -> proxy = ProxySOCKS5(uri.host, if (uri.port > 0) uri.port else 1080).apply {
                    val (u, p) = userPass(uri); if (u != null) setUserPasswd(u, p ?: "")
                }
                "ssh" -> {
                    val jump = jumpSession(context, uri, timeoutMs)
                    port = jump.setPortForwardingL(0, host, port)
                    host = "127.0.0.1"
                }
                else -> throw IllegalArgumentException("不认识的中转：$r")
            }
        }
        val session = jsch.getSession(s.user, host, port)
        if (password != null) session.setPassword(password)
        session.userInfo = Prompter(password)
        // Host key is checked against our own pin below (TOFU), so JSch's
        // known_hosts check is off; a mismatch still refuses to connect.
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications",
            if (password != null) "password,keyboard-interactive" else "publickey")
        session.setServerAliveInterval(15_000)   // [38.9] keepalive 15 s x 4
        session.setServerAliveCountMax(4)
        if (proxy != null) session.setProxy(proxy) else session.setSocketFactory(KeepAliveSockets)
        val repo = PinRepo(jsch)
        session.setHostKeyRepository(repo)
        try { session.connect(timeoutMs) } catch (t: Throwable) {
            // the host key seen in key exchange decides, whatever failed after it (auth with a key the new box lacks …)
            val old = prefs(context).getString("pin:${s.host}:${s.port}", null)
            if (SessionStatus.hostKeyChanged(old, repo.seen)) throw HostKeyChangedException(repo.seen ?: "")
            throw t
        }
        session.timeout = 0
        pinHostKey(context, s, session, jsch)
        return session
    }

    /** [38.9.11.2] smoke / 自检: key exchange only with [host]:[port] (fake user, no key) → negotiated host key, or the error */
    fun kexProbe(context: Context, host: String, port: Int): String {
        appCtx = context.applicationContext; algos
        val j = JSch()
        val sess = j.getSession("mines-probe", host, port)
        sess.setConfig("StrictHostKeyChecking", "no"); sess.setConfig("PreferredAuthentications", "password")
        sess.userInfo = Prompter("x"); sess.setSocketFactory(KeepAliveSockets)
        return try { sess.connect(12_000); "connected ${sess.hostKey?.type} route=$lastSocketRoute" }
        catch (t: Throwable) {
            val hk = runCatching { sess.hostKey?.type }.getOrNull()
            (if (hk != null) "kex ok hostkey=$hk route=$lastSocketRoute; " else "") + "error=" + (t.message ?: t.javaClass.simpleName).take(160)
        } finally { runCatching { sess.disconnect() } }
    }

    private fun jumpSession(context: Context, uri: URI, timeoutMs: Int): Session {
        val key = uri.toString()
        jumps[key]?.takeIf { it.isConnected }?.let { return it }
        appCtx = context.applicationContext; algos
        val jsch = newJsch(context, withKey = true)
        val j = jsch.getSession(uri.userInfo ?: "root", preferIpv4(uri.host), if (uri.port > 0) uri.port else 22)
        j.userInfo = Prompter(null)
        j.setConfig("StrictHostKeyChecking", "no")
        j.setConfig("PreferredAuthentications", "publickey")
        j.setServerAliveInterval(15_000); j.setServerAliveCountMax(4)
        j.setSocketFactory(KeepAliveSockets)
        j.connect(timeoutMs)
        jumps[key] = j
        return j
    }

    private fun pinHostKey(context: Context, s: VpsServer, session: Session, jsch: JSch) {
        val hk = session.hostKey ?: return
        val fp = hk.getFingerPrint(jsch)
        val k = "pin:${s.host}:${s.port}"
        val p = prefs(context)
        val old = p.getString(k, null)
        if (old == null) p.edit().putString(k, fp).apply()
        else if (old != fp) { session.disconnect(); throw HostKeyChangedException(fp) }
    }

    /** Records the host key fingerprint offered in key exchange; the decision is made by [open] (pin vs seen). */
    private class PinRepo(private val jsch: JSch) : com.jcraft.jsch.HostKeyRepository {
        @Volatile var seen: String? = null
        override fun check(host: String?, key: ByteArray?): Int {
            seen = runCatching { com.jcraft.jsch.HostKey(host ?: "", key).getFingerPrint(jsch) }.getOrNull()
            return com.jcraft.jsch.HostKeyRepository.OK
        }
        override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "mines-pins"
        override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
    }

    /** 「信任新指纹」: the user confirmed the reinstall — the old pin goes, the next login pins the new key. */
    fun trustNewHostKey(context: Context, s: VpsServer) { forgetPin(context, s); drop(s.id); runCatching { MinesLink.kick() } }

    /** Forget a server's pinned host key (used when the user edits/re-adds it). */
    fun forgetPin(context: Context, s: VpsServer) {
        prefs(context).edit().remove("pin:${s.host}:${s.port}").apply()
    }

    data class ExecResult(val exit: Int, val output: String)

    fun exec(session: Session, command: String, timeoutMs: Long = 20_000, stdin: ByteArray? = null): ExecResult {
        val ch = session.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.setInputStream(if (stdin != null) ByteArrayInputStream(stdin) else null)
        val buf = ByteArrayOutputStream()
        ch.setErrStream(buf, true)
        val input = ch.inputStream
        ch.connect(15_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        val tmp = ByteArray(8192)
        try {
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(tmp); if (n < 0) break; buf.write(tmp, 0, n)
                }
                if (ch.isClosed) {
                    while (input.available() > 0) { val n = input.read(tmp); if (n < 0) break; buf.write(tmp, 0, n) }
                    break
                }
                if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("command timed out")
                if (!session.isConnected) throw IOException("connection is closed")
                Thread.sleep(40)
            }
        } finally {
            ch.disconnect()
        }
        return ExecResult(ch.exitStatus, buf.toString("UTF-8"))
    }

    /**
     * Run [command] on [s] over the cached session, opening one (direct, then
     * relay) when there is none or it died, and retrying with backoff on a
     * dropped connection. Only for idempotent commands (stats, probes).
     * Returns the result plus whether the relay was used.
     */
    fun run(context: Context, s: VpsServer, command: String, timeoutMs: Long = 20_000, attempts: Int = 3): Pair<ExecResult, Boolean> {
        // [Mines-link-31] the HTTPS link first when paired (no SSH handshake for a middlebox to cut)
        MinesLink.init(context)
        MinesLink.conn(s)?.let { c ->
            val r = runCatching { kotlinx.coroutines.runBlocking { c.exec(command, ((timeoutMs + 999) / 1000).toInt(), if (c.online) 2_000 else 4_000) } }.getOrNull()
            if (r != null) return ExecResult(r.first, r.second) to false
        }
        var last: Throwable? = null
        var delay = 700L
        repeat(attempts) { i ->
            try {
                val (sess, relay) = session(context, s)
                return exec(sess, command, timeoutMs) to relay
            } catch (e: NoKeyException) { throw e
            } catch (e: HostKeyChangedException) { throw e
            } catch (t: Throwable) {
                last = t
                drop(s.id)
                if (i < attempts - 1) { Thread.sleep(delay); delay *= 3 }
            }
        }
        throw last ?: IOException("unreachable")
    }

    /** Live session for [s] (cached), with the route it took. */
    fun session(context: Context, s: VpsServer): Pair<Session, Boolean> {
        sessions[s.id]?.takeIf { it.isConnected }?.let { return it to (it.getConfig("mines.relay") == "1") }
        val direct = runCatching { openAny(context, s) }
        val sess: Session; val relay: Boolean
        if (direct.isSuccess) { sess = direct.getOrThrow(); relay = false }
        else {
            val e = direct.exceptionOrNull()!!
            if (e is NoKeyException || e is HostKeyChangedException || s.relay.isBlank()) throw e
            sess = try { open(context, s, viaRelay = true) } catch (t: Throwable) {
                throw IOException("${reason(e)}；中转：${reason(t)}", t)
            }
            relay = true
        }
        sess.setConfig("mines.relay", if (relay) "1" else "0")
        sessions.put(s.id, sess)?.let { old -> if (old !== sess) runCatching { old.disconnect() } }
        return sess to relay
    }

    fun drop(serverId: String) { sessions.remove(serverId)?.let { runCatching { it.disconnect() } } }

    fun dropAll() {
        sessions.values.forEach { runCatching { it.disconnect() } }; sessions.clear()
        jumps.values.forEach { runCatching { it.disconnect() } }; jumps.clear()
    }

    /**
     * First-time authorisation: log in with the password once, install the
     * app's public key into ~/.ssh/authorized_keys, then prove the key works
     * with a key-only login. The password is never stored.
     */
    fun authorize(context: Context, s: VpsServer, password: String): Result<String> = runCatching {
        val (_, pub) = ensureKey(context)
        // [Mines-vps-31] same route list as normal logins (22 → 443 → Tailscale), then the relay.
        val sess = try { openAny(context, s, password) } catch (t: Throwable) {
            if (s.relay.isBlank()) throw IOException(t.message ?: reason(t), t)
            try { open(context, s, true, password) } catch (t2: Throwable) { throw IOException((t.message ?: "") + "；中转：" + reason(t2), t2) }
        }
        try {
            val r = exec(sess,
                "umask 077; mkdir -p ~/.ssh ~/minis-jobs; k=\$(cat); touch ~/.ssh/authorized_keys; " +
                    "grep -qxF \"\$k\" ~/.ssh/authorized_keys || printf '%s\\n' \"\$k\" >> ~/.ssh/authorized_keys; " +
                    "chmod 700 ~/.ssh; chmod 600 ~/.ssh/authorized_keys; echo installed",
                20_000, (pub + "\n").toByteArray())
            if (!r.output.contains("installed")) throw IOException("写入钥匙失败：${r.output.take(120)}")
        } finally { sess.disconnect() }
        materializeKey(context)
        drop(s.id)
        val check = run(context, s, "echo ok", attempts = 2).first
        if (!check.output.contains("ok")) throw IOException("钥匙已写入，但用钥匙登录失败（服务器可能禁用了公钥登录）")
        "已授权，以后免密登录"
    }
}
