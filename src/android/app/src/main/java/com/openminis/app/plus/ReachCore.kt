package com.openminis.app.plus

/**
 * [Mines-38.9.6 i17] Is a server down, or is only the phone's path to it broken? (user 2026-10-18 17:15: the agent said
 * 8.217.89.133 「全端口失联」 and 梦云 「SSH 断」 while both answered from outside.) A verdict needs three paths: direct from
 * the phone, through the user's proxy, and from outside (check-host.net nodes). One failed path never means 「服务器挂了」.
 * Pure (JVM-testable): the probing itself is in [ReachProbe].
 */
object ReachCore {
    enum class Kind { OK_DIRECT, OK_PROXY, PHONE_PATH, DOWN, UNKNOWN }

    /** null = path not tried / no answer (e.g. no proxy configured, outside check unavailable) */
    data class Paths(val direct: Boolean?, val proxy: Boolean?, val outsideOk: Int = 0, val outsideTotal: Int = 0,
                     val directWhy: String = "", val proxyWhy: String = "")

    data class Verdict(val kind: Kind, val text: String) {
        /** only this verdict lets anyone say the server itself is down */
        val serverDown: Boolean get() = kind == Kind.DOWN
        val reachable: Boolean get() = kind == Kind.OK_DIRECT || kind == Kind.OK_PROXY
    }

    fun verdict(p: Paths): Verdict {
        val outside = if (p.outsideTotal > 0) "外部检测 ${p.outsideOk}/${p.outsideTotal} 个节点连得上" else "外部检测没结果"
        fun d() = "直连" + when (p.direct) { true -> "通"; false -> "不通" + why(p.directWhy); null -> "没测" }
        fun x() = "走代理" + when (p.proxy) { true -> "通"; false -> "不通" + why(p.proxyWhy); null -> "（没设代理）" }
        return when {
            p.direct == true -> Verdict(Kind.OK_DIRECT, "在线（直连通）")
            p.proxy == true -> Verdict(Kind.OK_PROXY, "在线（${d()}，走代理通）")
            p.outsideTotal > 0 && p.outsideOk > 0 -> Verdict(Kind.PHONE_PATH, "手机网络到不了，服务器本身在线（${d()}，${x()}，$outside）")
            p.outsideTotal >= 2 && p.outsideOk == 0 && p.direct == false ->
                Verdict(Kind.DOWN, "服务器挂了或端口关了（${d()}，${x()}，$outside）")
            else -> Verdict(Kind.UNKNOWN, "暂时没法判断（${d()}，${x()}，$outside），不能说服务器挂了")
        }
    }

    private fun why(s: String) = if (s.isBlank()) "" else "：" + s.take(40)

    /**
     * check-host.net /check-result/<id> JSON → (ok, answered, pending). Each node: null = still running,
     * [[{"time":…,"address":…}]] / [{"time":…}] = connected, [{"error":"…"}] = failed.
     */
    fun parseCheckHost(json: String): Triple<Int, Int, Int> {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return Triple(0, 0, 0)
        var ok = 0; var answered = 0; var pending = 0
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v == null || v == org.json.JSONObject.NULL) { pending++; continue }
            val arr = v as? org.json.JSONArray ?: continue
            var first: Any? = if (arr.length() > 0) arr.opt(0) else null
            if (first is org.json.JSONArray) first = if (first.length() > 0) first.opt(0) else null
            val e = first as? org.json.JSONObject
            answered++
            if (e != null && e.has("time") && !e.has("error")) ok++
        }
        return Triple(ok, answered, pending)
    }
}
