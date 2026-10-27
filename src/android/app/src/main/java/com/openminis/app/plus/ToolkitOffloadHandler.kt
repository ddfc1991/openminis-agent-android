package com.openminis.app.plus

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs

/**
 * [Mines-toolkit] `mines-toolkit` — 统一 CLI 入口（用户清单 ⑪）。
 *
 * 问题：`minis-accounts` / `minis-email` / `minis-config` / `minis-vps` / `minis-dsh`
 * 各是一条独立命令，用户和模型都要记一堆名字。这个 handler 做**分发**：
 * 把 `mines-toolkit <域> <子命令...>` 转给对应 handler，输出原样返回。
 *
 * 它不重复实现任何逻辑，只是路由 + 统一帮助 + 跨域状态汇总。
 *
 * Subcommands:
 *   status                  一句话看全部：账号/邮箱/续期/工具档
 *   accounts <...>          转给 minis-accounts
 *   email <...>             转给 minis-email
 *   health                  账号台账 + 续期摘要（最常用的两件事）
 *   tier [lite|medium|heavy|auto]  看/设工具档位
 *   help                    这个帮助
 *
 * Exit codes: 0 ok / 1 invalid args
 */
class ToolkitOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private companion object {
        const val EXIT_OK = 0
        const val EXIT_INVALID_ARGS = 1

        const val HELP_TEXT =
            "mines-toolkit - 统一 CLI 入口（账号 / 邮箱 / 续期 / 工具档）\n" +
                "\n" +
                "USAGE:\n" +
                "  mines-toolkit <subcommand> [args]\n" +
                "\n" +
                "SUBCOMMANDS:\n" +
                "  status                   一句话看全部：账号 / 邮箱 / 续期 / 工具档\n" +
                "  health                   账号台账 + 续期摘要（最常用）\n" +
                "  accounts <...>           转给 minis-accounts（例：accounts list --all）\n" +
                "  email <...>              转给 minis-email（例：email list --kind temp）\n" +
                "  tier                     看当前工具档位\n" +
                "  tier lite|medium|heavy   设死档位\n" +
                "  tier auto                按设备自动选\n" +
                "  help                     这个帮助\n" +
                "\n" +
                "EXAMPLES:\n" +
                "  mines-toolkit status\n" +
                "  mines-toolkit accounts list\n" +
                "  mines-toolkit email list --kind temp\n" +
                "  mines-toolkit tier medium\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(EXIT_OK, HELP_TEXT)

        val sub = args.positional.firstOrNull() ?: "status"
        val rest = args.positional.drop(1)

        return try {
            when (sub) {
                "status" -> cmdStatus()
                "health" -> cmdHealth()
                "accounts", "acct", "a" -> forward("minis-accounts", rest, request)
                "email", "mail", "e" -> forward("minis-email", rest, request)
                "tier" -> cmdTier(rest)
                else -> NativeOffloadResult(EXIT_INVALID_ARGS, "未知子命令 '$sub'。用 mines-toolkit help\n")
            }
        } catch (e: Exception) {
            NativeOffloadResult(EXIT_INVALID_ARGS, "错误: ${e.message}\n")
        }
    }

    /** 把子命令转给另一个 handler，保持原始 argv 结构。 */
    private fun forward(name: String, rest: List<String>, original: NativeOffloadRequest): NativeOffloadResult {
        val handler = runCatching { ToolkitRegistry.get(context, name) }.getOrNull()
            ?: return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                "没有 $name 这条命令（可能这个版本没注册）\n",
            )
        // argv[0] 用目标命令名，其余照搬，这样被转发的 handler 看到的和直连一样。
        val fwd = original.copy(argv = listOf(name) + rest)
        return handler.handle(fwd)
    }

    /** 一句话看全部。最省 token 的入口。 */
    private fun cmdStatus(): NativeOffloadResult {
        val sb = StringBuilder()

        // 账号
        runCatching {
            AutoAccounts.init(context)
            val entries = AutoAccounts.list.value
            val bad = entries.count { isAbnormal(it.status) }
            sb.append("账号:  共 ${entries.size}，异常 $bad\n")
        }.onFailure { sb.append("账号:  读取失败（${it.message}）\n") }

        // 邮箱
        runCatching {
            MailLedger.init(context)
            val all = MailLedger.all(context)
            val t = all.count { it.kind == MailLedger.TEMP }
            val a = all.count { it.kind == MailLedger.ALIAS }
            sb.append("邮箱:  共 ${all.size}（临时 $t / 别名 $a）\n")
        }.onFailure { sb.append("邮箱:  读取失败（${it.message}）\n") }

        // 续期
        runCatching { sb.append("续期:  ").append(AutoRenew.summary(context)).append('\n') }
            .onFailure { sb.append("续期:  读取失败（${it.message}）\n") }

        // 工具档
        runCatching {
            val eff = ToolTierStore.effective(context)
            val raw = ToolTierStore.rawChoice(context)
            sb.append("工具档: ${eff.label}（设置=${if (raw == ToolTier.AUTO) "自动" else raw}）\n")
        }.onFailure { sb.append("工具档: 读取失败（${it.message}）\n") }

        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    /** 最常用的两件事：台账异常 + 续期情况 */
    private fun cmdHealth(): NativeOffloadResult {
        val sb = StringBuilder()

        runCatching {
            AutoAccounts.init(context)
            val entries = AutoAccounts.list.value
            val bad = entries.filter { isAbnormal(it.status) }
            sb.append("=== 账号台账 ===\n")
            sb.append("共 ${entries.size}，异常 ${bad.size}\n")
            bad.take(20).forEach { e ->
                sb.append("  ! ${e.site} / ${e.account.ifBlank { "(无)" }} — ${e.status}\n")
            }
            if (bad.size > 20) sb.append("  …还有 ${bad.size - 20} 条，用 accounts list 看全部\n")
        }.onFailure { sb.append("账号台账读取失败: ${it.message}\n") }

        sb.append("\n=== 自动续期 ===\n")
        runCatching { sb.append(AutoRenew.summary(context)).append('\n') }
            .onFailure { sb.append("续期读取失败: ${it.message}\n") }

        return NativeOffloadResult(EXIT_OK, sb.toString())
    }

    /** 看/设工具档 */
    private fun cmdTier(rest: List<String>): NativeOffloadResult {
        val want = rest.firstOrNull()?.lowercase()
        if (want == null) {
            val eff = ToolTierStore.effective(context)
            val raw = ToolTierStore.rawChoice(context)
            return NativeOffloadResult(
                EXIT_OK,
                "当前: ${eff.label}\n设置: ${if (raw == ToolTier.AUTO) "自动（按设备）" else raw}\n" +
                    "可设: auto / lite / medium / heavy\n",
            )
        }
        if (want != ToolTier.AUTO && ToolTier.forName(want) == null) {
            return NativeOffloadResult(EXIT_INVALID_ARGS, "档位只能是 auto / lite / medium / heavy\n")
        }
        ToolTierStore.setChoice(context, want)
        val eff = ToolTierStore.effective(context)
        return NativeOffloadResult(EXIT_OK, "已设为 $want（当前生效: ${eff.label}）\n")
    }

    private fun isAbnormal(status: String): Boolean {
        val s = status.lowercase()
        return s.contains("重登") || s.contains("失效") || s.contains("过期") ||
            s.contains("失败") || s.contains("error") || s.contains("expired") ||
            s.contains("invalid") || s.contains("relogin")
    }
}

/**
 * [Mines-toolkit] 让 [ToolkitOffloadHandler] 能拿到别的 handler 实例。
 *
 * 为什么需要：`NativeOffloadServer.register` 只存 handler，没有对外暴露查询接口。
 * 这里在注册时顺便记一份到 map，供转发用。不改 NativeOffloadServer 本身（那是共享代码）。
 */
object ToolkitRegistry {
    private val map = HashMap<String, NativeOffloadHandler>()

    fun put(name: String, h: NativeOffloadHandler) {
        map[name] = h
    }

    fun get(c: Context, name: String): NativeOffloadHandler? = map[name]

    fun names(): Set<String> = map.keys.toSet()
}
