// Headless run of every case in qa-cases.json with the same code the page uses.
// node test/run-cases.mjs [ID ...] [--verbose] [--json out.json]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadParts, SIM_QA } from './load.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const X = loadParts(SIM_QA, ['runHarnessCase', 'DrivingRun', 'buildWorld', 'qaF32', 'SCENARIOS', 'KIND_LABEL']);
const spec = JSON.parse(fs.readFileSync(path.join(ROOT, 'qa-cases.json'), 'utf8'));
const args = process.argv.slice(2), verbose = args.includes('--verbose');
const jsonOut = args.includes('--json') ? args[args.indexOf('--json') + 1] : null;
const only = args.filter(a => /^(TC|DRV)-\d+$/.test(a));
// --variant fixed|original|both (기본 both): 수정본과 공개본 5ffe61e 를 같은 케이스로
const vArg = args.includes('--variant') ? args[args.indexOf('--variant') + 1] : 'both';
const variants = vArg === 'both' ? ['original', 'fixed'] : [vArg];
const world = X.buildWorld(7);
const out = [];
for (const v of variants) {
X.VARIANT.fixed = v === 'fixed';
console.log(`\n=== 앱 코드: ${X.VARIANT.label} ===`);
for (const c of spec.cases) {
  if (only.length && !only.includes(c.id)) continue;
  const t0 = Date.now();
  let r;
  if (c.type === 'driving') {
    const run = new X.DrivingRun(world, c, spec.fixtures);
    while (!run.tick(500));
    r = run.evaluate();
    r.simSec = +run.clock.t.toFixed(2);
  } else r = X.runHarnessCase(c, spec.fixtures);
  r.ms = Date.now() - t0; r.variant = v;
  out.push(r);
  const mark = r.status === 'PASS' ? 'PASS' : r.status;
  console.log(`${c.id.padEnd(7)} ${mark.padEnd(17)} ${c.title}  (${r.ms} ms${r.simSec != null ? `, sim ${r.simSec}s` : ''})`);
  if (r.type === 'driving') {
    for (const i of r.items) if (verbose || !i.ok) console.log(`        ${i.ok ? 'ok ' : 'BAD'} ${i.key}: 기대 ${i.expected} | 실제 ${i.actual}`);
    const o = r.obs;
    console.log(`        truth: Δv ${o.truthDv != null ? o.truthDv.toFixed(1) + ' km/h' : '-'} deg ${o.truthDeg != null ? o.truthDeg.toFixed(0) : '-'} airbag ${o.airbagSynth} | app: maxG ${o.maxG.toFixed(2)} peak ${o.peakG ?? '-'} delay ${o.delay != null ? o.delay.toFixed(2) + 's' : '-'} reason ${o.reason ?? '-'} rec "${o.recText ?? '-'}" phoneCases ${o.phoneCases}`);
  } else {
    if (r.failure) console.log(`        첫 실패(JUnit 멈춤): ${r.failure.text}${r.failure.actual != null ? ` — 실제 ${r.failure.actual}` : ''}`);
    if (verbose) for (const s of r.trace) console.log(`        ${s.after ? '·' : ' '} ${s.kind.padEnd(7)} ${s.text}`);
    else for (const s of r.trace.filter(s => s.after && s.kind === 'fail')) console.log(`        · (참고) ${s.text}`);
  }
}
}
if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify(out, null, 1));
