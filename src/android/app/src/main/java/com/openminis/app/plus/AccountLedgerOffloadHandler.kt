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
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * `minis-accounts` offload handler — 账号台账 CLI。
 *
 * 为什么要有：账号台账原来只能走 UI 渲染链，每读一次都渲染整页，慢且费 token。
 * 这个 handler 让 CLI 直接读 AutoAccounts / SavedLogins 的数据，毫秒级返回。
 * 默认增量：只报异常（要重登/失效），`--all` 才全量。
 *
 * Subcommands:
 *   list [--all] [--json]      台账列表。默认只报异常
 *   get <site>                 单个站点详情
 *   check [site]               验证状态（读本地记录 + 密码是否在保管库，不联网）
 *   stats                      统计：总数/正常/要重登/身份数（最省 token）
 *
 * Exit codes: 0 ok / 1 invalid args
 */
class AccountLedgerOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private companion object {
        const val EXIT_OK = 0
        const val EXIT_INVALID_ARGS = 1

        const val HELP_TEXT =
            "minis-accounts - 账号台账（毫秒级，默认只报异常）\n" +
                "\n" +
                "USAGE:\n" +
                "  minis-accounts <subcommand> [args]\n" +
                "\n" +
                "SUBCOMMANDS:\n" +
                "  list [--all] [--json]     台账列表。默认只报异常，--all 全量\n" +
                "  get <site>                单个站点详情\n" +
                "  check [site]              验证状态（本地记录，不联网）\n  login <site>              触发该站点自动续期（复用 AutoRenew）\n" +
                "  stats                     统计数字\n" +
                "  --help                    这个帮助\n" +
                "\n" +
                "EXAMPLES:\n" +
                "  minis-accounts list              只报要重登的\n" +
                "  minis-accounts list --all        全部\n" +
                "  minis-accounts get github        看 github 详情\n" +
                "  minis-accounts stats             92 正常 / 3 要重登\n"

        val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(EXIT_OK, HELP_TEXT)
        }

        AutoAccounts.init(context)

        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "get" -> cmdGet(args)
                "check" -> cmdCheck(args)
                "login" -> cmdLogin(args)
                "stats" -> cmdStats()
                else -> NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    "未知子命令 '$sub'。用 minis-accounts --help\n",
                )
            }
        } catch (e: Exception) {
            NativeOffloadResult(EXIT_INVALID_ARGS, "错误: ${e.message}\n")
        }
    }

    /** list：默认只报异常，--all 才全量 */
    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val all = args.hasFlag("all", "a")
        val asJson = args.hasFlag("json", "j")
        val entries = AutoAccounts.list.value
        val shown = if (all) entries else entries.filter { isAbnormal(it.status) }

        if (asJson) {
            val arr = JSONArray()
            shown.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("site", e.site)
                        .put("host", e.host)
                        .put("account", e.account)
                        .put("status", e.status)
                        .put("source", e.source)
                        .put("at", e.at)
                        .put("abnormal", isAbnormal(e.status)),
                )
            }
            return NativeOffloadResult(EXIT_OK, arr.toString() + "\n")
        }

        if (shown.isEmpty()) {
            val msg = if (all) "台账为空\n" else "没有异常账号（${entries.size} 个全部正常）\n"
            return NativeOffloadResult(EXIT_OK, msg)
        }

        val sb = StringBuilder()
        sb.append(if (all) "全部 ${shown.size} 条：\n" else "异常 ${shown.size} 条：\n")
        shown.forEach { e ->
            val flag = if (isAbnormal(e.status)) "!" else " "
            sb.append(flag).append(' ')
            sb.append(e.site.padEnd(20)).append(' ')
            sb.append(e.account.ifBlank { "(无账号)" }.padEnd(28)).append(' ')
            sb.append(e.status)
            if (e.source.isNotBlank()) sb.append("  [").append(e.source).append(']')
            sb.append('\n')
        }
        if (!all && entries.size > shown.size) {
            sb.append("\n（另有 ${entries.size - shown.size} 条正常，用 --all 看全部）\n")
        }
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    /** get：单个站点详情 */
    private fun cmdGet(args: OffloadArgs): NativeOffloadResult {
        val site = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-accounts get <site>\n")
        val hit = AutoAccounts.list.value.filter {
            it.site.equals(site, true) ||
                it.host.equals(site, true) ||
                it.host.contains(site, true)
        }
        if (hit.isEmpty()) {
            return NativeOffloadResult(EXIT_OK, "没找到站点 '$site'\n")
        }
        val sb = StringBuilder()
        hit.forEach { e ->
            sb.append("站点:   ").append(e.site).append('\n')
            sb.append("域名:   ").append(e.host).append('\n')
            sb.append("账号:   ").append(e.account.ifBlank { "(无)" }).append('\n')
            sb.append("状态:   ").append(e.status)
            if (isAbnormal(e.status)) sb.append("  <- 要重登")
            sb.append('\n')
            sb.append("来源:   ").append(e.source.ifBlank { "(未知)" }).append('\n')
            sb.append("记录于: ").append(DATE_FMT.format(Date(e.at))).append('\n')
            sb.append('\n')
        }
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    /** check：读本地记录判断状态，不联网 */
    private fun cmdCheck(args: OffloadArgs): NativeOffloadResult {
        val site = args.positional.getOrNull(1)
        val entries = AutoAccounts.list.value.filter {
            site == null || it.site.equals(site, true) || it.host.contains(site, true)
        }
        if (entries.isEmpty()) {
            return NativeOffloadResult(EXIT_OK, "没有匹配的站点\n")
        }
        var ok = 0
        var bad = 0
        val sb = StringBuilder()
        entries.forEach { e ->
            val hasPw = runCatching {
                SavedLogins.find(context, e.host, e.account) != null
            }.getOrDefault(false)
            val abnormal = isAbnormal(e.status)
            if (abnormal) bad++ else ok++
            sb.append(if (abnormal) "FAIL " else "OK   ")
            sb.append(e.site).append(" / ").append(e.account.ifBlank { "(无)" })
            sb.append("  status=").append(e.status)
            sb.append("  密码在保管库=").append(if (hasPw) "是" else "否")
            sb.append('\n')
        }
        sb.append("\n合计: ok=").append(ok).append(" 异常=").append(bad).append('\n')
        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    /** login：触发单站点自动续期（复用 AutoRenew） */
    private fun cmdLogin(args: OffloadArgs): NativeOffloadResult {
        val site = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(EXIT_INVALID_ARGS, "用法: minis-accounts login <site>\n")
        val st = runBlocking { AutoRenew.renewSite(context, site) }
            ?: return NativeOffloadResult(EXIT_OK, "没找到站点 '$site'\n")
        val line = when (st.state) {
            "ok" -> "OK 会话有效（" + st.host + "）"
            "reused" -> "OK 已把保存的会话写回（" + st.host + "）"
            "need_login" -> "需要人工登录（" + st.note + "）"
            else -> "状态 " + st.state + "（" + st.note + "）"
        }
        return NativeOffloadResult(EXIT_OK, line + "\n")
    }

    /** stats：只要数字，最省 token */
    private fun cmdStats(): NativeOffloadResult {
        val entries = AutoAccounts.list.value
        val total = entries.size
        val bad = entries.count { isAbnormal(it.status) }
        val ok = total - bad
        val idents = runCatching {
            IdentityStore.init(context)
            IdentityStore.current(context).size
        }.getOrDefault(-1)
        return NativeOffloadResult(
            EXIT_OK,
            "总数=$total 正常=$ok 要重登=$bad 身份=${if (idents >= 0) idents else "?"}\n",
        )
    }

    /** 什么算异常：状态里带 要重登/失效/过期/失败/error 之类 */
    private fun isAbnormal(status: String): Boolean {
        val s = status.lowercase()
        return s.contains("重登") || s.contains("失效") || s.contains("过期") ||
            s.contains("失败") || s.contains("error") || s.contains("expired") ||
            s.contains("invalid") || s.contains("relogin")
    }
}
