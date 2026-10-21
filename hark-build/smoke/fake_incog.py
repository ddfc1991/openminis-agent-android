#!/usr/bin/env python3
"""[38.9.11.2] 无痕 isolation test page (adb reverse tcp:8099 → http://localhost:8099 on the emulator = a secure context, so
cookies, localStorage, IndexedDB, Cache Storage and a service worker all work). /t?act=set|read&tag=X sets or reads them and
reports what it sees to /report; GET /reports returns every report as JSON."""
import json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
R = []; L = threading.Lock()
PAGE = r"""<!doctype html><html><head><meta name=viewport content="width=device-width"><title>isolation</title>
<style>body{font:18px sans-serif;margin:16px}b{font-size:22px}</style></head><body><b id=h>…</b><pre id=o></pre><script>
const q=new URLSearchParams(location.search), act=q.get('act')||'read', tag=q.get('tag')||'x';
function idbGet(){return new Promise(r=>{try{const o=indexedDB.open('mdb',1);o.onupgradeneeded=()=>o.result.createObjectStore('s');
 o.onsuccess=()=>{try{const g=o.result.transaction('s').objectStore('s').get('k');g.onsuccess=()=>r(g.result||'');g.onerror=()=>r('')}catch(e){r('')}};o.onerror=()=>r('err')}catch(e){r('err')}})}
function idbPut(v){return new Promise(r=>{const o=indexedDB.open('mdb',1);o.onupgradeneeded=()=>o.result.createObjectStore('s');
 o.onsuccess=()=>{const t=o.result.transaction('s','readwrite');t.objectStore('s').put(v,'k');t.oncomplete=()=>r(1);t.onerror=()=>r(0)};o.onerror=()=>r(0)})}
async function run(){
 if(act==='set'){document.cookie='mc='+tag+'; max-age=86400; path=/';localStorage.setItem('ml',tag);await idbPut(tag);
  try{const c=await caches.open('mcache');await c.put('/cached',new Response(tag))}catch(e){}
  try{await navigator.serviceWorker.register('/sw.js')}catch(e){}}
 const ck=(document.cookie.match(/(?:^|; )mc=([^;]*)/)||[])[1]||'';const ls=localStorage.getItem('ml')||'';const idb=await idbGet();
 let cache='';try{if(await caches.has('mcache')){const r=await (await caches.open('mcache')).match('/cached');cache=r?await r.text():''}}catch(e){cache='err'}
 let sw=0;try{sw=(await navigator.serviceWorker.getRegistrations()).length}catch(e){sw=-1}
 const rep={act,tag,ck,ls,idb,cache,sw};document.getElementById('h').textContent=(act==='set'?'已写入 ':'读到 ')+tag;
 document.getElementById('o').textContent='cookie: '+(ck||'（无）')+'\nlocalStorage: '+(ls||'（无）')+'\nIndexedDB: '+(idb||'（无）')+'\nCache: '+(cache||'（无）')+'\nService worker: '+sw;
 fetch('/report?'+new URLSearchParams(Object.assign({},rep,{sw:String(sw)})),{cache:'no-store'})}
run()</script></body></html>"""
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _s(self, code, body, ct="text/html; charset=utf-8"):
        b = body.encode(); self.send_response(code); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/t": return self._s(200, PAGE)
        if u.path == "/sw.js": return self._s(200, "self.addEventListener('fetch',()=>{})", "application/javascript")
        if u.path == "/report":
            with L: R.append(q)
            print("REPORT", json.dumps(q, ensure_ascii=False), flush=True); return self._s(204, "")
        if u.path == "/reports":
            with L: return self._s(200, json.dumps(R, ensure_ascii=False), "application/json")
        self._s(404, "no")
ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 8099), H).serve_forever()
