package com.openminis.app.sandbox.offload

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadResult
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.atomic.AtomicLong

/**
 * [Mines-fast-ui] The quick way to drive the phone: one numbered text list of
 * what is on screen, actions by number, and many actions batched into ONE
 * call. Most of the old slowness was round trips, not taps: every step was
 * `ui dump` (a large JSON tree) → model reads it → `tap node <id>` → another
 * dump, so a five-step task meant ten tool calls and ten model turns. Now:
 *
 *   android-a11y-cli ui list                 numbered snapshot (also: `list`)
 *   android-a11y-cli tap 3                   tap item 3 of the last list
 *   android-a11y-cli type 5 "你好"            focus item 5 and type
 *   android-a11y-cli do "tap 3; type 5 你好; key enter; wait 500; list"
 *
 * Every action waits for the screen to settle (event-driven, ~150–1200 ms
 * instead of fixed sleeps) and `do` ends with a fresh list, so the model sees
 * the result without asking again. No screenshots are taken.
 *
 * Backends, chosen per call:
 *   1. Accessibility service (fastest: the tree is read in-process).
 *   2. Shizuku / adb-level shell when accessibility is off (common for
 *      sideloaded installs, where Android greys the toggle out):
 *      `uiautomator dump` + `input tap/text/swipe/keyevent`. Slower to read
 *      (~1–2 s per list) but needs no accessibility grant and no root.
 */
internal class FastUi(private val context: Context) {

    data class El(
        val n: Int, val label: String, val role: String, val bounds: Rect,
        val node: AccessibilityNodeInfo?, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
        // [Mines-38.8 v5d] resource-id / content-desc (element list + replay matching) and where the element came from (tree / ocr)
        val rid: String = "", val desc: String = "", val source: String = "tree",
    ) {
        fun item() = ScreenTiers.Item(label, desc, rid, role, bounds.left, bounds.top, bounds.right, bounds.bottom, clickable, source)
    }

    private enum class Backend(val label: String) { A11Y("无障碍"), SHIZUKU("Shizuku") }

    companion object {
        private const val TAG = "FastUi"
        @Volatile private var last: List<El> = emptyList()
        @Volatile private var lastPkg: String = ""
        private val lastEventAt = AtomicLong(0L)
        @Volatile private var listening = false
        @Volatile private var lastTier: String = ScreenTiers.TIER_TREE

        const val HELP = """Fast UI (numbered list + batching; no screenshots):
  ui list [--all]          numbered list of what is on screen (alias: list)
  tap <n>                  tap item n of the last list (tap "文本" also works inside do)
  type <n> <text>          focus item n and type text
  key back|home|recents|enter|notifications|quicksettings
  open <包名或应用名>       open an app (verified: says whether it really came to the front)
  shade open|qs|close      pull down the notification shade / quick settings, or close it
  do "<step>; <step>; …"   run several steps in ONE call, then print a fresh list
       steps: tap N | tap "文本" | tap X,Y | longpress N|"文本"|X,Y | swipe X1 Y1 X2 Y2 [MS] | type N 文本 | key K |
              scroll down|up [N] | wait MS | open <包名/应用名> | shade open|qs|close | list
  do --task "<任务名>" "<steps>"   same, and remember the path when every step worked; next time the saved
                           path is replayed first (each step checked on screen, falls back on the first mismatch)
  replay "<任务名>"         replay a saved path now        paths    list saved paths
  ui elements [--all]      JSON element list (text / desc / id / role / clickable / center / bounds)
Reading tiers, fastest first (every result says which one was used):
  1·回放 saved path → 2·无障碍树 tree (default, no screenshot) → 3·OCR on-device text recognition when the tree has
  no text (games / canvas) → 4·云端视觉: ui screenshot + read_image to the cloud vision group (last resort; no local model).
Backend: accessibility when enabled, otherwise Shizuku (uiautomator + input).
"""

        fun handles(args: OffloadArgs): Boolean {
            val p = args.positional
            return when (p.getOrNull(0)) {
                "list", "do", "type", "elements", "replay", "paths" -> true
                "ui" -> p.getOrNull(1) == "list" || p.getOrNull(1) == "elements"
                "tap" -> p.getOrNull(1)?.toIntOrNull() != null
                "key" -> true
                // [Mines-launch-29] top-level open/launch and the shade work
                // without a UI backend too (see AppLauncher / shade()).
                "open", "launch", "shade" -> true
                else -> false
            }
        }

        fun shizukuReady(): Boolean = runCatching {
            ShizukuManager.refresh(); ShizukuManager.snapshot.value.state == ShizukuManager.State.READY
        }.getOrDefault(false)
    }

    private fun backend(): Backend? = when {
        MinisAccessibilityService.getInstance() != null -> Backend.A11Y
        shizukuReady() -> Backend.SHIZUKU
        else -> null
    }

    // ── voice call tools ([Mines-voice-phone]) ───────────────────────────

    private val noBackend = "手机控制不可用：请在 设置 → 无障碍 打开 Mines，或启动 Shizuku 并授权给 Mines。"
    private val sensitive = Regex("支付|付款|转账|确认付款|立即支付|下单|提交订单|购买|发送|发消息|send|pay|transfer|checkout", RegexOption.IGNORE_CASE)

    /** Numbered list of the current screen, for the voice agent. */
    fun voiceScreen(): String = backend()?.let { ensureListener(); list(it, false) } ?: noBackend

    /**
     * Run a batch of steps for the voice agent. Steps that would pay, transfer,
     * order or send something are refused unless [confirmed] (the agent must
     * ask the user out loud first).
     */
    fun voiceDo(script: String, confirmed: Boolean): String {
        val be = backend() ?: return noBackend
        ensureListener()
        if (!confirmed) {
            val hit = sensitiveStep(be, script)
            if (hit != null) return "NEEDS_CONFIRMATION: 「$hit」涉及付款/下单/发送。先用一句话向用户确认，用户明确说“确认”后再用 confirmed=true 调用。"
        }
        return batch(be, script)
    }

    private fun sensitiveStep(be: Backend, script: String): String? {
        if (last.isEmpty()) list(be, false)
        for (st in splitSteps(script)) {
            val verb = st.substringBefore(' ').lowercase()
            val rest = st.substringAfter(' ', "").trim()
            if (verb in setOf("tap", "click", "点")) {
                val label = rest.toIntOrNull()?.let { n -> last.firstOrNull { it.n == n }?.label } ?: unquote(rest)
                if (sensitive.containsMatchIn(label)) return label
            }
            if (verb == "key" && rest.lowercase() in setOf("enter", "回车")) {
                // Enter in a chat box sends the message.
                if (lastPkg.contains("tencent.mm") || lastPkg.contains("mobileqq") || lastPkg.contains("sms") || lastPkg.contains("messag")) return "回车发送"
            }
        }
        return null
    }

    // ── [Mines-keys-31] helpers for the API-key agent ─────────────────────

    /** True when a UI backend (accessibility or Shizuku) is available. */
    fun ready(): Boolean = backend() != null

    /** Every visible text on screen, untruncated (keys can be longer than list labels). */
    fun allText(): String {
        val be = backend() ?: return ""
        if (be == Backend.A11Y) {
            val svc = MinisAccessibilityService.getInstance() ?: return ""
            val sb = StringBuilder()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 60 || sb.length > 60_000) return
                n.text?.let { sb.append(it).append('\n') }
                n.contentDescription?.let { sb.append(it).append('\n') }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            for (r in svc.rootNodes()) walk(r, 0)
            return sb.toString()
        }
        return snapshotShizuku(true).joinToString("\n") { it.label }
    }

    /** Tap the first element whose label equals / contains [text]; false when not on screen. */
    fun tapText(text: String): Boolean {
        val be = backend() ?: return false
        ensureListener()
        val el = findByText(be, text) ?: return false
        return tapEl(be, el).first
    }

    /** Open an app by its name as shown on the launcher (or a package name). */
    fun voiceOpenApp(name: String): String {
        val r = com.openminis.app.plus.AppLauncher.open(context, name)
        val be = backend()
        if (r.ok && be != null) { ensureListener(); settle(be) }
        return r.message + (if (r.ok && be != null) "\n" + list(be, false) else "")
    }

    fun handle(args: OffloadArgs): NativeOffloadResult {
        val p0 = args.positional
        if (p0.firstOrNull() == "open" || p0.firstOrNull() == "launch") {
            val q = p0.drop(1).joinToString(" ")
            if (q.isBlank()) return NativeOffloadResult(2, "用法：open <包名或应用名>\n")
            val r = com.openminis.app.plus.AppLauncher.open(context, q)
            val be0 = backend()
            val tail = if (r.ok && be0 != null) { ensureListener(); settle(be0); "\n" + list(be0, false) } else "\n"
            return NativeOffloadResult(if (r.ok) 0 else 1, r.message + tail)
        }
        if (p0.firstOrNull() == "shade") {
            val r = shade(p0.getOrNull(1) ?: "open")
            return NativeOffloadResult(if (r.first) 0 else 1, r.second + "\n")
        }
        val be = backend() ?: return NativeOffloadResult(77, noBackendHelp())
        ensureListener()
        val p = args.positional
        return try {
            when (p[0]) {
                "ui", "list", "elements" -> if (p[0] == "elements" || p.getOrNull(1) == "elements") {
                    val els = elements(be, args.hasFlag("all"))
                    NativeOffloadResult(0, ScreenTiers.elementsJson(els.map { it.item() }, lastTier, lastPkg).toString() + "\n")
                } else NativeOffloadResult(0, list(be, args.hasFlag("all")))
                "replay" -> {
                    val task = p.drop(1).joinToString(" ").let { unquote(it) }
                    if (task.isBlank()) return NativeOffloadResult(2, "用法：replay <任务名>\n")
                    val r = replay(be, task)
                    NativeOffloadResult(if (r.first) 0 else 1, r.second + list(be, false))
                }
                "paths" -> NativeOffloadResult(0, PathStore.describe(context))
                "tap" -> step(be, "tap ${p[1]}").let { NativeOffloadResult(if (it.first) 0 else 1, it.second + "\n") }
                "type" -> {
                    val n = p.getOrNull(1) ?: return NativeOffloadResult(2, "用法：type <n> <文字>\n")
                    val text = p.drop(2).joinToString(" ")
                    val r = doType(be, n, text)
                    NativeOffloadResult(if (r.first) 0 else 1, r.second + "\n")
                }
                "key" -> step(be, "key ${p.getOrNull(1) ?: ""}").let { NativeOffloadResult(if (it.first) 0 else 1, it.second + "\n") }
                "do" -> {
                    val script = p.drop(1).joinToString(" ")
                    val task = args.get("task")?.trim()?.takeIf { it.isNotEmpty() }
                    if (task == null) return NativeOffloadResult(0, batch(be, script))
                    // [Mines-38.8 v5d] tier 1: a saved path for this app + task is replayed first
                    val pre = StringBuilder()
                    if (PathStore.get(context, currentPkg(be), task) != null) {
                        val (ok, note) = replay(be, task)
                        if (ok) return NativeOffloadResult(0, note + list(be, false))
                        pre.append(note)
                    }
                    if (script.isBlank()) return NativeOffloadResult(1, pre.toString() + "没有保存过「$task」的路径，请给出步骤。\n" + list(be, false))
                    NativeOffloadResult(0, pre.toString() + batch(be, script, task))
                }
                else -> NativeOffloadResult(2, HELP)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "fast ui failed: ${t.message}")
            NativeOffloadResult(1, "出错：${t.javaClass.simpleName}: ${t.message}\n")
        }
    }

    // ── batching ─────────────────────────────────────────────────────────

    private fun splitSteps(script: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null
        for (c in script) {
            when {
                q != null -> { if (c == q) q = null; cur.append(c) }
                c == '"' || c == '\'' -> { q = c; cur.append(c) }
                c == ';' || c == '\n' -> { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private fun batch(be: Backend, script: String, task: String? = null): String {
        val steps = splitSteps(script)
        val sb = StringBuilder()
        var endsWithList = false
        val startPkg = if (task != null) currentPkg(be) else ""
        val startAct = if (task != null) currentActivity() else ""
        val rec = ArrayList<ScreenTiers.Step>()
        var allOk = true
        for ((i, st) in steps.withIndex()) {
            if (st == "list") {
                sb.append(list(be, false)); endsWithList = i == steps.lastIndex; continue
            }
            lastTarget = null
            val (ok, msg) = step(be, st)
            sb.append("${i + 1}. $st → ").append(if (ok) "✓ " else "✗ ").append(msg).append('\n')
            if (!ok) { allOk = false; sb.append("（后面的步骤已停止）\n"); break }
            val verb = st.substringBefore(' ').lowercase(); val rest = st.substringAfter(' ', "").trim()
            val t = lastTarget
            rec += when {
                t != null && verb in setOf("type", "input", "输入") -> ScreenTiers.Step("type", t.label, t.rid, t.bounds.centerX(), t.bounds.centerY(), unquote(rest.substringAfter(' ', "")))
                t != null -> ScreenTiers.Step(if (verb in setOf("longpress", "长按")) "longpress" else "tap", t.label, t.rid, t.bounds.centerX(), t.bounds.centerY())
                else -> ScreenTiers.Step("raw", arg = st)
            }
        }
        if (task != null) {
            if (allOk && ScreenTiers.replayable(rec)) {
                PathStore.put(context, ScreenTiers.Path(startPkg, startAct, task, rec, System.currentTimeMillis()))
                // [Mines-38.9] the path also becomes a 经验 entry, so any model finds it (and the replay command) next time
                com.openminis.app.plus.Experience.recordReplay(context, startPkg, task, rec.map { st -> (st.verb + " " + st.label.ifBlank { st.arg }).trim() })
                sb.append("读屏方式：${lastTier} · 已记下「$task」的路径（${rec.size} 步，下次先回放）\n")
            } else if (allOk) sb.append("（含付款/发送一类步骤，路径不保存）\n")
        }
        if (!endsWithList) sb.append(list(be, false))
        return sb.toString()
    }

    // ── tier 1: replay ───────────────────────────────────────────────────

    @Volatile private var lastTarget: El? = null

    private fun currentPkg(be: Backend): String {
        MinisAccessibilityService.getInstance()?.foregroundPackage()?.first?.let { if (it.isNotBlank()) return it }
        if (lastPkg.isBlank()) runCatching { stableSnapshot(be, false) }
        return lastPkg
    }
    private fun currentActivity(): String = MinisAccessibilityService.getInstance()?.foregroundPackage()?.second ?: ""

    /** Replays the saved path; (true, note) when every step matched and worked, else (false, why) after the first mismatch. */
    private fun replay(be: Backend, task: String): Pair<Boolean, String> {
        val path = PathStore.get(context, currentPkg(be), task) ?: return false to "没有保存过「$task」的路径。\n"
        val t0 = System.currentTimeMillis()
        for ((i, s) in path.steps.withIndex()) {
            val r: Pair<Boolean, String> = when (s.verb) {
                "raw" -> step(be, s.arg)
                "tap", "longpress", "type" -> {
                    val els = stableSnapshot(be, true); last = els
                    val hit = ScreenTiers.matchStep(s, els.map { it.item() })
                    val el = hit?.let { h -> els.firstOrNull { it.bounds.left == h.l && it.bounds.top == h.t && it.bounds.right == h.r && it.bounds.bottom == h.b && it.label == h.label } }
                    if (el == null) {
                        return false to "回放「$task」第 ${i + 1} 步对不上（没找到「${s.label.ifBlank { s.rid }}」），改用读屏：\n"
                    }
                    when (s.verb) {
                        "type" -> doTypeEl(be, el, s.arg)
                        "longpress" -> longPressXY(be, el.bounds.centerX(), el.bounds.centerY()).let { it to (if (it) "长按了「${el.label}」" else "长按失败") }
                        else -> tapEl(be, el)
                    }.also { if (it.first) settle(be) }
                }
                else -> true to ""
            }
            if (!r.first) return false to "回放「$task」第 ${i + 1} 步失败（${r.second}），改用读屏：\n"
        }
        PathStore.put(context, path.copy(uses = path.uses + 1))
        return true to "读屏方式：${ScreenTiers.TIER_REPLAY} · 回放「$task」${path.steps.size} 步全部成功（${System.currentTimeMillis() - t0} ms）\n"
    }

    private fun unquote(s: String) = s.trim().let {
        if (it.length >= 2 && (it.first() == '"' && it.last() == '"' || it.first() == '\'' && it.last() == '\'')) it.substring(1, it.length - 1) else it
    }

    /** One step; returns success and a short note. Waits for the UI to settle afterwards. */
    private fun step(be: Backend, st: String): Pair<Boolean, String> {
        val verb = st.substringBefore(' ').lowercase()
        val rest = st.substringAfter(' ', "").trim()
        val r: Pair<Boolean, String> = when (verb) {
            "tap", "click", "点" -> {
                val xy = parseXY(rest)
                if (xy != null) tapXY(be, xy.first, xy.second).let { it to (if (it) "点了 (${xy.first},${xy.second})" else "点击失败") }
                else {
                    val n = rest.toIntOrNull()
                    val el = if (n != null) elementFor(be, n) else findByText(be, unquote(rest))
                    lastTarget = el
                    if (el == null) false to (if (n != null) "没有第 $n 项（先 list）" else "屏幕上没找到「${unquote(rest)}」")
                    else tapEl(be, el)
                }
            }
            "longpress", "长按" -> {
                val xy = parseXY(rest)
                if (xy != null) longPressXY(be, xy.first, xy.second).let { it to (if (it) "长按了 (${xy.first},${xy.second})" else "长按失败") }
                else {
                    val n = rest.toIntOrNull()
                    val el = if (n != null) elementFor(be, n) else findByText(be, unquote(rest))
                    lastTarget = el
                    if (el == null) false to "没找到要长按的「$rest」"
                    else longPressXY(be, el.bounds.centerX(), el.bounds.centerY()).let { it to (if (it) "长按了「${el.label}」" else "长按失败") }
                }
            }
            "swipe" -> {
                val nums = rest.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
                if (nums.size >= 4) swipeXY(be, nums[0], nums[1], nums[2], nums[3], (nums.getOrNull(4) ?: 300).coerceIn(50, 3000))
                else scroll(be, rest)
            }
            "type", "input", "输入" -> {
                val n = rest.substringBefore(' ')
                doType(be, n, unquote(rest.substringAfter(' ', "")))
            }
            "key" -> key(be, rest.lowercase())
            "back" -> key(be, "back")
            "home" -> key(be, "home")
            "scroll" -> scroll(be, rest)
            "wait", "sleep" -> { Thread.sleep((rest.toLongOrNull() ?: 500L).coerceIn(0L, 10_000L)); return true to "等了 ${rest.ifBlank { "500" }} ms" }
            "open", "launch" -> open(be, rest)
            "shade" -> shade(rest.ifBlank { "open" })
            else -> return false to "不认识的步骤（可用：tap/longpress/swipe/type/key/scroll/wait/open/shade/list）"
        }
        if (r.first) settle(be)
        // [Mines-30] Going home starts the launcher's enter animation; give it time.
        if (r.first && (verb == "home" || verb == "key" && rest.lowercase() in setOf("home", "主页"))) Thread.sleep(350)
        return r
    }

    // ── snapshot ─────────────────────────────────────────────────────────

    /** [Mines-38.8 v5d] tier 2 (tree), plus tier 3 (OCR lines) when the tree has almost no text. Sets [lastTier]. */
    private fun elements(be: Backend, all: Boolean): List<El> {
        val tree = stableSnapshot(be, all)
        lastTier = ScreenTiers.TIER_TREE
        if (!ScreenTiers.needsOcr(tree.map { it.item() })) { last = tree; return tree }
        val o = ScreenOcr.readBest(context, tree.map { it.item() })   // [Mines-38.9] ML Kit, then cloud OCR for Chinese / thin results
        if (o.items.isEmpty()) { last = tree; if (o.error != null) ocrNote = o.error; return tree }
        val extra = ScreenTiers.mergeOcr(tree.map { it.item() }, o.items)
        lastTier = ScreenTiers.TIER_TREE + " + " + o.tier + "（${o.ms} ms）"
        if (o.error != null) ocrNote = o.error
        val merged = tree + extra.map { e -> El(0, e.label, if (e.r > e.l) "文字(OCR)" else "文字(OCR·无位置)", Rect(e.l, e.t, e.r, e.b), null, e.r > e.l, false, false, source = "ocr") }
        return merged.mapIndexed { i, e -> e.copy(n = i + 1) }.also { last = it }
    }
    @Volatile private var ocrNote: String? = null

    private fun list(be: Backend, all: Boolean): String {
        ocrNote = null
        val els = elements(be, all)
        val sb = StringBuilder()
        sb.append("屏幕：").append(lastPkg.ifBlank { "?" }).append(" · ").append(be.label).append(" · ").append(els.size).append(" 项 · 读屏 ").append(lastTier).append('\n')
        for (e in els) {
            sb.append(e.n).append(' ')
            if (e.role.isNotEmpty()) sb.append('[').append(e.role).append("] ")
            sb.append(e.label).append("  (").append(e.bounds.centerX()).append(',').append(e.bounds.centerY()).append(")\n")
        }
        if (els.isEmpty() || ScreenTiers.needsOcr(els.map { it.item() }))
            sb.append("（读到的字很少${ocrNote?.let { "；$it" } ?: ""}。最后一招 ${ScreenTiers.TIER_CLOUD}：android-a11y-cli ui screenshot，再用 read_image 交给云端视觉模型组看；不用本地模型）\n")
        return sb.toString()
    }

    private fun roleOf(cls: String, clickable: Boolean, editable: Boolean, scrollable: Boolean, checkable: Boolean, checked: Boolean): String = when {
        editable || cls.endsWith("EditText") -> "输入框"
        checkable -> if (checked) "开✓" else "关"
        cls.endsWith("Button") || cls.endsWith("ImageButton") -> "按钮"
        clickable -> "可点"
        scrollable -> "可滑动"
        else -> ""
    }

    private fun snapshotA11y(all: Boolean): List<El> {
        val svc = MinisAccessibilityService.getInstance() ?: return emptyList()
        val out = ArrayList<El>()
        val roots = svc.rootNodes()
        // [Mines-38.9] the app in front (active application window), not the systemui status-bar window listed first
        lastPkg = runCatching { svc.activeAppPackage() }.getOrDefault("").ifBlank { roots.firstOrNull()?.packageName?.toString() ?: "" }
        val screen = Rect()
        fun childText(n: AccessibilityNodeInfo, depth: Int, acc: StringBuilder) {
            if (depth > 4 || acc.length > 40) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val t = (c.text ?: c.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty()) { if (acc.isNotEmpty()) acc.append(' '); acc.append(t) }
                childText(c, depth + 1, acc)
            }
        }
        fun walk(n: AccessibilityNodeInfo?, depth: Int, insideInteractive: Boolean) {
            if (n == null || depth > 40 || out.size >= 120) return
            if (!n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() <= 1 || r.height() <= 1) return
            val cls = n.className?.toString() ?: ""
            val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || (n.isScrollable && all)
            val own = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (interactive) {
                var label = own
                if (label.isEmpty()) { val sb = StringBuilder(); childText(n, 0, sb); label = sb.toString() }
                if (label.isEmpty()) label = n.viewIdResourceName?.substringAfter("/")?.let { "#$it" } ?: cls.substringAfterLast('.')
                if (n.isEditable && n.text.isNullOrEmpty()) n.hintText?.let { label = "（$it）" }
                out += El(out.size + 1, label.replace('\n', ' ').take(60),
                    roleOf(cls, n.isClickable, n.isEditable, n.isScrollable, n.isCheckable, n.isChecked),
                    r, n, n.isClickable, n.isEditable, n.isScrollable,
                    rid = n.viewIdResourceName ?: "", desc = n.contentDescription?.toString()?.take(80) ?: "")
            } else if (own.isNotEmpty() && !insideInteractive) {
                out += El(out.size + 1, own.replace('\n', ' ').take(60), "", r, n, false, false, false,
                    rid = n.viewIdResourceName ?: "", desc = n.contentDescription?.toString()?.take(80) ?: "")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, insideInteractive || interactive)
        }
        for (root in roots) walk(root, 0, false)
        if (!all) {
            // Hide our own overlays (desk pet, capsule) from the list.
            out.removeAll { it.node?.packageName?.toString() == context.packageName && lastPkg != context.packageName }
        }
        return out.mapIndexed { i, e -> e.copy(n = i + 1) }
    }

    private fun parseBounds(b: String): Rect {
        val m = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(b) ?: return Rect()
        val (a, c, d, e) = m.destructured
        return Rect(a.toInt(), c.toInt(), d.toInt(), e.toInt())
    }

    private fun snapshotShizuku(all: Boolean): List<El> {
        val path = "/data/local/tmp/mines_ui.xml"
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", "uiautomator dump $path >/dev/null 2>&1; cat $path"), timeoutMs = 12_000L)
        val xml = r.stdout.substringAfter("<?xml", "").let { if (it.isEmpty()) r.stdout else "<?xml$it" }
        val out = ArrayList<El>()
        if (!xml.contains("<node")) return out
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        val interactiveDepth = ArrayList<Boolean>()
        var ev = parser.eventType
        lastPkg = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && parser.name == "node") {
                fun a(k: String) = parser.getAttributeValue(null, k) ?: ""
                if (lastPkg.isEmpty() || lastPkg == "com.android.systemui") a("package").takeIf { it.isNotEmpty() && (lastPkg.isEmpty() || it != "com.android.systemui") }?.let { lastPkg = it }   // [Mines-38.9] skip the status bar
                val cls = a("class"); val text = a("text").ifEmpty { a("content-desc") }.trim()
                val clickable = a("clickable") == "true" || a("long-clickable") == "true"
                val editable = cls.endsWith("EditText")
                val scrollable = a("scrollable") == "true"
                val checkable = a("checkable") == "true"
                val inside = interactiveDepth.any { it }
                val interactive = clickable || editable || checkable || (scrollable && all)
                val rect = parseBounds(a("bounds"))
                if (rect.width() > 1 && rect.height() > 1) {
                    if (interactive) {
                        out += El(0, text.ifEmpty { a("resource-id").substringAfter('/').let { if (it.isEmpty()) cls.substringAfterLast('.') else "#$it" } }.take(60),
                            roleOf(cls, clickable, editable, scrollable, checkable, a("checked") == "true"), rect, null, clickable, editable, scrollable,
                            rid = a("resource-id"), desc = a("content-desc").take(80))
                    } else if (text.isNotEmpty() && !inside) {
                        out += El(0, text.take(60), "", rect, null, false, false, false, rid = a("resource-id"), desc = a("content-desc").take(80))
                    }
                }
                interactiveDepth += interactive
            } else if (ev == XmlPullParser.END_TAG && parser.name == "node") {
                if (interactiveDepth.isNotEmpty()) interactiveDepth.removeAt(interactiveDepth.lastIndex)
            }
            ev = parser.next()
        }
        return out.take(120).mapIndexed { i, e -> e.copy(n = i + 1) }
    }

    private fun elementFor(be: Backend, n: Int): El? {
        if (last.isEmpty()) list(be, false)
        return last.firstOrNull { it.n == n }
    }

    private fun findByText(be: Backend, text: String): El? {
        val els = if (be == Backend.A11Y) snapshotA11y(true) else snapshotShizuku(true)
        last = els
        // [Mines-38.8 v5d] ranked match (exact > prefix > contains > fuzzy; label or content-desc), then OCR lines (tier 3)
        ScreenTiers.best(text, els.map { it.item() })?.let { b -> els.firstOrNull { it.item() == b }?.let { return it } }
        val ro = ScreenOcr.readBest(context, els.map { it.item() })   // [Mines-38.9] cloud OCR when ML Kit cannot read it (Chinese)
        val b = ScreenTiers.best(text, ro.items.filter { it.r > it.l }) ?: return null
        lastTier = ro.tier
        return El(0, b.label, "文字(OCR)", Rect(b.l, b.t, b.r, b.b), null, true, false, false, source = "ocr")
    }

    private fun parseXY(s: String): Pair<Int, Int>? {
        val m = Regex("^\\(?\\s*(\\d{1,5})\\s*[, ]\\s*(\\d{1,5})\\s*\\)?$").find(s.trim()) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun longPressXY(be: Backend, x: Int, y: Int): Boolean {
        if (be == Backend.A11Y) {
            val svc = MinisAccessibilityService.getInstance()
            if (svc != null) {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 0.1f, y + 0.1f) }
                if (svc.dispatchSimpleGesture(path, 0L, 700L)) return true
            }
        }
        return shizukuReady() && shell("input", "swipe", "$x", "$y", "$x", "$y", "700")
    }

    private fun swipeXY(be: Backend, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Pair<Boolean, String> {
        val ok = if (be == Backend.A11Y && MinisAccessibilityService.getInstance() != null) {
            val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            MinisAccessibilityService.getInstance()!!.dispatchSimpleGesture(p, 0L, ms.toLong())
        } else shell("input", "swipe", "$x1", "$y1", "$x2", "$y2", "$ms")
        return ok to (if (ok) "从 ($x1,$y1) 滑到 ($x2,$y2)" else "滑动失败")
    }

    /** type into a replayed element (same path as `type N`, without the numbered list). */
    private fun doTypeEl(be: Backend, el: El, text: String): Pair<Boolean, String> {
        val idx = last.indexOf(el)
        if (idx >= 0) return doType(be, last[idx].n.toString(), text)
        return false to "输入框不在列表里"
    }

    // ── actions ──────────────────────────────────────────────────────────

    private fun shell(vararg argv: String): Boolean =
        ShizukuManager.runProcess(arrayOf(*argv), timeoutMs = 8_000L).exitCode == 0

    private fun tapXY(be: Backend, x: Int, y: Int): Boolean {
        if (be == Backend.A11Y) {
            val svc = MinisAccessibilityService.getInstance() ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 0.1f, y + 0.1f) }
            if (svc.dispatchSimpleGesture(path, 0L, 40L)) return true
            if (shizukuReady()) return shell("input", "tap", "$x", "$y")
            return false
        }
        return shell("input", "tap", "$x", "$y")
    }

    private fun tapEl(be: Backend, el: El): Pair<Boolean, String> {
        val node = el.node
        if (be == Backend.A11Y && node != null) {
            runCatching { node.refresh() }
            var cur: AccessibilityNodeInfo? = node; var hops = 0
            while (cur != null && !cur.isClickable && hops < 6) { cur = cur.parent; hops++ }
            if (cur != null && cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true to "点了「${el.label}」"
        }
        val ok = tapXY(be, el.bounds.centerX(), el.bounds.centerY())
        return ok to (if (ok) "点了「${el.label}」" else "点击失败")
    }

    private fun doType(be: Backend, nArg: String, text: String): Pair<Boolean, String> {
        val n = nArg.toIntOrNull()
        if (n != null) lastTarget = elementFor(be, n)
        if (be == Backend.A11Y) {
            val svc = MinisAccessibilityService.getInstance() ?: return false to "无障碍未运行"
            val target: AccessibilityNodeInfo? = if (n != null) elementFor(be, n)?.node else
                svc.rootNodes().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            if (target != null) {
                runCatching { target.refresh() }
                if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return true to "输入了「${text.take(30)}」"
            }
            if (!shizukuReady()) return false to "这个位置不能直接输入"
        }
        // Shizuku: tap to focus, then type. `input text` only does ASCII, so
        // anything else goes through the clipboard + paste key.
        if (n != null) elementFor(be, n)?.let { tapXY(be, it.bounds.centerX(), it.bounds.centerY()); Thread.sleep(250) }
        val ascii = text.all { it.code in 32..126 }
        val ok = if (ascii) shell("input", "text", text.replace(" ", "%s"))
        else {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { cm.setPrimaryClip(ClipData.newPlainText("mines", text)) }; latch.countDown()
            }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            shell("input", "keyevent", "279") // KEYCODE_PASTE
        }
        return ok to (if (ok) "输入了「${text.take(30)}」" else "输入失败")
    }

    private fun key(be: Backend, k: String): Pair<Boolean, String> {
        val svc = MinisAccessibilityService.getInstance()
        val global = when (k) {
            "back", "返回" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "主页" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings", "qs" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> null
        }
        if (global != null && svc != null && be == Backend.A11Y) return svc.performGlobalAction(global) to "按了 $k"
        val code = when (k) {
            "back", "返回" -> "4"; "home", "主页" -> "3"; "recents" -> "187"; "enter", "回车", "search" -> "66"
            "notifications" -> return shade("open"); "quicksettings", "qs" -> return shade("qs")
            "delete", "del" -> "67"; else -> k.toIntOrNull()?.toString()
        } ?: return false to "不认识的按键 $k"
        if (code == "66" && svc != null && Build.VERSION.SDK_INT >= 30) {
            val f = svc.rootNodes().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            if (f != null && f.performAction(android.R.id.accessibilityActionImeEnter)) return true to "按了回车"
        }
        if (!shizukuReady()) return false to "这个按键需要 Shizuku"
        return shell("input", "keyevent", code) to "按了 $k"
    }

    private fun scroll(be: Backend, arg: String): Pair<Boolean, String> {
        val parts = arg.split(' ').filter { it.isNotBlank() }
        val dir = parts.getOrNull(0)?.lowercase() ?: "down"
        val times = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val (y1, y2) = if (dir == "up") (h * 0.3).toInt() to (h * 0.75).toInt() else (h * 0.75).toInt() to (h * 0.3).toInt()
        repeat(times) {
            val ok = if (be == Backend.A11Y) {
                val svc = MinisAccessibilityService.getInstance() ?: return false to "无障碍未运行"
                val p = Path().apply { moveTo(w / 2f, y1.toFloat()); lineTo(w / 2f, y2.toFloat()) }
                svc.dispatchSimpleGesture(p, 0L, 280L)
            } else shell("input", "swipe", "${w / 2}", "$y1", "${w / 2}", "$y2", "280")
            if (!ok) return false to "滑动失败"
            if (times > 1) Thread.sleep(250)
        }
        return true to "向${if (dir == "up") "上" else "下"}滑了 $times 次"
    }

    @Suppress("UNUSED_PARAMETER")
    private fun open(be: Backend, pkg: String): Pair<Boolean, String> {
        val r = com.openminis.app.plus.AppLauncher.open(context, unquote(pkg))
        return r.ok to r.message
    }

    /**
     * [Mines-shade-29] Notification shade / quick settings. Tapping status-bar
     * icons does nothing on Android and a swipe from the very top edge is
     * often not delivered to a11y gestures (realme), so use the global
     * actions; Shizuku falls back to `cmd statusbar`.
     */
    private fun shade(what: String): Pair<Boolean, String> {
        val svc = MinisAccessibilityService.getInstance()
        val w = what.trim().lowercase()
        val action = when (w) {
            "open", "notifications", "通知", "down", "" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "qs", "quicksettings", "quick", "settings", "快捷设置", "控制中心" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "close", "collapse", "收起" -> if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE else AccessibilityService.GLOBAL_ACTION_BACK
            else -> return false to "shade 只认 open / qs / close"
        }
        if (svc != null && svc.performGlobalAction(action)) {
            Thread.sleep(450)
            return true to when (action) {
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> "拉下了通知栏（用 list 看通知，tap N 点通知）"
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> "打开了快捷设置"
                else -> "收起了通知栏"
            }
        }
        if (shizukuReady()) {
            val sub = when (action) {
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> "expand-notifications"
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> "expand-settings"
                else -> "collapse"
            }
            if (shell("cmd", "statusbar", sub)) { Thread.sleep(450); return true to "通知栏：$sub" }
        }
        return false to (if (svc == null) "需要无障碍服务或 Shizuku 才能拉下通知栏" else "系统拒绝了这个操作")
    }

    private fun noBackendHelp(): String {
        val st = runCatching { ShizukuManager.refresh(); ShizukuManager.snapshot.value.state }.getOrNull()
        val shz = when (st) {
            ShizukuManager.State.NEED_PERMISSION -> {
                // [Mines-shizuku-29] one tap: Shizuku's own allow dialog.
                val asked = com.openminis.app.plus.ShizukuAsk.request(context)
                "Shizuku 在运行但没授权给 Mines" + (if (asked) "——已弹出授权框，点「允许」后重试。" else "——打开 Shizuku → 授权应用 → 打开 Mines。")
            }
            ShizukuManager.State.NOT_RUNNING -> "Shizuku 装了但没启动（打开 Shizuku 点启动）"
            ShizukuManager.State.NOT_INSTALLED -> "没装 Shizuku"
            else -> "Shizuku 就绪"
        }
        // [Mines-perm-30] Same machine-readable PERM_DENIED line as the other phone CLIs.
        val action = if (st == ShizukuManager.State.NEED_PERMISSION) PermDenied.SHIZUKU else PermDenied.ACCESSIBILITY
        return PermDenied.json("SERVICE_NOT_RUNNING", "accessibility off and Shizuku not usable", action).toString() + "\n" +
            "手机控制不可用：无障碍服务没开；$shz。\n最简单：设置 → 无障碍 → Mines 打开。\n"
    }

    /**
     * [Mines-30] build-28 smoke: icon coordinates on the home screen changed
     * between two `key home; list` runs — the launcher was still animating
     * (icons fly in / page snaps) and sends no accessibility events while
     * doing so, so [settle] returned early. Take snapshots until two in a row
     * agree on every label + bounds (max ~1.5 s); Shizuku dumps are slow
     * enough to be taken once.
     */
    private fun stableSnapshot(be: Backend, all: Boolean): List<El> {
        if (be != Backend.A11Y) return snapshotShizuku(all)
        fun sig(l: List<El>) = l.joinToString("|") { it.label + "@" + it.bounds.flattenToString() }
        var prev = snapshotA11y(all)
        val until = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < until) {
            Thread.sleep(150)
            val cur = snapshotA11y(all)
            if (sig(cur) == sig(prev)) return cur
            prev = cur
        }
        return prev
    }

    // ── settle ───────────────────────────────────────────────────────────

    private fun ensureListener() {
        if (listening) return
        val svc = MinisAccessibilityService.getInstance() ?: return
        svc.addEventListener { lastEventAt.set(System.currentTimeMillis()) }
        listening = true
    }

    /**
     * Wait until the screen stops changing: no accessibility event for
     * 180 ms, at least 120 ms and at most 1200 ms after the action. With the
     * Shizuku backend (no event stream) a fixed 400 ms.
     */
    private fun settle(be: Backend) {
        if (be != Backend.A11Y || MinisAccessibilityService.getInstance() == null) { Thread.sleep(400); return }
        val start = System.currentTimeMillis()
        Thread.sleep(120)
        while (System.currentTimeMillis() - start < 1200) {
            if (System.currentTimeMillis() - lastEventAt.get() >= 180) break
            Thread.sleep(40)
        }
    }
}
