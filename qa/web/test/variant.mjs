// Browser check: the code-variant switch gives original = FAIL, fixed = PASS for TC-04 and DRV-12.
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright'); // 예: PLAYWRIGHT=/path/to/playwright/index.mjs
const b = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const p = await b.newPage({ viewport: { width: 1500, height: 950 } });
const errs = []; p.on('pageerror', e => errs.push(String(e)));
await p.goto('http://localhost:8765/index-local.html', { waitUntil: 'load' });
await p.waitForFunction(() => window.GW && document.querySelector('#loading').hidden, null, { timeout: 120000 });
const run = async v => {
  await p.selectOption('#variant', v);
  return p.evaluate(async () => {
    const out = {};
    const wait = () => new Promise(r => { const t = setInterval(() => { if (!GW.UI.running) { clearInterval(t); r(); } }, 100); });
    for (const id of ['TC-04', 'DRV-12']) { const c = GW.QA.cases.find(x => x.id === id); GW.UI.skip = true; GW.simulateCase(c, true); await new Promise(r => setTimeout(r, 200)); await wait(); await new Promise(r => setTimeout(r, 300)); out[id] = GW.UI.results.get(id)?.status; }
    out.badge = document.querySelector('#variant-badge').textContent; return out;
  });
};
console.log('original:', JSON.stringify(await run('original')));
console.log('fixed:', JSON.stringify(await run('fixed')));
console.log('page errors:', errs.length ? errs : 'none');
await b.close();
