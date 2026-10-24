package com.openminis.app.plus

/**
 * [Mines-38.9.7] user 2026-10-18 21:22 (phone): 市场 「安装 rea 没成功 / 没有权限」 — npm ran
 * `git ls-remote ssh://git@github.com/morluto/rea.git` → 「Permission denied (publickey)」: the phone sandbox has no GitHub
 * SSH key, so every github: / git+ssh dependency failed. Every sandbox process now gets git config (GIT_CONFIG_COUNT env,
 * no file needed) that rewrites GitHub SSH urls to HTTPS, and GIT_TERMINAL_PROMPT=0 (never hangs on a password prompt).
 * The installer retries once with an HTTPS spec when a publickey error still shows up. Pure (unit-tested).
 */
object MarketGitCore {
    /** Environment for every sandbox process (PRootKernel) and minis-dsh. Order matters for GIT_CONFIG_KEY_n. */
    val GIT_ENV: Map<String, String> = linkedMapOf(
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_CONFIG_COUNT" to "2",
        "GIT_CONFIG_KEY_0" to "url.https://github.com/.insteadOf", "GIT_CONFIG_VALUE_0" to "ssh://git@github.com/",
        "GIT_CONFIG_KEY_1" to "url.https://github.com/.insteadOf", "GIT_CONFIG_VALUE_1" to "git@github.com:",
    )

    private val GH_SHORT = Regex("^github:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?(#.*)?$")
    private val GH_SSH = Regex("^(?:git\\+)?ssh://git@github\\.com[:/]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?(#.*)?$")
    private val GH_SCP = Regex("^git@github\\.com:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?(#.*)?$")

    /** github:o/r, git+ssh://git@github.com/o/r.git, git@github.com:o/r.git → git+https://github.com/o/r.git (#ref kept); others unchanged. */
    fun httpsSpec(spec: String): String {
        val s = spec.trim()
        for (re in listOf(GH_SHORT, GH_SSH, GH_SCP)) re.matchEntire(s)?.let { m ->
            return "git+https://github.com/${m.groupValues[1]}/${m.groupValues[2]}.git" + m.groupValues[4]
        }
        return s
    }

    fun isPublickey(text: String): Boolean =
        Regex("Permission denied \\(publickey\\)|Host key verification failed|ssh://git@github\\.com|git@github\\.com: Permission", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Plain Chinese reason for a failed install (null = no git/GitHub problem in the output). */
    fun reason(text: String): String? = when {
        Regex("RETRIED_HTTPS", RegexOption.IGNORE_CASE).containsMatchIn(text) && isPublickey(text) ->
            "这个插件要从 GitHub 拉代码，已改用 HTTPS 重试，还是没拉下来（检查网络或代理）"
        isPublickey(text) -> "这个插件要从 GitHub 拉代码，手机里没有 GitHub 密钥（已改用 HTTPS 重试）"
        Regex("could not read Username|terminal prompts disabled|Authentication failed for 'https://github", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
            "GitHub 上这个仓库是私有的或不存在，拉不下来"
        Regex("Repository not found|remote: Not Found", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "GitHub 上找不到这个仓库"
        else -> null
    }

    /** What a SkillHub plugin row says when it is not one-tap verified. */
    data class Kind(val pill: String?, val line: String, val canTry: Boolean,
                    /** [Mines-38.9.8] only works on a computer (Windows / macOS / desktop control) → 「只能电脑用」 group, install off */
                    val desktopOnly: Boolean = false,
                    /** [Mines-38.9.8] its value is prompts / workflow / docs → can be turned into a phone skill */
                    val convertible: Boolean = false)

    /**
     * [Mines-38.9.8] user 01:20 (dsh-pilot 「用不了」): a plugin that drives a Windows / macOS desktop can never run in the
     * phone sandbox. Returns 「Windows」 / 「macOS」 / 「电脑」 for such a plugin, null otherwise.
     */
    fun platformOnly(name: String, description: String, topics: List<String>): String? {
        val d = (description + " " + name).lowercase(); val t = topics.map { it.lowercase() }
        val ctl = Regex("screen_view|desktop_control|desktop_sequence|desktop automation|real mouse|mouse,? keyboard|mouse and keyboard|鼠标|键盘鼠标|桌面自动化|操控(电脑|桌面)|pyautogui|pywinauto|uiautomation|autohotkey|\\bahk\\b|win32|winapi|windows api|powershell module|applescript|osascript|accessibility api|\\.exe\\b|\\.dll\\b|\\.msi\\b")
        val win = Regex("windows (machine|desktop|only|pc|电脑)|only (on|for) windows|仅(支持)? ?windows|windows ?专用|windows 桌面|win32|winapi|windows api|\\.exe\\b|\\.msi\\b|autohotkey|pywinauto|powershell module").containsMatchIn(d) || "windows" in t
        val mac = Regex("macos only|only (on|for) macos|仅(支持)? ?mac|applescript|osascript|\\bmac app\\b").containsMatchIn(d) || "macos" in t
        return when {
            win && (ctl.containsMatchIn(d) || "windows" in d) -> "Windows"
            mac -> "macOS"
            ctl.containsMatchIn(d) -> "电脑"
            else -> null
        }
    }

    /** [Mines-38.9.8] prompts / workflow / docs plugin with no desktop control: 「转成手机技能」 is offered. */
    fun convertible(name: String, description: String, topics: List<String>): Boolean {
        if (platformOnly(name, description, topics) != null) return false
        val d = (description + " " + name + " " + topics.joinToString(" ")).lowercase()
        if (Regex("electron|tauri|native binary|二进制|驱动|daemon|gui app|桌面程序").containsMatchIn(d)) return false
        return Regex("prompt|提示词|workflow|工作流|guide|指南|教程|docs?\\b|文档|skill|技能|template|模板|playbook|cheat ?sheet|checklist|规范|案例|best practice|rules?\\b").containsMatchIn(d)
    }

    fun desktopLine(platform: String): String =
        if (platform == "电脑") "这个插件只能在电脑上用（要操控电脑的屏幕、鼠标和键盘），手机上装了也用不了"
        else "这个插件只能在 $platform 电脑上用，手机上装了也用不了"

    fun classify(name: String, description: String, topics: List<String>, category: String, installability: String): Kind {
        val t = topics.map { it.lowercase() }
        val d = description.lowercase()
        val n = name.lowercase()
        // [Mines-38.9.8] platform first: dsh-pilot was SkillHub-verified yet only drives a Windows desktop
        platformOnly(name, description, topics)?.let { return Kind("只能电脑用", desktopLine(it), false, desktopOnly = true) }
        if (installability == "verified") return Kind(null, "SkillHub 已验证：一键安装（固定版本）", true)
        // the harness itself / its desktop shell are hosts, not plugins (Mines is the host here)
        if (n == "deepseek-harness" || n == "dsh" || n == "dsh-desktop") return Kind("宿主", "DSH 本体，不是插件；Mines 自己就是插件宿主", false)
        val desktop = "electron" in t || "tauri" in t || "desktop" in t || "desktop-app" in t || n.endsWith("-desktop") ||
            Regex("桌面端|桌面版|desktop (app|client)|electron").containsMatchIn(d)
        if (desktop) return Kind("电脑版", "电脑桌面程序，手机上跑不了；它的插件可以单独在这里装", false, desktopOnly = true)
        val prompts = Regex("提示词|案例库|prompt(s)? (library|collection)|awesome[- ]").containsMatchIn(d) || n.startsWith("awesome-")
        if (prompts && "dsh-plugin" !in t) return Kind("资料库", "是提示词 / 案例合集，不是可运行的插件；可以转成手机技能", false, convertible = true)
        val dsh = "dsh-plugin" in t || "dsh" in t || "cordis" in t || n.startsWith("dsh-")
        return if (dsh) Kind("试装", "SkillHub 还没验证，可以试装（从 GitHub 用 HTTPS 拉代码）", true, convertible = convertible(name, description, topics))
        else Kind("非 DSH", "不是 DSH 插件（${category.ifBlank { "其他项目" }}），装了也调不起来；打开仓库看", false)
    }

    /**
     * [Mines-38.9.8] 「转成手机技能」: a SKILL.md built from the repo's README (+ prompt files). Pure; [docs] = (path, text).
     * Returns null when there is nothing usable (README empty / too short).
     */
    fun skillMd(owner: String, name: String, description: String, repo: String, docs: List<Pair<String, String>>, maxChars: Int = 24_000): String? {
        val body = docs.filter { it.second.isNotBlank() }
        if (body.sumOf { it.second.trim().length } < 80) return null
        val desc = description.replace(Regex("\\s+"), " ").trim().ifBlank { "$name（从 GitHub 仓库转来的技能）" }.take(300)
        val slug = skillSlug(name)
        val sb = StringBuilder()
        sb.append("---\nname: ").append(slug).append("\ndescription: ").append(desc.replace("\n", " ")).append("\nsource: ").append(repo.ifBlank { "https://github.com/$owner/$name" }).append("\n---\n\n")
        sb.append("# ").append(name).append("\n\n")
        sb.append("> 由 Mines 从 ").append(repo.ifBlank { "https://github.com/$owner/$name" }).append(" 的说明文件转成的手机技能：只用里面的提示词、流程和说明，不运行仓库里的程序。\n\n")
        var left = maxChars - sb.length
        for ((path, text) in body) {
            if (left <= 200) break
            val t = text.trim().let { if (it.length > left - 40) it.take(left - 60) + "\n\n…（后面太长，已截断）" else it }
            sb.append("## ").append(path).append("\n\n").append(t).append("\n\n"); left = maxChars - sb.length
        }
        return sb.toString()
    }

    fun skillSlug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(60).ifBlank { "repo-skill" }
}
