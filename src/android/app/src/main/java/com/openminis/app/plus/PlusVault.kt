package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.MinisApp
import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How "连接" works for a service. */
enum class ConnMethod {
    /** In-app WebView login on the official page; cookies shared with the AI browser. */
    WEB,
    /** Google refuses embedded WebView sign-in: Chrome Custom Tab + (for mail) an app password. */
    GOOGLE,
    /** GitHub OAuth device flow → real token. */
    GITHUB,
    /** Microsoft identity platform device-code flow → Graph token. */
    MICROSOFT,
    /** API key / token: open the official key page, pick the key up from the clipboard. */
    KEY,
    /** Phone calendar (syncs Google / Outlook calendars) via calendar permission. */
    CALENDAR,
    /** Phone app driven through Mines' accessibility service (WeChat). */
    A11Y,
}

/** Which permission rows a connection's detail page shows. */
enum class PermProfile { MAIL, CALENDAR, AI, DEV, FINANCE, SOCIAL, CHAT, FILES, GENERIC }

enum class PermLevel(val label: String) { ALLOW("允许"), ASK("询问"), DENY("禁止") }

data class Capability(
    val id: String,
    val label: String,
    val section: Int, // 0 = 读取权限, 1 = 写入和删除权限, 2 = 后台操作
    val default: PermLevel,
    val locked: Boolean = false,
)

/** A service the Connections page knows. */
data class ConnectionKind(
    val id: String,
    val name: String,
    val desc: String,
    val group: String,
    val icon: Int,
    val method: ConnMethod,
    val profile: PermProfile = PermProfile.GENERIC,
    /** Official login page (WEB / GOOGLE). */
    val loginUrl: String? = null,
    /** Cookie names that only exist once signed in. */
    val doneCookies: List<String> = emptyList(),
    /** URL pattern that means "signed in" (checked on page finish). */
    val doneUrl: Regex? = null,
    /** JS expression returning a truthy string once signed in (e.g. localStorage). */
    val doneJs: String? = null,
    /** JS expression returning the account name, when the page exposes it. */
    val accountJs: String? = null,
    /** Exact official page where the user creates a key. */
    val keyUrl: String? = null,
    /** What a key from this service looks like (clipboard auto-detect). */
    val keyRegex: Regex? = null,
    val keyLabel: String = "API Key",
    /** Env var the secret is exposed as when "交给 AI 使用" is on. */
    val envKey: String? = null,
    /** WEB services that also accept a token (Cloudflare, Vercel...). */
    val alsoKey: Boolean = false,
    /** Not listed in the catalog (legacy entries only shown when connected). */
    val hidden: Boolean = false,
    val panelUrl: String? = null,
    // [Mines-apps-32] 「应用」 catalog details (detail page 详情 group)
    val site: String? = null,
    val docs: String? = null,
    val privacy: String? = null,
    val beta: Boolean = false,
    /** Display type override, e.g. "MCP". */
    val appType: String? = null,
)

data class ConnectionEntry(
    val kindId: String,
    val account: String,
    val shareWithAi: Boolean,
    val updatedAt: Long,
    /** "web", "token", "key", "oauth", "app" — how it was connected. */
    val via: String = "",
)

/**
 * Account / credential vault behind the Connections page.
 * Secrets (keys, OAuth tokens, saved web-session cookies) are stored in Minis'
 * existing AES-256-GCM EncryptedSharedPreferences (Android Keystore). The AI
 * never reads the vault: when "交给 AI 使用" is on, a key is injected into the
 * sandbox as an env var (masked by Minis' redactor), and every action through
 * a connection is checked against its permission rows via `minis-connect`.
 */
object PlusVault {
    const val G_COMMON = "效率与办公"
    const val G_AI = "AI 模型"
    const val G_DEV = "开发与服务器"
    const val G_FIN = "金融与加密"
    const val G_SOCIAL = "社交与消息"
    const val G_MCP = "MCP"
    const val G_OTHER = "其他网站"
    val groups = listOf(G_AI, G_DEV, G_COMMON, G_FIN, G_SOCIAL, G_MCP)

    private val sk = Regex("""sk-[A-Za-z0-9_\-]{20,}""")

    val kinds: List<ConnectionKind> = listOf(
        // ---- 常用 ----
        ConnectionKind("google", "Google 账号", "在 Chrome 里登录 Google（Google 不允许内嵌网页登录）", G_COMMON,
            R.drawable.ic_brand_google, ConnMethod.GOOGLE, PermProfile.GENERIC,
            loginUrl = "https://accounts.google.com/ServiceLogin?continue=https://myaccount.google.com/"),
        // [Mines-sources-33] Gmail: signed in inside Mines (web session) → codes + mail read automatically;
        // the app password (IMAP/SMTP) stays as the second way in.
        ConnectionKind("gmail", "Gmail", "自动读验证码和重要邮件（在 Mines 里登录一次即可）", G_COMMON,
            R.drawable.ic_brand_gmail, ConnMethod.KEY, PermProfile.MAIL,
            loginUrl = "https://accounts.google.com/ServiceLogin?service=mail&continue=https%3A%2F%2Fmail.google.com%2Fmail%2F",
            doneCookies = listOf("SID", "__Secure-1PSID"), panelUrl = "https://mail.google.com/mail/",
            accountJs = """(document.title.match(/[\w.+-]+@[\w-]+\.[\w.]+/)||[''])[0]""",
            keyUrl = "https://myaccount.google.com/apppasswords",
            keyRegex = Regex("""\b[a-z]{4} ?[a-z]{4} ?[a-z]{4} ?[a-z]{4}\b"""),
            keyLabel = "应用专用密码（16 位）", envKey = "GMAIL_APP_PASSWORD"),
        // [Mines-sources-33] Chinese mailboxes: IMAP with the mailbox's 授权码 (account = the address)
        ConnectionKind("mail163", "网易邮箱", "163 / 126 / yeah 邮箱：自动读验证码和邮件（IMAP 授权码）", G_COMMON,
            0, ConnMethod.KEY, PermProfile.MAIL, keyUrl = "https://mail.163.com/", keyLabel = "IMAP 授权码", envKey = "MAIL163_AUTH_CODE",
            site = "https://mail.163.com"),
        ConnectionKind("qqmail", "QQ 邮箱", "自动读验证码和邮件（IMAP 授权码）", G_COMMON,
            0, ConnMethod.KEY, PermProfile.MAIL, keyUrl = "https://wx.mail.qq.com/", keyLabel = "IMAP 授权码", envKey = "QQMAIL_AUTH_CODE",
            site = "https://mail.qq.com"),
        ConnectionKind("gcal", "Google 日历", "读取手机里同步的 Google 日历", G_COMMON,
            R.drawable.ic_brand_gcal, ConnMethod.CALENDAR, PermProfile.CALENDAR),
        ConnectionKind("gdrive", "Google 云端硬盘", "在 Chrome 里登录云端硬盘", G_COMMON,
            R.drawable.ic_brand_gdrive, ConnMethod.GOOGLE, PermProfile.FILES,
            loginUrl = "https://accounts.google.com/ServiceLogin?continue=https://drive.google.com/"),
        ConnectionKind("github", "GitHub", "仓库、Actions、Release（一键授权）", G_DEV,
            R.drawable.ic_brand_github, ConnMethod.GITHUB, PermProfile.DEV,
            loginUrl = "https://github.com/login", doneCookies = listOf("logged_in=yes", "user_session"),
            accountJs = "(document.querySelector('meta[name=user-login]')||{}).content||''",
            envKey = "GITHUB_TOKEN"),
        ConnectionKind("microsoft", "Microsoft · Outlook", "Outlook 邮件、日历、OneDrive（一键授权）", G_COMMON,
            R.drawable.ic_brand_microsoft, ConnMethod.MICROSOFT, PermProfile.MAIL,
            loginUrl = "https://outlook.live.com/mail/0/", doneUrl = Regex("""https://outlook\.(live|office|office365)\.com/(mail|owa)/.*"""),
            envKey = "MS_GRAPH_TOKEN"),
        ConnectionKind("notion", "Notion", "笔记、数据库", G_COMMON,
            R.drawable.ic_brand_notion, ConnMethod.WEB, PermProfile.GENERIC,
            loginUrl = "https://www.notion.so/login", doneCookies = listOf("token_v2"),
            keyUrl = "https://www.notion.so/profile/integrations", keyRegex = Regex("""(ntn|secret)_[A-Za-z0-9]{40,}"""),
            keyLabel = "Integration Token", envKey = "NOTION_TOKEN", alsoKey = true),
        ConnectionKind("cloudflare", "Cloudflare", "域名、DNS、Workers", G_DEV,
            R.drawable.ic_brand_cloudflare, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://dash.cloudflare.com/login", doneUrl = Regex("""https://dash\.cloudflare\.com/[0-9a-f]{32}.*"""),
            keyUrl = "https://dash.cloudflare.com/profile/api-tokens", keyRegex = Regex("""\b[A-Za-z0-9_\-]{40}\b"""),
            keyLabel = "API Token", envKey = "CLOUDFLARE_API_TOKEN", alsoKey = true,
            panelUrl = "https://dash.cloudflare.com/"),
        ConnectionKind("slack", "Slack", "工作区消息", G_SOCIAL,
            R.drawable.ic_brand_slack, ConnMethod.WEB, PermProfile.CHAT,
            loginUrl = "https://slack.com/signin", doneCookies = listOf("d="),
            doneUrl = Regex("""https://app\.slack\.com/client/.*""")),

        // ---- AI 模型 ----
        ConnectionKind("openai", "OpenAI", "GPT 模型与语音", G_AI, R.drawable.ic_brand_openai, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://platform.openai.com/api-keys", keyRegex = Regex("""sk-(proj-)?[A-Za-z0-9_\-]{20,}"""), envKey = "OPENAI_API_KEY"),
        ConnectionKind("anthropic", "Claude", "Anthropic API", G_AI, R.drawable.ic_brand_anthropic, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.anthropic.com/settings/keys", keyRegex = Regex("""sk-ant-[A-Za-z0-9_\-]{20,}"""), envKey = "ANTHROPIC_API_KEY"),
        ConnectionKind("gemini", "Gemini", "实时语音、看图、长文本", G_AI, R.drawable.ic_brand_gemini, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://aistudio.google.com/apikey", keyRegex = Regex("""AIza[0-9A-Za-z_\-]{35}"""), envKey = "GEMINI_API_KEY"),
        ConnectionKind("deepseek", "DeepSeek", "深度求索", G_AI, R.drawable.ic_brand_deepseek, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://platform.deepseek.com/api_keys", keyRegex = Regex("""sk-[a-f0-9]{32}"""), envKey = "DEEPSEEK_API_KEY"),
        ConnectionKind("openrouter", "OpenRouter", "一个密钥用上百个模型", G_AI, R.drawable.ic_brand_openrouter, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://openrouter.ai/settings/keys", keyRegex = Regex("""sk-or-v1-[a-f0-9]{64}"""), envKey = "OPENROUTER_API_KEY"),
        ConnectionKind("kimi", "Kimi", "月之暗面", G_AI, R.drawable.ic_brand_kimi, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://platform.moonshot.cn/console/api-keys", keyRegex = sk, envKey = "MOONSHOT_API_KEY"),
        ConnectionKind("qwen", "通义千问", "阿里云百炼", G_AI, R.drawable.ic_brand_qwen, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key", keyRegex = Regex("""sk-ws-[A-Za-z0-9._-]{16,}|sk-[a-f0-9]{32}"""), envKey = "DASHSCOPE_API_KEY"),
        ConnectionKind("zhipu", "智谱 GLM", "BigModel 开放平台", G_AI, R.drawable.ic_brand_zhipu, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys", keyRegex = Regex("""[a-f0-9]{32}\.[A-Za-z0-9]{16}"""), envKey = "ZHIPUAI_API_KEY"),
        ConnectionKind("siliconflow", "硅基流动", "SiliconFlow", G_AI, R.drawable.ic_brand_siliconflow, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://cloud.siliconflow.cn/account/ak", keyRegex = sk, envKey = "SILICONFLOW_API_KEY"),
        ConnectionKind("xai", "xAI Grok", "Grok 模型", G_AI, R.drawable.ic_brand_xai, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.x.ai/", keyRegex = Regex("""xai-[A-Za-z0-9]{40,}"""), envKey = "XAI_API_KEY"),
        ConnectionKind("perplexity", "Perplexity", "联网搜索模型", G_AI, R.drawable.ic_brand_perplexity, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://www.perplexity.ai/account/api/keys", keyRegex = Regex("""pplx-[A-Za-z0-9]{40,}"""), envKey = "PERPLEXITY_API_KEY"),
        ConnectionKind("mistral", "Mistral", "Mistral AI", G_AI, R.drawable.ic_brand_mistral, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.mistral.ai/api-keys", keyRegex = Regex("""\b[A-Za-z0-9]{32}\b"""), envKey = "MISTRAL_API_KEY"),
        ConnectionKind("huggingface", "Hugging Face", "模型与数据集", G_AI, R.drawable.ic_brand_huggingface, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://huggingface.co/settings/tokens", keyRegex = Regex("""hf_[A-Za-z0-9]{30,}"""), keyLabel = "Access Token", envKey = "HF_TOKEN"),
        // [Mines-apps-32] more model providers
        ConnectionKind("zai", "Z.ai（智谱国际）", "GLM 模型 · 国际站", G_AI, R.drawable.ic_brand_zai, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://z.ai/manage-apikey/apikey-list", keyRegex = Regex("""[a-f0-9]{32}\.[A-Za-z0-9]{16}"""), envKey = "ZAI_API_KEY",
            site = "https://z.ai", docs = "https://docs.z.ai"),
        ConnectionKind("sensenova", "商汤 SenseNova", "商汤日日新大模型", G_AI, R.drawable.ic_brand_sensenova, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.sensecore.cn/aistudio/management/api-key", envKey = "SENSENOVA_API_KEY",
            site = "https://www.sensecore.cn", docs = "https://www.sensecore.cn/help/docs/model-as-a-service/nova"),
        ConnectionKind("volcengine", "火山方舟 · 豆包", "字节跳动豆包大模型", G_AI, R.drawable.ic_brand_volcengine, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey", envKey = "ARK_API_KEY",
            site = "https://www.volcengine.com/product/ark", docs = "https://www.volcengine.com/docs/82379"),
        ConnectionKind("minimax", "MiniMax", "海螺 / abab 模型、语音", G_AI, R.drawable.ic_brand_minimax, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key", envKey = "MINIMAX_API_KEY",
            site = "https://www.minimaxi.com", docs = "https://platform.minimaxi.com/document"),
        ConnectionKind("groq", "Groq", "超快推理（开源模型）", G_AI, R.drawable.ic_brand_groq, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.groq.com/keys", keyRegex = Regex("""gsk_[A-Za-z0-9]{40,}"""), envKey = "GROQ_API_KEY",
            site = "https://groq.com", docs = "https://console.groq.com/docs", privacy = "https://groq.com/privacy-policy/"),
        // [Mines-group-37] 群聊成员：元宝（混元）、Manus、扣子
        ConnectionKind("hunyuan", "腾讯混元 · 元宝", "元宝背后的混元大模型", G_AI, 0, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://console.cloud.tencent.com/hunyuan/start", keyRegex = Regex("""sk-[A-Za-z0-9]{40,}"""), envKey = "HUNYUAN_API_KEY",
            site = "https://hunyuan.tencent.com", docs = "https://cloud.tencent.com/document/product/1729/111007"),
        ConnectionKind("manus", "Manus", "通用智能体：发任务、收结果", G_AI, 0, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://manus.im/app?show_settings=integrations&app_name=api", envKey = "MANUS_API_KEY",
            site = "https://manus.im", docs = "https://open.manus.ai/docs"),
        ConnectionKind("coze", "扣子 Coze", "调用你自己的智能体", G_AI, 0, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://www.coze.cn/open/oauth/pats", keyRegex = Regex("""pat_[A-Za-z0-9]{30,}"""), keyLabel = "个人访问令牌", envKey = "COZE_API_TOKEN",
            site = "https://www.coze.cn", docs = "https://www.coze.cn/open/docs/developer_guides/chat_v3"),
        ConnectionKind("elevenlabs", "ElevenLabs", "语音合成与克隆", G_AI, R.drawable.ic_brand_elevenlabs, ConnMethod.KEY, PermProfile.AI,
            keyUrl = "https://elevenlabs.io/app/settings/api-keys", keyRegex = Regex("""sk_[a-f0-9]{40,}"""), envKey = "ELEVENLABS_API_KEY",
            site = "https://elevenlabs.io", docs = "https://elevenlabs.io/docs", privacy = "https://elevenlabs.io/privacy-policy"),

        // ---- 开发与服务器 (GitHub and Cloudflare are listed here too) ----
        ConnectionKind("vercel", "Vercel", "前端部署", G_DEV, R.drawable.ic_brand_vercel, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://vercel.com/login", doneUrl = Regex("""https://vercel\.com/(?!login|signup)[^/?#]+.*"""),
            keyUrl = "https://vercel.com/account/settings/tokens", keyRegex = Regex("""\b[A-Za-z0-9]{24}\b"""),
            keyLabel = "Token", envKey = "VERCEL_TOKEN", alsoKey = true),
        ConnectionKind("netlify", "Netlify", "静态站点部署", G_DEV, R.drawable.ic_brand_netlify, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://app.netlify.com/login", doneUrl = Regex("""https://app\.netlify\.com/(teams|sites|projects)/.*""")),
        ConnectionKind("supabase", "Supabase", "数据库与后端", G_DEV, R.drawable.ic_brand_supabase, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://supabase.com/dashboard/sign-in", doneUrl = Regex("""https://supabase\.com/dashboard/(projects|org|project)/?.*""")),
        ConnectionKind("railway", "Railway", "容器部署", G_DEV, R.drawable.ic_brand_railway, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://railway.com/login", doneUrl = Regex("""https://railway\.(com|app)/(dashboard|project)/?.*""")),
        ConnectionKind("digitalocean", "DigitalOcean", "云服务器", G_DEV, R.drawable.ic_brand_digitalocean, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://cloud.digitalocean.com/login", doneUrl = Regex("""https://cloud\.digitalocean\.com/(projects|droplets|welcome).*""")),
        ConnectionKind("aliyun", "阿里云", "ECS、域名、控制台", G_DEV, R.drawable.ic_brand_aliyun, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://account.aliyun.com/login/login.htm?oauth_callback=https%3A%2F%2Fhome.console.aliyun.com%2F",
            doneCookies = listOf("login_aliyunid_ticket"), doneUrl = Regex("""https://(home|ecs)\.console\.aliyun\.com/.*""")),
        ConnectionKind("wispbyte", "Wispbyte", "免费容器主机（GitHub 登录，无 SSH）", G_DEV, R.drawable.ic_brand_wispbyte, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://wispbyte.com/client/dashboard", doneUrl = Regex("""https://wispbyte\.com/client/(dashboard|servers?|create).*"""),
            panelUrl = "https://wispbyte.com/client/dashboard"),

        // ---- 金融 ----
        ConnectionKind("okx", "OKX", "看资产与行情（交易需你确认）", G_FIN, R.drawable.ic_brand_okx, ConnMethod.WEB, PermProfile.FINANCE,
            loginUrl = "https://www.okx.com/account/login", doneUrl = Regex("""https://www\.okx\.com/(zh-hans/|cn/)?(balance|account/(?!login)|assets|trade).*""")),
        ConnectionKind("binance", "Binance", "看资产与行情（交易需你确认）", G_FIN, R.drawable.ic_brand_binance, ConnMethod.WEB, PermProfile.FINANCE,
            loginUrl = "https://accounts.binance.com/zh-CN/login", doneCookies = listOf("p20t"),
            doneUrl = Regex("""https://www\.binance\.com/[a-zA-Z\-]+/(my/dashboard|my/wallet|markets).*""")),

        // ---- 社交与媒体 ----
        ConnectionKind("telegram_web", "Telegram", "个人账号（网页版登录）", G_SOCIAL, R.drawable.ic_brand_telegram, ConnMethod.WEB, PermProfile.CHAT,
            loginUrl = "https://web.telegram.org/a/", doneJs = "localStorage.getItem('user_auth')||localStorage.getItem('dc')||''"),
        ConnectionKind("telegram", "Telegram 机器人", "消息推送（BotFather 发的 Token）", G_SOCIAL, R.drawable.ic_brand_telegram, ConnMethod.KEY, PermProfile.CHAT,
            keyUrl = "https://t.me/BotFather", keyRegex = Regex("""\b\d{6,12}:[A-Za-z0-9_\-]{35}\b"""), keyLabel = "Bot Token", envKey = "TELEGRAM_BOT_TOKEN"),
        ConnectionKind("discord", "Discord", "服务器与私信", G_SOCIAL, R.drawable.ic_brand_discord, ConnMethod.WEB, PermProfile.CHAT,
            loginUrl = "https://discord.com/login", doneUrl = Regex("""https://discord\.com/(channels|app).*""")),
        ConnectionKind("x", "X / Twitter", "推特账号", G_SOCIAL, R.drawable.ic_brand_x, ConnMethod.WEB, PermProfile.SOCIAL,
            loginUrl = "https://x.com/i/flow/login", doneCookies = listOf("auth_token")),
        ConnectionKind("weibo", "微博", "微博账号", G_SOCIAL, R.drawable.ic_brand_weibo, ConnMethod.WEB, PermProfile.SOCIAL,
            loginUrl = "https://passport.weibo.com/sso/signin?entry=wapsso&r=https%3A%2F%2Fm.weibo.cn%2F", doneCookies = listOf("SUB=")),
        ConnectionKind("xiaohongshu", "小红书", "小红书账号", G_SOCIAL, R.drawable.ic_brand_xiaohongshu, ConnMethod.WEB, PermProfile.SOCIAL,
            loginUrl = "https://www.xiaohongshu.com/explore", doneCookies = listOf("customer-sso-sid", "galaxy_creator_session_id")),
        ConnectionKind("douyin", "抖音", "抖音账号", G_SOCIAL, R.drawable.ic_brand_douyin, ConnMethod.WEB, PermProfile.SOCIAL,
            loginUrl = "https://www.douyin.com/", doneCookies = listOf("sessionid_ss=", "sessionid=")),
        ConnectionKind("bilibili", "哔哩哔哩", "B 站账号", G_SOCIAL, R.drawable.ic_brand_bilibili, ConnMethod.WEB, PermProfile.SOCIAL,
            loginUrl = "https://passport.bilibili.com/h5-app/passport/login", doneCookies = listOf("SESSDATA")),
        ConnectionKind("youtube", "YouTube", "在 Chrome 里登录；AI 用 API Key 查数据", G_SOCIAL, R.drawable.ic_brand_youtube, ConnMethod.KEY, PermProfile.SOCIAL,
            keyUrl = "https://console.cloud.google.com/apis/credentials", keyRegex = Regex("""AIza[0-9A-Za-z_\-]{35}"""), envKey = "YOUTUBE_API_KEY"),
        ConnectionKind("wechat", "微信", "通过无障碍帮你操作微信（微信没有手机网页登录）", G_SOCIAL, R.drawable.ic_brand_wechat, ConnMethod.A11Y, PermProfile.CHAT),
        ConnectionKind("wecom", "企业微信", "群机器人（复制 Webhook 地址即可）", G_SOCIAL, R.drawable.ic_brand_wecom, ConnMethod.KEY, PermProfile.CHAT,
            keyUrl = "https://developer.work.weixin.qq.com/document/path/91770",
            keyRegex = Regex("""https://qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key=[A-Za-z0-9\-]+"""),
            keyLabel = "Webhook 地址", envKey = "WECOM_WEBHOOK"),
        ConnectionKind("feishu", "飞书", "飞书账号（网页版登录）", G_SOCIAL, R.drawable.ic_brand_feishu, ConnMethod.WEB, PermProfile.CHAT,
            // [Mines-38.7 v11] the app_id=2 login URL showed 「无效的请求参数 错误码 4401」 (smoke 2026-10-15); this is where
            // www.feishu.cn/messenger/ itself sends a signed-out visitor (checked 2026-10-15)
            loginUrl = "https://accounts.feishu.cn/accounts/page/login?app_id=1&no_trap=1&redirect_uri=https%3A%2F%2Fwww.feishu.cn%2Fmessenger%2F",
            doneUrl = Regex("""https://[a-z0-9\-]+\.feishu\.cn/(messenger|next/messenger|drive|docx|wiki).*""")),
        ConnectionKind("dingtalk", "钉钉", "群机器人（复制 Webhook 地址即可）", G_SOCIAL, R.drawable.ic_brand_dingtalk, ConnMethod.KEY, PermProfile.CHAT,
            keyUrl = "https://open.dingtalk.com/document/orgapp/custom-robot-access",
            keyRegex = Regex("""https://oapi\.dingtalk\.com/robot/send\?access_token=[a-f0-9]+"""),
            keyLabel = "Webhook 地址", envKey = "DINGTALK_WEBHOOK"),
        ConnectionKind("qq", "QQ", "QQ 空间 / QQ 邮箱网页登录", G_SOCIAL, R.drawable.ic_brand_qq, ConnMethod.WEB, PermProfile.CHAT,
            // [Mines-38.7 v11] style=40 is the desktop QR box (tiny on a phone, smoke 2026-10-15); this is where h5.qzone.qq.com
            // itself sends a phone (checked 2026-10-15): mobile page with 账号密码 / 一键登录
            loginUrl = "https://ui.ptlogin2.qq.com/cgi-bin/login?pt_hide_ad=1&style=9&appid=549000929&pt_no_auth=1&pt_wxtest=1&daid=5&s_url=https%3A%2F%2Fh5.qzone.qq.com%2Fmqzone%2Findex",
            doneCookies = listOf("p_skey", "skey=")),

        // ---- [Mines-apps-32] wider catalog ----
        ConnectionKind("neon", "Neon", "Serverless Postgres", G_DEV, 0, ConnMethod.KEY, PermProfile.DEV,
            keyUrl = "https://console.neon.tech/app/settings/api-keys", keyRegex = Regex("""napi_[A-Za-z0-9]{40,}"""), envKey = "NEON_API_KEY",
            site = "https://neon.tech", docs = "https://neon.tech/docs"),
        ConnectionKind("sentry", "Sentry", "错误监控", G_DEV, R.drawable.ic_brand_sentry, ConnMethod.KEY, PermProfile.DEV,
            keyUrl = "https://sentry.io/settings/account/api/auth-tokens/", keyRegex = Regex("""sntry[us]_[A-Za-z0-9+/=_\-]{30,}"""), keyLabel = "Auth Token", envKey = "SENTRY_AUTH_TOKEN",
            site = "https://sentry.io", docs = "https://docs.sentry.io/api/", privacy = "https://sentry.io/privacy/"),
        ConnectionKind("posthog", "PostHog", "产品分析", G_DEV, R.drawable.ic_brand_posthog, ConnMethod.KEY, PermProfile.DEV,
            keyUrl = "https://app.posthog.com/settings/user-api-keys", keyRegex = Regex("""phx_[A-Za-z0-9]{30,}"""), keyLabel = "Personal API Key", envKey = "POSTHOG_API_KEY",
            site = "https://posthog.com", docs = "https://posthog.com/docs/api"),
        ConnectionKind("tencentcloud", "腾讯云", "云服务器、COS、域名", G_DEV, 0, ConnMethod.WEB, PermProfile.DEV,
            loginUrl = "https://cloud.tencent.com/login", doneUrl = Regex("""https://console\.cloud\.tencent\.com/.*"""),
            site = "https://cloud.tencent.com", docs = "https://cloud.tencent.com/document/api"),
        ConnectionKind("todoist", "Todoist", "待办清单", G_COMMON, R.drawable.ic_brand_todoist, ConnMethod.KEY, PermProfile.GENERIC,
            keyUrl = "https://app.todoist.com/app/settings/integrations/developer", keyRegex = Regex("""\b[a-f0-9]{40}\b"""), keyLabel = "API Token", envKey = "TODOIST_API_TOKEN",
            site = "https://todoist.com", docs = "https://developer.todoist.com/rest/v2/"),
        ConnectionKind("linear", "Linear", "项目与 Issue", G_COMMON, R.drawable.ic_brand_linear, ConnMethod.KEY, PermProfile.DEV,
            keyUrl = "https://linear.app/settings/account/security", keyRegex = Regex("""lin_api_[A-Za-z0-9]{30,}"""), envKey = "LINEAR_API_KEY",
            site = "https://linear.app", docs = "https://linear.app/developers"),
        ConnectionKind("airtable", "Airtable", "在线表格数据库", G_COMMON, R.drawable.ic_brand_airtable, ConnMethod.KEY, PermProfile.GENERIC,
            keyUrl = "https://airtable.com/create/tokens", keyRegex = Regex("""pat[A-Za-z0-9]{14}\.[a-f0-9]{64}"""), keyLabel = "Personal Access Token", envKey = "AIRTABLE_TOKEN",
            site = "https://airtable.com", docs = "https://airtable.com/developers/web/api/introduction"),
        ConnectionKind("box", "Box", "云盘", G_COMMON, R.drawable.ic_brand_box, ConnMethod.WEB, PermProfile.FILES,
            loginUrl = "https://account.box.com/login", doneUrl = Regex("""https://app\.box\.com/(folder|files|recents).*"""), site = "https://www.box.com"),
        ConnectionKind("dropbox", "Dropbox", "云盘", G_COMMON, R.drawable.ic_brand_dropbox, ConnMethod.WEB, PermProfile.FILES,
            loginUrl = "https://www.dropbox.com/login", doneUrl = Regex("""https://www\.dropbox\.com/(home|h|recents).*"""), site = "https://www.dropbox.com"),
        ConnectionKind("clickup", "ClickUp", "任务与文档", G_COMMON, R.drawable.ic_brand_clickup, ConnMethod.KEY, PermProfile.GENERIC,
            keyUrl = "https://app.clickup.com/settings/apps", keyRegex = Regex("""pk_[0-9]+_[A-Z0-9]{20,}"""), keyLabel = "API Token", envKey = "CLICKUP_API_TOKEN",
            site = "https://clickup.com", docs = "https://clickup.com/api"),
        ConnectionKind("monday", "monday.com", "工作管理", G_COMMON, 0, ConnMethod.KEY, PermProfile.GENERIC,
            keyUrl = "https://auth.monday.com/login", keyLabel = "API Token", envKey = "MONDAY_API_TOKEN",
            site = "https://monday.com", docs = "https://developer.monday.com/api-reference"),
        ConnectionKind("make", "Make", "自动化流程", G_COMMON, R.drawable.ic_brand_make, ConnMethod.KEY, PermProfile.GENERIC,
            keyUrl = "https://www.make.com/en/login", keyLabel = "API Token", envKey = "MAKE_API_TOKEN",
            site = "https://www.make.com", docs = "https://developers.make.com/api-documentation"),
        ConnectionKind("zapier", "Zapier", "自动化流程", G_COMMON, R.drawable.ic_brand_zapier, ConnMethod.WEB, PermProfile.GENERIC,
            loginUrl = "https://zapier.com/app/login", doneUrl = Regex("""https://zapier\.com/app/(home|zaps|dashboard).*"""), site = "https://zapier.com"),
        ConnectionKind("coingecko", "CoinGecko", "币价与市场数据", G_FIN, 0, ConnMethod.KEY, PermProfile.FINANCE,
            keyUrl = "https://www.coingecko.com/en/developers/dashboard", keyRegex = Regex("""CG-[A-Za-z0-9]{20,}"""), envKey = "COINGECKO_API_KEY",
            site = "https://www.coingecko.com", docs = "https://docs.coingecko.com"),
        ConnectionKind("coindesk", "CoinDesk", "加密新闻与数据", G_FIN, 0, ConnMethod.KEY, PermProfile.FINANCE,
            keyUrl = "https://developers.coindesk.com/settings/api-keys", envKey = "COINDESK_API_KEY",
            site = "https://www.coindesk.com", docs = "https://developers.coindesk.com/documentation"),
        ConnectionKind("cryptocom", "Crypto.com", "看资产与行情（交易需你确认）", G_FIN, 0, ConnMethod.WEB, PermProfile.FINANCE,
            loginUrl = "https://crypto.com/exchange/login", doneUrl = Regex("""https://crypto\.com/exchange/(dashboard|wallet|trade).*"""), site = "https://crypto.com"),
        ConnectionKind("stripe", "Stripe", "收款与订阅", G_FIN, R.drawable.ic_brand_stripe, ConnMethod.KEY, PermProfile.FINANCE,
            keyUrl = "https://dashboard.stripe.com/apikeys", keyRegex = Regex("""(sk|rk)_(live|test)_[A-Za-z0-9]{20,}"""), keyLabel = "Secret / Restricted Key", envKey = "STRIPE_API_KEY",
            site = "https://stripe.com", docs = "https://docs.stripe.com/api", privacy = "https://stripe.com/privacy"),
        ConnectionKind("line", "LINE", "消息推送（Messaging API Token）", G_SOCIAL, R.drawable.ic_brand_line, ConnMethod.KEY, PermProfile.CHAT,
            keyUrl = "https://developers.line.biz/console/", keyLabel = "Channel Access Token", envKey = "LINE_CHANNEL_TOKEN",
            site = "https://line.me", docs = "https://developers.line.biz/en/docs/messaging-api/"),
        ConnectionKind("serverchan", "微信通知（Server酱）", "把提醒推送到你的微信", G_SOCIAL, R.drawable.ic_brand_wechat, ConnMethod.KEY, PermProfile.CHAT,
            keyUrl = "https://sct.ftqq.com/sendkey", keyRegex = Regex("""SCT[A-Za-z0-9]{20,}"""), keyLabel = "SendKey", envKey = "SERVERCHAN_SENDKEY",
            site = "https://sct.ftqq.com", docs = "https://sct.ftqq.com/sendkey"),
        // MCP servers: 「添加」 opens 市场 (one-tap install there)
        ConnectionKind("mcp_playwright", "Playwright MCP", "让 AI 操作真实浏览器", G_MCP, 0, ConnMethod.KEY, PermProfile.GENERIC, appType = "MCP",
            site = "https://github.com/microsoft/playwright-mcp", docs = "https://github.com/microsoft/playwright-mcp#readme"),
        ConnectionKind("firecrawl", "Firecrawl", "网页抓取成 Markdown（也有 MCP）", G_MCP, 0, ConnMethod.KEY, PermProfile.GENERIC, appType = "MCP",
            keyUrl = "https://www.firecrawl.dev/app/api-keys", keyRegex = Regex("""fc-[a-f0-9]{32}"""), envKey = "FIRECRAWL_API_KEY",
            site = "https://www.firecrawl.dev", docs = "https://docs.firecrawl.dev"),
        ConnectionKind("mcp_github", "GitHub MCP", "仓库、Issue、PR 工具（用 GitHub 连接的令牌）", G_MCP, R.drawable.ic_brand_github, ConnMethod.KEY, PermProfile.DEV, appType = "MCP",
            site = "https://github.com/github/github-mcp-server", docs = "https://github.com/github/github-mcp-server#readme"),

        // ---- legacy entries from build-3..19 (shown only when already connected) ----
        ConnectionKind("telegram_user", "Telegram 账号（旧）", "API Hash", G_SOCIAL, R.drawable.ic_brand_telegram, ConnMethod.KEY,
            PermProfile.CHAT, keyUrl = "https://my.telegram.org/apps", keyLabel = "API Hash", envKey = "TELEGRAM_API_HASH", hidden = true),
        ConnectionKind("custom", "其他账号（旧）", "账号密码", G_OTHER, R.drawable.ic_brand_github, ConnMethod.KEY,
            PermProfile.GENERIC, keyLabel = "密码", hidden = true),
    )

    /** Services shown in more than one group. */
    private val alsoIn = emptyMap<String, List<String>>()

    fun kindsIn(group: String): List<ConnectionKind> =
        alsoIn[group].orEmpty().mapNotNull { id -> kinds.firstOrNull { it.id == id } } +
            kinds.filter { it.group == group && !it.hidden }

    /** User-added "其他网站" entries, id = "site:<host>". */
    fun customSites(): List<ConnectionKind> = _entries.value.values.filter { it.kindId.startsWith("site:") }.map { siteKind(it.kindId.removePrefix("site:"), extra(it.kindId, "url")) }

    fun siteKind(host: String, url: String? = null) = ConnectionKind(
        "site:$host", host, "网页登录", G_OTHER, 0, ConnMethod.WEB, PermProfile.GENERIC,
        loginUrl = url ?: "https://$host/", panelUrl = url ?: "https://$host/",
    )

    fun kind(id: String): ConnectionKind? {
        if (id.contains('#')) return kind(baseId(id))?.copy(id = id)
        return kinds.firstOrNull { it.id == id } ?: if (id.startsWith("site:")) siteKind(id.removePrefix("site:"), extra(id, "url")) else null
    }

    // ---------------- multiple accounts per service ----------------
    // The first account of a service is stored under the service id ("github"),
    // further ones under "github#2", "github#3"… Each account is a full entry with
    // its own secret, session snapshot and permission rows (everything is keyed
    // by the entry id), so a ConnectionKind copied with id = entry id is all the
    // UI and the agent gate need.

    fun baseId(id: String): String = id.substringBefore('#')

    fun accountsOf(base: String): List<String> =
        _entries.value.keys.filter { baseId(it) == base }
            .sortedBy { it.substringAfter('#', "1").toIntOrNull() ?: 1 }

    fun defaultAccount(base: String): String? {
        val list = accountsOf(base)
        val d = meta?.getString("default_$base", null)
        return if (d != null && d in list) d else list.firstOrNull()
    }

    fun newAccountId(base: String): String {
        if (!_entries.value.containsKey(base)) return base
        var n = 2
        while (_entries.value.containsKey("$base#$n")) n++
        return "$base#$n"
    }

    /** Find an account of [base] by entry id or account name; null = the default one. */
    fun resolveAccount(base: String, account: String?): String? {
        val list = accountsOf(base)
        if (account.isNullOrBlank()) return defaultAccount(base)
        return list.firstOrNull { it == account } ?: list.firstOrNull {
            _entries.value[it]?.account?.equals(account, ignoreCase = true) == true
        }
    }

    /**
     * Make [id] the default account of its service. For web logins the shared
     * cookie jar holds one account per site, so the sessions are swapped.
     */
    fun setDefault(context: Context, id: String, swapCookies: Boolean = true) {
        val base = baseId(id)
        val old = defaultAccount(base)
        if (swapCookies && old != null && old != id) {
            val ok = kind(old); val nk = kind(id)
            if (ok != null && nk != null && ok.method == ConnMethod.WEB) {
                PlusConnect.snapshotCookies(ok, listOfNotNull(ok.loginUrl, ok.panelUrl))
                PlusConnect.clearCookies(ok)
                PlusConnect.restoreCookies(nk)
            }
        }
        meta?.edit()?.putString("default_$base", id)?.apply()
        _entries.value = _entries.value.toMap() // notify
        kind(base)?.let { syncEnv(context, it) }
    }

    // ---------------- permissions ----------------

    fun capabilities(profile: PermProfile): List<Capability> = when (profile) {
        PermProfile.MAIL -> listOf(
            Capability("drafts", "访问草稿", 0, PermLevel.ALLOW),
            Capability("read", "访问邮件", 0, PermLevel.ALLOW),
            Capability("search", "搜索邮件", 0, PermLevel.ALLOW),
            Capability("draft_write", "邮件草稿", 1, PermLevel.ALLOW),
            Capability("send", "发邮件", 1, PermLevel.ASK),
            Capability("delete", "删除邮件", 1, PermLevel.ASK),
            Capability("unsubscribe", "退订", 1, PermLevel.ASK),
            Capability("background", "后台检查新邮件", 2, PermLevel.ALLOW),
        )
        PermProfile.CALENDAR -> listOf(
            Capability("read", "查看日程", 0, PermLevel.ALLOW),
            Capability("create", "新建日程", 1, PermLevel.ASK),
            Capability("modify", "修改日程", 1, PermLevel.ASK),
            Capability("delete", "删除日程", 1, PermLevel.ASK),
            Capability("background", "后台提醒", 2, PermLevel.ALLOW),
        )
        PermProfile.AI -> listOf(
            Capability("model", "调用模型", 0, PermLevel.ALLOW),
            Capability("read", "查看余额与用量", 0, PermLevel.ALLOW),
            Capability("modify", "管理密钥与设置", 1, PermLevel.ASK),
            Capability("pay", "充值 / 付费", 1, PermLevel.ASK, locked = true),
            Capability("background", "后台任务使用", 2, PermLevel.ALLOW),
        )
        PermProfile.DEV -> listOf(
            Capability("read", "查看项目与仓库", 0, PermLevel.ALLOW),
            Capability("publish", "推送 / 部署", 1, PermLevel.ASK),
            Capability("modify", "修改设置", 1, PermLevel.ASK),
            Capability("delete", "删除", 1, PermLevel.ASK),
            Capability("pay", "支付 / 升级套餐", 1, PermLevel.ASK, locked = true),
            Capability("background", "后台定时任务", 2, PermLevel.ALLOW),
        )
        PermProfile.FINANCE -> listOf(
            Capability("read", "查看资产与行情", 0, PermLevel.ALLOW),
            Capability("trade", "下单 / 交易", 1, PermLevel.ASK, locked = true),
            Capability("pay", "提现 / 转账 / 支付", 1, PermLevel.ASK, locked = true),
            Capability("modify", "修改账户设置", 1, PermLevel.ASK),
            Capability("background", "后台盯行情", 2, PermLevel.ALLOW),
        )
        PermProfile.CHAT -> listOf(
            Capability("read", "读取消息", 0, PermLevel.ALLOW),
            Capability("search", "搜索", 0, PermLevel.ALLOW),
            Capability("send", "发送消息", 1, PermLevel.ASK),
            Capability("delete", "删除 / 撤回", 1, PermLevel.ASK),
            Capability("pay", "支付 / 转账", 1, PermLevel.ASK, locked = true),
            Capability("background", "后台接收消息", 2, PermLevel.ALLOW),
        )
        PermProfile.SOCIAL -> listOf(
            Capability("read", "浏览与读取", 0, PermLevel.ALLOW),
            Capability("search", "搜索", 0, PermLevel.ALLOW),
            Capability("publish", "发布 / 评论 / 私信", 1, PermLevel.ASK),
            Capability("delete", "删除", 1, PermLevel.ASK),
            Capability("pay", "支付 / 打赏", 1, PermLevel.ASK, locked = true),
            Capability("background", "后台浏览", 2, PermLevel.ASK),
        )
        PermProfile.FILES -> listOf(
            Capability("read", "读取文件", 0, PermLevel.ALLOW),
            Capability("search", "搜索文件", 0, PermLevel.ALLOW),
            Capability("publish", "上传 / 分享", 1, PermLevel.ASK),
            Capability("delete", "删除文件", 1, PermLevel.ASK),
            Capability("background", "后台同步", 2, PermLevel.ALLOW),
        )
        PermProfile.GENERIC -> listOf(
            Capability("read", "读取", 0, PermLevel.ALLOW),
            Capability("publish", "发布 / 发送", 1, PermLevel.ASK),
            Capability("delete", "删除", 1, PermLevel.ASK),
            Capability("pay", "支付 / 交易", 1, PermLevel.ASK, locked = true),
            Capability("background", "后台操作", 2, PermLevel.ASK),
        )
    }

    fun level(kind: ConnectionKind, cap: Capability): PermLevel {
        if (cap.locked) return PermLevel.ASK
        val raw = meta?.getString("perm_${kind.id}_${cap.id}", null) ?: return cap.default
        return runCatching { PermLevel.valueOf(raw) }.getOrDefault(cap.default)
    }

    fun setLevel(kind: ConnectionKind, cap: Capability, level: PermLevel) {
        if (cap.locked) return
        meta?.edit()?.putString("perm_${kind.id}_${cap.id}", level.name)?.apply()
        _permVersion.value = _permVersion.value + 1
    }

    private val _permVersion = MutableStateFlow(0)
    val permVersion: StateFlow<Int> = _permVersion.asStateFlow()

    // ---------------- storage ----------------

    private const val META = "plus_vault_meta"
    private const val SECRETS = "plus_vault_secrets"
    private val _entries = MutableStateFlow<Map<String, ConnectionEntry>>(emptyMap())
    val entries: StateFlow<Map<String, ConnectionEntry>> = _entries.asStateFlow()
    @Volatile private var meta: SharedPreferences? = null
    @Volatile private var secretsStore: SharedPreferences? = null
    /**
     * [Mines-38.9.14.2] reads never open the Keystore-backed store on the main thread (seconds on the realme at cold start,
     * a 3 s+ main-thread stall): there they get null until the background open finishes; any other thread opens it.
     */
    private val secrets: SharedPreferences?
        get() {
            secretsStore?.let { return it }
            val ctx = appCtx ?: return null
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                !com.openminis.app.util.EncryptedPrefsFactory.isOpen(SECRETS)) { openSecretsAsync(ctx); return null }
            return secretsW
        }
    /** writes always land: they open the store wherever they run (user actions, long after start-up) */
    private val secretsW: SharedPreferences?
        get() {
            secretsStore?.let { return it }
            val ctx = appCtx ?: return null
            return runCatching { com.openminis.app.util.EncryptedPrefsFactory.safeCreate(ctx, SECRETS) }.getOrNull()?.also { secretsStore = it; fireReady() }
        }
    private val opening = java.util.concurrent.atomic.AtomicBoolean(false)
    private val readyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** [38.9.14.2] run [f] once the secrets store is open (now, if it already is) */
    fun onSecretsReady(f: () -> Unit) { if (secretsStore != null) f() else readyListeners += f }
    private fun fireReady() { val l = readyListeners.toList(); readyListeners.clear(); l.forEach { runCatching { it() } } }
    private fun openSecretsAsync(ctx: Context) {
        if (!opening.compareAndSet(false, true)) return
        Thread({ runCatching { secretsW } }, "mines-vault-open").apply { isDaemon = true }.start()
    }
    @Volatile private var appCtx: Context? = null

    fun init(context: Context) {
        if (meta != null) return
        synchronized(this) {
            if (meta != null) return
            val ctx = context.applicationContext
            appCtx = ctx
            val m = ctx.getSharedPreferences(META, Context.MODE_PRIVATE)
            meta = m
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) openSecretsAsync(ctx) else secretsW
            // [38.9.11.2] atomic file (+2 backups) → the prefs copy → one notice if all fail
            val (file, allFailed) = AtomicStore.read(ctx, "plus_vault_entries.json") { runCatching { JSONArray(it); true }.getOrDefault(false) }
            val src = file ?: m.getString("entries", null)?.takeIf { runCatching { JSONArray(it) }.isSuccess }
                ?: "[]".also { if (allFailed) AtomicStore.notice(ctx, "账号关联") }
            _entries.value = runCatching {
                val arr = JSONArray(src)
                (0 until arr.length()).associate { i ->
                    val o = arr.getJSONObject(i)
                    val e = ConnectionEntry(o.getString("kind"), o.optString("account"),
                        o.optBoolean("share", false), o.optLong("updated"), o.optString("via"))
                    e.kindId to e
                }
            }.getOrDefault(emptyMap())
            // [38.9.14.2] the dedupe reads secrets, so it waits for the store (opened in the background from the main thread)
            if (secretsStore != null) runCatching { dedupeOnce() } else onSecretsReady { synchronized(this) { runCatching { dedupeOnce() } } }
        }
    }

    /** [Mines-38.9.4] issue #2 Bug 2: fold duplicate entries of one service (same key / same identity) into the default one, once. */
    private fun dedupeOnce() {
        val m = meta ?: return
        if (m.getBoolean("dedupe_v3894", false)) return
        val plan = ConnDedupe.plan(_entries.value.values.map { ConnDedupe.E(it.kindId, it.via, it.account, secret(it.kindId)) }) { b ->
            m.getString("default_$b", null)
        }
        for (f in plan) {
            val keep = _entries.value[f.keep] ?: continue
            val drop = _entries.value[f.drop] ?: continue
            if (f.moveSecret) secret(f.drop)?.let { secretsW?.edit()?.putString(f.keep, it)?.apply() }
            _entries.value = _entries.value - f.drop + (f.keep to keep.copy(account = f.account ?: keep.account,
                via = if (keep.via.isBlank()) drop.via else keep.via, shareWithAi = keep.shareWithAi || drop.shareWithAi))
            secretsW?.edit()?.apply { remove(f.drop); secretsW?.all?.keys?.filter { it.startsWith("${f.drop}__") }?.forEach { remove(it) } }?.apply()
            m.edit().apply { m.all.keys.filter { it.startsWith("perm_${f.drop}_") }?.forEach { remove(it) } }.apply()
            if (m.getString("default_${ConnDedupe.base(f.drop)}", null) == f.drop) m.edit().putString("default_${ConnDedupe.base(f.drop)}", f.keep).apply()
        }
        if (plan.isNotEmpty()) persist()
        m.edit().putBoolean("dedupe_v3894", true).apply()
    }

    /** Raw secret, for in-app use only. Never shown in UI. */
    fun secret(kindId: String): String? = secrets?.getString(kindId, null)?.takeIf { it.isNotBlank() }

    /** Extra encrypted values (refresh tokens, saved cookies). */
    fun secretExtra(kindId: String, key: String): String? =
        secrets?.getString("${kindId}__$key", null)?.takeIf { it.isNotBlank() }

    fun setSecretExtra(kindId: String, key: String, value: String?) {
        val e = secretsW?.edit() ?: return
        if (value.isNullOrEmpty()) e.remove("${kindId}__$key") else e.putString("${kindId}__$key", value)
        e.apply()
    }

    fun extra(kindId: String, key: String): String? =
        meta?.getString("extra_${kindId}_$key", null)?.takeIf { it.isNotBlank() }

    fun setExtra(kindId: String, key: String, value: String) {
        meta?.edit()?.putString("extra_${kindId}_$key", value.trim())?.apply()
    }

    fun hasSecret(kindId: String): Boolean = secrets?.contains(kindId) == true

    /** Masked preview only — the UI never shows the full secret. */
    fun preview(kindId: String): String {
        val s = secrets?.getString(kindId, null) ?: return ""
        return if (s.length <= 4) "••••" else "••••" + s.takeLast(4)
    }

    fun save(context: Context, kind: ConnectionKind, account: String, secret: String?, share: Boolean, via: String = "") {
        init(context)
        val old = secrets?.getString(kind.id, null)
        val changed = secret != null && secret.isNotEmpty() && old != secret
        // [Mines-fix-35.1] the replaced key is kept as 「上一个密钥」 for 7 days (one tap to restore), then deleted
        if (changed && !old.isNullOrBlank()) {
            setSecretExtra(kind.id, "prev_key", old)
            setExtra(kind.id, "prev_at", System.currentTimeMillis().toString())
            extra(kind.id, "token_name")?.let { setExtra(kind.id, "prev_token_name", it) } ?: setExtra(kind.id, "prev_token_name", "-")
        }
        if (secret != null && secret.isNotEmpty()) secretsW?.edit()?.putString(kind.id, secret)?.apply()
        if (secret != null && secret.isNotEmpty() && via != "model") runCatching { unmarkRemoved(secret) }   // [Mines-38.9.1] re-added on purpose
        val prev = _entries.value[kind.id]
        val e = ConnectionEntry(kind.id, account.trim(), share, System.currentTimeMillis(),
            via.ifEmpty { prev?.via ?: "" })
        _entries.value = _entries.value + (kind.id to e)
        persist()
        syncEnv(context, kind, share)
        // [Mines-patrol-35.1] new key → test it now and clear the old error (not at the next 4-hourly patrol)
        if (changed) runCatching { AccountPatrol.recheck(context, kind.id) }
    }

    /** [Mines-fix-35.1] 「上一个密钥」 still kept (less than 7 days old)? */
    fun hasPrevKey(kindId: String): Boolean {
        val at = extra(kindId, "prev_at")?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < 7 * 86_400_000L && secretExtra(kindId, "prev_key") != null
    }

    /** Puts the previous key back (the current one becomes the previous one). */
    fun restorePrevKey(context: Context, kindId: String): Boolean {
        val prev = secretExtra(kindId, "prev_key") ?: return false
        val k = kind(kindId) ?: return false
        val e = _entries.value[kindId]
        save(context, k, e?.account ?: "", prev, e?.shareWithAi ?: (k.envKey != null))
        return true
    }

    /** Deletes previous keys older than 7 days. */
    fun purgePrevKeys() {
        _entries.value.keys.forEach { id ->
            val at = extra(id, "prev_at")?.toLongOrNull() ?: return@forEach
            if (System.currentTimeMillis() - at >= 7 * 86_400_000L) { setSecretExtra(id, "prev_key", null); setExtra(id, "prev_at", "-") }
        }
    }

    fun setShare(context: Context, kind: ConnectionKind, share: Boolean) {
        val e = _entries.value[kind.id] ?: return
        _entries.value = _entries.value + (kind.id to e.copy(shareWithAi = share))
        persist()
        syncEnv(context, kind, share)
    }

    fun remove(context: Context, kind: ConnectionKind) {
        init(context)
        // [Mines-38.9.1] a deleted key stays deleted: 模型组 adoption must not bring it back (KeyTombstone)
        runCatching { secret(kind.id)?.takeIf { it.isNotBlank() }?.let { k -> meta?.edit()?.putString("removed_keys", KeyTombstone.add(meta?.getString("removed_keys", null), k))?.apply() } }
        secretsW?.edit()?.apply {
            remove(kind.id)
            secretsW?.all?.keys?.filter { it.startsWith("${kind.id}__") }?.forEach { remove(it) }
        }?.apply()
        _entries.value = _entries.value - kind.id
        if (meta?.getString("default_${baseId(kind.id)}", null) == kind.id) meta?.edit()?.remove("default_${baseId(kind.id)}")?.apply()
        meta?.edit()?.apply { meta?.all?.keys?.filter { it.startsWith("perm_${kind.id}_") }?.forEach { remove(it) } }?.apply()
        persist()
        syncEnv(context, kind, false)
    }

    fun removedKeys(): Set<String> = KeyTombstone.parse(meta?.getString("removed_keys", null))
    /** The user typed / pasted this key again on purpose: adoption may use it again. */
    fun unmarkRemoved(key: String) { meta?.edit()?.putString("removed_keys", KeyTombstone.clear(meta?.getString("removed_keys", null), key))?.apply() }

    private fun persist() {
        val arr = JSONArray()
        _entries.value.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("kind", e.kindId); put("account", e.account)
                put("share", e.shareWithAi); put("updated", e.updatedAt); put("via", e.via)
            })
        }
        appCtx?.let { AtomicStore.write(it, "plus_vault_entries.json", arr.toString()) }
        meta?.edit()?.putString("entries", arr.toString())?.apply()
    }

    private fun syncEnv(context: Context, kind: ConnectionKind, @Suppress("UNUSED_PARAMETER") share: Boolean = false) {
        val app = context.applicationContext as? MinisApp ?: return
        val base = kinds.firstOrNull { it.id == baseId(kind.id) } ?: kind.copy(id = baseId(kind.id))
        runCatching {
            val repo = app.envVarRepository
            fun put(key: String, value: String?) {
                val existing = repo.entries.value.firstOrNull { it.key == key }
                if (!value.isNullOrEmpty()) {
                    val note = "来自 账号连接 · ${base.name}"
                    if (existing == null) repo.add(key, value, note)
                    else if (repo.getValue(key) != value) repo.update(existing.id, key, value, note)
                } else if (existing != null && existing.note.startsWith("来自 账号连接")) {
                    repo.delete(existing.id)
                }
            }
            // Default account → the plain env var; every shared account → <VAR>_ACCOUNTS JSON.
            val accounts = accountsOf(base.id)
            val def = defaultAccount(base.id)
            val defEntry = def?.let { _entries.value[it] }
            val defSecret = if (defEntry?.shareWithAi == true) def.let { secrets?.getString(it, null) } else null
            val key = base.envKey
            if (key != null) {
                put(key, defSecret)
                val shared = accounts.mapNotNull { id ->
                    val e = _entries.value[id] ?: return@mapNotNull null
                    val sec = secrets?.getString(id, null)
                    if (e.shareWithAi && !sec.isNullOrEmpty()) (e.account.ifBlank { id }) to sec else null
                }
                put("${key}_ACCOUNTS", if (shared.size > 1) JSONObject().apply { shared.forEach { (a, v) -> put(a, v) } }.toString() else null)
            }
            if (base.id == "github") put("GH_TOKEN", defSecret)
            if (base.id == "gmail") put("GMAIL_USER", if (defSecret != null) defEntry?.account else null)
            // One overview line the agent sees, naming what is connected and how to act.
            val list = _entries.value.keys.sorted().joinToString(",") { id ->
                val a = _entries.value[id]?.account?.takeIf { it.isNotBlank() }
                if (a != null) "$id($a)" else id
            }
            val ov = repo.entries.value.firstOrNull { it.key == "MINES_CONNECTIONS" }
            val note = "Mines 已连接账号。用这些账号做事之前先运行 minis-connect check <服务> <能力> [--account 账号]；被设为询问的会弹窗请用户确认，禁止的不要做。"
            if (list.isEmpty()) { if (ov != null) repo.delete(ov.id) }
            else if (ov == null) repo.add("MINES_CONNECTIONS", list, note)
            else if (repo.getValue("MINES_CONNECTIONS") != list) repo.update(ov.id, "MINES_CONNECTIONS", list, note)
        }
    }
}
