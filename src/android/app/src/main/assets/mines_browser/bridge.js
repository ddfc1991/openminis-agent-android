/* [Mines-38.9.13 mines_browser] Content script of Mines' own browser. Kotlin sends {id, code}; the code runs here (it sees
   the page DOM, not the page's own JS globals) and the answer goes back as {id, ok, value}. Strings are returned as they
   are, other values as JSON, Promises are awaited — the same contract as WebView.evaluateJavascript in the WebView backend. */
(function () {
  if (window.top !== window) return;
  var port = null;
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  // element centre in CSS px after scrolling it into view (Kotlin then taps it with a native touch)
  async function __minesRect(sel) {
    var el = document.querySelector(sel);
    if (!el) return JSON.stringify({ error: 'Element not found: ' + sel });
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(); }
    await sleep(450);
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return JSON.stringify({ error: 'Element is not visible: ' + sel, tag: el.tagName });
    return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height,
      dpr: window.devicePixelRatio || 1, tag: el.tagName, text: (el.innerText || el.value || '').substring(0, 100) });
  }

  // type like a person: one character at a time with the gaps Kotlin computed (MinesBrowserCore.typingDelays)
  async function __minesType(sel, text, delays) {
    var el = sel ? document.querySelector(sel) : document.activeElement;
    if (!el) return JSON.stringify({ error: 'Element not found: ' + sel });
    el.focus();
    var isField = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
    if (isField) {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value');
      if (setter && setter.set) setter.set.call(el, ''); else el.value = '';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    for (var i = 0; i < text.length; i++) {
      await sleep(delays[i] || 80);
      var ch = text[i];
      el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
      var done = false;
      try { done = document.execCommand('insertText', false, ch); } catch (e) { done = false; }
      if (!done && isField) {
        var p2 = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var s2 = Object.getOwnPropertyDescriptor(p2, 'value');
        if (s2 && s2.set) s2.set.call(el, el.value + ch); else el.value += ch;
        el.dispatchEvent(new InputEvent('input', { data: ch, inputType: 'insertText', bubbles: true }));
      } else if (!done) {
        el.textContent = (el.textContent || '') + ch;
        el.dispatchEvent(new InputEvent('input', { data: ch, inputType: 'insertText', bubbles: true }));
      }
      el.dispatchEvent(new KeyboardEvent('keyup', { key: ch, bubbles: true }));
    }
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return JSON.stringify({ typed: true, tag: el.tagName, length: text.length });
  }

  // scroll in a few uneven steps (Kotlin computed them) with short pauses, like a thumb
  async function __minesScroll(sel, steps) {
    var el = sel ? document.querySelector(sel) : null;
    if (sel && !el) return JSON.stringify({ error: 'Element not found: ' + sel });
    for (var i = 0; i < steps.length; i++) {
      if (el) el.scrollBy({ top: steps[i], behavior: 'smooth' }); else window.scrollBy({ top: steps[i], behavior: 'smooth' });
      await sleep(140 + Math.floor(Math.random() * 160));
    }
    var t = el || document.scrollingElement || document.documentElement;
    return JSON.stringify({ scrolled: true, scrollTop: Math.round(t.scrollTop), scrollHeight: t.scrollHeight });
  }

  function connect() {
    try { port = browser.runtime.connectNative('mines'); } catch (e) { port = null; return; }
    port.onMessage.addListener(async function (m) {
      if (!m || typeof m.id !== 'number') return;
      var ok = true, value;
      try {
        var r = eval(m.code);
        if (r && typeof r.then === 'function') r = await r;
        value = (typeof r === 'string') ? r : (r === undefined ? 'null' : JSON.stringify(r));
      } catch (e) { ok = false; value = String((e && e.message) || e); }
      try { port.postMessage({ id: m.id, ok: ok, value: value }); } catch (e) { }
    });
    port.onDisconnect.addListener(function () { port = null; });
  }
  connect();
})();
