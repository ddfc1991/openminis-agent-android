package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] SkillHub install state keyed by publisher namespace + slug (38.9.4 smoke: after installing one publisher's
 * 「weather」, all 3 publishers' rows said 已安装 and 更新 on another row would have overwritten it with a different package).
 * The skill still lives in one dir per slug (that is how skills are loaded), so a second publisher's same-slug skill is
 * 「同名已装」 and only replaced on an explicit 替换. Source line: `skillhub:<namespace>/<slug>@<version>`; 38.9.4 wrote
 * `skillhub:<slug>@<version>` (publisher unknown → never claimed as this row's). Pure, unit-tested in SkillInstallKeyTest.
 */
object SkillInstallKey {
    enum class State { NONE, MINE, OTHER, LOCAL }

    fun source(namespace: String, slug: String, version: String): String =
        "skillhub:" + (if (namespace.isNotBlank()) "$namespace/" else "") + slug + "@" + version

    /** (namespace, slug, version) of a source line; namespace "" = not recorded (38.9.4) / none; null = not from SkillHub. */
    fun parse(src: String?): Triple<String, String, String>? {
        val s = src?.trim().orEmpty()
        if (!s.startsWith("skillhub:")) return null
        val body = s.removePrefix("skillhub:")
        val id = body.substringBefore('@'); val ver = body.substringAfter('@', "")
        return if ('/' in id) Triple(id.substringBefore('/'), id.substringAfter('/'), ver) else Triple("", id, ver)
    }

    /** [src] = the installed dir's source record (null = nothing installed under this slug, "local" = the user's own). */
    fun state(src: String?, namespace: String, slug: String): State {
        if (src == null) return State.NONE
        val p = parse(src) ?: return State.LOCAL
        if (p.second != slug) return State.OTHER
        if (p.first.isBlank()) return if (namespace.isBlank()) State.MINE else State.OTHER
        return if (p.first.equals(namespace, ignoreCase = true)) State.MINE else State.OTHER
    }

    /** Row identity in lists (two publishers' same slug are two rows). */
    fun rowKey(namespace: String, slug: String): String = if (namespace.isBlank()) slug else "$namespace/$slug"
}
