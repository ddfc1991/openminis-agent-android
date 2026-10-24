package com.openminis.app.ui.plus

import android.content.Context
import com.openminis.app.plus.HumanVerify
import com.openminis.app.plus.KeyAgent
import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * [Mines-register-35.1] 「帮我注册」 inside 一键接入 (user 2026-10-14): when the user has no account at a service yet.
 * Order: the page's 「用 GitHub 登录」 when the phone is signed in to GitHub, then 「用 Google 登录」 (finished in Chrome),
 * else an e-mail sign-up with a password Mines generates and keeps encrypted in the account vault; e-mail / SMS codes
 * are filled by HumanVerify. One account per service, for the user only: no batch sign-ups, captchas are left to the
 * user (the page says 需要你点一下验证), no pay buttons, no paid terms.
 */
object RegisterHelper {
    private val github = listOf("Continue with GitHub", "Sign in with GitHub", "Sign up with GitHub", "Log in with GitHub", "使用 GitHub 登录", "GitHub 登录", "GitHub")
    private val google = listOf("Continue with Google", "Sign in with Google", "Sign up with Google", "Log in with Google", "使用 Google 登录", "Google 登录", "Google")
    private val signup = listOf("Sign up", "Sign Up", "Create account", "Create an account", "Register", "注册", "立即注册", "免费注册", "注册账号")
    private val submit = listOf("Sign up", "Create account", "Create Account", "Register", "Continue", "注册", "立即注册", "下一步", "Next")
    private val verify = listOf("Verify", "Continue", "Confirm", "Submit", "验证", "确认", "下一步", "提交")

    /** E-mails the user already uses in Mines (mail sources, account identities). */
    fun emails(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        runCatching { com.openminis.app.plus.MailSources.boxes(context).forEach { b -> Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").find(b.toString())?.let { out += it.value } } }
        PlusVault.entries.value.forEach { (id, e) ->
            listOfNotNull(e.account, PlusVault.extra(id, "identity"), PlusVault.extra(id, "login_email")).forEach { v ->
                Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$").find(v.trim())?.let { out += it.value }
            }
        }
        return out.toList().take(6)
    }

    private fun password(): String {
        val r = java.security.SecureRandom()
        val sets = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#%*-_")
        val chars = (sets.map { it[r.nextInt(it.length)] } + List(14) { val s = sets[r.nextInt(3)]; s[r.nextInt(s.length)] }).shuffled(r)
        return chars.joinToString("")
    }

    private fun clickJs(labels: List<String>) = "window.__mines.click(${org.json.JSONArray(labels)})"

    private fun fillJs(email: String, pw: String) = """(function(){
var setv=function(e,v){var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set){d.set.call(e,v);}else{e.value=v;}
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));};
var vis=function(e){var r=e.getBoundingClientRect();return r.width>2&&r.height>2&&!e.disabled&&!e.readOnly;};
var ins=[].slice.call(document.querySelectorAll('input')).filter(vis);var n=0;
var em=ins.find(function(e){var h=(e.type+' '+(e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();return e.type==='email'||h.indexOf('email')>=0||h.indexOf('邮箱')>=0;});
if(em){em.focus();setv(em,${JSONObject.quote(email)});n++;}
ins.filter(function(e){return e.type==='password';}).forEach(function(e){e.focus();setv(e,${JSONObject.quote(pw)});n++;});
var PAY=/pay|payment|charge|subscription|billing|付费|订阅|扣费|自动续费/i;
[].slice.call(document.querySelectorAll('input[type=checkbox]')).filter(vis).forEach(function(c){if(c.checked)return;
 var t=((c.closest('label')||c.parentElement||{}).innerText||'');if(/(terms|privacy|agree|条款|协议|隐私|同意)/i.test(t)&&!PAY.test(t)){c.click();}});
return String(n);})()"""

    private const val CODE_FIELD_JS = """(function(){var i=[].slice.call(document.querySelectorAll('input')).find(function(e){var r=e.getBoundingClientRect();if(r.width<3)return false;
var h=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('autocomplete')||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();
return h.indexOf('one-time-code')>=0||h.indexOf('otp')>=0||h.indexOf('code')>=0||h.indexOf('验证码')>=0||e.maxLength===1;});return i?'1':'';})()"""

    /** Runs the sign-up on the open page; [eval] runs JS with KeyAgent's helper; returns the line to show. */
    suspend fun run(context: Context, accountId: String, email: String, eval: suspend (String) -> String, status: (String) -> Unit): String {
        PlusVault.extra(accountId, "login_email")?.takeIf { it != "-" }?.let { return "这个服务已经用 $it 注册过了，不再重复注册。直接登录就好" }
        // 1) GitHub, when the phone is already signed in to GitHub
        val ghSession = runCatching { android.webkit.CookieManager.getInstance().getCookie("https://github.com").orEmpty().contains("logged_in=yes") }.getOrDefault(false)
        if (ghSession && eval(clickJs(github)).isNotBlank()) {
            return "已点「用 GitHub 登录」，用的是你手机上已登录的 GitHub。如果页面要你点「授权」，点一下就好，然后 Mines 会自动继续"
        }
        // 2) Google (finished in Chrome)
        if (eval(clickJs(google)).isNotBlank()) {
            return "已点「用 Google 登录」，Google 要在 Chrome 里完成。选你的账号后回到这里点「继续」"
        }
        // 3) e-mail sign-up
        status("正在打开注册页…")
        eval(clickJs(signup)); delay(2500)
        val pw = password()
        val n = eval(fillJs(email, pw)).toIntOrNull() ?: 0
        if (n == 0) return "没找到注册表单。请你点一下页面上的「注册」，然后再点「帮我注册」"
        PlusVault.setSecretExtra(accountId, "password", pw)
        PlusVault.setExtra(accountId, "login_email", email)
        status("已填好邮箱和密码（密码已加密保存在账号库），正在提交…")
        delay(800)
        eval(clickJs(submit)); delay(3000)
        // codes from mail / SMS
        HumanVerify.armOtp()
        val until = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < until) {
            val text = eval("window.__mines.text()")
            KeyAgent.needsUser(text)?.let { return it }
            if (eval(CODE_FIELD_JS).isNotBlank()) {
                status("在等验证码（邮件或短信会自动填）…")
                val code = HumanVerify.awaitCode(20_000)
                if (code != null) {
                    eval(HumanVerify.fillJs(code, null)); delay(600); eval(clickJs(verify))
                    status("验证码已填好，正在继续…"); delay(3500)
                    return "注册好了。Mines 接着去创建密钥"
                }
            } else if (Regex("(?i)check your (e-?mail|inbox)|verify your e-?mail|查收|验证邮件|激活").containsMatchIn(text)) {
                return "网站发了一封确认邮件到 $email。打开邮件点里面的确认链接，然后回来点「继续」"
            }
            delay(2500)
        }
        return "注册信息已提交。如果页面还要你做什么，按页面提示点一下，然后点「继续」"
    }
}
