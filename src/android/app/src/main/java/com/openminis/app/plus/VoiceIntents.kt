package com.openminis.app.plus

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-voice-35.2] Things said in a call that Mines can just DO on the phone, instead of the
 * voice only promising them (phone, build-35.1: 「设定好我的声音录入」 → 「我马上为您设置」, nothing
 * opened). Used three ways: the `open_mines_page` tool the Live model can call, a keyword match on
 * the user's own words after each turn (works even when the model forgets the tool), and the
 * backup channel ([PlainVoice]) before the request goes to the agent.
 */
object VoiceIntents {
    const val TOOL = "open_mines_page"
    const val VOICEPRINT = "voiceprint_enroll"
    const val VOICE_SETTINGS = "voice_settings"
    const val CONNECTIONS = "connections"

    const val PROMPT = "用户要录入/设置自己的声音或声纹、改语音设置、打开账号连接时，直接调用 open_mines_page 打开对应页面，" +
        "不要只口头答应。任何工具失败都要直说没办成和原因。"

    /** Page the voice screen should open now (consumed and cleared by VoiceLiveScreen). */
    val request = MutableStateFlow<String?>(null)
    private val openedAt = HashMap<String, Long>()

    fun label(page: String) = when (page) {
        VOICEPRINT -> "声纹录入"
        VOICE_SETTINGS -> "语音设置"
        CONNECTIONS -> "账号"
        else -> page
    }

    fun toolDeclaration(): JSONObject = JSONObject()
        .put("name", TOOL)
        .put("description", "在手机上直接打开 Mines 的某个页面：voiceprint_enroll=录入用户的声音/声纹（只听用户的声音），" +
            "voice_settings=语音设置（换声音、语速），connections=账号连接（填 API Key）。")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("page", JSONObject().put("type", "STRING")
                .put("enum", JSONArray().put(VOICEPRINT).put(VOICE_SETTINGS).put(CONNECTIONS))
                .put("description", "要打开的页面")))
            .put("required", JSONArray().put("page")))

    private val notMine = Regex("你的声音|换.{0,3}(个)?声音|声音.{0,3}(大|小|快|慢|好听)|音色")
    private val voiceprint = listOf(
        Regex("(录|设|定|登记|注册|采集|添加|建立|弄|做|存).{0,6}(声纹|我的声音|我自己的声音|我的嗓音)"),
        Regex("(声纹|我的声音|声音录入).{0,4}(录入|录一下|录一遍|登记|设置|设定|弄好|搞好)"),
        Regex("只听我(的声音|说话)"),
    )
    private val voiceSettings = Regex("(打开|进入|去|开).{0,3}语音设置|(换|改).{0,4}(声音|音色)")
    private val connections = Regex("(打开|进入|去).{0,3}(账号连接|账号管理|填.{0,3}(key|密钥))", RegexOption.IGNORE_CASE)

    /** The page the user's words ask for, or null. */
    fun match(userText: String): String? {
        val t = userText.replace(Regex("[\\s，。！？,.!?]"), "")
        if (t.isEmpty()) return null
        if (voiceprint.any { it.containsMatchIn(t) } && !notMine.containsMatchIn(t.replace("我的声音", ""))) return VOICEPRINT
        if (connections.containsMatchIn(t)) return CONNECTIONS
        if (voiceSettings.containsMatchIn(t)) return VOICE_SETTINGS
        return null
    }

    private val promise = Regex("我去办|我这就|(帮你|为您|给你)去|马上(为您|为你|帮你|给你|去|就)|现在就(去|帮|为)|我来(帮你|为您)?(设置|处理|办|弄)|这就去|正在为您|稍等(一下)?我")

    /** True when the answer only promised to act (「好，我马上为您设置」). */
    fun isPromise(answer: String) = promise.containsMatchIn(answer)

    @Synchronized fun recentlyOpened(page: String): Boolean {
        val t = openedAt[page] ?: return false
        return System.currentTimeMillis() - t < 15_000
    }

    /** Opens [page]; returns one plain line for the transcript / the tool result. */
    @Synchronized fun open(c: Context, page: String): String {
        val p = when (page) { VOICEPRINT, VOICE_SETTINGS, CONNECTIONS -> page; else -> return "没办成：没有「$page」这个页面" }
        openedAt[p] = System.currentTimeMillis()
        val ui = com.openminis.app.ui.plus.PlusNav
        val routes = com.openminis.app.ui.plus.PlusRoutes
        if (p != CONNECTIONS) request.value = p
        // navigation must run on the main thread (calls come from the socket / IO threads)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // already on the call page: it shows the page itself (no second copy of the call screen)
            if (p == CONNECTIONS || !PetBus.voicePageOpen.value) runCatching { ui.go(if (p == CONNECTIONS) routes.CONNECTIONS else routes.VOICE) }
        }
        // bring Mines to the front when the call runs in the background (may be refused by Android;
        // then the page is opened the next time Mines is shown)
        val front = runCatching {
            c.startActivity(Intent(c, com.openminis.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.isSuccess
        android.util.Log.i("MinesVoice", "open page $p front=$front")
        return "已打开「${label(p)}」" + if (p == VOICEPRINT) "，照屏幕提示读三句话就行" else ""
    }
}
