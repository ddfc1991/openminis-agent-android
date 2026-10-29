package com.openminis.app.plus

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * [Mines-mail-ledger] `minis-email` offload handler — 临时邮箱台账 CLI。
 *
 * 和 minis-accounts 并列。两类邮箱必须分清：
 *   TEMP  临时（Mail.tm / Moakt）一次性
 *   ALIAS Gmail 别名（永久有效）
 *
 * Subcommands:
 *   list [--kind temp|alias] [--provider mail.tm|moakt|gmail] [--domain 域名] [--json]   台账列表
 *   get <地址|id>                        详情（含已注册平台、验证码记录状态、保活）
 *   add <地址> --kind temp|alias [opts]  新增
 *   register <地址|id> <平台>            记一条"已注册某平台"
 *   verify <地址|id> <平台>              标记该平台已验证
 *   detail <地址|id>                     同 get
 *   keepalive [on|off] [--interval H]    保活设置 / 看哪些到点了
 *   domains                             列出可选域名（14 个）
 *   remove <地址|id>                     删除
 *
 * Exit codes: 0 ok / 1 invalid args
 */
class MailLedgerOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private companion object {
        const val EXIT_OK = 0
        const val EXIT_INVALID_ARGS = 1
        val DATE_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        const val HELP_TEXT =
            "minis-email - 临时邮箱台账（临时邮箱 / Gmail别名 分开记）\n" +
                "\n" +
                "USAGE:\n" +
                "  minis-email <subcommand> [args]\n" +
                "\n" +
                "SUBCOMMANDS:\n" +
                "  list [--kind temp|alias] [--provider mail.tm|moakt|gmail] [--domain 域名] [--json]   台账列表\n" +
                "  get <地址|id>                        详情\n" +
                "  add <地址> --kind temp|alias [--provider mail.tm|moakt|gmail|manual] [--domain 域名]\n" +
                "  register <地址|id> <平台>            仅记入本地平台关联（不代注册网站）\n" +
                "  verify <地址|id> <平台>              标记该平台已验证\n" +
                "  keepalive                            查看需人工处理的保活项（不会自动续期）\n" +
                "  keepalive on <地址|id> [--interval 6] 开保活\n" +
                "  keepalive off <地址|id>              关保活\n" +
                "  domains                             列出可选域名\n" +
                "  remove <地址|id>                     删除\n" +
                "\n" +
                "EXAMPLES:\n" +
                "  minis-email list                     全部\n" +
                "  minis-email list --kind temp         只看临时邮箱\n" +
                "  minis-email add <邮箱> --kind temp --provider mail.tm --domain <域名>\n" +
                "  minis-email register <邮箱或id> <平台>\n" +
                "  minis-email keepalive                哪些该保活了\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(EXIT_OK, HELP_TEXT)

        MailLedger.init(context)
        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list", "ls" -> cmdList(args)
                "get", "detail" -> cmdGet(args)
                "add" -> cmdAdd(args)
                "register" -> cmdSite(args, verified = false)
                "verify" -> cmdSite(args, verified = true)
                "keepalive" -> cmdKeepAlive(args)
                "domains" -> NativeOffloadResult(
                    EXIT_OK,
                    "可选临时邮箱域名（${MailLedger.TEMP_DOMAINS.size} 个）:\n" +
                        MailLedger.TEMP_DOMAINS.joinToString("\n") { "  $it" } + "\n",
                )
                "remove", "rm" -> cmdRemove(args)
                else -> NativeOffloadResult(EXIT_INVALID_ARGS, "未知子命令。用 minis-email --help\n")
            }
        } catch (e: Exception) {
            // Exception messages may echo command arguments containing secrets; keep errors generic.
            NativeOffloadResult(EXIT_INVALID_ARGS, "操作失败（${e.javaClass.simpleName}）。\n")
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val kind = args.get("kind", "k")?.uppercase()
        if (kind != null && kind !in setOf(MailLedger.TEMP, MailLedger.ALIAS)) return NativeOffloadResult(EXIT_INVALID_ARGS, "--kind 仅支持 temp 或 alias。\n")
        val asJson = args.hasFlag("json", "j")
        val domain = args.get("domain", "d")?.trim()?.lowercase(Locale.ROOT)
        val provider = args.get("provider", "p")?.trim()?.let(::safeProvider)?.lowercase(Locale.ROOT)
        if (args.get("provider", "p") != null && provider == null) return NativeOffloadResult(EXIT_INVALID_ARGS, "来源仅支持 mail.tm、moakt、gmail、manual。\n")
        val all = MailLedger.all(context)
        val shown = MailLedgerSafeOutput.stableSort(all.filter {
            (kind.isNullOrBlank() || it.kind == kind) &&
                (provider.isNullOrBlank() || safeProvider(it.provider)?.lowercase(Locale.ROOT) == provider) &&
                (domain.isNullOrBlank() || it.domain.equals(domain, true) || it.address.substringAfterLast("@", "").equals(domain, true))
        })

        if (asJson) {
            val arr = JSONArray()
            shown.forEach { arr.put(toJson(it)) }
            return NativeOffloadResult(EXIT_OK, arr.toString() + "\n")
        }
        if (shown.isEmpty()) {
            val message = if (all.isEmpty()) "台账暂无记录\n" else "没有符合筛选条件的条目\n"
            return NativeOffloadResult(EXIT_OK, message)
        }
        val sb = StringBuilder()
        val temps = shown.count { it.kind == MailLedger.TEMP }
        val aliases = shown.count { it.kind == MailLedger.ALIAS }
        sb.append("共 ${shown.size} 条（临时 $temps / 别名 $aliases）：\n")
        shown.forEach { e ->
            sb.append(if (e.kind == MailLedger.TEMP) "[临时] " else "[别名] ")
            sb.append(MailLedgerSafeOutput.maskEmail(e.address).padEnd(24))
            sb.append(" 已注册 ${e.sites.size} 个")
            MailLedgerSafeOutput.codeLabel(e.lastCode)?.let { sb.append("  ").append(it) }
            sb.append("  最近使用 ").append(MailLedgerSafeOutput.timeLabel(e.lastUsedAt))
            if (e.keepAliveOn) sb.append("  保活提醒 ${e.keepAliveH}h")
            safeProvider(e.provider)?.let { sb.append("  [").append(it).append(']') }
            sb.append('\n')
        }
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    private fun cmdGet(args: OffloadArgs): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email get <地址|id>\n")
        val lookup = MailLedgerSafeOutput.resolve(MailLedger.all(context), key)
        val e = lookup.entry ?: return lookup.failureResult()

        val sb = StringBuilder()
        sb.append("地址:   ").append(MailLedgerSafeOutput.maskEmail(e.address)).append('\n')
        sb.append("类别:   ").append(if (e.kind == MailLedger.TEMP) "临时邮箱" else "Gmail 别名").append('\n')
        sb.append("来源:   ").append(safeProvider(e.provider) ?: "未指定").append('\n')
        sb.append("域名:   ").append(e.domain.takeIf { ProxyEndpointCore.validHost(it) } ?: "(未填)").append('\n')
        sb.append("已注册: ").append(e.sites.size).append(" 个平台\n")
        e.sites.forEach { s ->
            sb.append("        - ").append(MailLedgerSafeOutput.cleanLabel(s.site) ?: "已关联平台")
            sb.append(if (s.verified) " ✓已验证" else " 未验证")
            if (s.at > 0) sb.append("  ").append(DATE_FMT.format(Date(s.at)))
            sb.append('\n')
        }
        MailLedgerSafeOutput.codeLabel(e.lastCode)?.let { _ ->
            sb.append("最近验证码: 已记录（不显示验证码内容）")
            if (e.lastCodeAt > 0) sb.append("  (").append(DATE_FMT.format(Date(e.lastCodeAt))).append(')')
            sb.append('\n')
        }
        sb.append("最近使用: ").append(MailLedgerSafeOutput.timeLabel(e.lastUsedAt)).append('\n')
        sb.append("保活提醒: ").append(if (e.keepAliveOn) "开，每 ${e.keepAliveH}h（需手动访问服务）" else "关")
        if (e.keepAliveAt > 0) sb.append("，上次 ").append(DATE_FMT.format(Date(e.keepAliveAt)))
        sb.append('\n')
        // Free-form notes may contain credentials or tokens; do not echo them in the CLI.
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    private fun cmdAdd(args: OffloadArgs): NativeOffloadResult {
        val address = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email add <地址> --kind temp|alias\n")
        val kindRaw = args.get("kind", "k")?.uppercase()
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "必须给 --kind temp 或 --kind alias\n")
        if (kindRaw != MailLedger.TEMP && kindRaw != MailLedger.ALIAS) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "--kind 只能是 temp 或 alias\n")
        }
        val providerInput = args.get("provider", "p").orEmpty().trim().lowercase(Locale.ROOT)
        val provider = when (providerInput) {
            "mail.tm", "mailtm" -> "Mail.tm"
            "moakt" -> "Moakt"
            "gmail", "gmail-alias", "alias" -> "Gmail"
            "manual", "手动" -> "手动"
            "" -> if (kindRaw == MailLedger.ALIAS) "Gmail" else "未指定"
            else -> return NativeOffloadResult(EXIT_INVALID_ARGS, "--provider 仅支持 mail.tm、moakt、gmail 或 manual\n")
        }
        if (!MailLedgerSafeOutput.validEmail(address)) return NativeOffloadResult(EXIT_INVALID_ARGS, "邮箱格式无效。\n")
        if (kindRaw == MailLedger.TEMP && provider == "Gmail" || kindRaw == MailLedger.ALIAS && provider in setOf("Mail.tm", "Moakt"))
            return NativeOffloadResult(EXIT_INVALID_ARGS, "来源与邮箱类别不匹配。\n")
        val addressDomain = address.substringAfter('@', "").lowercase(Locale.ROOT)
        val requestedDomain = args.get("domain", "d")?.trim()?.lowercase(Locale.ROOT)
        if (requestedDomain != null && requestedDomain != addressDomain) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "--domain 必须与邮箱地址的实际域名一致。\n")
        }
        val domain = addressDomain
        if (!ProxyEndpointCore.validHost(domain)) return NativeOffloadResult(EXIT_INVALID_ARGS, "域名格式无效。\n")

        val existing = MailLedgerSafeOutput.resolve(MailLedger.all(context), address)
        if (existing.ambiguous) return existing.failureResult()
        if (existing.entry != null) {
            return NativeOffloadResult(EXIT_OK, "该邮箱已在台账中：${MailLedgerSafeOutput.maskEmail(existing.entry.address)}（未重复添加或覆盖已有信息）\n")
        }

        val e = MailLedger.Entry(
            id = "m" + UUID.randomUUID().toString().replace("-", "").take(12),
            kind = kindRaw,
            provider = provider,
            address = address,
            domain = domain,
            sites = emptyList(),
            lastCode = "",
            lastCodeAt = 0,
            keepAliveOn = false,
            keepAliveH = 6,
            keepAliveAt = 0,
            note = "",
            at = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )
        val saved = MailLedger.upsert(context, e)
        return NativeOffloadResult(
            EXIT_OK,
            "已记下 ${MailLedgerSafeOutput.maskEmail(saved.address)}  [${if (saved.kind == MailLedger.TEMP) "临时" else "别名"}]\n",
        )
    }

    private fun cmdSite(args: OffloadArgs, verified: Boolean): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: 需要 <地址|id> <平台>\n")
        val siteRaw = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: 需要 <地址|id> <平台>\n")
        val site = MailLedgerSafeOutput.cleanLabel(siteRaw)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "平台名格式无效。\n")
        val lookup = MailLedgerSafeOutput.resolve(MailLedger.all(context), key)
        val target = lookup.entry ?: return lookup.failureResult()
        val e = MailLedger.markSite(context, target.address, site, verified)
            ?: return NativeOffloadResult(EXIT_OK, "没找到指定台账条目\n")
        return NativeOffloadResult(
            EXIT_OK,
            "${MailLedgerSafeOutput.maskEmail(e.address)}: $site -> ${if (verified) "已验证" else "已注册"}\n",
        )
    }

    private fun cmdKeepAlive(args: OffloadArgs): NativeOffloadResult {
        val op = args.positional.getOrNull(1)?.lowercase()
        val key = args.positional.getOrNull(2)

        // 无参数：列出到点的
        if (op == null) {
            val due = MailLedger.dueForKeepAlive(context)
            if (due.isEmpty()) return NativeOffloadResult(EXIT_OK, "没有到点该保活的\n")
            val sb = StringBuilder("到点该保活 ${due.size} 条：\n")
            due.forEach { sb.append("  ").append(MailLedgerSafeOutput.maskEmail(it.address)).append("  提醒间隔 ").append(it.keepAliveH).append("h\n") }
            return NativeOffloadResult(EXIT_OK, sb.toString())
        }

        if (op == "done") {
            if (key == null) return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email keepalive done <地址|id>\n")
            val lookup = MailLedgerSafeOutput.resolve(MailLedger.all(context), key)
            val e = lookup.entry ?: return lookup.failureResult()
            val now = System.currentTimeMillis()
            MailLedger.replace(context, e.copy(keepAliveAt = now, lastUsedAt = now))
                ?: return NativeOffloadResult(EXIT_OK, "没找到指定台账条目\n")
            return NativeOffloadResult(EXIT_OK, "已记录人工检查时间；未自动访问或续期邮箱服务\n")
        }
        if (op != "on" && op != "off") {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email keepalive [on|off <地址|id> --interval 6]\n")
        }
        if (key == null) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "要给地址或 id\n")
        }
        val lookup = MailLedgerSafeOutput.resolve(MailLedger.all(context), key)
        val e = lookup.entry ?: return lookup.failureResult()
        val rawInterval = args.get("interval", "i")
        val parsedInterval = rawInterval?.toIntOrNull()
        if (rawInterval != null && parsedInterval == null) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "--interval 必须是 1–8760 的整数小时。\n")
        }
        val interval = parsedInterval ?: e.keepAliveH.coerceIn(1, 8760)
        if (interval !in 1..8760) return NativeOffloadResult(EXIT_INVALID_ARGS, "保活提醒间隔需为 1–8760 小时。\n")
        val updated = MailLedger.replace(
            context,
            e.copy(keepAliveOn = op == "on", keepAliveH = interval),
        ) ?: return NativeOffloadResult(EXIT_OK, "没找到指定台账条目\n")
        return NativeOffloadResult(
            EXIT_OK,
            "${MailLedgerSafeOutput.maskEmail(updated.address)} 保活提醒=${if (updated.keepAliveOn) "开，每 ${updated.keepAliveH}h" else "关"}（服务续期需手动处理）\n",
        )
    }

    private fun cmdRemove(args: OffloadArgs): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email remove <地址|id>\n")
        val lookup = MailLedgerSafeOutput.resolve(MailLedger.all(context), key)
        val entry = lookup.entry ?: return lookup.failureResult()
        return if (MailLedger.remove(context, entry.address)) {
            NativeOffloadResult(EXIT_OK, "已删除指定台账条目\n")
        } else {
            NativeOffloadResult(EXIT_OK, "没找到指定台账条目\n")
        }
    }

    private fun safeProvider(value: String): String? = when (value.trim().lowercase(Locale.ROOT)) {
        "mail.tm", "mailtm" -> "Mail.tm"
        "moakt" -> "Moakt"
        "gmail", "gmail-alias", "alias" -> "Gmail"
        "manual", "手动" -> "手动"
        "", "未指定" -> "未指定"
        else -> null
    }

    private fun toJson(e: MailLedger.Entry): JSONObject = MailLedgerSafeOutput.toJson(e)
}

/** Safe CLI projection of OTP state: expose presence/time, never the code bytes. */
internal object MailLedgerSafeOutput {
    data class Lookup(
        val entry: MailLedger.Entry?,
        val ambiguous: Boolean = false,
        val candidates: List<MailLedger.Entry> = emptyList(),
    ) {
        fun failureResult(): NativeOffloadResult {
            if (!ambiguous) return NativeOffloadResult(1, "没找到指定台账条目\n")
            val options = stableSort(candidates).joinToString("\n") { "  ${it.id}  ${maskEmail(it.address)}" }
            return NativeOffloadResult(1, "匹配到多个条目，请用完整地址或候选 id 精确指定；未擅自选择：\n$options\n")
        }
    }

    fun resolve(entries: List<MailLedger.Entry>, key: String): Lookup {
        val byAddress = entries.filter { it.address.equals(key, ignoreCase = true) }
        if (byAddress.size == 1) return Lookup(byAddress.single())
        if (byAddress.size > 1) return Lookup(null, ambiguous = true, candidates = byAddress)
        val byId = entries.filter { it.id == key }
        return when (byId.size) {
            0 -> Lookup(null)
            1 -> Lookup(byId.single())
            else -> Lookup(null, ambiguous = true, candidates = byId)
        }
    }

    /** Stable list order independent of the insertion/update order in SharedPreferences. */
    fun stableSort(entries: List<MailLedger.Entry>): List<MailLedger.Entry> = entries.sortedWith(
        compareBy<MailLedger.Entry>({ it.kind }, { it.provider.lowercase(Locale.ROOT) },
            { it.domain.lowercase(Locale.ROOT) }, { it.address.lowercase(Locale.ROOT) }, { it.id }),
    )

    fun maskEmail(address: String): String {
        val at = address.lastIndexOf('@')
        if (at <= 0 || at == address.lastIndex) return "***"
        val local = address.substring(0, at)
        val domain = address.substring(at + 1)
        return local.take(1) + "***@" + domain
    }
    fun timeLabel(timestamp: Long): String = if (timestamp > 0L) SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestamp)) else "暂无记录"
    fun hasRecentCode(code: String): Boolean = code.isNotBlank()
    fun validEmail(address: String): Boolean {
        if (address.length !in 3..254 || address.any { it.isWhitespace() || it.code < 0x21 }) return false
        val parts = address.split('@')
        if (parts.size != 2 || parts[0].isBlank() || parts[0].length > 64) return false
        return ProxyEndpointCore.validHost(parts[1]) && parts[0].none { it in "<>\"(),:;\\[]" }
    }
    fun cleanLabel(label: String): String? {
        val normalized = label.trim()
        if (normalized.isEmpty() || normalized.length > 48 || normalized.any { it.isISOControl() }) return null
        if (!normalized.matches(Regex("[\\p{L}\\p{N} ._-]+"))) return null
        if (normalized.split(Regex("[^A-Za-z0-9]+" )).any { it.length > 24 }) return null
        return normalized
    }
    fun codeLabel(code: String): String? = if (hasRecentCode(code)) "验证码已记录" else null
    fun codeTimestamp(code: String, timestamp: Long): Long = if (hasRecentCode(code)) timestamp else 0L

    /** JSON default projection deliberately excludes OTP, notes, credentials, and full addresses. */
    fun toJson(e: MailLedger.Entry): JSONObject {
        val sites = JSONArray()
        e.sites.forEach { site ->
            val safe = cleanLabel(site.site) ?: "已关联平台"
            sites.put(JSONObject().put("site", safe).put("verified", site.verified).put("at", site.at))
        }
        val safeDomain = e.domain.takeIf { ProxyEndpointCore.validHost(it) } ?: ""
        return JSONObject()
            .put("id", e.id)
            .put("kind", if (e.kind == MailLedger.ALIAS) MailLedger.ALIAS else MailLedger.TEMP)
            .put("provider", when (e.provider.trim().lowercase(Locale.ROOT)) {
                "mail.tm", "mailtm" -> "Mail.tm"
                "moakt" -> "Moakt"
                "gmail", "gmail-alias", "alias" -> "Gmail"
                "manual", "手动" -> "手动"
                else -> "未指定"
            })
            .put("address", maskEmail(e.address))
            .put("domain", safeDomain)
            .put("sites", sites)
            .put("linkedPlatformCount", e.sites.size)
            .put("lastUsedAt", e.lastUsedAt)
            .put("hasRecentCode", hasRecentCode(e.lastCode))
            .put("lastCodeAt", codeTimestamp(e.lastCode, e.lastCodeAt))
            .put("keepAliveOn", e.keepAliveOn)
            .put("keepAliveH", e.keepAliveH.coerceIn(1, 8760))
            .put("keepAliveAt", e.keepAliveAt)
            .put("at", e.at)
    }
}
