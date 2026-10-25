"""OpenAI-compatible chat completions with tools (stdlib only). The provider list comes from the phone
(POST /v1/providers: the user's own gateways and keys, the same ones Mines uses) and is kept in the brain's 0600
SQLite file; keys are never logged or put into events."""
import json
import socket
import time
import urllib.error
import urllib.request


class ModelError(Exception):
    def __init__(self, msg, retry=True, status=0):
        super().__init__(msg)
        self.retry = retry
        self.status = status


def _url(base):
    b = base.rstrip("/")
    if b.endswith("/chat/completions"):
        return b
    return b + "/chat/completions"


_DEAD = {}          # base url -> unreachable until (epoch s); e.g. a China-only endpoint seen from the Singapore box
DEAD_FOR = 600


def _reachable(base, timeout=8):
    """a fast TCP check so an unreachable gateway costs 8 s, not three 90 s read timeouts"""
    from urllib.parse import urlparse
    u = urlparse(base)
    try:
        socket.create_connection((u.hostname, u.port or (443 if u.scheme == "https" else 80)), timeout).close()
        return True
    except OSError:
        return False


def chat(provider, messages, tools=None, timeout=90, max_tokens=2000):
    body = {"model": provider["model"], "messages": messages, "max_tokens": max_tokens, "temperature": 0.3}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    data = json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(_url(provider["base"]), data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if provider.get("key"):
        req.add_header("Authorization", "Bearer " + provider["key"])
    req.add_header("User-Agent", provider.get("ua") or "Mines-Brain/1.0")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            j = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace")[:300]
        # 400/401/403/404 are not fixed by retrying the same model: go to the next one
        raise ModelError(f"HTTP {e.code}: {txt}", retry=e.code in (408, 409, 425, 429) or e.code >= 500, status=e.code)
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
        raise ModelError(f"network: {e}", retry=True)
    except ValueError as e:
        raise ModelError(f"bad json: {e}", retry=True)
    ch = (j.get("choices") or [{}])[0]
    msg = ch.get("message") or {}
    if not msg.get("content") and not msg.get("tool_calls"):
        raise ModelError("empty reply", retry=True)
    out = {"role": "assistant", "content": msg.get("content") or ""}
    if msg.get("tool_calls"):
        out["tool_calls"] = [{"id": tc.get("id") or f"call_{i}_{int(time.time()*1000)}", "type": "function",
                              "function": {"name": tc["function"]["name"], "arguments": tc["function"].get("arguments") or "{}"}}
                             for i, tc in enumerate(msg["tool_calls"])]
    return out


def chat_with_fallback(providers, messages, tools=None, tries_per_model=3, backoff=(2, 6, 15), sleep=time.sleep, log=None,
                       timeout=90):
    """per-step retry with back-off on the same model, then the next model; raises the last error when all fail"""
    last = None
    now = time.time()
    live = [p for p in providers if _DEAD.get(p.get("base", ""), 0) < now]
    for p in (live or providers):
        if not _reachable(p.get("base", "")):
            _DEAD[p.get("base", "")] = time.time() + DEAD_FOR
            last = ModelError(f"network: {p.get('base', '')} 连不上（服务器所在地区访问不了）", retry=True)
            if log:
                log(p, 0, last)
            continue
        for i in range(tries_per_model):
            try:
                out = chat(p, messages, tools, timeout=timeout)
                return out, p
            except ModelError as e:
                last = e
                if log:
                    log(p, i, e)
                if not e.retry:
                    break
                if i < tries_per_model - 1:
                    sleep(backoff[min(i, len(backoff) - 1)])
    raise last or ModelError("没有可用的模型（手机还没同步模型到云端）", retry=False)


def public(p):
    """a provider as the phone may see it again (no key)"""
    return {"name": p.get("name", ""), "model": p.get("model", ""), "base": p.get("base", "")}
