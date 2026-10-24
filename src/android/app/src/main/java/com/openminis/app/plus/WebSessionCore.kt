package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] user 2026-10-17 22:02 / 22:04 (Notion on 38.9.4): after signing in in the Mines browser the page still
 * said 「需要登录」, and the detail page contradicted itself (green 「已连接 · 网页登录」, red 「已连接 · 断开」, row 「未检查」).
 * Root causes: (1) 「我已登录好了」 / 「我在 Chrome 里登录好了」 marked the service signed in WITHOUT any check — a Google
 * sign-in finished in Chrome leaves its cookies in Chrome, never in Mines' WebView, so Mines had no session at all;
 * (2) the 网站登录 row read a cached record (status written once, never re-checked); (3) the hero read 「entry exists」 as
 * 「connected」.
 *
 * Now a web sign-in is judged by ONE rule: cookies flushed, then a real authenticated request with those cookies
 * (Notion: POST /api/v3/getSpaces). Pure decision logic here (unit-tested: WebSessionCoreTest); the request is in
 * [WebSession].
 */
object WebSessionCore {
    enum class St(val label: String) { SIGNED_IN("已登录"), SIGNED_OUT("未登录"), UNCHECKED("未检查"), CHECKING("检查中"), ERROR("检查失败") }

    /** How to ask a site 「am I signed in?」 with its cookies. */
    data class Probe(val url: String, val method: String = "GET", val body: String? = null,
                     /** the response is signed-in when [okWhen] says so (code, body) */
                     val kind: Judge = Judge.CODE_2XX_NO_LOGIN_REDIRECT)
    enum class Judge { NOTION_SPACES, CODE_2XX_NO_LOGIN_REDIRECT }

    /** Known probes; others fall back to the panel / site page (2xx and not redirected to a login page). */
    fun probeFor(kindId: String, loginUrl: String?, panelUrl: String?, site: String?): Probe? = when (kindId.substringBefore('#')) {
        "notion" -> Probe("https://www.notion.so/api/v3/getSpaces", "POST", "{}", Judge.NOTION_SPACES)
        else -> (panelUrl ?: site)?.takeIf { it.startsWith("http") }?.let { Probe(it) }
    }

    private val LOGIN_PATH = Regex("""(?i)/(login|signin|sign-in|sign_in|passport|auth/login|account/login)(\b|/|\?|$)""")

    /** One response → state. [location] = redirect target (not followed), [hasCookie] = the site's session cookie is there. */
    fun judge(p: Probe, code: Int, body: String, location: String?, hasCookie: Boolean): St = when (p.kind) {
        Judge.NOTION_SPACES -> when {
            code == 401 || code == 403 -> St.SIGNED_OUT
            code in 200..299 -> {
                val t = body.trim()
                // getSpaces answers {"<userId>": {"space": {...}, "notion_user": {...}}} when signed in, {} when not
                if (t.length > 2 && t.startsWith("{") && (t.contains("notion_user") || t.contains("\"space\""))) St.SIGNED_IN else St.SIGNED_OUT
            }
            code == 0 -> St.ERROR
            else -> if (!hasCookie) St.SIGNED_OUT else St.ERROR
        }
        Judge.CODE_2XX_NO_LOGIN_REDIRECT -> when {
            !hasCookie -> St.SIGNED_OUT
            code in 300..399 && location != null && LOGIN_PATH.containsMatchIn(location) -> St.SIGNED_OUT
            code == 401 || code == 403 -> St.SIGNED_OUT
            code in 200..399 -> St.SIGNED_IN
            code == 0 -> St.ERROR
            else -> St.ERROR
        }
    }

    /**
     * State transition shown on the page. A network ERROR never flips a confirmed state (no false 「登录失效」 on a blip),
     * except that it can't claim 已登录 for something never confirmed; CHECKING keeps the previous label underneath.
     */
    fun next(prev: St, result: St): St = when (result) {
        St.ERROR -> if (prev == St.SIGNED_IN || prev == St.SIGNED_OUT) prev else St.ERROR
        St.CHECKING -> St.CHECKING
        else -> result
    }

    /** What 「我已登录好了」 does: finish only on a confirmed session; otherwise stay on the page and say why. */
    fun finishAllowed(st: St): Boolean = st == St.SIGNED_IN

    /** Status line for the detail page hero (one truth for hero, row and button). */
    fun heroLine(st: St, method: String): String = when (st) {
        St.SIGNED_IN -> "已登录 · $method"
        St.SIGNED_OUT -> "未登录 · 点「登录」"
        St.CHECKING -> "正在检查登录…"
        St.ERROR -> "暂时检查不了（网络）"
        St.UNCHECKED -> "未检查"
    }

    /** Hint when the site was signed in through Google in Chrome (cookies stay in Chrome). */
    fun chromeHint(kindName: String): String =
        "在 Chrome 里用 Google 登录的 $kindName，Mines 读不到。请在 Mines 里选「用邮箱登录」，验证码会自动从你关联的邮箱读取；或者用 Integration Token。"
}
