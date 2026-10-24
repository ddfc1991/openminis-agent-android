package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] user 2026-10-21 (frustrated): the 台账 rule lives in the agent's BUILT-IN system prompt, not only in code.
 * Pure (AccountsPromptTest asserts the wording, that AccountsTool.PROMPT starts with it, that the prompt builder always
 * appends it and that the accounts tool is always registered).
 */
object AccountsPrompt {
    /** [38.9.11.3] the 台账 rule in the agent's built-in system prompt (user 2026-10-21, AccountsPromptTest asserts it) */
    const val LEDGER_RULE = "【台账规则·必须遵守】任务只要涉及账号——登录、注册、多号/多账号注册、换号、用某平台的 API key / token / cookie——你必须先自己调用 accounts 工具查台账：" +
        "accounts(action=list) 看每个账号在哪些平台、有哪些 token / API key / cookie（已登录会话）和备注；accounts(action=ledger, site) 看这个网站台账里的账号和推荐用哪个；" +
        "然后自己选、自己用，不要问用户台账里已经有的东西（账号、邮箱、密码、密钥、验证码邮箱）。多号注册：accounts(action=register_pick, site, count) 从台账自动取还没在这个网站注册过的邮箱（已注册的自动跳过）并生成密码；" +
        "每个注册完 accounts(action=register_done, site, account, ok) 写回台账（备注 注册于 日期）；新拿到的 token / API key 用 accounts(action=secret, kind=api_key) 存进台账。" +
        "密码、密钥、token 只用 secret id：{{secret:ID}} / {{user:ID}}（shell 命令和 browser 的 type 里都能用，Mines 替换成真值），绝不在聊天、日志、文件里写出真值。"

}
