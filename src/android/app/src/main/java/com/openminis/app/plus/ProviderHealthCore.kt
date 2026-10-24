package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.11 i32] AI 服务商 page (user 2026-10-20 08:14: every row had a green dot whatever its real state). Each provider
 * gets a real check — one tiny chat through the same code path a conversation uses — and the page splits into 能用 / 不能用.
 * This file is the pure part: the check record, turning an error into one plain-Chinese reason + a kind, the 一键修复 plan,
 * staleness, the header summary. Unit-tested in ProviderHealthCoreTest; the phone side is [ProviderHealth].
 */
object ProviderHealthCore {
    /** a check older than this is re-run in the background when the page opens */
    const val TTL_MS = 6 * 3600_000L
    /** a failed check is re-run sooner (the user may have fixed it elsewhere) */
    const val TTL_BAD_MS = 30 * 60_000L

    enum class Kind(val zh: String) {
        OK("能用"), AUTH("密钥或登录失效"), LOGIN("要重新登录"), QUOTA("额度或余额用完"), RATE("请求太频繁"),
        MODEL("模型不可用"), NO_MODEL("没有模型"), NETWORK("连不上"), SERVER("服务器出错"), UNKNOWN("没成功")
    }

    data class Check(
        val id: String,
        val ok: Boolean,
        val kind: Kind,
        /** one plain-Chinese sentence for the user */
        val reason: String,
        /** the server's / exception's own words, folded under 「原始信息」 */
        val raw: String = "",
        val model: String = "",
        val ms: Long = 0,
        val at: Long = 0,
    )

    fun toJson(c: Check): JSONObject = JSONObject().put("id", c.id).put("ok", c.ok).put("kind", c.kind.name).put("reason", c.reason)
        .put("raw", c.raw).put("model", c.model).put("ms", c.ms).put("at", c.at)

    fun fromJson(o: JSONObject): Check? = runCatching {
        Check(o.getString("id"), o.optBoolean("ok"), runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.UNKNOWN),
            o.optString("reason"), o.optString("raw"), o.optString("model"), o.optLong("ms"), o.optLong("at"))
    }.getOrNull()

    fun stale(c: Check?, now: Long): Boolean = c == null || now - c.at > (if (c.ok) TTL_MS else TTL_BAD_MS) || c.at > now + 60_000L

    private fun hasZh(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    /** the Chinese sentence the provider layer already wrote, behind an English exception prefix, if any */
    private fun zhTail(m: String): String? {
        val t = m.replace(Regex("^(Invalid API key|Provider error|Transient error|Rate limited|Network error): ?"), "").trim()
        return t.takeIf { hasZh(it) }?.lines()?.firstOrNull { it.isNotBlank() }?.take(140)
    }

    /** error message (exception text) → kind + one plain-Chinese reason. Never returns raw English as the reason. */
    fun classify(msg: String?, oauth: Boolean = false): Pair<Kind, String> {
        val m = msg?.trim().orEmpty()
        val low = m.lowercase()
        val kind = when {
            m.isEmpty() -> Kind.UNKNOWN
            low.contains("timeout") || low.contains("timed out") || m.contains("没有回复") || m.contains("超时") -> Kind.NETWORK
            low.contains("unable to resolve host") || low.contains("unknownhost") || low.contains("failed to connect") ||
                low.contains("connection refused") || low.contains("network error") || low.contains("connection reset") ||
                low.contains("ssl") || m.contains("连不上") -> Kind.NETWORK
            m.contains("重新登录") || m.contains("还没登录") || m.contains("登录已失效") || m.contains("未连接") -> Kind.LOGIN
            low.contains("insufficient") || low.contains("balance") || low.contains("credit") || low.contains("quota") ||
                low.contains("402") || m.contains("余额") || m.contains("额度") || m.contains("欠费") -> Kind.QUOTA
            low.contains("429") || low.contains("rate limit") || low.contains("too many") || m.contains("太频繁") -> Kind.RATE
            low.contains("model_not_found") || low.contains("model not found") || low.contains("model_not_supported") ||
                low.contains("does not exist") || low.contains("404") || m.contains("模型") && (m.contains("不存在") || m.contains("用不了") || m.contains("不能用")) -> Kind.MODEL
            low.contains("401") || low.contains("403") || low.contains("invalid api key") || low.contains("unauthorized") ||
                low.contains("forbidden") || low.contains("incorrect api key") || m.contains("无效") || m.contains("没有权限") ->
                if (oauth) Kind.LOGIN else Kind.AUTH
            Regex("\\b5\\d\\d\\b").containsMatchIn(low) || low.contains("bad gateway") || low.contains("unavailable") -> Kind.SERVER
            else -> Kind.UNKNOWN
        }
        val reason = zhTail(m) ?: when (kind) {
            Kind.NETWORK -> "连不上服务器：检查网络或代理（这个服务在海外的话要开代理）"
            Kind.LOGIN -> "登录失效了：点「一键修复」重新登录"
            Kind.AUTH -> "API Key 无效或被停用：点「一键修复」换一个新的"
            Kind.QUOTA -> "额度或余额用完了：需要你自己充值，或换别的服务商（Mines 不会替你付钱）"
            Kind.RATE -> "请求太频繁，被暂时限流：过几分钟会自己恢复"
            Kind.MODEL -> "用来测试的模型在这个服务商那里不能用了：点进去刷新模型列表"
            Kind.SERVER -> "服务商的服务器出错了：一般过一会儿会恢复"
            Kind.NO_MODEL -> "还没有模型：点进去刷新模型列表"
            Kind.OK -> "能用"
            Kind.UNKNOWN -> "没成功，原因见下面的原始信息"
        }
        return kind to reason
    }

    /** what 「一键修复」 will try, in order (shown as the button's progress line) */
    enum class Step(val zh: String) { RETEST("重新检测"), REFRESH_LOGIN("刷新登录"), VAULT_KEY("换用保险箱里的新密钥"),
        REFRESH_MODELS("刷新模型列表"), RELOGIN("重新登录"), FETCH_KEY("自动去官网拿新密钥") }

    fun fixPlan(kind: Kind, oauth: Boolean, hasVaultKey: Boolean, hasKeyRecipe: Boolean): List<Step> = buildList {
        add(Step.RETEST)
        when {
            oauth -> { add(Step.REFRESH_LOGIN); add(Step.RELOGIN) }
            kind == Kind.MODEL || kind == Kind.NO_MODEL -> add(Step.REFRESH_MODELS)
            kind == Kind.AUTH || kind == Kind.UNKNOWN -> {
                if (hasVaultKey) add(Step.VAULT_KEY)
                if (hasKeyRecipe) add(Step.FETCH_KEY)
            }
            else -> {}
        }
    }

    /** fixable by tapping (a network / rate / quota / server problem is not: the button only re-tests) */
    fun fixable(kind: Kind): Boolean = kind != Kind.OK

    fun summary(ok: Int, bad: Int, checking: Int): String = buildString {
        append("能用 ").append(ok).append(" · 不能用 ").append(bad)
        if (checking > 0) append(" · 检测中 ").append(checking)
    }

    /** age line for a check (「刚刚检测」「12 分钟前检测」「3 小时前检测」) */
    fun ago(at: Long, now: Long): String {
        if (at <= 0) return "还没检测"
        val d = (now - at).coerceAtLeast(0)
        return when { d < 60_000 -> "刚刚检测"; d < 3_600_000 -> "${d / 60_000} 分钟前检测"; d < 86_400_000 -> "${d / 3_600_000} 小时前检测"; else -> "${d / 86_400_000} 天前检测" }
    }

    /** pick the model to test with: a cheap / small one first (fewest tokens), else the first visible */
    fun pickTestModel(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val cheap = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b|4.1)")
        val bad = Regex("(?i)(embed|tts|whisper|image|dall|vision-preview|audio|rerank|moderation|ocr)")
        val ok = ids.filterNot { bad.containsMatchIn(it) }.ifEmpty { ids }
        return ok.firstOrNull { cheap.containsMatchIn(it) } ?: ok.first()
    }

    /** a stable soft colour for a letter avatar (providers without a brand icon) */
    fun avatarHue(label: String): Float = ((label.hashCode() and 0x7fffffff) % 360).toFloat()

    fun initial(label: String): String = label.trim().firstOrNull()?.uppercase() ?: "?"
}
