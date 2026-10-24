package com.openminis.app.plus

/**
 * [Mines-38.9.13 handoff_v2.oauth] Does a sign-up page offer an e-mail / phone way in, or only 「Continue with Google」 style
 * buttons? Auto-register uses e-mail / phone first and goes to a third party only when it is the page's only option
 * (Google / Apple refuse embedded WebViews; that session would stay in Chrome). Pure (OAuthOnlyCoreTest).
 */
object OAuthOnlyCore {
    enum class Kind { EMAIL, OAUTH_ONLY, UNKNOWN }

    val THIRD_PARTY = Regex("(?i)(google|apple|github|gitlab|bitbucket|microsoft|facebook|twitter|discord|wechat|微信|\\bqq\\b|微博|\\bx\\b)")
    private val EMAIL_ENTRY = Regex("(?i)(e-?mail|邮箱|手机|phone|短信|\\bsms\\b)")

    /** [hasCredentialInput] = a visible e-mail / phone / password input; [labels] = visible button / link texts */
    fun detect(hasCredentialInput: Boolean, labels: List<String>): Kind {
        if (hasCredentialInput) return Kind.EMAIL
        if (emailEntry(labels) != null) return Kind.EMAIL
        return if (labels.any { THIRD_PARTY.containsMatchIn(it) && it.length <= 40 }) Kind.OAUTH_ONLY else Kind.UNKNOWN
    }

    /** the 「Continue with Email」 / 「邮箱注册」 button to press, or null */
    fun emailEntry(labels: List<String>): String? =
        labels.firstOrNull { it.length <= 40 && EMAIL_ENTRY.containsMatchIn(it) && !THIRD_PARTY.containsMatchIn(it) }

    /** visible inputs + button / link texts of the page, as {"input":bool,"labels":[…]} */
    const val PAGE_JS = """(function(){try{
var vis=function(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!=='hidden';};
var inp=[].slice.call(document.querySelectorAll('input')).some(function(e){if(!vis(e)||e.disabled)return false;var h=((e.type||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.autocomplete||'')+' '+(e.placeholder||'')).toLowerCase();
 return e.type==='email'||e.type==='password'||e.type==='tel'||/email|phone|mobile|邮箱|手机/.test(h);});
var l=[].slice.call(document.querySelectorAll('button,a,[role=button]')).filter(vis).map(function(e){return (e.innerText||e.getAttribute('aria-label')||'').trim().slice(0,60);}).filter(function(t){return t;}).slice(0,60);
return JSON.stringify({input:inp,labels:l});}catch(x){return '{}';}})()"""
}
