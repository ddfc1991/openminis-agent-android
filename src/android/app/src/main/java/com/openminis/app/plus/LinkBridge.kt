package com.openminis.app.plus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [Mines-link-31] Loopback bridge so the sandbox's `minis-vps` can use the
 * app's HTTPS link (MinesLink) instead of its own ssh: the reconnecting /
 * resuming happens in the app, the sandbox keeps one local HTTP stream.
 *   127.0.0.1:8792  header X-Mines: $MINIS_LINK_TOKEN
 *   GET /ping · POST /exec?t=sec · POST /run?name= · GET /attach?name=&since= · GET /jobs · POST /stop?name=
 * Output of exec/run/attach ends with a line "__MINES_RC=<code>".
 */
object LinkBridge {
    const val PORT = 8792
    private const val TAG = "LinkBridge"
    val token: String = java.util.UUID.randomUUID().toString().replace("-", "")
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        runCatching {
            val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            server = ss
            Thread({
                while (!ss.isClosed) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { handle(app, sock) }.onFailure { Log.w(TAG, "req: ${it.message}") }; runCatching { sock.close() } }
                }
            }, "mines-link-bridge").apply { isDaemon = true; start() }
        }.onFailure { Log.w(TAG, "bridge not started: ${it.message}") }
    }

    private fun linkServer(id: String?): Pair<VpsServer, MinesLink.Conn>? {
        val list = PlusPrefs.state.value.servers.filter { it.enabled && MinesLink.parse(it.linkUrl) != null }
        val s = (if (!id.isNullOrBlank()) list.firstOrNull { it.id == id || it.name == id || it.host == id } else null) ?: list.firstOrNull() ?: return null
        val c = MinesLink.conn(s) ?: return null
        return s to c
    }

    private fun handle(app: Context, sock: Socket) {
        sock.soTimeout = 30_000
        val inp = sock.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
        val req = line().split(' ')
        val headers = HashMap<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val out = sock.getOutputStream()
        if (headers["x-mines"] != token) { reply(out, 403, "forbidden\n"); return }
        val path = req.getOrNull(1) ?: "/"
        val q = path.substringAfter('?', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) String(ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }, Charsets.UTF_8) else ""
        val target = linkServer(q["s"])
        when (path.substringBefore('?')) {
            "/ping" -> reply(out, 200, if (target != null && target.second.online) "pong ${target.first.name}\n" else "pong-offline\n")
            "/exec" -> {
                val (_, c) = target ?: return reply(out, 503, "no link\n")
                val r = runBlocking { c.exec(body, q["t"]?.toIntOrNull() ?: 600) }
                if (r == null) reply(out, 503, "link offline\n") else reply(out, 200, r.second + (if (r.second.endsWith("\n") || r.second.isEmpty()) "" else "\n") + "__MINES_RC=${r.first}\n")
            }
            "/run", "/attach" -> {
                val (_, c) = target ?: return reply(out, 503, "no link\n")
                val name = q["name"] ?: ("run-" + System.currentTimeMillis())
                out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray()); out.flush()
                val done = CountDownLatch(1); var rc = 1
                val onOut: (ByteArray) -> Unit = { b -> synchronized(out) { runCatching { out.write(b); out.flush() } } }
                val onExit: (Int) -> Unit = { code -> rc = code; done.countDown() }
                val ok = runBlocking {
                    if (path.startsWith("/run")) c.runJob(name, body, onOut, onExit) else c.attach(name, q["since"]?.toLongOrNull() ?: 0, onOut, onExit)
                }
                if (!ok) { out.write("\n__MINES_RC=255\n".toByteArray()); return }
                sock.soTimeout = 0
                // The phone link may drop and come back meanwhile; MinesLink resumes the stream.
                done.await(24, TimeUnit.HOURS)
                synchronized(out) { runCatching { out.write("\n__MINES_RC=$rc\n".toByteArray()); out.flush() } }
            }
            "/jobs" -> {
                val (_, c) = target ?: return reply(out, 503, "no link\n")
                val j = runBlocking { c.jobs() }
                if (j == null) reply(out, 503, "link offline\n") else reply(out, 200, j + "\n")
            }
            "/start" -> {
                val (_, c) = target ?: return reply(out, 503, "no link\n")
                val name = q["name"] ?: ("bg-" + System.currentTimeMillis())
                if (runBlocking { c.startOnly(name, body) }) reply(out, 200, "started $name\n") else reply(out, 503, "link offline\n")
            }
            "/stop" -> { target?.second?.stop(q["name"] ?: ""); reply(out, 200, "ok\n") }
            else -> reply(out, 404, "not found\n")
        }
    }

    private fun reply(out: OutputStream, code: Int, text: String) {
        val b = text.toByteArray()
        out.write("HTTP/1.0 $code X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }
}
