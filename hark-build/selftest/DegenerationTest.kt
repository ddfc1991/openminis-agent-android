import com.openminis.app.agent.DegenerationDetector
import java.io.File

// [Mines-degen-34] JVM self-test for the streaming degeneration detector.
// Normal text (Chinese, English, code, JSON, tables) must never trip; garbage must trip within ~2K chars.
fun feedAll(text: String, step: Int = 37): Pair<String?, Long> {
    val d = DegenerationDetector()
    var i = 0
    while (i < text.length) {
        val r = d.feed(text.substring(i, minOf(text.length, i + step)))
        if (r != null) return r to d.total
        i += step
    }
    return null to d.total
}

fun main(args: Array<String>) {
    var fail = 0; var pass = 0
    fun expect(name: String, text: String, bad: Boolean, within: Int = 2600) {
        val (r, at) = feedAll(text)
        val ok = if (bad) r != null && at <= within else r == null
        if (ok) pass++ else fail++
        println((if (ok) "PASS " else "FAIL ") + name + " -> " + (r ?: "ok") + (if (r != null) " @${at}" else ""))
    }
    val rnd = java.util.Random(7)
    // --- garbage (user's report style) ---
    val junk = StringBuilder("让我想想这个推特链接分享的问题。首先检查一下安全设置，然后")
    val alphabet = "LLLL_나L_yLL//////"
    while (junk.length < 30000) junk.append(alphabet[rnd.nextInt(alphabet.length)])
    expect("garbage LLLL_나L_y////", junk.toString(), true)
    expect("slash run", "好的，我来检查。" + "/".repeat(5000), true)
    expect("sentence loop", "开始分析。" + "我需要再检查一下这个链接是否安全，然后继续。".repeat(300), true)
    expect("english loop", "Okay. " + "Wait, let me re-check the URL encoding again. ".repeat(200), true)
    val sy = "#%&*(){}[]<>/|~^_=+-.,;:!?@"; val soup = StringBuilder(); while (soup.length < 6000) soup.append(sy[rnd.nextInt(sy.length)])
    expect("symbol soup", soup.toString(), true)
    val few = StringBuilder(); while (few.length < 6000) few.append("abLx_ "[rnd.nextInt(6)])
    expect("low diversity", few.toString(), true)
    // --- normal ---
    for (f in args) {
        val t = File(f).readText()
        expect("normal file " + File(f).name, t.take(60000), false)
    }
    val json = (1..400).joinToString(",", "[", "]") { """{"id":$it,"name":"item-$it","price":${it * 3 % 97}.5,"ok":${it % 2 == 0}}""" }
    expect("json list", json, false)
    val table = "| 名称 | 价格 | 备注 |\n|---|---|---|\n" + (1..200).joinToString("\n") { "| 商品$it | ¥${it * 7 % 300} | 第${it}行说明 ${(it * 31) % 17} |" }
    expect("markdown table", table, false)
    val think = "用户想分享推特链接，需要确认是否安全。" + (1..120).joinToString("") { "第${it}步：检查域名 t.co/${(it * 7919).toString(36)} 的跳转目标，看是否是 x.com 或已知的安全站点，再看 https 证书。" }
    expect("long varied thinking", think, false)
    println("RESULT pass=$pass fail=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
