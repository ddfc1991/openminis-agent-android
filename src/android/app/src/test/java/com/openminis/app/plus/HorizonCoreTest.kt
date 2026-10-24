package com.openminis.app.plus

import com.openminis.app.plus.HorizonCore.Kind
import com.openminis.app.plus.HorizonCore.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11 c] 每日简报 (Horizon pipeline on the phone): sources, parsers, URL merge, scores, dedup, balance, brief. */
class HorizonCoreTest {
    @Test fun sourceListRoundTripsAndDefaultsCoverHorizonKinds() {
        val d = HorizonCore.DEFAULTS
        for (k in listOf(Kind.RSS, Kind.HN, Kind.REDDIT, Kind.TELEGRAM, Kind.GITHUB, Kind.X, Kind.MINES)) assertTrue("default $k", d.any { it.kind == k })
        assertEquals(d, HorizonCore.decode(HorizonCore.encode(d)))
        assertEquals(d, HorizonCore.decode(null)); assertEquals(d, HorizonCore.decode("not json"))
        val one = listOf(HorizonCore.Source("a", Kind.REDDIT, "rust", "r/rust", "开发", on = false))
        assertEquals(one, HorizonCore.decode(HorizonCore.encode(one)))
    }

    @Test fun addSourceDetectsType() {
        assertEquals(Kind.REDDIT, HorizonCore.parseSourceLine("https://www.reddit.com/r/LocalLLaMA/")!!.kind)
        assertEquals("LocalLLaMA", HorizonCore.parseSourceLine("r/LocalLLaMA")!!.value)
        assertEquals(Kind.TELEGRAM, HorizonCore.parseSourceLine("https://t.me/s/durov")!!.kind)
        assertEquals("@karpathy", HorizonCore.parseSourceLine("https://x.com/karpathy")!!.value)
        assertEquals(Kind.X, HorizonCore.parseSourceLine("@sama")!!.kind)
        assertEquals("ollama/ollama", HorizonCore.parseSourceLine("github.com/ollama/ollama")!!.value)
        assertEquals("user:torvalds", HorizonCore.parseSourceLine("https://github.com/torvalds")!!.value)
        assertEquals(Kind.HN, HorizonCore.parseSourceLine("hn")!!.kind)
        val rss = HorizonCore.parseSourceLine("https://simonwillison.net/atom/everything/")!!
        assertEquals(Kind.RSS, rss.kind); assertEquals("simonwillison.net", rss.name)
        assertNull(HorizonCore.parseSourceLine("   ")); assertNull(HorizonCore.parseSourceLine("随便写点"))
    }

    @Test fun urlKeyIsConservative() {
        assertEquals(HorizonCore.urlKey("https://Example.com:443/a/b/?utm_source=x&id=3&fbclid=9"), HorizonCore.urlKey("https://example.com/a/b?id=3"))
        assertTrue(HorizonCore.urlKey("https://example.com/a?id=3") != HorizonCore.urlKey("https://example.com/a?id=4"))
        assertTrue(HorizonCore.urlKey("http://example.com/a") != HorizonCore.urlKey("https://example.com/a"))
    }

    @Test fun crossSourceMergeKeepsMostDiscussed() {
        val a = Raw("hn:1", Kind.HN, "Hacker News", "Llama 5", "https://ai.meta.com/llama5?utm_source=hn", "", 0, 500, 300, discussion = listOf("hn says"))
        val b = Raw("rd:1", Kind.REDDIT, "r/LocalLLaMA", "Llama 5 out", "https://ai.meta.com/llama5", "", 0, 200, 50, discussion = listOf("reddit says"))
        val c = Raw("rss:1", Kind.RSS, "Blog", "Other", "https://x.org/p", "", 0)
        val m = HorizonCore.mergeCrossSource(listOf(a, b, c))
        assertEquals(2, m.size)
        val l = m.first { it.id == "hn:1" }
        assertEquals(listOf("Hacker News", "r/LocalLLaMA"), l.sources); assertEquals(350, l.comments); assertTrue(l.discussion.containsAll(listOf("hn says", "reddit says")))
    }

    @Test fun parsesRssAtomHnRedditTelegramGithub() {
        val rss = """<?xml version="1.0"?><rss><channel><title>T</title><item><title><![CDATA[Hello &amp; world]]></title><link>https://a.com/1</link>
            <pubDate>Mon, 19 Oct 2026 10:00:00 GMT</pubDate><description>&lt;p&gt;Body&lt;/p&gt;</description></item></channel></rss>"""
        val r = HorizonCore.parseFeed(rss)
        assertEquals(1, r.size); assertEquals("Hello & world", r[0].title); assertEquals("https://a.com/1", r[0].link); assertEquals("Body", r[0].text); assertTrue(r[0].at > 0)
        val atom = """<feed><entry><title>v1.2.0</title><link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/v1.2.0"/>
            <updated>2026-10-19T08:00:00Z</updated><content type="html">Notes</content></entry></feed>"""
        val e = HorizonCore.parseFeed(atom).single()
        assertEquals("https://github.com/o/r/releases/tag/v1.2.0", e.link); assertEquals(java.time.Instant.parse("2026-10-19T08:00:00Z").toEpochMilli(), e.at)
        val hn = """{"hits":[{"objectID":"42","title":"Big thing","url":"https://b.com","points":300,"num_comments":120,"created_at_i":1760860800},
            {"objectID":"43","title":"Small","url":"https://c.com","points":20,"num_comments":2,"created_at_i":1760860800}]}"""
        val h = HorizonCore.parseHn(hn, 100, "科技")
        assertEquals(1, h.size); assertEquals("hn:42", h[0].id); assertEquals(300, h[0].points); assertEquals(1760860800000L, h[0].at)
        assertEquals(listOf("This is a long enough comment"), HorizonCore.parseHnComments("""{"children":[{"text":"<p>This is a long enough comment</p>"},{"text":"short"}]}"""))
        val rd = """{"data":{"children":[{"data":{"id":"abc","title":"Q &amp; A","permalink":"/r/x/comments/abc/q/","url_overridden_by_dest":"","selftext":"hi","created_utc":1760860800,"score":900,"num_comments":40}},
            {"data":{"id":"st","title":"rules","stickied":true,"permalink":"/r/x/st"}}]}}"""
        val d = HorizonCore.parseReddit(rd, "x", "AI")
        assertEquals(1, d.size); assertEquals("Q & A", d[0].title); assertEquals("https://www.reddit.com/r/x/comments/abc/q/", d[0].url)
        assertEquals(listOf("A useful comment about the post"), HorizonCore.parseRedditComments("""[{},{"data":{"children":[{"data":{"body":"A useful comment about the post"}},{"data":{"body":"[deleted]"}}]}}]"""))
        val tg = """<div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="durov/400"><div class="tgme_widget_message_text js-message_text" dir="auto">New <b>feature</b> today</div>
            <span class="tgme_widget_message_views">1.2K</span><time datetime="2026-10-19T09:00:00+00:00" class="time">09:00</time></div></div>"""
        val t = HorizonCore.parseTelegram(tg, "durov", "科技").single()
        assertEquals("tg:durov/400", t.id); assertEquals("New feature today", t.text); assertEquals("https://t.me/durov/400", t.url); assertEquals(1, t.points)
        val ev = """[{"id":"1","type":"ReleaseEvent","repo":{"name":"o/r"},"created_at":"2026-10-19T08:00:00Z","payload":{"release":{"tag_name":"v2","html_url":"https://github.com/o/r/releases/v2","body":"x"}}},
            {"id":"2","type":"PushEvent","repo":{"name":"o/r"},"created_at":"2026-10-19T08:00:00Z","payload":{}}]"""
        assertEquals("o/r 发布 v2", HorizonCore.parseGithubEvents(ev, "o", "开发").single().title)
        assertTrue(HorizonCore.parseHn("garbage", 0, "x").isEmpty()); assertTrue(HorizonCore.parseFeed("<html/>").isEmpty())
    }

    @Test fun heuristicScoreRewardsEngagementAndFollowsPenalisesPromo() {
        val now = 1_760_900_000_000L
        val big = Raw("1", Kind.HN, "HN", "Claude 5 released", "u", "", now - 3_600_000, 800, 400)
        val small = Raw("2", Kind.HN, "HN", "My weekend project", "u", "", now - 3_600_000, 30, 3)
        val promo = Raw("3", Kind.RSS, "B", "Giveaway: promo code inside", "u", "", now)
        assertTrue(HorizonCore.heuristicScore(big, emptyList(), now) > HorizonCore.heuristicScore(small, emptyList(), now))
        assertTrue(HorizonCore.heuristicScore(small, listOf("weekend"), now) > HorizonCore.heuristicScore(small, emptyList(), now))
        assertTrue(HorizonCore.heuristicScore(promo, emptyList(), now) < 2.0)
        assertTrue(HorizonCore.heuristicScore(big, emptyList(), now) <= 10.0)
    }

    @Test fun modelJsonIsParsedDefensively() {
        val a = HorizonCore.parseAnalysis("好的：```json\n[{\"i\":0,\"score\":8.5,\"reason\":\"重要\",\"summary\":\"s\",\"tags\":[\"a\",\"b\"],\"cat\":\"AI\"},{\"i\":9,\"score\":3},{\"i\":1,\"score\":\"x\"}]\n```", 2)
        assertEquals(1, a.size); assertEquals(8.5, a[0]!!.score, 1e-9); assertEquals("AI", a[0]!!.cat)
        assertTrue(HorizonCore.parseAnalysis("no json", 3).isEmpty())
        assertEquals(listOf(listOf(0, 2)), HorizonCore.parseDedup("""{"duplicates": [[0, 2], [2, 3], [5, 1]]}""", 4))
        assertTrue(HorizonCore.parseDedup("""{"duplicates": []}""", 4).isEmpty())
        val w = HorizonCore.parseEnrich("""[{"i":0,"title":"标题","summary":"摘要。","background":"背景。","discussion":""},{"i":1,"title":"","summary":"x"}]""", 2)
        assertEquals(1, w.size); assertEquals("", w[0]!!.discussion)
        assertTrue(HorizonCore.analysisPrompt(listOf(Raw("1", Kind.HN, "HN", "t", "u", "x", 0)), listOf("Claude")).contains("9-10"))
        assertTrue(HorizonCore.dedupPrompt(listOf("A" to "a")).contains("拿不准就当不重复"))
        assertTrue(HorizonCore.enrichPrompt(listOf(Raw("1", Kind.HN, "HN", "t", "u", "x", 0))).contains("discussion"))
    }

    @Test fun heuristicDedupGroupsSameStoryOnly() {
        val g = HorizonCore.heuristicDuplicates(listOf("OpenAI releases GPT-6 model today", "GPT-6 model releases today from OpenAI", "Rust 2.0 announced"))
        assertEquals(listOf(listOf(0, 1)), g)
        val merged = HorizonCore.applyGroups(listOf("a", "b", "c"), g) { p, d -> p + d.joinToString("") }
        assertEquals(listOf("ab", "c"), merged)
    }

    @Test fun balancedDigestCapsCategories() {
        val items = listOf("AI" to 9, "AI" to 8, "AI" to 7, "开发" to 6, "AI" to 5, "安全" to 4)
        assertEquals(listOf("AI" to 9, "AI" to 8, "开发" to 6, "安全" to 4), HorizonCore.balance(items, 10, 2) { it.first })
        assertEquals(2, HorizonCore.balance(items, 2, 5) { it.first }.size)
    }

    @Test fun briefRoundTripAndRender() {
        val b = HorizonCore.Brief("2026-10-20", 1L, listOf(
            HorizonCore.BriefItem("1", "Llama 5 发布", "Meta 发布了 Llama 5。", "上一代是 Llama 4。", "网友认为许可证仍有限制。", 8.6, "大版本", listOf("LLM"), "AI", "https://a", listOf("Hacker News", "r/LocalLLaMA"), 0),
            HorizonCore.BriefItem("2", "Rust 2.0", "s", "", "", 7.0, "", emptyList(), "开发", "", listOf("RSS"), 0)), 120, 2, "deepseek-chat", listOf("X 没读到"))
        val back = HorizonCore.briefFromJson(HorizonCore.briefToJson(b))
        assertNotNull(back); assertEquals(b, back)
        val md = HorizonCore.render(b)
        assertTrue(md.startsWith("# 每日简报 · 2026-10-20"))
        assertTrue(md.contains("## AI") && md.contains("**背景**：上一代是 Llama 4。") && md.contains("**网友讨论**：网友认为"))
        assertTrue(md.indexOf("## AI") < md.indexOf("## 开发"))
        assertTrue("no empty background line", !md.substringAfter("### Rust 2.0").contains("**背景**"))
        assertNull(HorizonCore.briefFromJson("x"))
    }

    @Test fun dueOncePerDayAfterSeven() {
        assertTrue(HorizonCore.due(null, "2026-10-20", 8)); assertTrue(HorizonCore.due("2026-10-19", "2026-10-20", 7))
        assertTrue(!HorizonCore.due("2026-10-20", "2026-10-20", 22)); assertTrue(!HorizonCore.due("2026-10-19", "2026-10-20", 6))
    }
}
