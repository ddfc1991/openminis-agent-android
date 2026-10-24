package com.openminis.app.plus

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-feed-32] Rewrites 消息 cards into a Chinese takeaway headline
 * ("这个仓库今天涨了 626 星：免费用 Claude 的方法"), a 2–4 line plain summary and
 * 「包含的内容」 (what Mines will do when the user taps 开始吧).
 *
 * The user said tokens are fine here (2026-10-13), so the best key they have
 * is used: DeepSeek (deepseek-chat) → 智谱 (glm-4-flash, free) → 硅基流动
 * (free Qwen2.5-7B). Batches of 12, results cached per card id, at most
 * [DAILY_CAP] calls a day; without a key or past the cap the rule-based
 * [fallback] text is used. Link previews (og:image) are plain HTTP, cached.
 */
object DigestWriter {
    private const val PREFS = "mines_digest_writer"
    private const val DAILY_CAP = 40
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS).build()

    data class Written(val h: String, val s: String, val steps: List<Pair<String, String>>)

    fun cachedFull(c: Context, id: String): Written? {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("h_" + id.hashCode(), null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val h = o.optString("h").takeIf { it.isNotBlank() } ?: return null
        val st = o.optJSONArray("d")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("t") to it.optString("s") } } } ?: emptyList()
        return Written(h, o.optString("s"), st)
    }

    fun cached(c: Context, id: String): Pair<String, String>? = cachedFull(c, id)?.let { it.h to it.s }

    /** Rule-based headline when no model text exists. */
    fun fallback(it: Digest.Item): Pair<String, String> {
        val h = when {
            it.source == "Trending" && it.stars != null -> "${it.title.substringAfter('/')} 今天涨了 ${it.stars} 星"
            it.cat == Digest.Cat.ACT -> "要你处理：" + it.title
            it.cat == Digest.Cat.DEAL -> "值得关注：" + it.title
            it.cat == Digest.Cat.LEARN -> "值得一看：" + it.title
            else -> it.title
        }
        val body = it.summary.replace(Regex("""^今日 \+[\d,]+ ★ · """), "")
        return h.take(60) to body
    }

    /** Rule-based 「包含的内容」 when the model gave none. */
    fun fallbackSteps(it: Digest.Item): List<Pair<String, String>> = when {
        it.act?.startsWith("kind:") == true && it.source == "免费额度" -> listOf(
            "打开官方页面" to "在 Mines 里打开活动页，你只需要登录", "创建并保存密钥" to "拿到后加密存好并测试一次", "设到期提醒" to "额度快到期时提前告诉你")
        it.act?.startsWith("kind:") == true -> listOf("检查这个账号" to "重新测试密钥或登录状态", "一键修好" to "能自动修的直接修，不能的告诉你一步怎么做")
        it.act?.startsWith("route:") == true -> listOf("测试所有线路" to "直连、备用端口、Tailscale、中转", "换成能用的那条" to "并说清楚是服务器还是手机的问题")
        it.source == "Trending" -> listOf("读懂这个仓库" to "用三句话说它能干什么、值不值得用", "给出上手步骤" to "要装什么、怎么跑起来")
        it.source == "GitHub" -> listOf("打开并总结" to "看清楚要你做什么", "起草回复或修复" to "发出去之前先给你看")
        else -> listOf("总结要点" to "用大白话说清楚", "给出下一步" to "需要你做的只留一步")
    }

    private fun endpoint(): Triple<String, String, String>? {
        PlusVault.defaultAccount("deepseek")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.deepseek.com/chat/completions", it, "deepseek-chat") }
        PlusVault.defaultAccount("zhipu")?.let { PlusVault.secret(it) }?.let { return Triple("https://open.bigmodel.cn/api/paas/v4/chat/completions", it, "glm-4-flash") }
        PlusVault.defaultAccount("siliconflow")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.siliconflow.cn/v1/chat/completions", it, "Qwen/Qwen2.5-7B-Instruct") }
        return null
    }

    /** Writes every card without text, in batches of 12. Blocking (IO). True when something new was cached. */
    fun writeAll(c: Context, items: List<Digest.Item>): Boolean {
        var any = false
        items.filter { it.brief == null && cachedFull(c, it.id) == null }.chunked(12).take(3).forEach { if (write(c, it)) any = true }
        return any
    }

    fun write(c: Context, todo: List<Digest.Item>): Boolean {
        if (todo.isEmpty()) return false
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val used = if (p.getString("day", "") == day) p.getInt("count", 0) else 0
        if (used >= DAILY_CAP) return false
        val (url, key, model) = endpoint() ?: return false
        p.edit().putString("day", day).putInt("count", used + 1).apply()
        val list = JSONArray()
        todo.forEachIndexed { i, it ->
            list.put(JSONObject().put("i", i).put("src", it.source).put("from", it.sender).put("cat", it.cat.label)
                .put("title", it.title.take(140)).put("text", it.summary.take(400)).put("n", it.merged))
        }
        val prompt = "你是用户的贴心助理 Mines。把下面每条消息改写成中文信息流卡片：\n" +
            "h：一句结论式标题，不超过 24 个字，直接说这件事对用户意味着什么（例如「这个仓库今天涨了 626 星：免费用 Claude 的方法」「Mines 构建 3 次，全部成功」），不要标题党、不要英文原标题直译堆砌；\n" +
            "s：2-4 句大白话摘要，不超过 110 个字，像朋友解释给一个不懂技术的人听；\n" +
            "d：2-3 个「Mines 可以帮你做的事」，每个 {t: 4-8 字动作, s: 一句说明}，只写 Mines 真能在手机/服务器上做的（打开、总结、起草、测试、领取、设提醒），付款类不写。\n" +
            "n 大于 1 表示多条同类消息已合并。只输出 JSON 数组 [{\"i\":序号,\"h\":\"…\",\"s\":\"…\",\"d\":[{\"t\":\"…\",\"s\":\"…\"}]}]。\n" + list.toString()
        val body = JSONObject().put("model", model).put("temperature", 0.4).put("max_tokens", 3500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val text = runCatching {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                JSONObject(r.body?.string().orEmpty()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }.getOrNull() ?: return false
        val arr = runCatching { JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)) }.getOrNull() ?: return false
        var n = 0
        val e = p.edit()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val it = todo.getOrNull(o.optInt("i", -1)) ?: continue
            val h = o.optString("h").trim().take(40); if (h.isBlank()) continue
            val d = JSONArray()
            o.optJSONArray("d")?.let { a -> for (j in 0 until minOf(3, a.length())) a.optJSONObject(j)?.let { x -> d.put(JSONObject().put("t", x.optString("t").take(16)).put("s", x.optString("s").take(60))) } }
            e.putString("h_" + it.id.hashCode(), JSONObject().put("h", h).put("s", o.optString("s").trim().take(240)).put("d", d).toString())
            n++
        }
        e.apply()
        return n > 0
    }

    // ---- link previews (og:image), plain HTTP, cached ----

    fun preview(c: Context, url: String): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("og_" + url.hashCode(), null)?.takeIf { it.startsWith("http") }

    fun previews(c: Context, items: List<Digest.Item>) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        for (it in items) {
            val u = it.url ?: continue
            if (p.contains("og_" + u.hashCode())) continue
            val img = runCatching {
                if (u.startsWith("https://github.com/") && it.source == "Trending") "https://opengraph.githubassets.com/1/" + u.removePrefix("https://github.com/")
                else http.newCall(Request.Builder().url(u).header("User-Agent", MINES_MOBILE_UA).header("Range", "bytes=0-65535").build()).execute().use { r ->
                    val html = r.body?.string().orEmpty().take(65536)
                    Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                        ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                }
            }.getOrNull()
            e.putString("og_" + u.hashCode(), img?.takeIf { it.startsWith("http") } ?: "-")
        }
        e.apply()
    }
}
