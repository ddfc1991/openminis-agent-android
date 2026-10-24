package com.openminis.app.plus

/**
 * [Mines-keys-31] Smart API-key acquisition.
 *
 * The user only logs in. After that Mines drives the provider's own key page
 * (in-app WebView with injected JS; Google pages in Chrome through the
 * accessibility service), creates a key, reads it straight off the page,
 * saves it encrypted, tests it with one cheap call and shows ✓.
 *
 * It stops and asks exactly one simple thing when a step really needs the
 * user (SMS code, CAPTCHA, payment) and never clicks anything that pays.
 * Console URLs checked 2026-10-13 against the providers' docs:
 *  - Gemini: aistudio.google.com/apikey
 *  - Mistral: console.mistral.ai → API Keys → Create new key (Free mode, no card)
 *  - Perplexity: console.perplexity.ai/project/settings (project first) → /project/keys (pay-as-you-go, needs credit)
 *  - Hugging Face: huggingface.co/settings/tokens/new
 *  - DigitalOcean: cloud.digitalocean.com → Account → API → Generate New Token
 *  - Cloudflare: dash.cloudflare.com/profile/api-tokens
 *  - GitHub: github.com/settings/tokens/new (classic, scopes via query)
 *  - 阿里云百炼: bailian.console.aliyun.com/cn-beijing/model/settings/api-key → 创建 API Key
 */
object KeyAgent {
    enum class Mode { WEBVIEW, CHROME_A11Y }

    /** One step: click the first element whose text matches any label; or fill the name field. */
    data class Step(val click: List<String> = emptyList(), val fill: String? = null, val optional: Boolean = false)

    data class Recipe(
        val kindId: String,
        val mode: Mode,
        val url: String,
        val keyRegex: Regex,
        val steps: List<Step>,
        /** A page-text hint that the user still has to sign in. */
        val loginHints: List<String> = listOf("Sign in", "Log in", "登录", "Continue with Google", "Sign up"),
        /** Plain note shown before starting. */
        val note: String = "",
    )

    private val nameField = "Mines"
    /** [Mines-fix-35.1] the page listing existing keys (to delete the old one). */
    fun listUrl(kindId: String): String {
        val base = PlusVault.baseId(kindId)
        return when (base) {
            "huggingface" -> "https://huggingface.co/settings/tokens"
            "github" -> "https://github.com/settings/tokens"
            else -> recipe(base)?.url?.substringBefore("/new")?.substringBefore("?") ?: "about:blank"
        }
    }

    /** Red outline around the list row that mentions [name] (the old token), scrolled into view. Never clicks. */
    fun highlightJs(name: String): String = """(function(){var w=${org.json.JSONObject.quote(name)};var best=null;
[].slice.call(document.querySelectorAll('tr,li,[role=row],div')).forEach(function(e){var t=(e.innerText||'');if(t.indexOf(w)>=0&&t.length<400&&(!best||t.length<best.innerText.length))best=e;});
if(best){best.style.outline='3px solid #E5484D';best.style.outlineOffset='2px';best.scrollIntoView({block:'center'});return 'ok';}return '';})()"""

    /** [Mines-fix-35.1] key / token names carry the date so the user can tell them apart on the website. */
    /** [Mines-38.9.1] desktop-only consoles: open their key page in 电脑版 from the start (they are blank with a phone UA). */
    private val DESKTOP_HOSTS = listOf("console.volcengine.com", "bailian.console.aliyun.com", "console.aliyun.com", "cloud.siliconflow.cn",
        "cloud.siliconflow.com", "platform.openai.com", "console.cloud.tencent.com", "console.bce.baidu.com", "platform.moonshot.cn", "open.bigmodel.cn")
    fun desktopFirst(url: String): Boolean = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("").let { h -> DESKTOP_HOSTS.any { h == it || h.endsWith(".$it") } }

    /** The same Chrome version as the phone's WebView, as a Windows desktop browser (kept constant per install: no random UA). */
    fun desktopUa(c: android.content.Context): String {
        val real = runCatching { android.webkit.WebSettings.getDefaultUserAgent(c.applicationContext) }.getOrNull().orEmpty()
        val v = Regex("""Chrome/([0-9]+)""").find(real)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v.0.0.0 Safari/537.36"
    }

    /** Closes a nav drawer / menu the page left open after a click (Escape, then the overlay / close button). */
    const val CLOSE_DRAWER_JS = """(function(){try{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,bubbles:true}));
var d=[].slice.call(document.querySelectorAll('[role=dialog][data-state=open],[data-state=open][role=menu],nav[data-state=open],aside[data-state=open]')).filter(function(e){return !/key|密钥|token/i.test(e.innerText||'')||/sign out|退出|playground/i.test(e.innerText||'');});
d.forEach(function(e){var c=e.querySelector('[aria-label*=lose],[aria-label*=关闭],button[data-dismiss]');if(c)c.click();});
var o=document.querySelector('[data-state=open][data-aria-hidden],.backdrop,[class*=overlay][data-state=open]');if(o&&d.length)o.click();return String(d.length);}catch(e){return '';}})()"""

    fun tokenName(fill: String): String = if (fill == nameField)
        "mines-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date()) else fill

    /** [Mines-keys-35.3] a key-looking token (services without a known key format) — only taken after Mines clicked. */
    val GENERIC_KEY = Regex("""(?<![A-Za-z0-9_\-])[A-Za-z0-9][A-Za-z0-9_\-\.]{31,}(?![A-Za-z0-9_\-])""")

    val recipes: Map<String, Recipe> = listOf(
        Recipe("gemini", Mode.CHROME_A11Y, "https://aistudio.google.com/apikey",
            Regex("""AIza[0-9A-Za-z_\-]{35}"""),
            listOf(
                Step(listOf("Create API key", "创建 API 密钥", "创建 API key", "Get API key")),
                Step(listOf("Create API key in new project", "在新项目中创建 API 密钥", "Create key", "创建密钥", "Create"), optional = true),
            ),
            note = "Google 只能在 Chrome 里登录；需要打开 Mines 的无障碍，Mines 才能在 Chrome 里替你点。"),
        // [Mines-38.1b] 扣子 (user 2026-10-14 「点扣子看不到 key」): www.coze.cn/open/oauth/pats redirects through the
        // 火山引擎 sign-in (signin.volcengine.com → volccloudidentity.com → coze.cn/auth/callback) and its button is
        // 「添加新令牌」/「添加」 — the generic recipe had neither label and its contains-match could hit 「创建智能体」.
        // The new token (pat_ + 64) is shown ONCE in the result dialog; it is read off the page or from its 「复制」.
        Recipe("coze", Mode.WEBVIEW, "https://www.coze.cn/open/oauth/pats",
            Regex("""pat_[A-Za-z0-9]{30,}"""),
            listOf(
                Step(listOf("添加新令牌", "添加令牌", "添加新的个人访问令牌", "添加", "Add new token", "Add token")),
                Step(fill = nameField, optional = true),
                Step(listOf("全选", "选择全部", "Select all", "All permissions"), optional = true),
                Step(listOf("所有工作空间", "所有空间", "全部空间", "All workspaces", "All spaces"), optional = true),
                Step(listOf("确定", "确认", "Confirm", "OK")),
                Step(listOf("复制", "Copy"), optional = true),
            ),
            loginHints = listOf("登录", "登录 / 注册", "手机号登录", "账号登录", "Sign in", "Continue with google", "Welcome to Coze"),
            note = "扣子要先用手机号登录（火山引擎账号）。登录好 Mines 会自动打开「个人访问令牌」并新建一个，新令牌只显示一次，Mines 会当场读取。"),
        Recipe("mistral", Mode.WEBVIEW, "https://console.mistral.ai/api-keys",
            Regex("""\b[A-Za-z0-9]{32}\b"""),
            listOf(
                Step(listOf("Free", "Experiment", "Activate", "Start for free", "Choose Experiment", "Subscribe to Experiment"), optional = true),
                Step(listOf("Create new key", "Create API key", "Create key", "New key")),
                Step(fill = nameField, optional = true),
                Step(listOf("Create new key", "Create key", "Create")),
            ),
            note = "选免费模式（不绑卡）。如果要验证手机号，Mines 会停下来请你填短信验证码。"),
        Recipe("perplexity", Mode.WEBVIEW, "https://console.perplexity.ai/project/keys",
            Regex("""pplx-[A-Za-z0-9]{40,}"""),
            listOf(
                Step(listOf("Create project", "Create Project", "New project"), optional = true),
                Step(fill = nameField, optional = true),
                Step(listOf("Create", "Save"), optional = true),
                Step(listOf("Create Key", "Create API Key", "Generate", "Create key", "+ Create Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("Create", "Generate", "Confirm"), optional = true),
            ),
            note = "Perplexity 的 API 要先充值才能用。Mines 只创建密钥，不会替你付钱。"),
        Recipe("huggingface", Mode.WEBVIEW, "https://huggingface.co/settings/tokens/new?tokenType=read",
            Regex("""hf_[A-Za-z0-9]{30,}"""),
            listOf(
                Step(listOf("Read"), optional = true),
                Step(fill = nameField),
                Step(listOf("Create token", "Create")),
            )),
        Recipe("digitalocean", Mode.WEBVIEW, "https://cloud.digitalocean.com/account/api/tokens",
            Regex("""dop_v1_[a-f0-9]{64}"""),
            listOf(
                Step(listOf("Generate New Token", "Generate new token", "Create Token")),
                Step(fill = nameField),
                Step(listOf("Full Access"), optional = true),
                Step(listOf("Generate Token", "Generate token")),
            )),
        Recipe("cloudflare", Mode.WEBVIEW, "https://dash.cloudflare.com/profile/api-tokens",
            Regex("""\b[A-Za-z0-9_\-]{40}\b"""),
            listOf(
                Step(listOf("Create Token", "创建令牌")),
                Step(listOf("Use template", "使用模板")),
                Step(listOf("Continue to summary", "继续以显示摘要", "继续")),
                Step(listOf("Create Token", "创建令牌")),
            ),
            note = "会用 Cloudflare 的第一个模板（编辑区域 DNS）创建令牌，之后可在 Cloudflare 里改权限。"),
        Recipe("github", Mode.WEBVIEW, "https://github.com/settings/tokens/new?scopes=repo,workflow,read:user,notifications&description=Mines",
            Regex("""ghp_[A-Za-z0-9]{36}"""),
            listOf(
                Step(listOf("Generate token")),
            )),
        Recipe("qwen", Mode.WEBVIEW, "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key",
            Regex("""sk-ws-[A-Za-z0-9._-]{16,}|sk-[a-f0-9]{32}"""),
            listOf(
                Step(listOf("创建 API Key", "创建API Key", "创建 API-KEY", "创建我的API-KEY")),
                Step(listOf("确定", "确认")),
                Step(listOf("查看", "显示"), optional = true),
            ),
            note = "百炼按量计费、开通免费；Mines 不会点任何充值或购买按钮。"),
        // [Mines-offers-32] providers with free credits (「帮我领」)
        Recipe("zhipu", Mode.WEBVIEW, "https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys",
            Regex("""[a-f0-9]{32}\.[A-Za-z0-9]{16}"""),
            listOf(
                Step(listOf("添加新的 API Key", "添加 API Key", "新建 API Key", "创建 API Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("确定", "确认", "创建")),
                Step(listOf("复制", "查看"), optional = true),
            ),
            note = "新用户注册后自动发体验包。要手机验证码或实名时 Mines 会停下来请你做。"),
        Recipe("siliconflow", Mode.WEBVIEW, "https://cloud.siliconflow.cn/account/ak",
            Regex("""sk-[A-Za-z0-9]{40,}"""),
            listOf(
                Step(listOf("新建 API 密钥", "新建API密钥", "创建 API 密钥", "Create API Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("新建密钥", "确定", "确认", "Create")),
            ),
            note = "注册送的额度会自动到账。要短信验证码时 Mines 会停下来请你填。"),
        Recipe("groq", Mode.WEBVIEW, "https://console.groq.com/keys",
            Regex("""gsk_[A-Za-z0-9]{40,}"""),
            listOf(
                Step(listOf("Create API Key", "Create API key")),
                Step(fill = nameField, optional = true),
                // [Mines-38.9.1] free tier: pick 「No expiration」 when the dialog asks (so the user never types)
                Step(listOf("No expiration", "Never", "永不过期"), optional = true),
                Step(listOf("Submit", "Create")),
            ),
            note = "Groq 免费层不用绑卡。"),
        // [Mines-keys-35.3] user 2026-10-14: 「点了获取，还要我填有点反人性」 — every AI provider row gets the automatic flow.
        // Button labels are broad lists (the consoles change wording); a key that was already on the page before
        // Mines clicked anything is never taken (baseline), and every key is tested before it is saved.
        Recipe("deepseek", Mode.WEBVIEW, "https://platform.deepseek.com/api_keys",
            Regex("""sk-[a-f0-9]{32}"""),
            listOf(
                Step(listOf("创建 API key", "创建 API Key", "创建API key", "Create new API key", "Create API key", "Create API Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("创建", "确定", "Create", "Confirm")),
                Step(listOf("复制", "Copy"), optional = true),
            ),
            note = "DeepSeek 按量计费，余额不足时 Mines 只会告诉你，不会充值。"),
        Recipe("kimi", Mode.WEBVIEW, "https://platform.moonshot.cn/console/api-keys",
            Regex("""sk-[A-Za-z0-9]{40,}"""),
            listOf(
                Step(listOf("新建 API Key", "新建API Key", "新建", "创建 API Key", "Create API Key", "Create")),
                Step(fill = nameField, optional = true),
                Step(listOf("确定", "确认", "创建", "OK", "Create")),
                Step(listOf("复制", "Copy"), optional = true),
            )),
        Recipe("openrouter", Mode.WEBVIEW, "https://openrouter.ai/settings/keys",
            Regex("""sk-or-v1-[a-f0-9]{64}"""),
            listOf(
                Step(listOf("Create API Key", "Create Key", "Create new key", "Create")),
                Step(fill = nameField, optional = true),
                Step(listOf("Create", "Create Key", "Save")),
                Step(listOf("Copy"), optional = true),
            ),
            note = "OpenRouter 有免费模型；Mines 不会买 credits。"),
        Recipe("openai", Mode.WEBVIEW, "https://platform.openai.com/api-keys",
            Regex("""sk-(proj-)?[A-Za-z0-9_\-]{20,}"""),
            listOf(
                Step(listOf("Create new secret key", "Create secret key", "+ Create new secret key")),
                Step(fill = nameField, optional = true),
                Step(listOf("Create secret key", "Create key", "Create")),
                Step(listOf("Copy"), optional = true),
            )),
        Recipe("anthropic", Mode.WEBVIEW, "https://console.anthropic.com/settings/keys",
            Regex("""sk-ant-[A-Za-z0-9_\-]{20,}"""),
            listOf(
                Step(listOf("Create Key", "Create key", "+ Create Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("Add", "Create Key", "Create")),
                Step(listOf("Copy Key", "Copy"), optional = true),
            )),
        Recipe("xai", Mode.WEBVIEW, "https://console.x.ai/",
            Regex("""xai-[A-Za-z0-9]{40,}"""),
            listOf(
                Step(listOf("API Keys", "API keys"), optional = true),
                Step(listOf("Create API Key", "Create API key", "Create key")),
                Step(fill = nameField, optional = true),
                Step(listOf("Save", "Create", "Create API Key")),
            )),
        Recipe("zai", Mode.WEBVIEW, "https://z.ai/manage-apikey/apikey-list",
            Regex("""[a-f0-9]{32}\.[A-Za-z0-9]{16}"""),
            listOf(
                Step(listOf("Create a new API key", "Add new API key", "Create API Key", "Create")),
                Step(fill = nameField, optional = true),
                Step(listOf("Confirm", "Create", "OK")),
            )),
        Recipe("volcengine", Mode.WEBVIEW, "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
            GENERIC_KEY,
            listOf(
                Step(listOf("创建 API Key", "创建API Key", "Create API Key")),
                Step(fill = nameField, optional = true),
                Step(listOf("创建", "确定", "Create")),
                Step(listOf("查看", "显示", "复制"), optional = true),
            ),
            note = "火山方舟开通免费，Mines 不会点任何充值或购买按钮。"),
    ).associateBy { it.kindId }


    /** services whose 「获取」 page is not a key page (mail auth codes, bots, docs) or needs Google sign-in */
    private val noAuto = setOf("gmail", "mail163", "qqmail", "telegram", "telegram_user", "wecom", "dingtalk", "youtube", "line", "monday", "make")

    /** [Mines-keys-35.3] adb self-test only: kind → fake key page (smoke) */
    val testUrl = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * [Mines-keys-35.3] Generic automatic flow for every other KEY service with an official key page: open it in
     * Mines, wait for the user's one sign-in, click the usual 「Create … key」 buttons, read the key off the page
     * (or from the page's own 「复制」), test, save.
     */
    private fun generic(base: String): Recipe? {
        val k = PlusVault.kind(base) ?: return null
        if (k.method != ConnMethod.KEY || base in noAuto) return null
        val url = k.keyUrl?.takeIf { it.startsWith("https://") } ?: return null
        val host = PlusConnect.hostOf(url)
        if (host.endsWith("google.com") || host == "t.me" || url.contains("/document")) return null
        return Recipe(base, Mode.WEBVIEW, url, k.keyRegex ?: GENERIC_KEY,
            listOf(
                Step(listOf("Create API Key", "Create API key", "Create new key", "Create key", "New API key", "New key", "Create token", "New token",
                    "Generate", "Generate token", "+ Create", "创建 API Key", "创建 API key", "新建 API Key", "创建密钥", "新建密钥", "创建", "新建", "生成")),
                Step(fill = nameField, optional = true),
                Step(listOf("Create", "Generate", "Save", "Confirm", "OK", "创建", "确定", "确认", "生成"), optional = true),
                Step(listOf("Copy", "复制", "Reveal", "Show", "查看", "显示"), optional = true),
            ))
    }

    fun recipe(kindId: String): Recipe? {
        val base = PlusVault.baseId(kindId)
        val r = recipes[base] ?: generic(base) ?: return null
        return testUrl[base]?.let { r.copy(url = it) } ?: r
    }

    /** Words that mean "this step costs money": never clicked, the user is told instead. */
    val payWords = listOf("支付", "付款", "充值", "购买", "下单", "Add payment", "Payment method", "Credit card", "Add credits", "Buy credits", "Upgrade", "Checkout", "Pay ")
    val captchaWords = listOf("captcha", "CAPTCHA", "人机验证", "Verify you are human", "I'm not a robot", "滑块", "拖动滑块", "安全验证")
    val smsWords = listOf("verification code", "Verify your phone", "phone number", "SMS", "短信验证码", "手机号", "验证码")

    /** What the user has to do, in one sentence, or null when Mines can continue. */
    fun needsUser(pageText: String): String? = when {
        captchaWords.any { pageText.contains(it, true) } -> "页面要你做一次人机验证，请完成后点「继续」"
        smsWords.any { pageText.contains(it, true) } && (pageText.contains("phone", true) || pageText.contains("手机", true)) ->
            "要验证手机号：请在页面上填手机号和收到的短信验证码，填好后点「继续」"
        else -> null
    }

    /**
     * JS helper injected into the page. `click(labels)` only clicks buttons /
     * links whose own text matches, never anything with a payment word.
     */
    val JS_HELPER = """
(function(){
 if (window.__mines) return;
 var PAY = ${payWords.joinToString(",", "[", "]") { "\"" + it.trim().lowercase() + "\"" }};
 function vis(e){ var r=e.getBoundingClientRect(); return r.width>1&&r.height>1&&getComputedStyle(e).visibility!='hidden'; }
 function txt(e){ return (e.innerText||e.value||e.getAttribute('aria-label')||'').trim(); }
 window.__mines = {
  text: function(){
    var t = document.body ? document.body.innerText : '';
    var ins = document.querySelectorAll('input,textarea,code,pre');
    for (var i=0;i<ins.length;i++){ t += '\n' + (ins[i].value||ins[i].innerText||''); }
    return t;
  },
  click: function(labels){
    var els = Array.prototype.slice.call(document.querySelectorAll('button,a,[role=button],[role=menuitem],[role=radio],[role=tab],label,div[tabindex],span'));
    var want = labels.map(function(l){return l.toLowerCase();});
    for (var pass=0; pass<2; pass++){
      for (var i=els.length-1;i>=0;i--){
        var e=els[i]; if(!vis(e)) continue; var t=txt(e).toLowerCase(); if(!t||t.length>60) continue;
        if (PAY.some(function(p){return t.indexOf(p)>=0;})) continue;
        for (var j=0;j<want.length;j++){
          if ((pass==0 && t==want[j]) || (pass==1 && t.indexOf(want[j])>=0)){
            if (e.disabled) continue;
            e.scrollIntoView({block:'center'}); e.click(); return labels[j];
          }
        }
      }
    }
    return '';
  },
  fill: function(v){
    var root = document.querySelector('[role=dialog]') || document;
    var ins = root.querySelectorAll('input[type=text],input:not([type]),input[name*=name i]');
    for (var i=0;i<ins.length;i++){
      var e=ins[i]; if(!vis(e)||e.readOnly||e.disabled) continue;
      if (e.value && e.value.length>0) continue;
      var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(e,v);
      e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
      return 'ok';
    }
    return '';
  }
 };
})();
""".trimIndent()

    /** Find a key in page text; the first match that is not something we already saved. */
    fun findKey(recipe: Recipe, text: String, known: String?): String? =
        recipe.keyRegex.findAll(text).map { it.value }.firstOrNull { it != known && !it.all { c -> c.isDigit() } }

    /**
     * Gemini (and any Chrome page): drive Chrome through Mines' accessibility
     * service. Blocking; call on IO. [status] gets one-line progress.
     */
    fun runChromeA11y(context: android.content.Context, recipe: Recipe, known: String?, cancelled: () -> Boolean, status: (String) -> Unit): String? {
        val ui = com.openminis.app.sandbox.offload.FastUi(context)
        if (!ui.ready()) { status("没有打开 Mines 的无障碍：请复制密钥后回到这里，Mines 会自动填入"); return null }
        val until = System.currentTimeMillis() + 4 * 60_000
        var step = 0
        var waitedLogin = false
        while (!cancelled() && System.currentTimeMillis() < until) {
            Thread.sleep(1500)
            val text = ui.allText()
            val found = findKey(recipe, text, known)
            if (found != null) return found
            val ask = needsUser(text)
            if (ask != null) { status(ask); continue }
            if (recipe.loginHints.any { text.contains(it) } && step == 0) {
                if (!waitedLogin) status("请在 Chrome 里登录，登录好 Mines 会自动继续"); waitedLogin = true; continue
            }
            if (step < recipe.steps.size) {
                val s = recipe.steps[step]
                val ok = s.click.isNotEmpty() && s.click.any { ui.tapText(it) }
                if (ok) { status("已点「${s.click.first()}」…"); step++ }
                else if (s.optional) step++
            } else status("正在读取新密钥…")
        }
        return null
    }
}
