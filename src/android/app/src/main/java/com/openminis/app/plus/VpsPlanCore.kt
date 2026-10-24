package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.13 vps_page_v2] VPS / 电脑: who does what, and where each kind of task goes (user 2026-10-24 16:53–17:23).
 * Hark's default plan is built in; the user's changes are stored as overrides only, so an unchanged rule keeps following
 * the default — including the planned switch of 国内网站 / 中转 from 阿里云 to 梦云 on [MIGRATE_AT] (阿里云 credit ends ~12-21).
 * 登录 / 验证码 / 支付 always stay on the phone. Pure part (no Android), unit-tested in VpsPlanCoreTest.
 */
object VpsPlanCore {
    const val PHONE = "phone"; const val ALIYUN = "aliyun"; const val AWS = "aws"; const val MENGYUN = "mengyun"
    const val INSTABOX = "instabox"; const val ASCS = "ascs"; const val KATABUMP = "katabump"; const val GHA = "gha"

    /** 2026-12-14 00:00 Asia/Shanghai: 阿里云 迁移提醒 — from here the 国内网站 / 中转 default is 梦云. */
    const val MIGRATE_AT = 1_797_177_600_000L
    const val THRESHOLD_DEFAULT = 88
    val THRESHOLD_RANGE = 50..95

    data class Machine(
        val id: String, val name: String, val role: String, val sub: String,
        val accepts: String, val forbids: String,
        /** words in a 工作位置 server's name / address that mean this machine */
        val match: List<String> = emptyList(),
        val inSubDefault: Boolean = false, val canSub: Boolean = false, val canPause: Boolean = true,
        val expiry: String = "", val note: String = "",
    )

    val MACHINES = listOf(
        Machine(ALIYUN, "阿里云", "国内中转", "香港", "国内网站 · 中转 · 代理", "登录/支付 · 重任务",
            listOf("阿里", "aliyun", "8.217.89.133"), inSubDefault = true, canSub = true,
            expiry = "试用 2027-01-07", note = "额度约 12-21 用完 · 12-14 提醒迁移到梦云"),
        Machine(AWS, "AWS siyun-vps", "总枢纽", "t4g.small 2核 / 2G", "调度 · 定时 · 轻任务 · 代理", "重任务 · 编译",
            listOf("siyun", "aws", "18.143.139.29"), inSubDefault = true, canSub = true),
        Machine(MENGYUN, "梦云", "模型网关", "香港 · 2核 / 2G", "模型网关(new-api) · 轻浏览器(Steel, 1G 上限) · 香港代理出口",
            "重任务 · 编译 · 长时间爬取", listOf("梦云", "mengyun", "38.76.209.77"), inSubDefault = true, canSub = true,
            note = "只接轻任务 · 12-14 起接手阿里云的国内网站 / 中转"),
        Machine(INSTABOX, "InstaBox", "重任务", "免费 · 4核 / 8G · 闲置自动暂停，有重任务自动唤醒", "重任务 · 云浏览器 · 编译",
            "登录/验证码/支付", listOf("instabox"), canSub = true),
        Machine(ASCS, "ASCS", "看门狗", "仅 IPv6 · 常断线", "看门狗 · 备份", "主任务 · 代理 · 需要 IPv4 的事",
            listOf("ascs", "2001:470:1f2f:164::57f"), canSub = true),
        Machine(KATABUMP, "katabump", "定时续期", "平台 · 名称待确认 · 到期待确认", "定时续期", "长任务 · 存密钥", listOf("katabump")),
        Machine(GHA, "GitHub Actions", "编译", "云端 · 用时才开机", "编译 · 冒烟测试", "测试版上架 · 存密钥", canPause = false),
        Machine(PHONE, "手机（本机）", "专用", "本机", "登录 · 验证码 · 支付", "重任务 · 长任务 · 代理", canPause = false),
    )

    fun machine(id: String): Machine? = MACHINES.firstOrNull { it.id == id }

    data class Rule(val id: String, val label: String, val target: String, val note: String = "", val locked: Boolean = false, val custom: Boolean = false)

    /** Hark's default rules at time [now]. */
    fun defaultRules(now: Long): List<Rule> = listOf(
        Rule("login", "登录 / 验证码 / 支付", PHONE, "只在本机做，不能换", locked = true),
        Rule("cn", "国内网站 / 中转", if (now >= MIGRATE_AT) MENGYUN else ALIYUN),
        Rule("heavy", "重任务 / 长任务", INSTABOX, "先自动唤醒，做完闲置再暂停"),
        Rule("light", "定时 / 轻任务", AWS),
        Rule("gateway", "模型网关 / 轻浏览器", MENGYUN),
        Rule("build", "编译 / 冒烟", GHA),
        Rule("watch", "看门狗 / 备份", ASCS),
        Rule("renew", "定时续期", KATABUMP),
    )

    val FAILOVER_DEFAULT = listOf(INSTABOX, AWS, MENGYUN, ALIYUN)

    /** The user's changes only; anything not here follows the default. */
    data class Overrides(
        val targets: Map<String, String> = emptyMap(),
        val custom: List<Rule> = emptyList(),
        val failover: List<String>? = null,
        val threshold: Int? = null,
        val paused: Set<String> = emptySet(),
        val sub: Map<String, Boolean> = emptyMap(),
        val accepts: Map<String, String> = emptyMap(),
        val forbids: Map<String, String> = emptyMap(),
        val idleMin: Int? = null,
    )

    /** Machines a rule may point at: everything but the phone (the phone only takes the locked 登录 rule). */
    val TARGETS: List<String> = MACHINES.map { it.id }.filter { it != PHONE }

    fun rules(o: Overrides, now: Long): List<Rule> =
        defaultRules(now).map { r -> if (r.locked) r else o.targets[r.id]?.takeIf { it in TARGETS }?.let { r.copy(target = it) } ?: r } + o.custom

    /** The default target when [r] was changed by the user; null = unchanged (or a rule the user added). */
    fun changedFrom(r: Rule, now: Long): String? {
        if (r.custom || r.locked) return null
        val d = defaultRules(now).firstOrNull { it.id == r.id }?.target ?: return null
        return d.takeIf { it != r.target }
    }

    fun setTarget(o: Overrides, ruleId: String, target: String, now: Long): Overrides {
        if (target !in TARGETS) return o
        if (o.custom.any { it.id == ruleId }) return o.copy(custom = o.custom.map { if (it.id == ruleId) it.copy(target = target) else it })
        val d = defaultRules(now).firstOrNull { it.id == ruleId } ?: return o
        if (d.locked) return o
        return o.copy(targets = if (d.target == target) o.targets - ruleId else o.targets + (ruleId to target))
    }

    fun addRule(o: Overrides, label: String, target: String, id: String): Overrides {
        val l = label.trim()
        if (l.isBlank() || target !in TARGETS) return o
        return o.copy(custom = o.custom + Rule(id, l.take(30), target, custom = true))
    }

    fun removeRule(o: Overrides, id: String): Overrides = o.copy(custom = o.custom.filter { it.id != id })

    fun failover(o: Overrides): List<String> {
        val f = o.failover?.filter { it in TARGETS }?.distinct().orEmpty()
        return if (f.isEmpty()) FAILOVER_DEFAULT else f
    }

    fun failoverChanged(o: Overrides): Boolean = failover(o) != FAILOVER_DEFAULT

    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices) return list
        val m = list.toMutableList(); val x = m.removeAt(from); m.add(to.coerceIn(0, m.size), x); return m
    }

    fun threshold(o: Overrides): Int = (o.threshold ?: THRESHOLD_DEFAULT).coerceIn(THRESHOLD_RANGE)
    fun bumpThreshold(o: Overrides, delta: Int): Overrides = o.copy(threshold = (threshold(o) + delta).coerceIn(THRESHOLD_RANGE).takeIf { it != THRESHOLD_DEFAULT })

    /** 恢复默认 on the routing section: rules, failover and the stop line go back to Hark's plan (machine settings stay). */
    fun restoreRouting(o: Overrides): Overrides = o.copy(targets = emptyMap(), custom = emptyList(), failover = null, threshold = null)

    fun inSub(o: Overrides, m: Machine): Boolean = m.canSub && (o.sub[m.id] ?: m.inSubDefault)
    fun accepts(o: Overrides, m: Machine): String = o.accepts[m.id]?.takeIf { it.isNotBlank() } ?: m.accepts
    fun forbids(o: Overrides, m: Machine): String = o.forbids[m.id]?.takeIf { it.isNotBlank() } ?: m.forbids
    fun idleMin(o: Overrides): Int = (o.idleMin ?: 10).coerceIn(5, 120)

    /** Which 工作位置 entry is this machine: the first whose name / address carries one of [Machine.match]. */
    fun matchIndex(m: Machine, names: List<String>): Int {
        if (m.match.isEmpty()) return -1
        return names.indexOfFirst { n -> val t = n.lowercase(); m.match.any { t.contains(it.lowercase()) } }
    }

    /** What the app knows about a machine right now. [configured] = it is in 工作位置 (or needs no setup: phone / Actions). */
    data class Health(val configured: Boolean, val online: Boolean? = null, val memPct: Int? = null)

    /**
     * Where a task of [ruleId] goes now: the rule's machine if it can take work, else the failover order (skipping that one).
     * A machine can take work when it is set up, not paused, not known to be down and under the stop line. null = queue it.
     * The locked 登录 rule always answers 手机.
     */
    fun pick(o: Overrides, ruleId: String, health: Map<String, Health>, now: Long): String? {
        val r = rules(o, now).firstOrNull { it.id == ruleId } ?: return null
        if (r.locked) return r.target
        val th = threshold(o)
        fun ok(id: String): Boolean {
            val h = health[id] ?: return false
            return h.configured && id !in o.paused && h.online != false && (h.memPct ?: 0) < th
        }
        return (listOf(r.target) + failover(o).filter { it != r.target }).firstOrNull(::ok)
    }

    /** Block for the system prompt: the agent places every task by these rules. */
    fun promptBlock(o: Overrides, hosts: Map<String, String>, health: Map<String, Health>, now: Long): String {
        fun nm(id: String) = machine(id)?.let { it.name + (hosts[id]?.let { h -> "($h)" } ?: "") } ?: id
        val sb = StringBuilder("任务分工（用户在 VPS / 电脑 页设定，选在哪台机器上跑任务时照这个来，不要自己另选；app_self section=routing 看实时去向）：\n")
        rules(o, now).forEach { r ->
            sb.append("- ").append(r.label).append(" → ").append(nm(r.target))
            if (r.locked) sb.append("（锁定，只在手机上做，绝不上服务器）")
            else pick(o, r.id, health, now).let { p -> if (p != r.target) sb.append("；现在 ").append(machine(r.target)?.name).append(" 不可用，改去 ").append(p?.let(::nm) ?: "排队等") }
            sb.append('\n')
        }
        sb.append("- 失败切换：").append(failover(o).joinToString(" → ") { machine(it)?.name ?: it })
            .append("（前一台不通或内存超过 ").append(threshold(o)).append("% 就交给下一台）\n")
        val paused = MACHINES.filter { it.id in o.paused }
        if (paused.isNotEmpty()) sb.append("- 已暂停（不分新任务）：").append(paused.joinToString("、") { it.name }).append('\n')
        sb.append("- 禁区：").append(MACHINES.joinToString("；") { it.name + " 不做 " + forbids(o, it) })
        return sb.toString()
    }

    // ---------------- JSON ----------------

    fun toJson(o: Overrides): String = JSONObject()
        .put("targets", JSONObject(o.targets))
        .put("custom", JSONArray(o.custom.map { JSONObject().put("id", it.id).put("label", it.label).put("target", it.target) }))
        .put("failover", o.failover?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("threshold", o.threshold ?: JSONObject.NULL)
        .put("paused", JSONArray(o.paused.toList()))
        .put("sub", JSONObject(o.sub))
        .put("accepts", JSONObject(o.accepts))
        .put("forbids", JSONObject(o.forbids))
        .put("idleMin", o.idleMin ?: JSONObject.NULL)
        .toString()

    fun fromJson(s: String?): Overrides = runCatching {
        val j = JSONObject(s ?: return Overrides())
        fun strMap(k: String) = j.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } }.orEmpty()
        fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
        Overrides(
            targets = strMap("targets"),
            custom = j.optJSONArray("custom")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .map { Rule(it.optString("id"), it.optString("label"), it.optString("target"), custom = true) } }.orEmpty(),
            failover = list("failover"),
            threshold = if (j.isNull("threshold")) null else j.optInt("threshold"),
            paused = list("paused").orEmpty().toSet(),
            sub = j.optJSONObject("sub")?.let { m -> m.keys().asSequence().associateWith { m.optBoolean(it) } }.orEmpty(),
            accepts = strMap("accepts"), forbids = strMap("forbids"),
            idleMin = if (j.isNull("idleMin")) null else j.optInt("idleMin"),
        )
    }.getOrDefault(Overrides())
}
