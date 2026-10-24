package com.openminis.app.agent

/**
 * [Mines-38.9.9] 内置提示词 (user 2026-10-19 09:04): always-on, concise Chinese working rules appended to the main agent's
 * system prompt — 排障纪律, 解决后归档, 高级工程师, 自动化工作流, 红蓝队（仅限授权）. Editable in 设置 › Agent 工具 › 内置提示词
 * (each section can be edited, switched off, or restored). The text is byte-stable unless the user edits it (prompt cache).
 * Pure: storage is passed in, unit-tested.
 */
object BuiltinPrompts {
    data class Section(val key: String, val title: String, val default: String)

    val SECTIONS = listOf(
        Section("debug", "排障纪律", """排障纪律（内置）：不要停在表面现象上兜圈子。
1. 先问本质：这个东西本来是干什么的？选它做这件事对不对？（例：把带机器人的平台当反代用，慢是天生的，调参数救不了。）
2. 先测量再读代码：把链路分段（客户端 → 代理/网关 → 上游），测出每一层的耗时和结果，用数字定位瓶颈。
3. 一次只提一个假设，用一个最小实验去证伪；一次只改一处，改完用同样的方法复测对比。
4. 同一方向两次没进展就停：退一步重审前提（第 1 条），并换更强的模型来想。
5. 结论分清「测到的」（附数字/日志）和「猜的」（标明推测），再写下一步。"""),
        Section("archive", "解决后归档", """解决后归档（内置）：问题解决后，用几行总结完整流程（现象 → 根因 → 改了什么 → 怎么验证），并自动存成可复用的工作流/技能（工作流记录），下次遇到类似问题先调用它。"""),
        Section("senior", "高级工程师", """工作方式（内置）：像资深工程师一样做事——先看本质再动手；读过再改，改完必验证；最小改动；不确定就查证，不编造；结论简短直接，说明验证了什么、没验证什么。用户教过的方法（记忆里、技能里标为学到的）优先于通用习惯：选方法前先查它们，照着做。"""),
        Section("automation", "自动化工作流", """自动化（内置）：把任务拆成可复用的小步骤；每步可重复执行而不产生副作用（幂等），先检查当前状态再操作；每步记录输入、输出和结果；失败时有限次重试（最多 2 次、间隔递增），超过就停下报告原因。"""),
        Section("redblue", "红蓝队（仅限授权）", """安全测试（内置，仅限授权与防御）：只在用户拥有或书面授权的系统上做安全测试，开始前先确认范围（目标、时间、方式）；绝不攻击未授权的系统，不帮助入侵、窃取或破坏。红队发现的每个问题都同时给出蓝队视角：如何检测（日志/告警特征）、如何修复与加固、如何验证已修好。"""),
    )

    /** what the user stored for one section: null = default text; enabled false = section off */
    data class Stored(val text: String?, val enabled: Boolean = true)

    fun textOf(s: Section, stored: Stored?): String = stored?.text?.takeIf { it.isNotBlank() } ?: s.default

    /** the block appended to the system prompt ("" when every section is off) */
    fun block(read: (String) -> Stored?): String {
        val parts = SECTIONS.mapNotNull { s -> val st = read(s.key); if (st != null && !st.enabled) null else textOf(s, st) }
        return parts.joinToString("\n\n")
    }

    // ---- Android storage (SharedPreferences "mines_builtin_prompts": t_<key> text, on_<key> enabled) ----
    private const val PREFS = "mines_builtin_prompts"
    fun stored(c: android.content.Context, key: String): Stored? {
        val p = c.getSharedPreferences(PREFS, 0)
        if (!p.contains("t_$key") && !p.contains("on_$key")) return null
        return Stored(p.getString("t_$key", null), p.getBoolean("on_$key", true))
    }
    fun save(c: android.content.Context, key: String, text: String?, enabled: Boolean) {
        val s = SECTIONS.first { it.key == key }
        c.getSharedPreferences(PREFS, 0).edit()
            .apply { if (text == null || text.trim() == s.default.trim()) remove("t_$key") else putString("t_$key", text) }
            .putBoolean("on_$key", enabled).apply()
    }
    fun reset(c: android.content.Context, key: String) { c.getSharedPreferences(PREFS, 0).edit().remove("t_$key").remove("on_$key").apply() }
    fun prompt(c: android.content.Context): String = block { stored(c, it) }
}
