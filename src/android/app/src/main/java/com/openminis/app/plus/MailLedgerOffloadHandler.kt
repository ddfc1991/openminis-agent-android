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

/**
 * [Mines-mail-ledger] `minis-email` offload handler — 临时邮箱台账 CLI。
 *
 * 和 minis-accounts 并列。两类邮箱必须分清：
 *   TEMP  临时（Mail.tm / Moakt）一次性
 *   ALIAS Gmail 别名（deningyang+ai@gmail.com）永久
 *
 * Subcommands:
 *   list [--kind temp|alias] [--json]   台账列表
 *   get <地址|id>                        详情（含已注册平台、最近验证码、保活）
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
                "  list [--kind temp|alias] [--json]   台账列表\n" +
                "  get <地址|id>                        详情\n" +
                "  add <地址> --kind temp|alias [--provider X] [--domain Y] [--note Z]\n" +
                "  register <地址|id> <平台>            记一条已注册平台\n" +
                "  verify <地址|id> <平台>              标记该平台已验证\n" +
                "  keepalive                            看哪些到点该保活了\n" +
                "  keepalive on <地址|id> [--interval 6] 开保活\n" +
                "  keepalive off <地址|id>              关保活\n" +
                "  domains                             列出可选域名\n" +
                "  remove <地址|id>                     删除\n" +
                "\n" +
                "EXAMPLES:\n" +
                "  minis-email list                     全部\n" +
                "  minis-email list --kind temp         只看临时邮箱\n" +
                "  minis-email add abc@drmail.in --kind temp --provider mail.tm\n" +
                "  minis-email register abc@drmail.in openai\n" +
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
                else -> NativeOffloadResult(EXIT_INVALID_ARGS, "未知子命令 '$sub'。用 minis-email --help\n")
            }
        } catch (e: Exception) {
            NativeOffloadResult(EXIT_INVALID_ARGS, "错误: ${e.message}\n")
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val kind = args.get("kind", "k")?.uppercase()
        val asJson = args.hasFlag("json", "j")
        val all = MailLedger.all(context)
        val shown = if (kind.isNullOrBlank()) all else all.filter { it.kind == kind }

        if (asJson) {
            val arr = JSONArray()
            shown.forEach { arr.put(toJson(it)) }
            return NativeOffloadResult(EXIT_OK, arr.toString() + "\n")
        }
        if (shown.isEmpty()) {
            return NativeOffloadResult(EXIT_OK, "台账为空\n")
        }
        val sb = StringBuilder()
        val temps = shown.count { it.kind == MailLedger.TEMP }
        val aliases = shown.count { it.kind == MailLedger.ALIAS }
        sb.append("共 ${shown.size} 条（临时 $temps / 别名 $aliases）：\n")
        shown.forEach { e ->
            sb.append(if (e.kind == MailLedger.TEMP) "[临时] " else "[别名] ")
            sb.append(e.address.padEnd(34))
            sb.append(" 已注册 ${e.sites.size} 个")
            if (e.lastCode.isNotBlank()) sb.append("  最近码 ${e.lastCode}")
            if (e.keepAliveOn) sb.append("  保活 ${e.keepAliveH}h")
            if (e.provider.isNotBlank()) sb.append("  [").append(e.provider).append(']')
            sb.append('\n')
        }
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    private fun cmdGet(args: OffloadArgs): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email get <地址|id>\n")
        val e = MailLedger.all(context).firstOrNull {
            it.id == key || it.address.equals(key, true)
        } ?: return NativeOffloadResult(EXIT_OK, "没找到 '$key'\n")

        val sb = StringBuilder()
        sb.append("地址:   ").append(e.address).append('\n')
        sb.append("类别:   ").append(if (e.kind == MailLedger.TEMP) "临时邮箱" else "Gmail 别名").append('\n')
        sb.append("来源:   ").append(e.provider.ifBlank { "(未填)" }).append('\n')
        sb.append("域名:   ").append(e.domain.ifBlank { "(未填)" }).append('\n')
        sb.append("已注册: ").append(e.sites.size).append(" 个平台\n")
        e.sites.forEach { s ->
            sb.append("        - ").append(s.site)
            sb.append(if (s.verified) " ✓已验证" else " 未验证")
            if (s.at > 0) sb.append("  ").append(DATE_FMT.format(Date(s.at)))
            sb.append('\n')
        }
        if (e.lastCode.isNotBlank()) {
            sb.append("最近验证码: ").append(e.lastCode)
            if (e.lastCodeAt > 0) sb.append("  (").append(DATE_FMT.format(Date(e.lastCodeAt))).append(')')
            sb.append('\n')
        }
        sb.append("保活:   ").append(if (e.keepAliveOn) "开，每 ${e.keepAliveH}h" else "关")
        if (e.keepAliveAt > 0) sb.append("，上次 ").append(DATE_FMT.format(Date(e.keepAliveAt)))
        sb.append('\n')
        if (e.note.isNotBlank()) sb.append("备注:   ").append(e.note).append('\n')
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
        val provider = args.get("provider", "p").orEmpty()
        val domain = args.get("domain", "d").orEmpty().ifBlank { address.substringAfter('@', "") }
        val note = args.get("note", "n").orEmpty()

        val e = MailLedger.Entry(
            id = "m" + System.currentTimeMillis().toString(36),
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
            note = note,
            at = System.currentTimeMillis(),
        )
        val saved = MailLedger.upsert(context, e)
        return NativeOffloadResult(
            EXIT_OK,
            "已记下 ${saved.address}  [${if (saved.kind == MailLedger.TEMP) "临时" else "别名"}]\n",
        )
    }

    private fun cmdSite(args: OffloadArgs, verified: Boolean): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: 需要 <地址|id> <平台>\n")
        val site = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: 需要 <地址|id> <平台>\n")
        val e = MailLedger.markSite(context, key, site, verified)
            ?: return NativeOffloadResult(EXIT_OK, "没找到 '$key'\n")
        return NativeOffloadResult(
            EXIT_OK,
            "${e.address}: $site -> ${if (verified) "已验证" else "已注册"}\n",
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
            due.forEach { sb.append("  ").append(it.address).append("  间隔 ").append(it.keepAliveH).append("h\n") }
            return NativeOffloadResult(EXIT_OK, sb.toString())
        }

        if (op != "on" && op != "off") {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email keepalive [on|off <地址|id> --interval 6]\n")
        }
        if (key == null) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "要给地址或 id\n")
        }
        val e = MailLedger.all(context).firstOrNull { it.id == key || it.address.equals(key, true) }
            ?: return NativeOffloadResult(EXIT_OK, "没找到 '$key'\n")
        val interval = args.getInt("interval", "i") ?: e.keepAliveH
        val updated = MailLedger.upsert(
            context,
            e.copy(keepAliveOn = op == "on", keepAliveH = interval),
        )
        return NativeOffloadResult(
            EXIT_OK,
            "${updated.address} 保活=${if (updated.keepAliveOn) "开，每 ${updated.keepAliveH}h" else "关"}\n",
        )
    }

    private fun cmdRemove(args: OffloadArgs): NativeOffloadResult {
        val key = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-email remove <地址|id>\n")
        return if (MailLedger.remove(context, key)) {
            NativeOffloadResult(EXIT_OK, "已删除 '$key'\n")
        } else {
            NativeOffloadResult(EXIT_OK, "没找到 '$key'\n")
        }
    }

    private fun toJson(e: MailLedger.Entry): JSONObject {
        val sites = JSONArray()
        e.sites.forEach { sites.put(JSONObject().put("site", it.site).put("verified", it.verified).put("at", it.at)) }
        return JSONObject()
            .put("id", e.id)
            .put("kind", e.kind)
            .put("provider", e.provider)
            .put("address", e.address)
            .put("domain", e.domain)
            .put("sites", sites)
            .put("lastCode", e.lastCode)
            .put("lastCodeAt", e.lastCodeAt)
            .put("keepAliveOn", e.keepAliveOn)
            .put("keepAliveH", e.keepAliveH)
            .put("keepAliveAt", e.keepAliveAt)
            .put("note", e.note)
            .put("at", e.at)
    }
}
