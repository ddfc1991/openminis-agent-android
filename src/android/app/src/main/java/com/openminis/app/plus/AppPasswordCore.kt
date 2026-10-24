package com.openminis.app.plus

/**
 * [Mines-38.9.11 b] 应用专用密码向导 (user 2026-10-20 08:47 queue #6): login codes from Gmail through IMAP + a Google app
 * password, one step at a time, no Cloudflare anywhere (the user has no CF account; a CF sign-up / login code that lands in
 * this Gmail is read the same way). Pure part, unit-tested in AppPasswordCoreTest.
 */
object AppPasswordCore {
    const val TWO_STEP_URL = "https://myaccount.google.com/signinoptions/twosv"
    const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"

    data class Step(val title: String, val body: String, val button: String?)

    val STEPS = listOf(
        Step("选要用的 Gmail", "填你要收验证码的 Gmail 地址。", null),
        Step("打开两步验证", "谷歌要求先开「两步验证」才能建应用专用密码。点下面按钮，在打开的页面里按提示开启；已经开了就直接点「下一步」。", "打开两步验证页面"),
        Step("建一个应用专用密码", "点下面按钮，在打开的页面里：名称填 Mines → 点「创建」→ 复制弹出的 16 位密码。回到 Mines 会自动填好。", "打开应用专用密码页面"),
        Step("粘贴并测试", "16 位密码已经填在下面（没有的话粘贴进去），点「测试并保存」：Mines 会真的登录一次 Gmail 收件箱。", null),
        Step("好了", "以后网站发到这个 Gmail 的验证码（包括 Cloudflare 注册 / 登录码）Mines 都会自己读出来填好。不需要 Cloudflare 账号。", null),
    )

    fun progress(i: Int): String = "第 ${i + 1} 步 / 共 ${STEPS.size} 步"

    /** The 16-letter app password from a copied text ("abcd efgh ijkl mnop", with any spaces / line breaks), or null. */
    fun parse(text: String?): String? {
        val t = text.orEmpty().lowercase().replace(Regex("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]+"), " ").trim()
        Regex("""(?<![a-z])([a-z]{4}) ?([a-z]{4}) ?([a-z]{4}) ?([a-z]{4})(?![a-z])""").find(t)?.let { m ->
            return m.groupValues.drop(1).joinToString("")
        }
        return null
    }

    fun isGmail(addr: String): Boolean = Regex("^[^@\\s]+@(gmail\\.com|googlemail\\.com)$").matches(addr.trim().lowercase())

    /** Result line after the inbox test: newest code mail or "inbox readable". */
    fun testLine(code: String?, from: String?, ageMin: Long?): String = when {
        code == null -> "收件箱能读了。现在还没有新的验证码邮件，以后来了会自动读。"
        else -> "收件箱能读了：最近一封验证码 $code" + (from?.takeIf { it.isNotBlank() }?.let { "（$it" + (ageMin?.let { a -> "，$a 分钟前）" } ?: "）") } ?: "")
    }
}
