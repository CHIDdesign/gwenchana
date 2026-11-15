// ===== 70-main.js — 페이지: 자유 주행 + 차 화면 + 시험 케이스 + 개발 창 =====
// 브라우저 전용. 위 파일들(앱 사본·시뮬레이터·시험 실행기·차 화면)을 묶어 돌린다.

const QA = JSON.parse(document.getElementById('qa-cases').textContent);
const $ = s => document.querySelector(s);
const UI = { timeScale: 1, paused: false, results: new Map(), running: null, devTab: 'state', lastDev: 0, gHist: [] };

/** 자유 주행 세션: 차·주변 차·앱·휴대폰·서버를 한 시계로 */
function makeFreeSession(world, personaId) {
  world.grid.g.forEach((list, k) => world.grid.g.set(k, list.filter(s => !s.temp))); // 시험 케이스가 남긴 물체 정리
  const clock = new SimClock(Math.floor(Date.now() / 1000)), rt = new Runtime(clock), server = new SyncServer(), net = new Network(rt, server);
  rt.net = net;
  const prefs = new Map([['consent', new Map(CONSENT_ITEMS.map(k => [k, k !== 'CLOUD']))]]);
  const app = new GwenApp({ clock, rt, fs: new VFS(), prefsStore: prefs, personaId, account: 'demo' });
  const phone = new PhoneApp({ rt, fs: new VFS(), prefsStore: new Map(), account: 'demo', clock });
  const sim = new DriveSim(world, clock, rt);
  spawnTraffic(sim, 3);
  sim.placePlayer(NB, -330, Math.PI / 2);
  app.startService(); app.carSync.startPolling(); phone.startPolling();
  sim.attach(app);
  const s = { kind: 'free', clock, rt, server, net, app, phone, sim, world, prefs, startedAt: clock.t };
  app.onLog = msg => logLine('앱', msg);
  return s;
}
/** 앱 재시작: 디스크·설정은 그대로, 메모리·작업은 새로(시험 TC-03 처럼) */
function restartApp(s) {
  s.app.shutdown();
  for (const k of s.rt.tasks) if (k.label === 'recorder' || /car-|load-record|report|pre-save|repeak|kind-write/.test(k.label)) k.cancelled = true;
  const app = new GwenApp({ clock: s.clock, rt: s.rt, fs: s.app.fs, prefsStore: s.app.prefsStore, personaId: s.app.personaId, account: 'demo' });
  s.app = app; app.onLog = msg => logLine('앱', msg);
  app.startService(); app.carSync.startPolling(); s.sim.attach(app); s.startedAt = s.clock.t;
  HU.attach(s); logLine('웹', '앱을 다시 켰어요(기록 파일·동의는 그대로)');
}

let WORLD, FREE, ACTIVE, VIEW, HU;
const LOG = [];
function logLine(who, msg) { const t = ACTIVE ? tsKST(ACTIVE.clock.now()).slice(0, 10) : ''; LOG.push(`${t}  [${who}] ${msg}`); if (LOG.length > 300) LOG.shift(); }

function setActive(s) {
  ACTIVE = s; VIEW.setSim(s.sim); HU.attach(s);
  if (s.app.crashFlow.incident) HU.openCrash(); // 즉시 실행으로 이미 사고가 난 세션
  $('#mode-chip').textContent = s.kind === 'free' ? '자유 주행' : `시험 ${s.run.c.id} · 자동 운전`;
  $('#mode-chip').classList.toggle('auto', s.kind !== 'free');
  $('#back-free').hidden = s.kind === 'free';
}
function backToFree() { if (UI.running && UI.running.kind === 'drive') UI.running = null; setActive(FREE); world2free(); }
function world2free() { WORLD.grid.g.forEach((list, k) => WORLD.grid.g.set(k, list.filter(s => !s.temp))); }

// ── 입력 ──
const KEYMAP = { ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down', ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', Space: 'brake' };
function typing(e) { const t = e.target; return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable); }
function onKey(e, down) {
  if (typing(e)) return;
  const sim = ACTIVE && ACTIVE.sim; if (!sim) return;
  const k = KEYMAP[e.code];
  if (k) { if (!sim.autopilot) sim.input[k] = down; e.preventDefault(); return; }
  if (!down) return;
  if (sim.autopilot && ['KeyQ', 'KeyE', 'KeyP', 'KeyR'].includes(e.code)) return;
  switch (e.code) {
    case 'KeyQ': sim.signal = sim.signal === 'L' ? 'none' : 'L'; break;
    case 'KeyE': sim.signal = sim.signal === 'R' ? 'none' : 'R'; break;
    case 'KeyH': case 'Slash': toggleHazard(); break;
    case 'KeyP': park(); break;
    case 'KeyR': resetCar(); break;
    case 'KeyC': cycleCam(); break;
    case 'Escape': HU.back(); break;
    default: return;
  }
  e.preventDefault();
}
function toggleHazard() { const sim = ACTIVE.sim; sim.hazard = !sim.hazard; }
function park() { const p = ACTIVE.sim.player; if (p && Math.abs(p.vf) < 0.5) p.gear = 'P'; }
function resetCar() { if (ACTIVE.kind !== 'free') return; const sim = ACTIVE.sim; sim.placePlayer(NB, -330, Math.PI / 2); sim.attach(ACTIVE.app); }
function cycleCam() { const m = ['chase', 'top', 'hood']; VIEW.camMode = m[(m.indexOf(VIEW.camMode) + 1) % m.length]; VIEW.camInit = false; $('#cam-btn').textContent = { chase: '카메라: 뒤', top: '카메라: 위', hood: '카메라: 운전석' }[VIEW.camMode]; }

// ── 시험 실행 ──
function statusChip(st) {
  const cls = st === 'PASS' ? 'pass' : st === 'FAIL_REQUIREMENT' ? 'fail' : st === 'RUNNING' ? 'run' : 'blocked';
  return h('span', { class: 'chipq ' + cls }, st === 'RUNNING' ? '실행 중' : st);
}
function renderCaseList() {
  const list = $('#case-list'); list.textContent = '';
  for (const c of QA.cases) {
    const r = UI.results.get(c.id), running = UI.running && UI.running.id === c.id;
    const drv = c.type === 'driving';
    list.appendChild(h('div', { class: 'case' + (UI.sel === c.id ? ' sel' : ''), onclick: () => { UI.sel = c.id; renderCaseList(); renderResult(); } },
      h('div', { class: 'case-top' }, h('b', null, c.id), running ? statusChip('RUNNING') : r ? statusChip(r.status) : h('span', { class: 'chipq idle' }, '안 돌림')),
      h('div', { class: 'case-title' }, c.title),
      h('div', { class: 'case-meta' }, drv ? `자동 운전 · ${Personas.byId(c.persona || 'guest').name}` : '운전으로 못 만드는 값 → 가상 실행'),
      h('div', { class: 'case-btns' },
        h('button', { class: 'qbtn', disabled: !!UI.running, onclick: () => simulateCase(c, false) }, 'Simulate test case'),
        drv && h('button', { class: 'qbtn ghost', disabled: !!UI.running, title: '그리지 않고 끝까지 바로', onclick: () => simulateCase(c, true) }, '즉시'))));
  }
  const n = [...UI.results.values()], pass = n.filter(r => r.status === 'PASS').length;
  $('#qa-sum').textContent = n.length ? `${n.length}/${QA.cases.length} 실행 · PASS ${pass} · FAIL_REQUIREMENT ${n.filter(r => r.status === 'FAIL_REQUIREMENT').length} · BLOCKED ${n.filter(r => r.status.startsWith('BLOCKED')).length}` : '아직 실행한 케이스가 없어요';
}
function renderResult() {
  const box = $('#case-result'); box.textContent = '';
  const c = QA.cases.find(x => x.id === UI.sel); if (!c) { box.appendChild(h('div', { class: 'muted' }, '왼쪽에서 케이스를 고르세요.')); return; }
  const r = UI.results.get(c.id);
  box.appendChild(h('div', { class: 'res-head' }, h('div', null, h('b', null, `${c.id} · ${c.title}`), h('div', { class: 'muted' }, (c.requirement || []).join(' · '))), r && statusChip(r.status)));
  if (c.why) box.appendChild(h('div', { class: 'note' }, '왜 가상 실행인가: ' + c.why));
  if (c.basis) box.appendChild(h('div', { class: 'note' }, '근거: ' + c.basis));
  if (c.type === 'driving') box.appendChild(h('div', { class: 'note' }, (c.warmupSec ?? 65) > 0 ? `준비: 앱을 켜고 ${c.warmupSec ?? 65}초 뒤 출발(실차처럼 시동 걸고 1분 넘게 지난 상태) · 주변 차 없음 · 운전자는 화면을 누르지 않음` : '준비: 앱을 켜자마자 출발 · 주변 차 없음 · 운전자는 화면을 누르지 않음'));
  if (!r) { box.appendChild(h('div', { class: 'muted', style: 'margin-top:8px' }, '"Simulate test case"를 누르면 결과가 여기에 나와요.')); return; }
  if (r.status !== 'PASS') {
    box.appendChild(h('div', { class: 'failbox' }, h('b', null, r.status === 'FAIL_REQUIREMENT' ? '기대와 다름' : '실행 못 함'), r.failClass && h('div', null, '분류: ' + r.failClass),
      r.failure && h('div', null, (r.junitStopsAt ? `JUnit 은 여기서 멈춤 → [${r.junitStopsAt}] ` : '') + r.failure.text + (r.failure.actual != null ? ` · 실제 ${r.failure.actual}` : '')),
      r.gap && h('div', { class: 'muted' }, '현재 제품: ' + r.gap)));
  }
  if (r.type === 'driving') {
    const t = h('table', { class: 'qtable' }, h('tr', null, h('th', null, '항목'), h('th', null, '기대'), h('th', null, '실제'), h('th', null, '')));
    for (const i of r.items) t.appendChild(h('tr', { class: i.ok ? '' : 'bad' }, h('td', null, i.key), h('td', null, i.expected), h('td', null, i.actual), h('td', null, i.ok ? '✓' : '✗')));
    box.appendChild(t);
    const o = r.obs;
    box.appendChild(h('div', { class: 'note mono' }, `시뮬레이터 정답(앱은 모름): Δv ${o.truthDv != null ? o.truthDv.toFixed(1) + ' km/h' : '-'} · 방향 ${o.truthDeg != null ? o.truthDeg.toFixed(0) + '°' : '-'} · 합성 에어백 ${o.airbagSynth ? '예' : '아니오'}\n앱 값: 최대 보정 ${o.maxG.toFixed(2)}g · impact.json ${o.peakG != null ? o.peakG.toFixed(2) + 'g' : '-'} · 감지 지연 ${o.delay != null ? o.delay.toFixed(2) + '초' : '-'} · 기록 띠 "${o.recText || '-'}" · 휴대폰 사고 ${o.phoneCases}건`));
    if (o.facts && o.facts.length) box.appendChild(h('details', null, h('summary', null, `사실 목록 ${o.facts.length}줄`), h('pre', { class: 'mono' }, o.facts.join('\n'))));
  } else {
    if (r.notes && r.notes.length) box.appendChild(h('div', { class: 'note' }, '어댑터 메모: ' + r.notes.join(' · ')));
    const ol = h('div', { class: 'trace' });
    for (const s of r.trace) ol.appendChild(h('div', { class: `tr ${s.kind}${s.after ? ' after' : ''}${s.first ? ' first' : ''}` }, h('span', { class: 'tk' }, { step: '▸', pass: '✓', fail: '✗', observe: '◎', note: '·' }[s.kind] || '·'), s.text));
    box.appendChild(h('div', { class: 'muted', style: 'margin-top:8px' }, '흐린 줄 = JUnit 이 멈춘 뒤의 단계(참고로 계속 돌림)'));
    box.appendChild(ol);
  }
}
/** 한 케이스. 운전 케이스는 차를 직접 자동 운전(instant=그리지 않고 바로), 나머지는 "Simulating…" 화면 */
function simulateCase(c, instant) {
  if (UI.running) return;
  UI.sel = c.id;
  if (c.type !== 'driving') return simulateHarness(c);
  const run = new DrivingRun(WORLD, c, QA.fixtures);
  run.app.onLog = msg => logLine('앱', msg);
  const s = { kind: 'case', run, clock: run.clock, rt: run.rt, server: run.server, net: run.net, app: run.app, phone: run.phone, sim: run.sim, world: WORLD, startedAt: 0 };
  if (instant) {
    overlay(true, `${c.id} 즉시 실행`, ['그리지 않고 끝까지 진행해요…']);
    setTimeout(() => { while (!run.tick(2000)); finishDrive(c, run, s); overlay(false); setActive(s); }, 30);
    return;
  }
  UI.running = { id: c.id, kind: 'drive', run, s };
  setActive(s);
  logLine('웹', `${c.id} 자동 운전 시작 (앱은 ${run.warmupSec}초 전에 켬)`);
  renderCaseList(); renderResult();
}
function finishDrive(c, run, s) {
  const r = run.evaluate(); r.simSec = +run.elapsed.toFixed(1);
  UI.results.set(c.id, r); UI.running = null;
  logLine('웹', `${c.id} 끝: ${r.status}`);
  renderCaseList(); renderResult();
}
/** 운전으로 만들 수 없는 값: 가상 시계로 실행하면서 "Simulating…" 화면에 단계를 흘려 보여 준다 */
function simulateHarness(c) {
  const r = runHarnessCase(c, QA.fixtures);
  UI.running = { id: c.id, kind: 'harness' };
  renderCaseList();
  const lines = r.trace.map(s => `${{ step: '▸', pass: '✓', fail: '✗', observe: '◎', note: '·' }[s.kind] || '·'} ${s.text}`);
  overlay(true, `Simulating… ${c.id}`, [], c.why);
  let i = 0;
  const tick = () => {
    if (UI.skip) { while (i < lines.length) { overlayLine(lines[i], r.trace[i]); i++; } }
    if (i < lines.length) { overlayLine(lines[i], r.trace[i]); i++; setTimeout(tick, r.trace[i - 1].kind === 'step' ? 35 : 75); return; }
    setTimeout(() => { overlay(false); UI.results.set(c.id, r); UI.running = null; UI.skip = false; renderCaseList(); renderResult(); }, UI.skip ? 150 : 900);
  };
  UI.skip = false;
  setTimeout(tick, 300);
}
function overlay(on, title, lines = [], why = '') {
  const o = $('#sim-overlay'); o.hidden = !on; if (!on) return;
  $('#sim-title-text').textContent = title; $('#sim-why').textContent = why || ''; const box = $('#sim-lines'); box.textContent = '';
  lines.forEach(l => overlayLine(l));
}
function overlayLine(text, s) { const box = $('#sim-lines'); box.appendChild(h('div', { class: 'sl ' + (s ? s.kind + (s.after ? ' after' : '') : '') }, text)); box.scrollTop = box.scrollHeight; }
async function runAll() {
  if (UI.running) return;
  UI.running = { id: '*', kind: 'all' }; renderCaseList();
  overlay(true, 'Simulating… 전체 케이스', [], '운전 케이스는 그리지 않고 끝까지, 나머지는 가상 시계로 돌려요');
  for (const c of QA.cases) {
    overlayLine(`▸ ${c.id} ${c.title}`);
    await new Promise(r => setTimeout(r, 20));
    let r;
    if (c.type === 'driving') { const run = new DrivingRun(WORLD, c, QA.fixtures); while (!run.tick(4000)); r = run.evaluate(); r.simSec = +run.elapsed.toFixed(1); }
    else r = runHarnessCase(c, QA.fixtures);
    UI.results.set(c.id, r);
    overlayLine(`   ${r.status === 'PASS' ? '✓' : '✗'} ${r.status}${r.failure ? ' — ' + r.failure.text : r.items ? ' — ' + r.items.filter(i => !i.ok).map(i => i.key).join(', ') : ''}`, { kind: r.status === 'PASS' ? 'pass' : 'fail' });
  }
  world2free();
  UI.running = null; renderCaseList(); renderResult();
  setTimeout(() => overlay(false), 1400);
}
function copyResults() {
  const out = { format: 'gwenchana-qa-results/1', model: `web model of ${VARIANT.id === 'fixed' ? 'fixed branch (TC-04, DRV-12)' : 'public main 5ffe61e'}`, variant: VARIANT.id, at: new Date().toISOString(), results: [...UI.results.values()].map(r => {
    const x = { id: r.id, status: r.status, failClass: r.failClass || null, junitStopsAt: r.junitStopsAt || null, failure: r.failure ? { text: r.failure.text, field: r.failure.field, expected: r.failure.expected, actual: r.failure.actual } : null };
    if (r.items) x.items = r.items; if (r.obs) x.obs = Object.assign({}, r.obs, { facts: r.obs.facts });
    if (r.trace) x.trace = r.trace.map(s => ({ kind: s.kind, text: s.text, afterJUnitStop: !!s.after }));
    return x;
  }) };
  const text = JSON.stringify(out, null, 2);
  const done = ok => { $('#copy-msg').textContent = ok ? '복사했어요' : '복사 못 함 — 브라우저가 막았어요'; setTimeout(() => $('#copy-msg').textContent = '', 2500); };
  try { navigator.clipboard.writeText(text).then(() => done(true), () => done(false)); } catch { done(false); }
}

// ── 개발 창: 앱 상태·휴대폰·기록 ──
function kv(k, v, cls = '') { return h('div', { class: 'kv ' + cls }, h('span', null, k), h('b', null, v)); }
function renderDev() {
  const s = ACTIVE, app = s.app, cs = app.carState, n = cs.now, cf = app.crashFlow, inc = cf.incident, w = app.watcher;
  const box = $('#dev-body'); box.textContent = '';
  if (UI.devTab === 'state') {
    const up = s.clock.t - (s.kind === 'free' ? s.startedAt : 0);
    const col1 = h('div', { class: 'devcol' }, h('h4', null, '차 신호 (앱이 받는 값)'),
      kv('속도', `${n.speedKph.toFixed(1)} km/h`), kv('기어', n.gear), kv('앞바퀴 각도', `${n.steerDeg.toFixed(1)}°`), kv('브레이크', n.brake ? '밟음' : '-'), kv('방향지시등', n.signal),
      kv('경고등', n.lamps || '-'), kv('에어백', n.airbag ? '전개' : '-'), kv('비상등', cs.hazard ? '켜짐' : '-'), kv('제한속도', cs.roadLimitKph ? cs.roadLimitKph + ' km/h' : '모름'),
      kv('위치', n.lat != null ? `${n.lat.toFixed(5)}, ${n.lon.toFixed(5)}` : '없음(위치 동의 꺼짐?)'));
    const g = h('canvas', { width: 300, height: 90, class: 'gplot' });
    const col2 = h('div', { class: 'devcol' }, h('h4', null, '가속도 센서 (ImpactSensor)'),
      kv('보정 충격', w ? `${w.impact.lastG.toFixed(2)} g` : '-'), kv('최근 1.5초 최대', w ? `${w.impact.peak(app.clock.now() - 1.5, app.clock.now())[0].toFixed(2)} g` : '-'),
      kv('중력 추정', w ? w.impact.gravity.map(v => v.toFixed(2)).join(', ') : '-'), g,
      kv('앱 켠 지', `${up.toFixed(0)}초`, up < 60 && !VARIANT.fixed ? 'warn' : ''), up < 60 && !VARIANT.fixed ? h('div', { class: 'tiny warn' }, '공개본: 켠 지 60초 안에는 충격으로 먼저 잡힌 사고가 에어백 신호로 큰 사고로 안 올라가요(DRV-12, Crash.kt 390행). 수정본은 고쳤어요') : null);
    const col3 = h('div', { class: 'devcol' }, h('h4', null, '사고 상태 (CrashFlow)'),
      kv('사고', inc ? `${tsKST(inc.t).slice(0, 10)} · ${inc.reason}` : '없음'), inc && kv('큰 사고 · 고속도로', `${inc.severe ? '예' : '아니오'} · ${inc.highway ? '예' : '아니오'}${inc.roadKnown ? '' : '(모름)'}`),
      inc && kv('충격 방향', inc.impactDeg != null ? `${inc.impactDeg.toFixed(0)}° ${claimSide(inc.impactDeg)}` : '-'), kv('대상', `${KIND_LABEL[cf.kind]}${cf.kindChosen ? ' (운전자)' : ''}`),
      kv('부상·주변·이동', `${cf.hurt ? '다침' : '-'} · ${cf.near ? '주변 부상' : '-'} · ${cf.moved ? '옮김' : '-'}`), kv('상태', cf.status), kv('안내 단계', String(cf.guideStep)),
      kv('기록 띠', inc ? app.recStageNow().text : '-'));
    const st = s.server.view(s.server.state('demo'));
    const col4 = h('div', { class: 'devcol' }, h('h4', null, '전달 (CarSync · 서버 · 휴대폰)'),
      kv('서버 응답', app.carSync.ok ? '있음' : '없음'), kv('기록 접수(recordAcked)', app.carSync.recordAcked ? tsKST(app.carSync.recordAcked).slice(0, 10) : '-'), kv('휴대폰 저장 확인(seen)', app.carSync.seen ? tsKST(app.carSync.seen).slice(0, 10) : '-'),
      kv('서버', `v${st.version} · 사고 ${st.incident ? '있음' : '없음'} · 단계 ${st.step ?? '-'}${st.stepBy ? '(' + st.stepBy + ')' : ''}${st.closed ? ' · 닫힘' : ''}${st.wiped ? ' · 지움' : ''}`),
      kv('네트워크', s.net.online ? '연결' : '끊김', s.net.online ? '' : 'warn'),
      h('div', { class: 'devbtns' },
        h('button', { class: 'qbtn ghost', onclick: () => { s.net.online = !s.net.online; logLine('웹', `네트워크 ${s.net.online ? '연결' : '끊음'}`); renderDev(); } }, s.net.online ? '네트워크 끊기' : '네트워크 잇기'),
        h('button', { class: 'qbtn ghost', disabled: s.kind !== 'free', onclick: () => restartApp(s) }, '앱 재시작'),
        h('button', { class: 'qbtn ghost', disabled: !w, onclick: () => w && w.trigger('충격 신호', false, true) }, '연습 사고'),
        h('button', { class: 'qbtn ghost', disabled: !w, onclick: () => w && w.trigger('에어백 전개', true, true) }, '연습 사고(에어백)')),
      h('h4', null, '동의 (Consent)'), h('div', { class: 'devbtns' }, CONSENT_ITEMS.map(k => h('label', { class: 'tog' }, h('input', { type: 'checkbox', checked: app.consent.allowed(k) ? true : null, onchange: e => { app.consent.set(k, e.target.checked); logLine('웹', `동의 ${k} = ${e.target.checked}`); if (k === 'DRIVE' && e.target.checked && !app.watcher) { app.startService(); s.sim.attach(app); } } }), k))));
    box.append(col1, col2, col3, col4);
    const ctx = g.getContext('2d'); ctx.fillStyle = '#0f1115'; ctx.fillRect(0, 0, 300, 90);
    const max = 6, y = v => 86 - clamp(v / max, 0, 1) * 80;
    ctx.strokeStyle = '#7a2e2a'; ctx.setLineDash([4, 4]); ctx.beginPath(); ctx.moveTo(0, y(2.5)); ctx.lineTo(300, y(2.5)); ctx.stroke(); ctx.setLineDash([]);
    ctx.fillStyle = '#9aa0a6'; ctx.font = '10px sans-serif'; ctx.fillText('2.5g', 2, y(2.5) - 3); ctx.fillText('최근 10초', 240, 10);
    ctx.strokeStyle = '#e8eaed'; ctx.beginPath(); UI.gHist.forEach((v, i) => { const x = i / Math.max(1, UI.gHist.length - 1) * 300; i ? ctx.lineTo(x, y(v)) : ctx.moveTo(x, y(v)); }); ctx.stroke();
  } else if (UI.devTab === 'files') {
    const fs = app.fs, names = fs.list('crashes');
    const col = h('div', { class: 'devcol wide' }, h('h4', null, '차 저장소 crashes/ (가상 파일)'));
    if (!names.length) col.appendChild(h('div', { class: 'muted' }, '사고 폴더가 없어요'));
    for (const nme of names) {
      const d = 'crashes/' + nme;
      col.appendChild(h('div', { class: 'fdir' }, h('b', null, nme + '/'), ' ', fs.filesUnder(d).map(f => h('span', { class: 'file', title: '눌러서 내용 보기', onclick: () => { UI.fileView = d + '/' + f; renderDev(); } }, f))));
    }
    if (UI.fileView && fs.exists(UI.fileView)) { let t = fs.read(UI.fileView); if (UI.fileView.endsWith('.json')) { try { t = JSON.stringify(JSON.parse(t), null, 1); } catch { } } col.appendChild(h('pre', { class: 'mono fileview' }, `${UI.fileView}\n${t.length > 6000 ? t.slice(0, 6000) + '\n…' : t}`)); }
    const pcol = h('div', { class: 'devcol wide' }, h('h4', null, '휴대폰 저장소 cases/'));
    for (const nme of ACTIVE.phone.fs.list('cases')) pcol.appendChild(h('div', { class: 'fdir' }, h('span', { class: 'file' }, nme)));
    box.append(col, pcol);
  } else if (UI.devTab === 'phone') {
    const ph = s.phone, cases = ph.readCases(), d = cases[0];
    const col = h('div', { class: 'devcol wide phonecol' }, h('h4', null, '휴대폰 앱(가상) — 서버에서 받은 사고'));
    if (!d) col.appendChild(h('div', { class: 'muted' }, '받은 사고가 없어요. 사고가 나면 서버를 거쳐 여기로 와요.'));
    else {
      const st = ph.state || {};
      col.append(kv('사고 시각', tsKST(d.t).slice(0, 10)), kv('대상', `${d.kind}${d.kindChosen ? ' (운전자 선택)' : ' (추정)'}`), kv('지문', d.hash ? d.hash + '…' : '아직 없음(임시 자료)'),
        kv('진술', d.statement ?? '-'), kv('차 단계', st.step != null ? `${st.step + 1} / ${d.steps.length}${st.stepBy ? ' · ' + st.stepBy : ''}` : '-'), kv('휴대폰이 이어받는 단계', `${d.phoneFrom + 1}번부터`),
        kv('알림', ph.alerts.length ? ph.alerts.map(a => tsKST(a.at).slice(0, 8) + ' 사고 알림').join(', ') : '-'), kv('닫힘', st.closed ? (st.notAccident ? '사고 아님' : '안내 끝') : '-'));
      col.appendChild(h('h4', null, '안내 단계(휴대폰 쪽 목록)'));
      const ol = h('ol', { class: 'steps' }); d.steps.forEach((x, i) => ol.appendChild(h('li', { class: i >= d.phoneFrom ? 'ph' : '' }, `${x.title}${x.call ? ' · ☎ ' + x.call : ''}`))); col.appendChild(ol);
      if (d.report) col.appendChild(h('details', null, h('summary', null, '경위서(차가 보낸 사실 목록)'), h('pre', { class: 'mono' }, d.report)));
      if (ph.log.length) col.appendChild(h('div', { class: 'muted' }, ph.log.map(l => `${tsKST(l.at).slice(0, 8)} ${l.text}`).join(' · ')));
    }
    box.append(col);
  } else {
    const col = h('div', { class: 'devcol wide' }, h('h4', null, '기록 (앱·웹)'), h('pre', { class: 'mono logview' }, LOG.slice(-80).join('\n')));
    const ncol = h('div', { class: 'devcol wide' }, h('h4', null, '네트워크 요청 (보낸 순서)'), h('pre', { class: 'mono logview' }, s.net.log.slice(-40).map(r => `${tsKST(s.clock.epochBase + r.at).slice(0, 10)} ${r.from || '?'} → ${r.what}${r.what === 'incident' ? (HandoffCodec.decode(r.body)?.hash ? ' (완성본)' : ' (임시)') : ''}`).join('\n')));
    box.append(col, ncol);
  }
}

// ── HUD ──
function renderHud() {
  const p = ACTIVE.sim.player; if (!p) return;
  const cs = ACTIVE.app.carState, kph = Math.abs(p.vf) * 3.6;
  $('#hud-speed').textContent = kph.toFixed(0); $('#hud-gear').textContent = p.gear;
  const lim = cs.roadLimitKph; $('#hud-limit').textContent = lim || ''; $('#hud-limit').hidden = !lim;
  const blink = Math.floor(performance.now() / 400) % 2 === 0, sim = ACTIVE.sim;
  $('#hud-left').classList.toggle('on', (sim.hazard || p.signal === 'L') && blink); $('#hud-right').classList.toggle('on', (sim.hazard || p.signal === 'R') && blink);
  $('#hud-hazard').classList.toggle('on', sim.hazard); $('#hud-airbag').classList.toggle('on', p.airbag); $('#hud-trunk').classList.toggle('on', !!sim.trunkOpen);
  $('#hud-auto').hidden = !sim.autopilot;
  if (ACTIVE.kind === 'case' && UI.running && UI.running.kind === 'drive') $('#hud-auto').textContent = `자동 운전 · ${ACTIVE.run.c.id} · ${ACTIVE.run.elapsed.toFixed(1)}초 · 키 입력 무시`;
  else if (ACTIVE.kind === 'case') $('#hud-auto').textContent = `${ACTIVE.run.c.id} 끝 · 차 화면을 눌러 볼 수 있어요`;
}

function fitHeadUnit() {
  const host = $('#hu-host'), frame = $('#hu-frame'), w = frame.clientWidth, hgt = frame.clientHeight;
  const k = Math.min(w / HU_W, hgt / HU_H);
  host.style.transform = `scale(${k})`; host.style.left = `${(w - HU_W * k) / 2}px`; host.style.top = `${(hgt - HU_H * k) / 2}px`;
}
function fitView() { const c = $('#view'), r = c.parentElement.getBoundingClientRect(); VIEW.resize(Math.max(50, r.width), Math.max(50, r.height)); }

let lastT = performance.now();
function loop(t) {
  requestAnimationFrame(loop);
  const dt = Math.min(0.1, (t - lastT) / 1000); lastT = t;
  const s = ACTIVE;
  if (!UI.paused && s) {
    const steps = Math.min(400, Math.round(dt * UI.timeScale / 0.01));
    if (s.kind === 'case' && UI.running && UI.running.kind === 'drive') { if (s.run.tick(steps)) finishDrive(s.run.c, s.run, s); }
    else if (s.kind === 'case') { for (let i = 0; i < steps; i++) { s.sim.autopilot = s.run.spec.autopilot; s.sim.step(); } }
    else for (let i = 0; i < steps; i++) s.sim.step();
    const w = s.app.watcher; UI.gHist.push(w ? w.impact.lastG : 0); if (UI.gHist.length > 600) UI.gHist.shift();
  }
  VIEW.render(Math.floor(t / 400) % 2 === 0);
  HU.frame(); tickLineArts(t); renderHud();
  if (t - UI.lastDev > 250 && !$('#dev').hidden) { UI.lastDev = t; renderDev(); }
}

async function boot(THREE, GLTFLoader) {
  try { CAR_FIGS = await (await fetch('car/car_figures.json')).json(); } catch (e) { console.warn('차 그림 정보 없음', e); }
  WORLD = buildWorld(7);
  VIEW = new WorldView(THREE, $('#view'), { GLTFLoader });
  VIEW.build(WORLD);
  HU = new HeadUnit($('#hu-host'));
  FREE = makeFreeSession(WORLD, $('#persona').value);
  setActive(FREE);
  addEventListener('keydown', e => onKey(e, true)); addEventListener('keyup', e => onKey(e, false));
  // 버튼·선택 상자가 포커스를 쥐면 Space·화살표가 운전 대신 그쪽으로 간다 → 누른 뒤 놓아 준다
  addEventListener('click', e => { const t = e.target.closest && e.target.closest('button'); if (t) t.blur(); }, true);
  addEventListener('change', e => { if (e.target.tagName === 'SELECT') e.target.blur(); }, true);
  addEventListener('blur', () => { if (ACTIVE) for (const k in ACTIVE.sim.input) ACTIVE.sim.input[k] = false; });
  addEventListener('resize', () => { fitHeadUnit(); fitView(); });
  new ResizeObserver(() => { fitHeadUnit(); fitView(); }).observe($('#main'));
  fitHeadUnit(); fitView();
  $('#persona').onchange = e => { ACTIVE.app.personaId = e.target.value; if (ACTIVE === FREE) FREE.app.personaId = e.target.value; HU.lastKey = ''; logLine('웹', '운전자: ' + Personas.byId(e.target.value).name); };
  $('#theme').onclick = () => { HU.setDark(!PL.dark); $('#theme').textContent = PL.dark ? '밤 화면' : '낮 화면'; };
  $('#voice').onclick = () => { Voice.on = !Voice.on; $('#voice').textContent = Voice.on ? '음성 켬' : '음성 끔'; if (!Voice.on) Voice.stop(); };
  $('#speed').onchange = e => { UI.timeScale = +e.target.value; };
  $('#pause').onclick = () => { UI.paused = !UI.paused; $('#pause').textContent = UI.paused ? '계속' : '멈춤'; };
  $('#cam-btn').onclick = cycleCam;
  $('#back-free').onclick = backToFree;
  $('#hu-back').onclick = () => HU.back();
  $('#hu-navi').onclick = () => { HU.mode = 'NAVI'; HU.lastKey = ''; };
  $('#hu-app').onclick = () => { if (HU.cf.screenOpen && HU.cf.incident) HU.mode = 'CRASH'; else HU.mode = 'HOME'; HU.lastKey = ''; };
  $('#btn-hazard').onclick = toggleHazard; $('#btn-park').onclick = park; $('#btn-reset').onclick = resetCar;
  $('#btn-sigl').onclick = () => { const s = ACTIVE.sim; s.signal = s.signal === 'L' ? 'none' : 'L'; }; $('#btn-sigr').onclick = () => { const s = ACTIVE.sim; s.signal = s.signal === 'R' ? 'none' : 'R'; };
  $('#variant').onchange = e => {
    if (UI.running) { e.target.value = VARIANT.id; return; }
    VARIANT.fixed = e.target.value === 'fixed'; UI.results.clear();
    FREE = makeFreeSession(WORLD, $('#persona').value); setActive(FREE);
    $('#variant-badge').textContent = `웹 사본 · ${VARIANT.label} · 실제 앱 아님`;
    logLine('웹', `앱 코드를 ${VARIANT.label}(으)로 바꿨어요. 결과를 비우고 앱을 새로 켰어요`); renderCaseList(); renderResult();
  };
  $('#variant-badge').textContent = `웹 사본 · ${VARIANT.label} · 실제 앱 아님`;
  $('#run-all').onclick = runAll; $('#copy-res').onclick = copyResults; $('#sim-skip').onclick = () => { UI.skip = true; };
  document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => { UI.devTab = b.dataset.tab; document.querySelectorAll('[data-tab]').forEach(x => x.classList.toggle('on', x === b)); renderDev(); });
  document.querySelectorAll('[data-drawer]').forEach(b => b.onclick = () => { const which = b.dataset.drawer; $('#qa').hidden = which !== 'qa'; $('#dev').hidden = which !== 'dev'; document.querySelectorAll('[data-drawer]').forEach(x => x.classList.toggle('on', x === b)); });
  UI.sel = QA.cases[0].id; renderCaseList(); renderResult();
  // 개발자용: 콘솔에서 GW.ACTIVE.app, GW.HU 등으로 들여다볼 수 있게
  window.GW = { get QA() { return QA; }, get UI() { return UI; }, get HU() { return HU; }, get ACTIVE() { return ACTIVE; }, get FREE() { return FREE; }, get VIEW() { return VIEW; }, simulateCase, runAll, backToFree, restartApp };
  $('#loading').hidden = true;
  requestAnimationFrame(loop);
}
