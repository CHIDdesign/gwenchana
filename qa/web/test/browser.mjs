// Headless Chrome smoke test: load page, drive a bit, run cases, take screenshots.
// node test/browser.mjs [outDir]
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright'); // 예: PLAYWRIGHT=/path/to/playwright/index.mjs
import fs from 'node:fs';
const out = process.argv[2] || '/tmp/shots';
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
const errors = [];
page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') errors.push(`[${m.type()}] ${m.text()}`); });
page.on('pageerror', e => errors.push('[pageerror] ' + e.message));
await page.goto('http://localhost:8765/index-local.html');
await page.waitForFunction(() => document.getElementById('loading').hidden === true, null, { timeout: 60000 });
await page.waitForTimeout(2500);
await page.screenshot({ path: `${out}/01-start.png` });
// drive forward 4 s
await page.keyboard.down('ArrowUp'); await page.waitForTimeout(4000); await page.keyboard.up('ArrowUp');
await page.screenshot({ path: `${out}/02-driving.png` });
const step = async (name, fn, wait = 800) => { await fn(); await page.waitForTimeout(wait); await page.screenshot({ path: `${out}/${name}.png` }); };
// instant DRV-01 → head unit should show popup after stop
await step('03-drv01', async () => { await page.evaluate(() => { const c = GW.QA.cases.find(x => x.id === 'DRV-01'); GW.simulateCase(c, true); }); }, 2500);
const st = await page.evaluate(() => { const HU = GW.HU; return { mode: HU.mode, stage: HU.stage, sc: HU.screenOf(HU.cf.incident), res: GW.UI.results.get('DRV-01')?.status }; });
console.log('after DRV-01:', JSON.stringify(st));
// press 안 다쳤어요 → kind floor
await step('04-kind', async () => { await page.evaluate(() => { const HU = GW.HU; HU.openCrash(); HU.stage = 'SCENE'; HU.lastKey = ''; }); });
await step('05-guide', async () => { await page.evaluate(() => { const HU = GW.HU; HU.cf.chooseKind('VEHICLE'); HU.stage = 'GUIDE'; HU.lastKey = ''; }); });
for (let i = 0; i < 6; i++) await step(`06-guide-${i}`, async () => { await page.evaluate(() => { const HU = GW.HU; const inc = HU.cf.incident; const steps = HU.stepsOf(inc); HU.next(steps, HU.cf.guideStep); }); }, 700);
await step('07-record', async () => { await page.evaluate(() => { const HU = GW.HU; HU.mode = 'CRASH'; HU.stage = 'RECORD'; HU.lastKey = ''; }); });
await step('08-home', async () => { await page.evaluate(() => { const HU = GW.HU; HU.mode = 'HOME'; HU.lastKey = ''; }); });
// harness TC-02 overlay mid-way
await page.evaluate(() => { const c = GW.QA.cases.find(x => x.id === 'TC-02'); GW.simulateCase(c, false); });
await page.waitForTimeout(1500); await page.screenshot({ path: `${out}/09-simulating.png` });
await page.waitForTimeout(6000); await page.screenshot({ path: `${out}/10-tc02-result.png` });
// dev panel
await step('11-dev', async () => { await page.click('[data-drawer="dev"]'); });
console.log('errors:', errors.length ? '\n' + errors.slice(0, 30).join('\n') : 'none');
await browser.close();
