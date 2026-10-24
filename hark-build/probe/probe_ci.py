import asyncio, json, os, sys, re
from playwright.async_api import async_playwright
UA = "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
SITES = json.load(open(os.path.join(os.path.dirname(__file__), "sites.json")))
DUMP = open(os.path.join(os.path.dirname(__file__), "dump.js")).read()
SEND = open(os.path.join(os.path.dirname(__file__), "send.js")).read()
OUT = sys.argv[1] if len(sys.argv) > 1 else "probe-out"
os.makedirs(OUT, exist_ok=True)
Q = "你好，请只回复两个字：收到"
async def one(b, k, url):
    ctx = await b.new_context(user_agent=UA, viewport={"width": 412, "height": 880}, device_scale_factor=2, is_mobile=True, has_touch=True, locale="zh-CN", timezone_id="Asia/Shanghai")
    pg = await ctx.new_page(); r = {"site": k}
    try:
        await pg.goto(url, timeout=45000, wait_until="domcontentloaded")
        await pg.wait_for_timeout(25000 if k in ("deepseek","chatgpt","chatglm") else 12000)
        r["before"] = await pg.evaluate(DUMP)
        r["cookies"] = [c["name"] + "@" + c["domain"] for c in await ctx.cookies()]
        await pg.screenshot(path=f"{OUT}/{k}-1.png")
        if r["before"]["inputs"]:
            r["send"] = await pg.evaluate(SEND, Q)
            await pg.wait_for_timeout(2500)
            await pg.screenshot(path=f"{OUT}/{k}-2.png")
            for i in range(10):
                await pg.wait_for_timeout(4000)
            r["after"] = await pg.evaluate(DUMP)
            r["cookies2"] = [c["name"] + "@" + c["domain"] for c in await ctx.cookies()]
            await pg.screenshot(path=f"{OUT}/{k}-3.png", full_page=False)
    except Exception as e:
        r["err"] = str(e)[:400]
    json.dump(r, open(f"{OUT}/{k}.json", "w"), ensure_ascii=False, indent=1)
    await ctx.close()
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        await asyncio.gather(*[one(b, k, u) for k, u in SITES.items()])
        await b.close()
asyncio.run(main())
