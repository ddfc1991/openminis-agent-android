package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One VPS the user added. No server is pre-filled. */
data class VpsServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val enabled: Boolean = true,
    /** Last measured TCP connect latency in ms, -1 = unknown, -2 = unreachable. */
    val lastLatencyMs: Int = -1,
    val lastCheckedAt: Long = 0L,
    /**
     * [Mines-server-card] What the box reports about itself, collected during
     * the periodic probe and shown on the 工作位置 card. Empty until the first
     * successful probe — an empty string reads as "检测中", never as a wrong
     * value.
     */
    val osLabel: String = "",
    /** The IP the server's own traffic leaves from, as it sees itself. */
    val egressIp: String = "",
    /**
     * [Mines-vps-link] Optional fallback route when the server's own SSH port
     * can't be reached from the phone: ws(s):// WebSocket bridge, ssh:// jump
     * host, http:// CONNECT or socks5:// proxy. Tried after a direct attempt.
     */
    val relay: String = "",
    /** The app's own key is installed and accepted (set by a real login). */
    val authorized: Boolean = false,
    /** Why the last check failed, in plain words ("" when it worked). */
    val lastError: String = "",
    /** [Mines-vps-31] Extra SSH port to try when the main one is cut (0 = 443 when main is 22). */
    val altPort: Int = 0,
    /** [Mines-vps-31] Tailscale / other private host name for the same box (tried when the public IP fails). */
    val tailscaleHost: String = "",
    /** [Mines-vps-31] Cloudflare Tunnel hostname that maps to this box's ssh://localhost:22 (tried first). */
    val cfHost: String = "",
    /** [Mines-vps-31] Mines link worker (wss://host:443/mines) token; blank = not installed. */
    val linkToken: String = "",
    val linkUrl: String = "",
    /** The last successful check went through [relay], not directly. */
    val viaRelay: Boolean = false,
) {
    val target: String get() = "$user@$host"
}

data class PlusState(
    val workMode: String = MODE_LOCAL,
    val servers: List<VpsServer> = emptyList(),
    val wallpaper: String = "",          // "", "preset:<id>", "file:<version>"
    val wallpaperDim: Float = 0.25f,
    /** Wallpaper opacity, 0..1 (separate from the dim layer). */
    val wallpaperAlpha: Float = 1f,
    /** Also show the wallpaper behind the conversation list. */
    val wallpaperOnHome: Boolean = false,
    val petEnabled: Boolean = false,
    val petStyle: String = "claude",      // Avatars.builtins id, or "custom"
    val petColor: Int = 0xFFD97757.toInt(),
    /** Bumped whenever the custom picture or its crop changes (cache key). */
    val avatarVersion: Long = 0L,
    val avatarScale: Float = 1f,
    val avatarOffX: Float = 0f,
    val avatarOffY: Float = 0f,
    val avatarRemoveBg: Boolean = false,
    val todayCollapsed: Boolean = false,
    /** [Mines-a11y-26] Home card text colour: "auto" (by background brightness), "white", "black", "custom". */
    val cardTextMode: String = "auto",
    val cardTextColor: Int = 0xFFFFFFFF.toInt(),
    /** [Mines-glass-26] Frosted-glass home cards: on/off, blur radius (dp), scrim opacity. */
    val glassOn: Boolean = true,
    val glassBlur: Int = 20,
    val glassOpacity: Float = 0.6f,
    val autoFallback: Boolean = true,
    val autoClean: Boolean = true,
    // [Mines-pet-25] Desk pet behaviour.
    /** [Mines-pet-32] size value 70…340; 150 ≈ one third of the screen width (build-31 key petSizeDp is retired). */
    val petSizeDp: Int = 150,
    /** [Mines-pet-32] white outline so the pet stands out on busy wallpapers. */
    val petOutline: Boolean = false,
    /** Touches go straight through the pet to the app underneath. */
    val petPassThrough: Boolean = false,
    /** Fade + shrink while the user touches / types in another app. */
    val petAutoFade: Boolean = true,
    val petEdgeSnap: Boolean = true,
    /** After snapping, tuck half of the pet behind the screen edge when idle. */
    val petHalfHide: Boolean = true,
    /** Now and then walk a little along the screen edge. */
    val petWalk: Boolean = true,
    /** Tap shows the status panel (tasks, balances, quick actions). */
    val petPanel: Boolean = true,
    /** [Mines-voice-31] Tap the pet = start / stop the live voice call (double tap = status panel). */
    val petTapVoice: Boolean = true,
    /** Offline wake word (Vosk) → voice chat. Off by default (uses the mic + battery). */
    val wakeWord: Boolean = false,
    val wakeText: String = "你好小鲸",
) {
    val isVps: Boolean get() = workMode == MODE_VPS
    val onlineServers: List<VpsServer> get() = servers.filter { it.enabled && it.lastLatencyMs >= 0 }

    companion object {
        const val MODE_LOCAL = "local"
        const val MODE_VPS = "vps"
    }
}

/**
 * Minis Plus settings: work mode, VPS list, wallpaper, avatar / desk pet.
 * Plain SharedPreferences (none of this is secret — SSH keys live in the
 * sandbox, account secrets live in [PlusVault]). Exposed as a process-wide
 * StateFlow so every screen updates live.
 */
object PlusPrefs {
    private const val FILE = "minis_plus"
    private val _state = MutableStateFlow(PlusState())
    val state: StateFlow<PlusState> = _state.asStateFlow()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            // [Mines-avatars-25] one-time id migration (gpt→grok, qwen→gpt).
            if (!p.getBoolean("avatarIds25", false)) {
                val old = p.getString("petStyle", null)
                p.edit().apply { if (old != null) putString("petStyle", Avatars.migrate24(old)) }
                    .putBoolean("avatarIds25", true).apply()
            }
            // [Mines-design-34] Default character is now Claude (user-approved design). The old
            // default 小鲸 is switched once; picking 小鲸 again afterwards sticks.
            if (!p.getBoolean("avatarDefault34", false)) {
                val cur = p.getString("petStyle", null)
                p.edit().apply { if (cur == null || cur == "whale") putString("petStyle", "claude") }
                    .putBoolean("avatarDefault34", true).apply()
            }
            _state.value = read(p)
        }
    }

    private fun read(p: SharedPreferences): PlusState {
        val servers = runCatching {
            val arr = JSONArray(p.getString("servers", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VpsServer(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user", "root"),
                    enabled = o.optBoolean("enabled", true),
                    lastLatencyMs = o.optInt("lat", -1),
                    lastCheckedAt = o.optLong("checked", 0L),
                    osLabel = o.optString("os"),
                    egressIp = o.optString("egress"),
                    relay = o.optString("relay"),
                    altPort = o.optInt("altPort", 0), tailscaleHost = o.optString("tailscaleHost"), cfHost = o.optString("cfHost"),
                    linkToken = o.optString("linkToken"), linkUrl = o.optString("linkUrl"),
                    authorized = o.optBoolean("authorized", false),
                    lastError = o.optString("err"),
                    viaRelay = o.optBoolean("viaRelay", false),
                )
            }
        }.getOrDefault(emptyList())
        return PlusState(
            workMode = p.getString("workMode", PlusState.MODE_LOCAL) ?: PlusState.MODE_LOCAL,
            servers = servers,
            wallpaper = p.getString("wallpaper", "") ?: "",
            wallpaperDim = p.getFloat("wallpaperDim", 0.25f),
            wallpaperAlpha = p.getFloat("wallpaperAlpha", 1f),
            wallpaperOnHome = p.getBoolean("wallpaperOnHome", false),
            petEnabled = p.getBoolean("petEnabled", false),
            petStyle = Avatars.normalize(p.getString("petStyle", "claude") ?: "claude"),
            // build-3..19 default blue → Mines terracotta
            petColor = p.getInt("petColor", 0xFFD97757.toInt()).let { if (it == 0xFF5B8DEF.toInt() && !p.contains("avatarVersion")) 0xFFD97757.toInt() else it },
            avatarVersion = p.getLong("avatarVersion", 0L),
            avatarScale = p.getFloat("avatarScale", 1f),
            avatarOffX = p.getFloat("avatarOffX", 0f),
            avatarOffY = p.getFloat("avatarOffY", 0f),
            avatarRemoveBg = p.getBoolean("avatarRemoveBg", false),
            todayCollapsed = p.getBoolean("todayCollapsed", false),
            cardTextMode = p.getString("cardTextMode", "auto") ?: "auto",
            cardTextColor = p.getInt("cardTextColor", 0xFFFFFFFF.toInt()),
            glassOn = p.getBoolean("glassOn", true),
            glassBlur = p.getInt("glassBlur", 20),
            glassOpacity = p.getFloat("glassOpacity", 0.6f),
            autoFallback = p.getBoolean("autoFallback", true),
            autoClean = p.getBoolean("autoClean", true),
            petSizeDp = p.getInt("petSize32", 150),
            petOutline = p.getBoolean("petOutline", false),
            petPassThrough = p.getBoolean("petPassThrough", false),
            petAutoFade = p.getBoolean("petAutoFade", true),
            petEdgeSnap = p.getBoolean("petEdgeSnap", true),
            petHalfHide = p.getBoolean("petHalfHide", true),
            petWalk = p.getBoolean("petWalk", true),
            petPanel = p.getBoolean("petPanel", true),
            petTapVoice = p.getBoolean("petTapVoice", true),
            wakeWord = p.getBoolean("wakeWord", false),
            wakeText = p.getString("wakeText", "你好小鲸") ?: "你好小鲸",
        )
    }

    fun update(context: Context, block: (PlusState) -> PlusState) {
        init(context)
        val next = synchronized(this) {
            val n = block(_state.value)
            _state.value = n
            n
        }
        val arr = JSONArray()
        next.servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("host", s.host)
                put("port", s.port); put("user", s.user); put("enabled", s.enabled)
                put("lat", s.lastLatencyMs); put("checked", s.lastCheckedAt)
                put("os", s.osLabel); put("egress", s.egressIp); put("relay", s.relay); put("altPort", s.altPort); put("tailscaleHost", s.tailscaleHost); put("cfHost", s.cfHost); put("linkToken", s.linkToken); put("linkUrl", s.linkUrl)
                put("authorized", s.authorized); put("err", s.lastError); put("viaRelay", s.viaRelay)
            })
        }
        prefs?.edit()
            ?.putString("workMode", next.workMode)
            ?.putString("servers", arr.toString())
            ?.putString("wallpaper", next.wallpaper)
            ?.putFloat("wallpaperDim", next.wallpaperDim)
            ?.putFloat("wallpaperAlpha", next.wallpaperAlpha)
            ?.putBoolean("wallpaperOnHome", next.wallpaperOnHome)
            ?.putLong("avatarVersion", next.avatarVersion)
            ?.putFloat("avatarScale", next.avatarScale)
            ?.putFloat("avatarOffX", next.avatarOffX)
            ?.putFloat("avatarOffY", next.avatarOffY)
            ?.putBoolean("avatarRemoveBg", next.avatarRemoveBg)
            ?.putBoolean("petEnabled", next.petEnabled)
            ?.putString("petStyle", next.petStyle)
            ?.putInt("petColor", next.petColor)
            ?.putBoolean("todayCollapsed", next.todayCollapsed)
            ?.putString("cardTextMode", next.cardTextMode)
            ?.putInt("cardTextColor", next.cardTextColor)
            ?.putBoolean("glassOn", next.glassOn)
            ?.putInt("glassBlur", next.glassBlur)
            ?.putFloat("glassOpacity", next.glassOpacity)
            ?.putBoolean("autoFallback", next.autoFallback)
            ?.putBoolean("autoClean", next.autoClean)
            ?.putInt("petSize32", next.petSizeDp)
            ?.putBoolean("petOutline", next.petOutline)
            ?.putBoolean("petPassThrough", next.petPassThrough)
            ?.putBoolean("petAutoFade", next.petAutoFade)
            ?.putBoolean("petEdgeSnap", next.petEdgeSnap)
            ?.putBoolean("petHalfHide", next.petHalfHide)
            ?.putBoolean("petWalk", next.petWalk)
            ?.putBoolean("petPanel", next.petPanel)
            ?.putBoolean("petTapVoice", next.petTapVoice)
            ?.putBoolean("wakeWord", next.wakeWord)
            ?.putString("wakeText", next.wakeText)
            ?.apply()
    }
}
