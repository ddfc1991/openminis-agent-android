package com.openminis.app.browser

/**
 * [Mines-38.9.11.2] 每个对话的隔离，账号隔离 (user 2026-10-21). Pure, unit-tested (ConvProfilesCoreTest).
 *  - every chat conversation's agent browser lives in its OWN WebView profile ("mines-conv-<id>"), kept for that conversation
 *    (its logins stay inside it) and deleted with the conversation; the home browser keeps the default profile;
 *  - a draft conversation that becomes a real one keeps its profile (alias);
 *  - a 密码库 account is auto-filled only in a conversation where the user picked it (binding conversation → site → account);
 *    elsewhere the secure login card asks first, prefilled with the saved account.
 */
object ConvProfilesCore {
    const val PREFIX = "mines-conv-"
    /** pools that are not one conversation's (home browser / CLI / smoke) */
    val SHARED_IDS = setOf("minis-browser-use")

    fun isConversation(sessionId: String?): Boolean =
        !sessionId.isNullOrBlank() && sessionId !in SHARED_IDS && !sessionId.startsWith("smoke397-")

    /** profile names allow [A-Za-z0-9_-]; long ids are shortened with a stable hash */
    fun profileName(sessionId: String): String {
        val clean = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return PREFIX + if (clean.length <= 48) clean else clean.take(32) + "-" + Integer.toHexString(sessionId.hashCode())
    }

    fun isConvProfile(name: String?) = name != null && name.startsWith(PREFIX)

    /** profiles whose conversation no longer exists (kept: [mapped] values and those of live ids) */
    fun orphans(allProfiles: Collection<String>, mapped: Collection<String>): List<String> =
        allProfiles.filter { isConvProfile(it) && it !in mapped }

    // ── account binding: "sid|site" → account ──
    fun bindKey(sessionId: String, site: String) = sessionId + "|" + site.lowercase().removePrefix("www.")

    /** auto-fill a saved account here only when the user picked exactly it in this conversation */
    fun mayAutoFill(bindings: Map<String, String>, sessionId: String?, site: String, account: String): Boolean {
        if (!isConversation(sessionId)) return true            // home browser: the 密码库 behaves as before
        return bindings[bindKey(sessionId!!, site)]?.equals(account, ignoreCase = true) == true
    }

    /** bindings after a conversation is deleted / renamed */
    fun dropSession(bindings: Map<String, String>, sessionId: String) = bindings.filterKeys { !it.startsWith("$sessionId|") }
    fun renameSession(bindings: Map<String, String>, from: String, to: String) =
        bindings.mapKeys { (k, _) -> if (k.startsWith("$from|")) to + k.substring(from.length) else k }
}
