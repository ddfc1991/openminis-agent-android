"""[38.10 cloud brain] 限制层 parity with the app's 38.9.15 LimitGuardCore (persona/LimitGuardCore.kt).

Default deny:
  - no account is allowed until the user allows it from the phone (policy is written ONLY by PUT /v1/policy, which
    the agent cannot reach: http_fetch refuses loopback/private hosts and the shell runs inside the devbox jail);
  - send / post / delete / pay need the user's one-shot 同意 from the phone (approval is per tool call id);
  - pay is never automatic, even off-account;
  - the kill switch stops everything except reading.
A refused step puts the task in 等你处理 (status waiting) and the phone gets an `ask` event.
"""
import re
import time

READ, DRAFT, TOUCH, SEND, POST, DELETE, PAY = "read", "draft", "touch", "send", "post", "delete", "pay"
CONFIRM_ACTS = {SEND, POST, DELETE, PAY}
ZH = {READ: "看", DRAFT: "写草稿", TOUCH: "操作", SEND: "发出去", POST: "发布", DELETE: "删除", PAY: "付款"}

WHY = {
    "ok": "",
    "killed": "总开关已关：你在手机上停了云端执行，先在 Mines 里打开再说",
    "not_allowed": "这个账号你还没允许我用，到手机 Mines 里点「允许」才行",
    "needs_confirm": "这一步要你点头：发出去/发布/删除/付款 默认都等你确认",
    "cap": "今天这个账号的次数用完了",
}

SEND_WORDS = ("send", "发送", "发出", "回复", "私信", "submit message")
POST_WORDS = ("post", "publish", "发布", "发表", "发帖", "评论", "转发", "tweet")
CODE_WORDS = ("验证码", "code", "otp")
DELETE_WORDS = ("delete", "删除", "永久删除", "清空", "remove account", "注销")
PAY_WORDS = ("pay", "checkout", "purchase", "付款", "支付", "购买", "下单", "订阅", "subscribe", "place order")


def act_of(tool, args):
    """the act a tool call amounts to; unknown verbs → the strictest reasonable match"""
    args = args or {}
    a = str(args.get("action", "")).strip().lower()
    text = " ".join(str(args.get(k, "")) for k in ("text", "target", "selector", "label", "url")).lower()
    if tool in ("send_email", "post_message"):
        return SEND
    if tool == "http_fetch":
        m = str(args.get("method", "GET")).upper()
        if m in ("GET", "HEAD", "OPTIONS"):
            return READ
        if m == "DELETE":
            return DELETE
        return PAY if any(w in text for w in PAY_WORDS) else SEND
    if tool == "shell":
        cmd = str(args.get("command", "")).lower()
        if re.search(r"\b(sendmail|mailx|mutt|swaks)\b", cmd):
            return SEND
        return TOUCH        # inside the devbox jail
    if tool == "browser":
        if a in ("open", "goto", "read", "text", "screenshot", "wait", "scroll", "back"):
            return READ
        if a in ("pay", "checkout", "purchase"):
            return PAY
        if a in ("post", "publish", "comment", "share", "tweet"):
            return POST
        if a in ("send", "message", "dm", "reply"):
            return SEND
        if a in ("delete",):
            return DELETE
        code = any(w in text for w in CODE_WORDS)
        if a == "click":
            if any(w in text for w in PAY_WORDS):
                return PAY
            if not code and any(w in text for w in SEND_WORDS):
                return SEND
            if any(w in text for w in POST_WORDS):
                return POST
            if any(w in text for w in DELETE_WORDS):
                return DELETE
        if a in ("click", "type", "fill", "press", "eval"):
            return TOUCH
        return READ
    if tool in ("ask_user", "handoff", "finish", "note", "phone"):
        return READ
    return TOUCH


def check(policy, account, act, used_today=0, approved=False):
    """(allowed, why) — the one rule, same order as LimitGuardCore.check"""
    policy = policy or {}
    killed = bool(policy.get("killed"))
    accounts = policy.get("accounts") or {}
    if not account:
        if killed and act != READ:
            return False, "killed"
        if act == PAY and not approved:
            return False, "needs_confirm"
        if act in CONFIRM_ACTS and not approved and policy.get("confirm_off_account", True):
            # off the user's accounts a send/post/delete still speaks for the user somewhere: ask (default on)
            return False, "needs_confirm"
        return True, "ok"
    if killed:
        return False, "killed"
    if act in (READ, DRAFT):
        return True, "ok"
    acc = accounts.get(account) or {}
    if not acc.get("allowed"):
        return False, "not_allowed"
    if used_today >= int(acc.get("cap", 30)):
        return False, "cap"
    if acc.get("confirm", True) and act in CONFIRM_ACTS and not approved:
        return False, "needs_confirm"
    return True, "ok"


def refusal(why, act, account):
    who = f"（账号 {account}）" if account else ""
    return (f"【限制层】这一步{who}被用户的设置拦住了：{WHY.get(why, why)}。你不能自己改这个设置，也不要换别的路子绕过；"
            "已经记成「等你处理」，等用户在手机上点同意或拒绝。")


def day():
    return time.strftime("%Y%m%d", time.gmtime(time.time() + 8 * 3600))
