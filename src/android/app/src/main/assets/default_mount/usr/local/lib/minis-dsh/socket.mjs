#!/usr/bin/env node
// minis-dsh socket: loads DeepSeek Harness (dsh) plugins without dsh itself,
// gives each one a compatible `ctx`, and serves the tools they register as a
// single stdio MCP server that Minis already knows how to call.
//
// Supported ctx surface: tools.register, systemPrompt.section/context,
// commands.register (recorded), skills (recorded), on()/effect()/logger(),
// and llm.registerAdapter (model-provider plugins such as dsh-workbuddy-connect;
// their models are served to Mines by `--gateway`, see below).
// A plugin that `inject`s a service outside this set (sessions, web UI…)
// is skipped and reported by `minis-dsh list`.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import http from 'node:http'

const HOME = process.env.MINIS_DSH_HOME || path.join(process.env.HOME || '/root', '.minis-dsh')
const STATE = path.join(HOME, 'plugins.json')
const SUPPORTED = new Set(['tools', 'systemPrompt', 'commands', 'skills', 'logger', 'agents', 'llm', 'webServer', 'credentials', 'connection'])
const fmt = (v) => v instanceof Error ? (v.stack || v.message) : (typeof v === 'string' ? v : (() => { try { return JSON.stringify(v) } catch { return String(v) } })())
const log = (...a) => { try { fs.appendFileSync(path.join(HOME, 'socket.log'), `[${new Date().toISOString()}] ${a.join(' ')}\n`) } catch {} }

// [Mines-dsh-25] Status format version. Mines treats a status.json without a
// matching status.meta.json as stale (written by an older socket) and
// re-checks in the background instead of showing a wrong label.
const STATUS_VERSION = 25
const withTimeout = (p, ms, what) => Promise.race([p, new Promise((_, bad) => setTimeout(() => bad(new Error(`${what} 超时（${ms / 1000}s）`)), ms).unref())])
// Every process a plugin started (update checks, helpers…) is killed when the
// socket exits. Inside PRoot the sandbox command only returns once ALL its
// processes are gone, so one leftover child kept 「检查插件」 spinning forever.
function descendants(root) {
  const kids = new Map()
  let list = []
  try { list = fs.readdirSync('/proc') } catch { return [] }
  for (const d of list) {
    if (!/^\d+$/.test(d)) continue
    try {
      const st = fs.readFileSync(`/proc/${d}/stat`, 'utf8')
      const ppid = Number(st.slice(st.lastIndexOf(')') + 2).split(' ')[1])
      if (!kids.has(ppid)) kids.set(ppid, [])
      kids.get(ppid).push(Number(d))
    } catch {}
  }
  const out = []
  const walk = (p, depth) => { if (depth > 16) return; for (const c of kids.get(p) || []) { out.push(c); walk(c, depth + 1) } }
  walk(root, 0)
  return out
}
function reapChildren() {
  for (const pid of descendants(process.pid).reverse()) { try { process.kill(pid, 'SIGKILL') } catch {} }
}
process.on('exit', reapChildren)
for (const sig of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(sig, () => process.exit(0))

const tools = new Map()        // name -> { def, plugin }
const sections = []            // { plugin, order, text|fn }
const status = {}              // plugin -> { ok, reason, tools: [], llm: [] }
const adapters = new Map()     // provider route id -> { adapter, plugin }
// [Mines-dsh-ui] Web UI plugins (widgets such as dsh-whale-widget): their
// HTTP routes, index-page transforms and event listeners. `--web` serves them
// on 127.0.0.1:8791 and Mines shows that page in a floating overlay.
const routes = []              // { kind, path, method, handler, plugin }
const indexTaps = []           // html => html
const listeners = new Map()    // event -> Set<fn>
function emit(name, ...args) { for (const fn of listeners.get(name) || []) { try { fn(...args) } catch (e) { log('event', name, e.message) } } }
const CRED_FILE = path.join(HOME, 'credentials.json')
function readCreds() { try { return JSON.parse(fs.readFileSync(CRED_FILE, 'utf8')) } catch { return {} } }
const credentials = {
  async resolve(ref) {
    const r = String(ref || '')
    const v = readCreds()[r] ?? process.env[r]
    if (!v) throw new Error(`没有 ${r}：在 Mines 账号连接里填写并打开「交给 AI 使用」`)
    return { ref: r, value: String(v) }
  },
  async get(ref) { try { return await credentials.resolve(ref) } catch { return null } },
  async set(ref, value) { const c = readCreds(); c[String(ref)] = String(value); fs.writeFileSync(CRED_FILE, JSON.stringify(c, null, 2), { mode: 0o600 }) },
  async unset(ref) { const c = readCreds(); delete c[String(ref)]; fs.writeFileSync(CRED_FILE, JSON.stringify(c, null, 2), { mode: 0o600 }) },
  async deleteRecord(ref) { return credentials.unset(ref) },
  has(ref) { return !!(readCreds()[String(ref)] ?? process.env[String(ref)]) },
}
const connection = { requestRejection() { return null }, isTrusted() { return true } }

// The `llm` service: model-provider plugins register an adapter per provider
// route. Mines does not run dsh's model loop; the gateway below turns each
// registered route into an OpenAI-compatible endpoint Mines already speaks.
function makeLlm(pluginName) {
  return {
    registerAdapter(ids, adapter) {
      const list = Array.isArray(ids) ? ids : [ids]
      for (const id of list) {
        adapters.set(String(id), { adapter, plugin: pluginName })
        if (!status[pluginName].llm.includes(String(id))) status[pluginName].llm.push(String(id))
      }
      return () => { for (const id of list) adapters.delete(String(id)) }
    },
    adapters() { return [...adapters.keys()] },
    prepareCall() { throw new Error('minis-dsh: llm.prepareCall is not available in Mines') },
  }
}

function makeCtx(pluginName, config) {
  const disposers = []
  const reg = (fn) => { disposers.push(fn); return fn }
  const ctx = {
    name: pluginName,
    config,
    tools: {
      register(def) {
        if (!def || !def.name) throw new Error('tool needs a name')
        tools.set(def.name, { def, plugin: pluginName })
        status[pluginName].tools.push(def.name)
        return reg(() => tools.delete(def.name))
      },
      guard() { return () => {} },
      schemas() { return [...tools.values()].map(t => schemaOf(t.def)) },
    },
    systemPrompt: {
      section(a, b) {
        const s = typeof a === 'object' ? a : { name: a, ...(typeof b === 'object' ? b : { text: b }) }
        sections.push({ plugin: pluginName, order: s.order ?? 100, text: s.text ?? s.content, fn: s.render })
        return () => {}
      },
      context(a, b) {
        const s = typeof a === 'object' ? a : { name: a, render: b }
        sections.push({ plugin: pluginName, order: s.order ?? 900, fn: s.render ?? s.value ?? s.text })
        return () => {}
      },
    },
    commands: { register() { return () => {} } },
    skills: { register() { return () => {} } },
    agents: {},
    llm: makeLlm(pluginName),
    webServer: {
      register(route) {
        if (!route || typeof route.handler !== 'function') throw new Error('route needs a handler')
        const r = { kind: route.kind || 'exact', path: String(route.path || '/'), method: route.method, handler: route.handler, plugin: pluginName }
        routes.push(r)
        return reg(() => { const i = routes.indexOf(r); if (i >= 0) routes.splice(i, 1) })
      },
      tapIndex(fn) { indexTaps.push(fn); status[pluginName].ui = true; return reg(() => { const i = indexTaps.indexOf(fn); if (i >= 0) indexTaps.splice(i, 1) }) },
      get port() { return WEB_PORT },
    },
    credentials,
    connection,
    on(name, fn) {
      if (typeof fn !== 'function') return () => {}
      if (!listeners.has(name)) listeners.set(name, new Set())
      listeners.get(name).add(fn)
      if (name === 'webserver/index-inject') status[pluginName].ui = true
      return () => listeners.get(name)?.delete(fn)
    },
    emit: (name, ...a) => emit(name, ...a), serial: async () => {}, parallel: async () => {},
    effect(fn) { try { const d = fn(); if (typeof d === 'function') disposers.push(d) } catch (e) { log(pluginName, 'effect', e.message) } return () => {} },
    // Callable (`ctx.logger('x').info()`) and usable directly (`ctx.logger.warn()`).
    logger: Object.assign(
      (name) => { const f = (...a) => log(`${pluginName}/${name}:`, ...a.map(fmt)); return { info: f, warn: f, error: f, debug: f, success: f } },
      Object.fromEntries(['info', 'warn', 'error', 'debug', 'success'].map(k => [k, (...a) => log(`${pluginName}:`, ...a.map(fmt))])),
    ),
    plugin() { return () => {} },
    // Optional services: run the callback only when every one is provided
    // here (dsh does the same when a service is absent).
    inject(deps, fn) {
      const need = Array.isArray(deps) ? deps : Object.keys(deps || {})
      if (need.some(d => !SUPPORTED.has(d))) return () => {}
      try { fn(ctx) } catch (e) { log(pluginName, 'inject', e.message) }
      return () => {}
    },
    get(name) { return SUPPORTED.has(name) ? ctx[name] : undefined },
    root: undefined,
    set() {}, provide() {}, mixin() {},
    scope: { dispose: () => disposers.forEach(d => { try { d() } catch {} }) },
  }
  return new Proxy(ctx, {
    get(t, k) {
      if (k in t) return t[k]
      if (typeof k === 'string' && k.startsWith('$')) return t
      return undefined
    },
  })
}

function schemaOf(def) {
  const params = def.parameters || def.inputSchema || def.input_schema || def.schema || { type: 'object', properties: {} }
  return { name: def.name, description: def.description || '', inputSchema: params }
}

function injectList(mod) {
  const inj = mod.inject
  if (!inj) return []
  if (Array.isArray(inj)) return inj
  if (typeof inj === 'object') return Object.entries(inj).filter(([, v]) => v === true || (v && v.required !== false)).map(([k]) => k)
  return []
}

async function loadPlugin(entry) {
  const name = entry.package
  status[name] = { ok: false, reason: '', tools: [], llm: [], ui: false }
  try {
    const req = createRequire(path.join(HOME, 'node_modules', 'noop.js'))
    let resolved
    try { resolved = req.resolve(name) } catch {
      const pj = JSON.parse(fs.readFileSync(path.join(HOME, 'node_modules', name, 'package.json'), 'utf8'))
      const main = (pj.exports && (pj.exports['.']?.default || pj.exports['.']?.import || pj.exports['.'])) || pj.module || pj.main || 'index.js'
      resolved = path.join(HOME, 'node_modules', name, typeof main === 'string' ? main : 'index.js')
    }
    const raw = await import(pathToFileURL(resolved).href)
    const candidates = []
    const push = (m) => { if (m && (typeof m.apply === 'function' || typeof m === 'function')) candidates.push(m) }
    push(raw); push(raw.default)
    if (raw.default && typeof raw.default === 'object') Object.values(raw.default).forEach(push)
    if (!candidates.length) throw new Error('不是 dsh 插件（找不到 apply）')
    const mod = candidates[0]
    const missing = injectList(mod).filter(s => !SUPPORTED.has(s))
    if (missing.length) { status[name].reason = `需要 dsh 内部服务：${missing.join(', ')}`; return }
    const ctx = makeCtx(name, entry.config || {})
    const run = async () => {
      if (typeof mod.apply === 'function') await mod.apply(ctx, entry.config || {})
      else if (mod.prototype && mod.prototype.constructor) new mod(ctx, entry.config || {})
      else await mod(ctx, entry.config || {})
    }
    // A plugin whose start-up never settles must not hold up the others.
    try { await withTimeout(run(), 10_000, 'apply') } catch (e) { if (!/超时/.test(e.message)) throw e; log('load', name, e.message) }
    status[name].ok = true
  } catch (e) {
    status[name].reason = e.message
    log('load', name, e.stack || e.message)
  }
}

function renderResult(def, args, value) {
  try {
    if (def.output && typeof def.output.render === 'function') {
      const blocks = def.output.render(args, value)
      if (Array.isArray(blocks) && blocks.length) {
        return blocks.map(b => b.type === 'text' ? { type: 'text', text: b.text } : { type: 'text', text: JSON.stringify(b) })
      }
    }
  } catch {}
  return [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value, null, 2) }]
}

async function instructions() {
  const parts = []
  for (const s of sections.sort((a, b) => a.order - b.order)) {
    try {
      const v = s.text ?? (typeof s.fn === 'function' ? await s.fn() : s.fn)
      if (typeof v === 'string' && v.trim()) parts.push(v.trim())
    } catch {}
  }
  return parts.join('\n\n')
}

function kindOf(st) {
  if (!st.ok) return 'broken'
  if (st.ui) return 'widget'
  if ((st.llm || []).length) return 'model'
  if ((st.tools || []).length) return 'tools'
  return 'prompt'
}
function writeStatus(mode) {
  for (const st of Object.values(status)) st.kind = kindOf(st)
  try { fs.writeFileSync(path.join(HOME, 'status.json'), JSON.stringify(status, null, 2)) } catch {}
  try { fs.writeFileSync(path.join(HOME, 'status.meta.json'), JSON.stringify({ v: STATUS_VERSION, at: Date.now(), mode }, null, 2)) } catch {}
}

async function boot() {
  let state = { plugins: [] }
  try { state = JSON.parse(fs.readFileSync(STATE, 'utf8')) } catch {}
  const mode = process.argv.includes('--check') ? 'check' : process.argv.includes('--web') ? 'web' : process.argv.includes('--gateway') ? 'gateway' : 'mcp'
  process.on('exit', () => writeStatus(mode))
  for (const p of state.plugins || []) {
    if (p.enabled === false) { status[p.package] = { ok: false, reason: '已停用', tools: [], llm: [], ui: false }; continue }
    await loadPlugin(p)
  }
  writeStatus(mode)
}

const WEB_PORT = Number(process.env.MINIS_DSH_WEB_PORT || 8791)

// --- `node socket.mjs --web`: host for Web UI plugins --------------------
//   GET /                the widget page (transparent, plugin scripts injected)
//   GET /__mines/health  liveness for Mines
//   *   <plugin routes>  exactly what each plugin registered on webServer
// Bound to 127.0.0.1 only; Mines shows the page in a floating overlay.
// [Mines-dsh-ui-25] Widgets only start on DSH's chat page: dsh-whale-widget
// looks for a composer inside #root (textarea / [data-composer-input]) and
// stays dormant otherwise. build-24 put the stand-in textarea outside #root,
// so the widget never initialised and the overlay stayed empty.
async function renderIndex() {
  const table = []
  emit('webserver/index-inject', table)
  const rows = table.map(r => {
    if (!r) return ''
    if (r.kind === 'script-src' && r.src) return `<script src="${String(r.src).replace(/"/g, '&quot;')}"></script>`
    if (r.kind === 'script' && r.text) return `<script>${r.text}</script>`
    if (r.kind === 'style' && r.text) return `<style>${r.text}</style>`
    if (r.kind === 'html' && r.text) return r.text
    return ''
  }).join('\n')
  let html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{background:transparent!important;margin:0;height:100%;overflow:hidden;font-family:sans-serif}
#mines-composer{position:absolute;left:-9999px;top:-9999px;width:1px;height:1px;opacity:0}</style></head>
<body data-mines-host="1"><div id="root"><div id="mines-chat" data-composer-seat="1"><textarea id="mines-composer" data-composer-input="true" aria-hidden="true"></textarea></div></div>${rows}</body></html>`
  for (const fn of indexTaps) { try { const v = await fn(html); if (typeof v === 'string') html = v } catch (e) { log('tapIndex', e.message) } }
  return html
}

if (process.argv.includes('--web')) {
  await boot()
  const srv = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1')
      const p = url.pathname
      if (p === '/__mines/health') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ ok: true, routes: routes.length })) }
      const method = String(req.method || 'GET').toUpperCase()
      const hit = routes.find(r => (r.kind === 'prefix' ? p.startsWith(r.path) : p === r.path) &&
        (!r.method || String(r.method).toUpperCase() === method))
      if (hit) return await hit.handler(req, res)
      if (p === '/' || p === '/index.html') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
        return res.end(await renderIndex())
      }
      res.writeHead(404); res.end('not found')
    } catch (e) {
      log('web', e.stack || e.message)
      try { res.writeHead(500); res.end(String(e.message || e)) } catch {}
    }
  })
  srv.listen(WEB_PORT, '127.0.0.1', () => log('web host on', WEB_PORT))
  await new Promise(() => {})
}

// --- model gateway helpers ---------------------------------------------
const GATEWAY_PORT = Number(process.env.MINIS_DSH_GATEWAY_PORT || 8790)

// pi-ai backed adapters (dsh-llm-pi-ai PiAiAdapter and subclasses) expose
// their per-route profile through `config.profiles()`; each model carries the
// wire protocol (`api`) and the endpoint it talks to (`baseUrl`).
function profileOf(adapter, route) {
  try { return adapter?.config?.profiles?.()?.get(route) } catch { return undefined }
}
function descriptorsOf(adapter, route) {
  try {
    const snap = typeof adapter.current === 'function' ? adapter.current() : undefined
    const ms = snap?.models?.getModels?.(route)
    if (Array.isArray(ms) && ms.length) return ms
  } catch {}
  try {
    const p = profileOf(adapter, route)
    const ms = p?.piProvider?.getModels?.()
    if (Array.isArray(ms)) return ms
  } catch {}
  return []
}
async function routeInfo(route) {
  const a = adapters.get(route)
  if (!a) return undefined
  let listed = []
  try { listed = await withTimeout(Promise.resolve(a.adapter.listModels(route)), 6000, 'listModels') } catch (e) { log('listModels', route, e.message) }
  const desc = descriptorsOf(a.adapter, route)
  const byId = new Map(desc.map(d => [d.id, d]))
  const models = (listed.length ? listed : desc).map(m => {
    const d = byId.get(m.id)
    return { id: m.id, name: m.name || m.id, api: d?.api || '', images: (m.inputModalities || d?.input || []).includes('image'),
      context: d?.contextWindow || 0, proxied: !!d && d.api === 'openai-completions' && !!d.baseUrl }
  })
  let name = route
  try { name = a.adapter.providerInfo?.(route)?.name || profileOf(a.adapter, route)?.displayName || route } catch {}
  return { id: route, name, plugin: a.plugin, models }
}
async function writeLlmIndex() {
  const out = { port: GATEWAY_PORT, updatedAt: Date.now(), providers: [] }
  for (const route of adapters.keys()) { const r = await routeInfo(route); if (r) out.providers.push(r) }
  try { fs.writeFileSync(path.join(HOME, 'llm.json'), JSON.stringify(out, null, 2)) } catch {}
  return out
}
// Plugins register their routes from async start-up work (a loopback shim
// has to bind first), so give them a moment before reporting.
async function settle(ms = 2500) {
  const want = Object.values(status).some(s => s.ok) ? ms : 0
  const t0 = Date.now()
  while (Date.now() - t0 < want) {
    await new Promise(r => setTimeout(r, 250))
    if (adapters.size && Date.now() - t0 > 800) break
  }
}

// --- `node socket.mjs --check`: load everything and print a report ---
if (process.argv.includes('--check')) {
  // Hard stop: whatever is known after 45 s is reported, never a spinner forever.
  setTimeout(() => { console.log('（部分插件 45 秒内没有响应，先显示已知结果）'); process.exit(0) }, 45_000).unref()
  await boot()
  await settle()
  let idx = { providers: [] }
  try { idx = await withTimeout(writeLlmIndex(), 15_000, 'models') } catch (e) { log('check', e.message) }
  writeStatus('check')
  for (const [name, st] of Object.entries(status)) {
    const llm = idx.providers.filter(p => p.plugin === name)
    const llmText = llm.length ? `  模型: ${llm.map(p => `${p.name}(${p.models.length})`).join(', ')}` : ''
    const uiText = st.ui ? '  界面: 挂件（在 Mines 插件页点「显示挂件」）' : ''
    const toolText = st.tools.length ? `  工具: ${st.tools.join(', ')}` : (st.ui || llm.length ? '' : '  工具: 无')
    console.log(`${st.ok ? '✓' : '✗'} ${name}${st.ok ? `${toolText}${llmText}${uiText}` : `  ${st.reason}`}`)
  }
  process.exit(0)
}

// --- `node socket.mjs --gateway`: OpenAI-compatible endpoint for plugin models ---
//   GET  /health
//   GET  /p/<route>/v1/models
//   POST /p/<route>/v1/chat/completions   (proxied to the plugin's own endpoint)
// Bound to 127.0.0.1 only. Mines adds one provider per route.
if (process.argv.includes('--gateway')) {
  await boot()
  await settle(4000)
  await writeLlmIndex()
  setInterval(() => { writeLlmIndex().catch(() => {}) }, 60_000).unref()
  const json = (res, code, body) => { const b = JSON.stringify(body); res.writeHead(code, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(b) }); res.end(b) }
  const err = (res, code, msg) => json(res, code, { error: { message: msg, type: 'minis_dsh', code } })
  const readBody = (req) => new Promise((ok, bad) => { const c = []; req.on('data', d => c.push(d)); req.on('end', () => ok(Buffer.concat(c))); req.on('error', bad) })
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1')
      if (url.pathname === '/health') return json(res, 200, { ok: true, routes: [...adapters.keys()] })
      if (url.pathname === '/index') return json(res, 200, await writeLlmIndex())
      const m = /^\/p\/([^/]+)(?:\/v1)?(\/.*)$/.exec(url.pathname)
      if (!m) return err(res, 404, 'unknown path')
      const route = decodeURIComponent(m[1]); const rest = m[2]
      const a = adapters.get(route)
      if (!a) return err(res, 404, `插件模型 ${route} 还没加载（检查登录或插件是否启用）`)
      if (req.method === 'GET' && (rest === '/models' || rest === '/models/')) {
        const info = await routeInfo(route)
        return json(res, 200, { object: 'list', data: (info?.models || []).filter(x => x.proxied).map(x => ({ id: x.id, object: 'model', created: 0, owned_by: route, name: x.name, context_length: x.context || undefined })) })
      }
      if (req.method === 'POST' && (rest === '/chat/completions' || rest === '/chat/completions/')) {
        const raw = await readBody(req)
        let body; try { body = JSON.parse(raw.toString('utf8')) } catch { return err(res, 400, 'bad json') }
        const d = descriptorsOf(a.adapter, route).find(x => x.id === body.model)
        if (!d) return err(res, 404, `没有模型 ${body.model}`)
        if (d.api !== 'openai-completions' || !d.baseUrl) return err(res, 501, `模型 ${body.model} 用的是 ${d.api || '未知'} 接口，Mines 暂时只能转发 OpenAI 格式`)
        let key = ''
        try { key = (await a.adapter.config?.resolveApiKey?.(route, profileOf(a.adapter, route))) || '' } catch (e) { return err(res, 401, `登录信息不可用：${e.message}`) }
        const ac = new AbortController(); res.on('close', () => ac.abort())
        let up
        try {
          up = await fetch(d.baseUrl.replace(/\/$/, '') + '/chat/completions', {
            method: 'POST', signal: ac.signal,
            headers: { 'content-type': 'application/json', accept: req.headers.accept || '*/*', ...(key ? { authorization: `Bearer ${key}` } : {}) },
            body: JSON.stringify(body),
          })
        } catch (e) { return err(res, 502, `插件端点连不上：${e.message}`) }
        res.writeHead(up.status, { 'content-type': up.headers.get('content-type') || 'application/json', 'cache-control': 'no-cache' })
        if (!up.body) return res.end()
        const reader = up.body.getReader()
        for (;;) { const { done, value } = await reader.read(); if (done) break; res.write(Buffer.from(value)) }
        return res.end()
      }
      return err(res, 404, `unsupported: ${req.method} ${rest}`)
    } catch (e) {
      log('gateway', e.stack || e.message)
      if (!res.headersSent) err(res, 500, e.message); else res.end()
    }
  })
  server.on('error', (e) => { log('gateway listen', e.message); process.exit(3) })
  server.listen(GATEWAY_PORT, '127.0.0.1', () => log('gateway listening', GATEWAY_PORT))
  process.on('SIGTERM', () => process.exit(0))
  await new Promise(() => {})
}

// --- stdio MCP server (newline-delimited JSON-RPC) ---
await boot()
let buf = ''
const send = (msg) => process.stdout.write(JSON.stringify(msg) + '\n')
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buf += chunk
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1)
    if (line) handle(line)
  }
})
process.stdin.on('end', () => process.exit(0))

async function handle(line) {
  let msg
  try { msg = JSON.parse(line) } catch { return }
  const { id, method, params } = msg
  if (id === undefined) return // notification
  try {
    if (method === 'initialize') {
      send({ jsonrpc: '2.0', id, result: {
        protocolVersion: params?.protocolVersion || '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'minis-dsh', version: '1.0.0' },
        instructions: await instructions(),
      } })
    } else if (method === 'tools/list') {
      send({ jsonrpc: '2.0', id, result: { tools: [...tools.values()].map(t => schemaOf(t.def)) } })
    } else if (method === 'tools/call') {
      const t = tools.get(params?.name)
      if (!t) return send({ jsonrpc: '2.0', id, result: { isError: true, content: [{ type: 'text', text: `no tool ${params?.name}` }] } })
      const args = params.arguments || {}
      const ac = new AbortController()
      const exec = {
        name: t.def.name, args, signal: ac.signal, id: String(id),
        deferContext() {}, concludeTurn() {},
      }
      try {
        const value = await t.def.execute(args, exec)
        send({ jsonrpc: '2.0', id, result: { content: renderResult(t.def, args, value) } })
      } catch (e) {
        send({ jsonrpc: '2.0', id, result: { isError: true, content: [{ type: 'text', text: e.message || String(e) }] } })
      }
    } else if (method === 'ping') {
      send({ jsonrpc: '2.0', id, result: {} })
    } else {
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: `unsupported: ${method}` } })
    }
  } catch (e) {
    send({ jsonrpc: '2.0', id, error: { code: -32603, message: e.message } })
  }
}
