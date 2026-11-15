// ===== 50-headunit.js — 차 화면(앱 창 1700×1184dp) : 내비 + 괜차나 차 앱(CarHome·CrashActivity)의 웹 사본 =====
// 좌표·크기는 Compose 원본의 dp 를 그대로 px 로 쓴다(창을 통째로 비율 축소). 원본: CarHome.kt, CrashActivity.kt, NaviActivity.kt(debug), Theme.kt

// ── Theme.kt Pleos 토큰 ──
const PL = (() => {
  const t = { dark: false };
  const def = (name, light, dark) => Object.defineProperty(t, name, { get() { return t.dark ? dark : light; }, enumerable: true });
  [['basic00', '#FFFFFF', '#131417'], ['basic50', '#F7F8FA', '#1D1E21'], ['basic100', '#EDEEF2', '#313236'], ['basic200', '#E0E1E6', '#44464E'], ['basic300', '#C7C9CE', '#686A72'],
   ['basic500', '#686A72', '#C7C9CE'], ['basic600', '#44464E', '#E0E1E6'], ['basic700', '#313236', '#EDEEF2'],
   ['backgroundPrimary', '#F7F8FA', '#1D1E21'], ['backgroundSecondary', '#FFFFFF', '#131417'], ['stageTop', '#FFFFFF', '#1D1E21'], ['stageMiddle', '#F1F1F1', '#1D1E21'],
   ['stageBottom', '#EEEEEE', '#131417'], ['rail', '#FFFFFF', '#131417'], ['horizon', '#E0E0E0', 'rgba(255,255,255,0.12)'], ['backgroundPopup', '#FFFFFF', '#313236'],
   ['textPrimary', '#131417', '#FFFFFF'], ['textSecondary', '#44464E', '#E0E1E6'], ['textTertiary', '#686A72', '#C7C9CE'], ['textQuaternary', 'rgba(19,20,23,0.32)', 'rgba(255,255,255,0.32)'],
   ['buttonFilled', '#44464E', '#E0E1E6'], ['buttonBasic', 'rgba(19,20,23,0.05)', 'rgba(255,255,255,0.05)'], ['outline', 'rgba(19,20,23,0.64)', 'rgba(255,255,255,0.64)'],
   ['divider', 'rgba(19,20,23,0.10)', 'rgba(255,255,255,0.12)'], ['negative', '#D32016', '#F2483A'], ['onNegative', '#FFFFFF', '#131417'], ['positive', '#0064FF', '#4AA3FF'],
   ['night', '#17181C', '#25272D'], ['night2', '#25272D', '#30323A'], ['night3', '#30323A', '#3A3C44'], ['paper', '#FFFFFF', '#1D1E21'],
   ['naviBg', '#F6F7FA', '#14171B'], ['naviRoad', '#AAB3C9', '#3A414B']].forEach(a => def(...a));
  t.grey = '#A4A8AE'; t.emergency = '#AE251F';
  return t;
})();
const PL_VARS = { '--ink': 'textPrimary', '--sec': 'textSecondary', '--ter': 'textTertiary', '--qua': 'textQuaternary', '--bg1': 'backgroundPrimary', '--bg2': 'backgroundSecondary',
  '--popup': 'backgroundPopup', '--filled': 'buttonFilled', '--onfilled': 'basic00', '--basic': 'buttonBasic', '--outline': 'outline', '--divider': 'divider', '--neg': 'negative',
  '--onneg': 'onNegative', '--pos': 'positive', '--b200': 'basic200', '--b700': 'basic700', '--stageTop': 'stageTop', '--stageMid': 'stageMiddle', '--stageBot': 'stageBottom', '--rail': 'rail',
  '--horizon': 'horizon', '--night': 'night', '--night2': 'night2', '--night3': 'night3', '--paper': 'paper' };
function parseColor(c) {
  if (c.startsWith('#')) { const n = parseInt(c.slice(1), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255, 1]; }
  const m = c.match(/[\d.]+/g).map(Number); return [m[0], m[1], m[2], m[3] ?? 1];
}
function mixColor(a, b, t) { const p = parseColor(a), q = parseColor(b); const v = p.map((x, i) => x + (q[i] - x) * t); return `rgba(${v[0] | 0},${v[1] | 0},${v[2] | 0},${v[3].toFixed(3)})`; }

// ── 작은 DOM 도구 ──
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  if (props) for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), e => { e.stopPropagation(); v(e); });
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  return el;
}
const at = (x, y, more = '') => `position:absolute;left:${x}px;top:${y}px;${more}`;
const BI = { // CarHome.kt BIcon: 24 격자, 선 1.8
  FLAME: 'M12 3c1 3.5 5 5 5 10a5 5 0 0 1-10 0c0-2.2 1-3.5 2-4.6.3 1.4 1 2.3 2 2.6-.5-2.3.3-5.5 1-8z',
  GEAR: 'M12 8.8a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4M12 2.5v2.6M12 18.9v2.6M2.5 12h2.6M18.9 12h2.6M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8',
  X: 'M6 6l12 12M18 6L6 18', LOCK: 'M7.5 10.5h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3',
  CHECK: 'M5 12.5l4.5 4.5L19 7.5', PLUS: 'M12 5v14M5 12h14', UNDO: 'M8 6L4 10l4 4M4 10h10a5.5 5.5 0 0 1 0 11h-3',
};
const PI = { // Theme.kt PleosIcons: 48 격자, 선 3.6
  BACK: 'M29.4 10.8L16.2 24L29.4 37.2', MIC: 'M17.4 12.6A6.6 6.6 0 0 1 30.6 12.6V22.2A6.6 6.6 0 0 1 17.4 22.2ZM10.2 23.4C10.2 31.8 16.2 36 24 36S37.8 31.8 37.8 23.4M24 36V42',
  DOC: 'M12.6 6H28.2L36 13.8V42H12.6ZM18 24H30M18 31.2H26.4', SPEAKER: 'M8.4 18.6H15.6L25.2 10.8V37.2L15.6 29.4H8.4ZM31.2 18C33.6 20.4 33.6 27.6 31.2 30M36.6 13.2C41.4 18 41.4 30 36.6 34.8',
};
function svgIcon(d, size, grid, w, color = 'currentColor', fill = false) {
  const s = document.createElementNS(SVGNS, 'svg'); s.setAttribute('viewBox', `0 0 ${grid} ${grid}`); s.setAttribute('width', size); s.setAttribute('height', size);
  const p = document.createElementNS(SVGNS, 'path'); p.setAttribute('d', d);
  if (fill) p.setAttribute('fill', color); else { p.setAttribute('fill', 'none'); p.setAttribute('stroke', color); p.setAttribute('stroke-width', w); p.setAttribute('stroke-linecap', 'round'); p.setAttribute('stroke-linejoin', 'round'); }
  s.appendChild(p); return s;
}
const bic = (d, size, color) => svgIcon(d, size, 24, 1.8, color);
const pic = (d, size, color) => svgIcon(d, size, 48, 3.6, color);

/** CarHome.kt FloorButton: kind RED·DARK·BASIC·PALE·GLASS, small = 되돌리기 크기 */
function floorBtn(text, kind, onClick, { icon = null, small = false, style = '' } = {}) {
  return h('button', { class: `fbtn ${kind.toLowerCase()}${small ? ' small' : ''} press`, style, onclick: onClick }, icon && bic(icon, small ? 32 : 36), h('span', null, text));
}
/** Theme.kt PleosButton: PRIMARY·SECONDARY·EMERGENCY·SAFE, large = 100dp */
function pleosBtn(text, kind, onClick, { large = true, icon = null, style = '' } = {}) {
  return h('button', { class: `pbtn ${kind.toLowerCase()}${large ? '' : ' medium'} press`, style, onclick: onClick }, icon && pic(icon, large ? 40 : 24), h('span', null, text));
}
const pleosLink = (text, onClick, style = '') => pleosBtn(text, 'SECONDARY', onClick, { large: false, style });
const squareBtn = (icon, label, onClick, style) => h('button', { class: 'sqbtn press', 'aria-label': label, title: label, style, onclick: onClick }, bic(icon, 44));
function card(label, ...kids) { return h('div', { class: 'pcard' }, label && h('div', { class: 'labelM ter' }, label), ...kids); }
function stageFloor(win, horizon) {
  const solid = Math.min(190 / horizon, 0.5), soft = clamp(290 / horizon, solid, 0.99);
  return h('div', { class: 'stagefloor', style: `--hz:${horizon}px;--solid:${(solid * 100).toFixed(2)}%;--soft:${(soft * 100).toFixed(2)}%` });
}
const HU_W = 1700, HU_H = 1184, HORIZON = 1030; // 창 1700×1184, 지평선 = 1120 − 창 위치 90

const Voice = {
  on: false, last: '',
  say(text) { this.last = text; if (!this.on || typeof speechSynthesis === 'undefined') return; try { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(text); u.lang = 'ko-KR'; speechSynthesis.speak(u); } catch { } },
  stop() { try { typeof speechSynthesis !== 'undefined' && speechSynthesis.cancel(); } catch { } },
};

/** CrashActivity.describe: 판단 없는 사고 설명 한 줄 */
function describeIncident(inc) {
  const side = inc.impactDeg != null ? PhotoGuide.part(inc.impactDeg).replace(/쪽$/, '') + '쪽에서 ' : '';
  if (inc.reason.startsWith('에어백')) return `${side}에어백이 터질 만큼 큰 충격이 있었어요`;
  if (inc.reason.startsWith('속도 급감')) return '속도가 갑자기 줄었어요';
  return `${side}충격이 감지되었어요`;
}
function crashDate(name, todayT) {
  const t = parseDirName(name); if (t == null) return name;
  const a = dirName(t), b = dirName(todayT);
  return a.slice(0, 8) === b.slice(0, 8) ? `오늘 ${a.slice(9, 11)}:${a.slice(11, 13)}` : `${+a.slice(4, 6)}월 ${+a.slice(6, 8)}일 ${a.slice(9, 11)}:${a.slice(11, 13)}`;
}
/** 기록지 쉬운 말(CrashActivity.plainFact) */
function plainFact(text, impactDeg, speedAtCrash) {
  const after = s => text.slice(text.indexOf(s) + s.length);
  if (text.includes('브레이크 페달 밟음')) return '브레이크를 밟았어요';
  if (text.includes('브레이크 페달 뗌')) return '브레이크를 뗐어요';
  if (text.startsWith('계기판 경고등 켜짐: 비상등')) return '비상등이 켜졌어요';
  if (text.startsWith('계기판 경고등 꺼짐: 비상등')) return '비상등이 꺼졌어요';
  if (text.startsWith('계기판 경고등 켜짐: ')) return `${after(': ')} 경고등이 켜졌어요`;
  if (text.startsWith('계기판 경고등 꺼짐: ')) return `${after(': ')} 경고등이 꺼졌어요`;
  if (text.startsWith('가속 시작')) return '가속을 시작했어요';
  if (text.startsWith('좌측 방향지시등')) return '왼쪽 방향지시등이 켜졌어요';
  if (text.startsWith('우측 방향지시등')) return '오른쪽 방향지시등이 켜졌어요';
  if (text.startsWith('방향지시등 꺼짐')) return '방향지시등이 꺼졌어요';
  if (text.startsWith('에어백 전개 신호')) return '에어백 신호를 기록했어요';
  if (text.startsWith('충격 감지')) return (impactDeg != null ? `${PhotoGuide.part(impactDeg)}에서 온 충격을 기록했어요 [추정]` : '충격을 기록했어요') + (speedAtCrash ? `\n그때 속도 ${speedAtCrash}` : '');
  if (text.startsWith('사고 감지')) return '사고를 기록했어요 (충격 센서 값 없음)';
  if (text.startsWith('기어 ')) return `기어를 ${text.slice(3).split('단')[0]}로 바꿨어요`;
  if (text.startsWith('조향 직진')) return '핸들을 바로 했어요';
  if (text.startsWith('좌측 조향')) return '왼쪽으로 핸들을 돌렸어요';
  if (text.startsWith('우측 조향')) return '오른쪽으로 핸들을 돌렸어요';
  return text;
}
const clockSeconds = s => { const p = s.split(':'); return p.length === 3 ? +p[0] * 3600 + +p[1] * 60 + +p[2] : null; };

class HeadUnit {
  constructor(host) {
    this.host = host;
    this.win = h('div', { class: 'hu-win' });
    host.appendChild(this.win);
    this.s = null; this.app = null;
    this.mode = 'NAVI';           // NAVI | HOME | CRASH
    this.dyn = []; this.lastKey = ''; this.toastTimer = null;
    this.resetCrash();
    this.setDark(false);
  }
  setDark(d) { PL.dark = d; for (const [k, v] of Object.entries(PL_VARS)) this.host.style.setProperty(k, PL[v]); this.host.classList.toggle('dark', d); this.lastKey = ''; }
  resetCrash() {
    Object.assign(this, { stage: 'POPUP', backTo: -1, dialedStep: -1, shownSteps: null, confirmLeave: false, fireOpen: false, pastDir: null, past: null, shotIndex: 0,
      stopSince: null, movingSince: null, stopped: false, parked: false, syncedStep: null, stepAt: 0, hazardOnAt: null, hazardStuck: false, trunkMsg: null, trunkOpen: false,
      calmProgress: 0, call: null, statementEdit: false, stepIndexSeen: -1, stageBeforePast: null });
  }
  /** 실행 중인 세션(자유 주행 또는 시험 케이스)에 붙는다 */
  attach(s) {
    this.s = s; this.app = s.app; this.mode = 'NAVI'; this.resetCrash(); this.lastKey = '';
    const app = this.app, prevInc = app.onIncident, prevRec = app.onRecord;
    app.onIncident = inc => { prevInc && prevInc(inc); this.openCrash(); };
    app.onRecord = () => { prevRec && prevRec(); this.lastKey = ''; };
  }
  get cf() { return this.app.crashFlow; }
  get persona() { return this.app.persona; }
  get calm() { return this.stopped; } // 차의 주행 규제 신호가 없는 웹: 정차만 본다
  now() { return this.app.clock.t; }
  /** CarService 가 사고 화면을 띄움(새 사고 = 처음부터) */
  openCrash(record = false) {
    const keepPast = this.mode === 'CRASH' && this.pastDir;
    this.mode = 'CRASH'; this.cf.screenOpen = true;
    if (!keepPast) this.pastDir = null;
    this.stage = record ? 'RECORD' : 'POPUP';
    this.dialedStep = -1; this.backTo = -1; this.confirmLeave = false; this.fireOpen = false; this.shownSteps = null; this.call = null;
    if (!record) { this.cf.guideStep = 0; this.shotIndex = 0; }
    const n = this.app.carState.now; this.stopped = n.speedKph < 1 || n.gear === 'P'; this.stopSince = null; this.movingSince = null;
    this.lastKey = '';
  }
  finishCrash(toast) { this.mode = 'NAVI'; this.cf.screenOpen = false; this.pastDir = null; Voice.stop(); this.lastKey = ''; if (toast) this.toast(toast); }
  openPast(name) { this.mode = 'CRASH'; if (this.cf.screenOpen && !this.pastDir) this.stageBeforePast = this.stage; this.pastDir = name; this.past = this.app.load('crashes/' + name); this.stage = 'RECORD'; this.lastKey = ''; }
  leavePast() { if (this.cf.incident && this.cf.screenOpen) { this.pastDir = null; this.stage = this.stageBeforePast || 'GUIDE'; this.lastKey = ''; } else this.finishCrash(); }
  toast(text, ms = 3200) {
    let t = this.win.querySelector('.hu-toast'); if (!t) { t = h('div', { class: 'hu-toast bodyM' }); this.win.appendChild(t); }
    t.textContent = text; t.classList.add('on'); clearTimeout(this.toastTimer); this.toastTimer = setTimeout(() => t.classList.remove('on'), ms);
  }
  stepsOf(inc) { const cf = this.cf; return Guides.steps(inc.highway, this.persona, inc.severe, cf.kind, cf.hurt, !inc.roadKnown, cf.near); }
  targetFrom(steps, from, dir) { const cf = this.cf; return guideTarget(steps.map(s => s.art), from, dir, a => skipped(a, cf.near, cf.moved, cf.kind === 'PERSON')); }
  screenOf(inc) {
    if (this.stage === 'RECORD' || !inc) return 'RECORD';
    if (!this.stopped) return 'BANNER';
    return this.stage === 'SCENE' ? 'KIND' : this.stage;
  }
  /** 뒤로(안드로이드 뒤로 가기 자리) */
  back() {
    if (this.call) { this.call = null; this.lastKey = ''; return; }
    if (this.mode !== 'CRASH') { this.mode = 'NAVI'; this.lastKey = ''; return; }
    const sc = this.screenOf(this.cf.incident);
    if (this.confirmLeave) this.confirmLeave = false;
    else if (this.fireOpen) this.fireOpen = false;
    else if (sc === 'RECORD') { if (this.pastDir) this.leavePast(); else this.finishCrash(); }
    else if (sc === 'POPUP' || sc === 'BANNER') { /* 버튼으로만 */ }
    else if (sc === 'KIND') this.confirmLeave = true;
    else if (sc === 'GUIDE') { const back = this.shownSteps ? this.targetFrom(this.shownSteps, this.cf.guideStep, -1) : this.cf.guideStep - 1; if (back >= 0) { this.backTo = back; this.cf.guideStep = back; } else this.confirmLeave = true; }
    else if (sc === 'PHOTOS') { if (this.shotIndex > 0) this.shotIndex--; else this.stage = 'GUIDE'; }
    this.lastKey = '';
  }
  /** 정차 판단(1.5초) + 다시 20km/h 넘게 60초 달리면 화면 닫기 (CrashActivity.trackMotion) */
  trackMotion() {
    const n = this.app.carState.now, t = this.now();
    this.parked = n.gear === 'P';
    if (n.speedKph < 1 || n.gear === 'P') { if (this.stopSince == null) this.stopSince = t; if (t - this.stopSince >= 1.5) this.stopped = true; }
    else { this.stopSince = null; if (n.speedKph >= 5) this.stopped = false; }
    if (this.mode === 'CRASH' && n.speedKph >= 20 && this.stage !== 'RECORD') {
      if (this.movingSince == null) this.movingSince = t;
      else if (t - this.movingSince > 60) this.finishCrash('사고 기록은 저장되어 있어요 · 알림을 눌러 다시 볼 수 있어요');
    } else this.movingSince = null;
  }
  viewKey() {
    const app = this.app, cf = this.cf, inc = cf.incident, rs = inc ? app.recStageNow() : null;
    const base = [this.mode, PL.dark, app.personaId, this.call ? this.call.n : '', inc ? inc.t : 0];
    if (this.mode === 'NAVI') return base.concat([inc && !this.stopped && this.cf.screenOpen ? 'B' + inc.severe + inc.highway : '', Math.round(app.carState.now.speedKph) < 5]).join('|');
    if (this.mode === 'HOME') return base.concat([app.fs.list('crashes').length, cf.record ? cf.record.dir : '', rs ? rs.stage : '', app.carSync.arrived(inc ? inc.t : -1), app.carState.now.speedKph >= 5, app.fs.list('crashes').map(n => app.fs.exists(`crashes/${n}/false_alarm`) ? 1 : 0).join('')]).join('|');
    const sc = this.screenOf(inc);
    return base.concat([sc, this.stage, inc ? [inc.severe, inc.highway, inc.roadKnown, inc.impactDeg != null ? Math.round(inc.impactDeg) : 'n', inc.reason] : '', cf.kind, cf.kindChosen, cf.hurt, cf.near, cf.moved,
      cf.guideStep, this.backTo, cf.record ? cf.record.dir + cf.record.report.length : '', app.carSync.recordAcked, app.carSync.ok, this.fireOpen, this.confirmLeave, this.shotIndex, this.pastDir,
      this.trunkMsg, this.trunkOpen, this.hazardStuck, this.hazardOnAt != null, this.parked, this.statementEdit, rs && (rs.stage === 'FAILED')]).join('|');
  }
  /** 매 화면 그리기(브라우저 rAF) */
  frame() {
    if (!this.app) return;
    this.trackMotion();
    this.logic();
    const key = this.viewKey();
    if (key !== this.lastKey) { this.lastKey = key; this.render(); }
    for (const f of this.dyn) f();
  }
  /** 화면과 무관한 진행(안내 자동 넘김·비상등 확인·단계 동기화) */
  logic() {
    if (this.mode !== 'CRASH' || this.pastDir) return;
    const cf = this.cf, inc = cf.incident, sc = this.screenOf(inc), t = this.now();
    if (!inc || sc !== 'GUIDE') { this.stepIndexSeen = -1; return; }
    const steps = this.syncSteps(inc), index = clamp(cf.guideStep, 0, steps.length - 1), step = steps[index], back = this.backTo === index;
    if (index !== this.stepIndexSeen) { this.stepIndexSeen = index; this.stepAt = t; this.hazardOnAt = null; this.hazardStuck = false; this.trunkMsg = null; this.trunkOpen = false; this.calmProgress = 0; Voice.say(step.voice); }
    if (!this.syncedStep || this.syncedStep[0] !== inc.t || this.syncedStep[1] !== index) { this.syncedStep = [inc.t, index]; this.app.carSync.step(inc.t, index); }
    const walkAt = steps.findIndex(s => s.art === 'WALK'), outside = inc.highway && walkAt >= 0 && index > walkAt;
    if (outside || this.confirmLeave || this.fireOpen || this.call) return;
    if (step.art === 'CALM' && !back) { this.calmProgress = clamp((t - this.stepAt) / 6, 0, 1); if (this.calmProgress >= 1) this.next(steps, index); }
    if (step.art === 'HAZARD' && this.app.carState.telltalesSeen) {
      const on = this.app.carState.hazard;
      if (on && !back) { if (this.hazardOnAt == null) this.hazardOnAt = t; else if (t - this.hazardOnAt >= 1.2) this.next(steps, index); }
      else this.hazardOnAt = on ? this.hazardOnAt : null;
      if (t - this.stepAt > 15) this.hazardStuck = true;
    }
  }
  /** 단계 목록이 바뀌어도 보던 단계를 그대로(GuideView 의 shownSteps) */
  syncSteps(inc) {
    const steps = this.stepsOf(inc), old = this.shownSteps, cf = this.cf;
    const same = old && old.length === steps.length && old.every((s, i) => s.art === steps[i].art && s.title === steps[i].title);
    if (!same) {
      const moved = i => { const o = old && old[i]; return o ? steps.findIndex(s => s.art === o.art && s.title === o.title) : -1; };
      const atI = moved(cf.guideStep); this.backTo = moved(this.backTo); this.dialedStep = moved(this.dialedStep);
      this.shownSteps = steps; if (atI >= 0) cf.guideStep = atI;
    } else this.shownSteps = old;
    return this.shownSteps;
  }
  go(i, steps) {
    if (i < 0) { this.backTo = -1; this.shownSteps = null; this.stage = this.cf.hurt ? 'POPUP' : 'SCENE'; }
    else if (i > steps.length - 1) this.stage = 'RECORD';
    else this.cf.guideStep = i;
    this.lastKey = '';
  }
  next(steps, index) {
    const inc = this.cf.incident, to = this.targetFrom(steps, index, 1);
    if (to <= steps.length - 1 && steps[to].art !== 'RECORD') return this.go(to, steps);
    this.syncedStep = [inc.t, steps.length - 1]; this.app.carSync.step(inc.t, steps.length - 1);
    this.stage = 'RECORD'; this.lastKey = '';
  }
  dismiss() { // "사고가 아니에요"
    const cf = this.cf, fs = this.app.fs;
    if (cf.dir) { try { fs.mkdirs(cf.dir); fs.write(cf.dir + '/false_alarm', '', this.app.clock.nowMs()); } catch { } }
    this.closeIncident(true, '사고 아님으로 표시했어요 · 기록은 7일 뒤 지워져요. 잘못 눌렀다면 홈에서 되돌리세요');
  }
  closeIncident(notAccident = false, toast = null) { const cf = this.cf; if (cf.incident) this.app.carSync.close(cf.incident.t, notAccident); cf.setIncident(() => null); this.finishCrash(toast); }
  reopen(name) { // CrashFlow.reopen: 사고 아님 표시를 지우고 그 사고를 다시 진행 중으로
    const app = this.app, fs = app.fs, d = 'crashes/' + name;
    if (!fs.exists(d + '/log.json') || this.cf.incident) return false;
    fs.delete(d + '/false_alarm');
    const r = app.load(d); if (!r) return false;
    let imp = null; try { imp = JSON.parse(fs.read(d + '/impact.json')); } catch { }
    const t = imp ? imp.t : parseDirName(name);
    const cf = this.cf;
    cf.dir = d; cf.record = r; cf.kind = r.kind || 'UNKNOWN'; cf.kindChosen = !!r.kindChosen; cf.hurt = false; cf.near = false; cf.moved = false; cf.guideStep = 0; cf.saveFailed = null;
    cf.setIncident(() => ({ t, reason: '충격 감지', severe: false, highway: false, impactDeg: r.impactDeg, preLog: r.log.filter(s => s.t >= t - 10 && s.t <= t), suggestWhy: null, roadKnown: false }));
    app.carSync.publish();
    this.openCrash();
    return true;
  }
  dial(n, step, steps, index) {
    const cf = this.cf;
    if (step && step.art === 'PERSON' && !cf.near) { cf.near = true; this.app.carSync.publish(); }
    this.call = { n, name: step && step.callName ? step.callName : null, then: steps ? () => this.next(steps, index) : null };
    this.lastKey = '';
  }

  // ───────────────────────── 그리기 ─────────────────────────
  render() {
    const w = this.win; this.dyn = [];
    const keepToast = w.querySelector('.hu-toast');
    w.textContent = '';
    if (this.mode === 'NAVI') this.renderNavi(w);
    else if (this.mode === 'HOME') (this.app.carState.now.speedKph >= 5 ? this.renderDrivingHome(w) : this.renderHome(w));
    else this.renderCrash(w);
    if (this.call) w.appendChild(this.callDialog());
    if (keepToast) w.appendChild(keepToast);
  }

  // ── NaviActivity: 진행 방향이 위, 내 차는 아래쪽 가운데. 도로는 실제 내비처럼 가늘게 ──
  renderNavi(w) {
    const cv = h('canvas', { class: 'navi-map', width: HU_W, height: HU_H });
    w.appendChild(cv);
    const limit = h('div', { class: 'limit', style: at(32, 32) });
    const search = h('div', { class: 'navi-search', style: at(24, HU_H - 24 - 172) },
      h('div', { class: 'navi-search-top' }, svgIcon('M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13M15.3 15.3L20 20', 38, 24, 2, '#fff'), h('span', { class: 'bodyM' }, '어디로 갈까요?')),
      h('div', { class: 'navi-search-row' }, svgIcon('M4 11l8-7 8 7v9h-5v-6H9v6H4z', 40, 24, 1.8, PL.textTertiary), svgIcon('M12 3.5l2.6 5.6 6 .7-4.5 4.1 1.2 6-5.3-3-5.3 3 1.2-6L3.4 9.8l6-.7z', 40, 24, 1.8, PL.textPrimary), h('b', { style: 'font-size:32px' }, 'P')));
    const pill = h('div', { class: 'navi-pill bodyM' });
    const btn2d = h('div', { class: 'navi-btn', style: at(HU_W - 24 - 78, 24) }, h('b', { style: 'font-size:26px' }, '2D'));
    w.append(limit, search, pill, btn2d);
    if (Math.round(this.app.carState.now.speedKph) < 5) w.appendChild(h('button', { class: 'navi-btn press', title: '설정(괜차나 홈)', style: at(HU_W - 24 - 78, HU_H - 24 - 78), onclick: () => { this.mode = 'HOME'; this.lastKey = ''; } }, bic(BI.GEAR, 40, PL.textPrimary)));
    const inc = this.cf.incident;
    if (inc && this.cf.screenOpen && !this.stopped) w.appendChild(this.bannerView(inc));
    const ctx = cv.getContext('2d');
    this.dyn.push(() => {
      const s = this.s, p = s.sim && s.sim.player, cs = this.app.carState;
      const lim = cs.roadLimitKph;
      limit.style.display = lim > 0 ? '' : 'none'; if (limit.textContent !== String(lim)) limit.textContent = lim;
      const rec = !!this.app.watcher;
      const txt = rec ? `${this.persona.name} · 사고 대비 기록 중` : '기록 꺼짐 — 앱을 다시 시작하세요';
      if (pill.dataset.t !== txt) { pill.dataset.t = txt; pill.textContent = ''; pill.append(rec ? h('i', { class: 'dot' }) : h('i', { class: 'bang' }, '!'), txt); }
      ctx.fillStyle = PL.naviBg; ctx.fillRect(0, 0, HU_W, HU_H);
      if (!p) return;
      const cx = HU_W / 2, cy = HU_H * 0.68, sc = Math.max(4, 9 - cs.now.speedKph / 20);
      const head = p.yaw; // 지도: 차 진행 방향을 위로
      ctx.save(); ctx.translate(cx, cy); ctx.rotate(head - Math.PI / 2); ctx.scale(sc, -sc); ctx.translate(-p.x, -p.y);
      ctx.strokeStyle = PL.naviRoad; ctx.lineCap = 'round';
      const reach = Math.max(HU_W, HU_H) / sc * 0.75;
      for (const r of s.world.roads) {
        if (segDist(p.x, p.y, r) > reach + 1500) continue;
        ctx.lineWidth = Math.max(3 / sc, r.w * 0.4); ctx.beginPath(); ctx.moveTo(r.ax, r.ay); ctx.lineTo(r.bx, r.by); ctx.stroke();
      }
      ctx.restore();
      const R = 18;
      ctx.fillStyle = '#1E88E5'; ctx.beginPath(); ctx.arc(cx, cy, 34, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(cx, cy, 27, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#1E88E5'; ctx.beginPath(); ctx.moveTo(cx, cy - R); ctx.lineTo(cx + R * 0.8, cy + R * 0.8); ctx.lineTo(cx, cy + R * 0.35); ctx.lineTo(cx - R * 0.8, cy + R * 0.8); ctx.closePath(); ctx.fill();
    });
  }

  // ── CrashActivity.Banner: 아직 달리는 중. 위쪽 띠만(아래 내비는 그대로) ──
  bannerView(inc) {
    const stopLine = inc.highway ? '비상등을 켜고, 갈 수 있으면 갓길에 세워 주세요.' : '안전한 곳에 차를 세워 주세요.';
    if (this.bannerSpokenFor !== inc.t) { this.bannerSpokenFor = inc.t; Voice.say(`${stopLine} 차가 멈추면 이어서 안내할게요.`); }
    return h('div', { class: 'banner' },
      lineArtEl(LineArts.impact(inc.impactDeg), { size: 80 }).el,
      h('div', { class: 'banner-text' }, h('div', { class: 'titleL b' }, stopLine), h('div', { class: 'bodyM sec' }, '차가 멈추면 이어서 안내할게요')),
      !inc.severe && pleosBtn('사고가 아니에요', 'SECONDARY', () => this.dismiss(), { large: false }));
  }

  // ── CarHome ──
  renderDrivingHome(w) {
    w.append(stageFloor(w, HORIZON), h('div', { class: 'H44', style: at(80, 300) }, '주행 중엔 뒤에서 기록만 해요'), squareBtn(BI.X, '앱 나가기', () => { this.mode = 'NAVI'; this.lastKey = ''; }, at(HU_W - 64 - 100, 48)));
  }
  renderHome(w) {
    const app = this.app, fs = app.fs, inc = this.cf.incident, activeDir = inc && this.cf.dir ? this.cf.dir.split('/')[1] : null;
    const all = app.pastCrashes(), past = all.filter(c => !c.falseAlarm && c.name !== activeDir), marked = all.find(c => c.falseAlarm);
    w.append(stageFloor(w, HORIZON), h('div', { class: 'H44', style: at(80, 68) }, '사고 기록'),
      squareBtn(BI.GEAR, '설정', () => this.toast('설정(동의·개인 정보)은 아래 개발 창의 "앱 상태"에서 바꿀 수 있어요'), at(HU_W - 188 - 100, 48)),
      squareBtn(BI.X, '앱 나가기', () => { this.mode = 'NAVI'; this.lastKey = ''; }, at(HU_W - 64 - 100, 48)));
    if (inc) w.appendChild(this.heroCard(inc, activeDir || ''));
    else w.appendChild(h('div', { style: at(80, 300, 'width:800px;display:flex;flex-direction:column;gap:12px') }, h('div', { class: 'H44' }, '진행 중인 사고가 없어요'), h('div', { class: 'B30 sec' }, '사고가 나면 이 화면이 먼저 열려요.')));
    if (marked) w.appendChild(h('div', { class: 'falserow rise', style: at(980, 290) },
      h('div', { class: 'B26 sec', style: 'flex:1' }, `${marked.name.slice(9, 11)}:${marked.name.slice(11, 13)} 사고 아님으로 표시 · 7일 보관`),
      !inc && floorBtn('되돌리기', 'BASIC', () => this.reopen(marked.name), { icon: BI.UNDO, small: true })));
    if (past.length) {
      const row = h('div', { class: 'pastrow', style: at(980, 430, `width:${HU_W - 980}px;height:570px`) });
      past.forEach((c, i) => row.appendChild(h('button', { class: 'pastcard press', style: `background:${i === 0 ? PL.night2 : PL.night3}`, onclick: () => this.openPast(c.name) },
        h('div', { class: 'pastcard-text' }, h('div', { class: 'titleS b', style: 'line-height:42px;color:#fff' }, crashDate(c.name, app.clock.now())), h('div', { class: 'B26 grey' }, c.title), h('div', { class: 'B26 grey' }, c.note)),
        (() => { const b = carBox('rear', 20, 360, 270), f = carFigureEl('rear'); f.style.cssText = at(b.left, b.top, `width:${b.width}px`); return f; })())));
      w.appendChild(row);
    }
    if (!inc) w.appendChild(floorBtn('차가 못 본 사고 기록하기', 'BASIC', () => { if (app.watcher) app.watcher.trigger('운전자 직접 기록', false, false, true); else this.toast('주행 기록 동의가 꺼져 있어요'); }, { icon: BI.PLUS, style: at(80, HORIZON + 28) }));
  }
  heroCard(inc, dir) {
    const cf = this.cf, saved = cf.dir != null && cf.record != null && cf.record.dir === cf.dir, arrived = this.app.carSync.arrived(inc.t);
    const title = arrived ? '휴대폰에서 이어서 하세요' : saved ? '기록을 차에 저장했어요' : '기록하고 있어요';
    const view = carViewFor(inc.impactDeg), b = carBox(view, 150, 278, 600), fig = carFigureEl(view, { deg: inc.impactDeg }); fig.style.cssText = at(b.left, b.top, `width:${b.width}px`);
    const chip = (icon, text) => h('div', { class: 'chip' }, bic(icon, 28, '#D7D9DF'), h('span', { class: 'bodyS' }, text));
    return h('div', { class: 'hero rise', style: at(80, 180) },
      h('div', { class: 'B26 grey', style: at(56, 52) }, crashDate(dir, this.app.clock.now()) + (inc.roadKnown ? ' · ' + (inc.highway ? '고속도로' : '시내') : '')),
      h('div', { class: 'headlineM xb', style: at(56, 100, 'color:#fff') }, title),
      h('div', { style: at(56, 190, 'display:flex;gap:12px') }, saved && chip(BI.LOCK, '차에 저장됨'), arrived && chip(BI.CHECK, '휴대폰 도착')),
      fig,
      h('div', { class: 'hero-actions' }, floorBtn('기록 보기', 'PALE', () => this.openCrash(true), { style: 'flex:1' }), floorBtn('차에게 묻기', 'GLASS', () => this.toast('"차에게 묻기"는 중계 서버(Claude)가 필요해 웹 시험장에는 없어요'))));
  }

  // ── CrashActivity ──
  renderCrash(w) {
    const cf = this.cf, inc = cf.incident, sc = this.screenOf(inc);
    if (sc === 'BANNER') { this.renderNavi(w); return; } // 정차 전: 내비 위 안내줄(renderNavi 가 붙임)
    w.appendChild(h('div', { class: 'crash-bg' }));
    if (sc === 'RECORD') this.recordView(w);
    else if (sc === 'POPUP') this.firstResponse(w, inc);
    else if (sc === 'KIND') this.kindFloor(w);
    else if (sc === 'GUIDE') this.guideView(w, inc);
    else if (sc === 'PHOTOS') this.photoPages(w, inc);
    if (inc && !this.pastDir) {
      w.appendChild(this.recordBar(inc));
      if (sc !== 'POPUP') w.appendChild(this.recordLabel(inc, sc === 'RECORD' ? 'position:absolute;left:420px;bottom:36px' : 'position:absolute;right:48px;bottom:30px'));
    }
    if (this.fireOpen && ['POPUP', 'KIND', 'GUIDE', 'PHOTOS'].includes(sc)) w.appendChild(this.fireView());
    if (this.confirmLeave) w.appendChild(this.leaveDialog());
  }
  recordBar(inc) {
    const bar = h('div', { class: 'recbar' });
    this.dyn.push(() => { const rs = this.app.recStageNow(); const on = rs && (rs.stage === 'RECORDING' || rs.stage === 'SAVING'); bar.style.display = on ? '' : 'none'; if (on) bar.style.width = (clamp(rs.elapsed / AFTER_SEC, 0, 1) * 100) + '%'; });
    return bar;
  }
  /** CarHome.RecordLabel: 점(기록·저장 중) 또는 자물쇠·체크 + 글자 */
  recordLabel(inc, style) {
    const el = h('div', { class: 'reclabel bodyS', style });
    this.dyn.push(() => {
      const rs = this.app.recStageNow(); if (!rs) { el.textContent = ''; return; }
      const k = rs.stage + rs.text; if (el.dataset.k === k) return; el.dataset.k = k; el.textContent = '';
      const failed = rs.stage === 'FAILED', ink = failed ? PL.negative : PL.textTertiary;
      el.style.color = ink;
      if (rs.stage === 'RECORDING' || rs.stage === 'SAVING') el.appendChild(h('i', { class: 'recdot blink', style: `background:${rs.stage === 'RECORDING' ? PL.negative : PL.textTertiary}` }));
      else if (failed) el.appendChild(h('i', { class: 'recdot', style: `background:${PL.negative}` }));
      else el.appendChild(bic(rs.stage === 'ARRIVED' ? BI.CHECK : BI.LOCK, 26, ink));
      el.appendChild(h('span', null, rs.text));
    });
    return el;
  }
  /** ① 첫 응답: "다치셨나요?" + 충격 지점이 보이는 차 + 지평선 아래 버튼 셋 */
  firstResponse(w, inc) {
    if (this.spokenFirst !== inc.t) { this.spokenFirst = inc.t; Voice.say(inc.highway ? '비상등을 켜 주세요. 차가 움직이면 갓길로 옮겨 세우고, 트렁크를 연 뒤 휴대폰을 들고 가드레일 밖으로 나가세요. 다쳤다면 다쳤어요를 누르세요. 불이나 연기가 보이면 바로 차에서 멀어지세요.' : `${describeIncident(inc)}. 다치셨나요?`); }
    const cf = this.cf, clock = tsKST(inc.t).slice(0, 8), side = inc.impactDeg != null ? PhotoGuide.part(inc.impactDeg).replace(/쪽$/, '') + '쪽 ' : '';
    const fact = inc.reason.startsWith('에어백') ? `${side}에어백 신호를 기록했어요` : inc.reason.startsWith('운전자') ? '직접 기록을 시작했어요' : inc.reason.startsWith('속도 급감') ? `${side}속도 급감을 기록했어요` : `${side}충격을 기록했어요`;
    const view = carViewFor(inc.impactDeg), b = carBox(view, 760, 290, 880), fig = carFigureEl(view, { deg: inc.impactDeg, animate: true });
    fig.style.cssText = at(b.left, b.top, `width:${b.width}px`); fig.classList.add('rise');
    w.append(stageFloor(w, HORIZON), fig,
      h('div', { class: 'headlineL', style: at(80, 130, 'font-size:104px;line-height:124px;letter-spacing:-2px') }, '다치셨나요?'),
      h('div', { style: at(80, 300, 'width:660px;display:flex;flex-direction:column;gap:4px') }, h('div', { class: 'B30 sec' }, clock), h('div', { class: 'B30 sec' }, fact), inc.impactDeg != null && h('div', { class: 'B30 ter' }, '[센서 추정]')),
      floorBtn(FireGuide.LINK, 'BASIC', () => { this.fireOpen = true; this.lastKey = ''; }, { icon: BI.FLAME, style: 'position:absolute;right:64px;top:48px' }),
      this.recordLabel(inc, at(80, HORIZON - 70)),
      h('div', { style: at(80, HORIZON + 28, `width:${HU_W - 160}px;display:flex;gap:20px`) },
        floorBtn('다쳤어요', 'RED', () => { cf.hurt = true; this.app.carSync.publish(); cf.guideStep = 0; this.stage = 'GUIDE'; this.lastKey = ''; }, { style: 'flex:1.2' }),
        floorBtn('안 다쳤어요', 'DARK', () => { if (cf.hurt) { cf.hurt = false; this.app.carSync.publish(); } this.stage = 'SCENE'; this.lastKey = ''; }, { style: 'flex:1.2' }),
        !inc.severe && floorBtn('사고가 아니에요', 'BASIC', () => this.dismiss(), { style: 'flex:1' })));
  }
  /** ② 대상 고르기(넓은 창): 어두운 큰 타일 셋 */
  kindFloor(w) {
    const inc = this.cf.incident;
    if (this.spokenKind !== inc.t) { this.spokenKind = inc.t; Voice.say(describeIncident(inc) + '. 무엇과 부딪혔나요?'); }
    const row = h('div', { style: at(80, 250, `width:${HU_W - 160}px;height:${HORIZON - 290}px;display:flex;gap:24px`) });
    for (const k of KIND_CHOICES) row.appendChild(h('button', { class: 'kindcard press', onclick: () => { this.cf.chooseKind(k); this.stage = 'GUIDE'; this.lastKey = ''; } },
      lineArtEl(LineArts.kind(k), { size: 320, ink: '#FFFFFF', stroke: 1.25 }).el, h('div', { class: 'headlineS b', style: 'font-size:44px;line-height:56px;color:#fff' }, KIND_LABEL[k])));
    const hint = inc.suggestWhy && !this.cf.kindChosen ? h('div', { class: 'bodyM ter', style: at(80, 196, 'width:1200px') }, `기록을 보고 미리 골라 두었어요(${KIND_LABEL[this.cf.kind]}). ${inc.suggestWhy}`) : null;
    w.append(stageFloor(w, HORIZON), h('div', { class: 'headlineL', style: at(80, 99, 'letter-spacing:-0.6px') }, '무엇과 부딪혔나요?'), hint,
      floorBtn(FireGuide.LINK, 'BASIC', () => { this.fireOpen = true; this.lastKey = ''; }, { icon: BI.FLAME, style: 'position:absolute;right:64px;top:48px' }), row,
      floorBtn('건너뛰기', 'BASIC', () => { this.stage = 'GUIDE'; this.lastKey = ''; }, { style: at(80, HORIZON + 28) }));
  }
  /** ③ 안내 */
  guideView(w, inc) {
    const cf = this.cf, steps = this.syncSteps(inc), index = clamp(cf.guideStep, 0, steps.length - 1), step = steps[index], back = this.backTo === index, kind = cf.kind;
    const walkAt = steps.findIndex(s => s.art === 'WALK'), outside = inc.highway && walkAt >= 0 && index > walkAt;
    const telltales = this.app.carState.telltalesSeen, waitHazard = step.art === 'HAZARD' && telltales, hazardOn = this.app.carState.hazard;
    const toRecord = (() => { const t = this.targetFrom(steps, index, 1); return t > steps.length - 1 || steps[t].art === 'RECORD'; })();
    const nxt = () => this.next(steps, index);
    const answer = first => {
      if (step.art === 'ROAD') { cf.setIncident(i => i ? Object.assign({}, i, { highway: first, roadKnown: true }) : i); this.app.carSync.publish(); this.lastKey = ''; return; }
      if (step.art === 'MOVE_RISK' && cf.moved !== first) { cf.moved = first; this.app.carSync.publish(); }
      nxt();
    };
    const actions = [];
    if (step.call != null) {
      const n = step.call, em = n === '119' || n === '112';
      actions.push(pleosBtn(em ? `${n} 전화` : '전화 걸기', em ? 'EMERGENCY' : 'SAFE', () => this.dial(n, step, steps, index), { style: 'flex:1' }),
        pleosBtn(step.decline ?? '다음', 'SECONDARY', () => { if (step.art === 'PERSON' && cf.near) { cf.near = false; this.app.carSync.publish(); } nxt(); }, { style: 'flex:1' }));
    } else if (waitHazard) {
      if (back || (!hazardOn && this.hazardStuck)) actions.push(pleosBtn(hazardOn ? '다음' : '건너뛰기', 'SECONDARY', nxt, { style: 'flex:1' }));
    } else if (step.art === 'TRUNK' && !this.trunkOpen) {
      actions.push(pleosBtn('트렁크 열기', 'PRIMARY', () => {
        this.trunkMsg = '트렁크를 여는 중이에요'; this.lastKey = '';
        setTimeout(() => { const sim = this.s.sim; if (sim) sim.trunkOpen = true; this.trunkMsg = '트렁크를 열었어요'; this.trunkOpen = true; this.lastKey = ''; }, 700);
      }, { style: 'flex:1' }), pleosBtn('다음', 'SECONDARY', nxt, { style: 'flex:1' }));
    } else if (isPhoto(step.art)) {
      actions.push(pleosBtn(step.next ?? '찍을 곳 알려 주세요', 'PRIMARY', () => { this.shotIndex = 0; this.stage = 'PHOTOS'; this.lastKey = ''; }, { style: 'flex:1' }), pleosBtn(step.decline ?? '건너뛰기', 'SECONDARY', nxt, { style: 'flex:1' }));
    } else if (step.decline != null) {
      actions.push(pleosBtn(step.next ?? '다음', 'PRIMARY', () => answer(true), { style: 'flex:1' }), pleosBtn(step.decline, 'SECONDARY', () => answer(false), { style: 'flex:1' }));
    } else actions.push(pleosBtn(toRecord ? '사고 기록 보기' : step.next ?? '다음', 'PRIMARY', nxt, { style: 'flex:1' }));
    const art = outside ? 'PHONE' : step.art, over = !outside && fillsStage(art);
    w.appendChild(stageFloor(w, HORIZON));
    let paperBox = null;
    if (over) { paperBox = h('div', { class: 'paper', style: `height:${HORIZON}px` }); w.appendChild(paperBox); }
    const vis = s => !skipped(s.art, cf.near, cf.moved, kind === 'PERSON');
    const shown = steps.filter(vis).length, atN = Math.max(0, steps.slice(0, index + 1).filter(vis).length - 1);
    const progress = h('div', { class: 'stepprog' }); for (let k = 0; k < shown; k++) progress.appendChild(h('i', { class: k <= atN ? 'on' : '' }));
    const col = h('div', { class: 'guide-col', style: `padding:16px 64px ${Math.max(24, HU_H - HORIZON - 128)}px 40px` },
      h('div', { class: 'guide-head' }, pleosLink('‹  이전', () => { const to = this.targetFrom(steps, index, -1); this.backTo = to; this.go(to, steps); }), progress,
        h('div', { class: 'labelM ter' }, `${atN + 1} / ${shown}`), pleosLink(FireGuide.LINK, () => { this.fireOpen = true; this.lastKey = ''; })));
    const body = h('div', { class: 'guide-body' }); col.appendChild(body); w.appendChild(col);
    if (outside) body.appendChild(this.stayOutView());
    else if (step.art === 'HANDOFF') body.appendChild(this.handoffView(steps, index, actions));
    else if (step.art === 'REPLAY') body.appendChild(this.replayView(inc, step, actions));
    else {
      const extra = [];
      if (step.art === 'HAZARD' && waitHazard) {
        const la = lineArtEl(LineArts.HAZARD_OK, { size: 100, animate: this.calm, until: hazardOn ? LineArts.HAZARD_OK.end : 0 });
        extra.push(h('div', { class: 'statusrow' }, la.el, h('div', { class: 'titleS', style: `color:${hazardOn ? PL.textPrimary : PL.textTertiary}` }, hazardOn ? '비상등이 켜졌어요' : back ? '비상등을 켜 주세요' : '비상등을 켜면 저절로 다음으로 넘어가요')));
      }
      if (step.art === 'TRUNK' && this.trunkMsg) extra.push(h('div', { class: 'titleS', style: `color:${this.trunkOpen ? PL.textPrimary : PL.textTertiary}` }, this.trunkMsg));
      if (step.art === 'CALM' && !back) { const bar = h('div', { class: 'timerbar' }, h('i')); this.dyn.push(() => { bar.firstChild.style.width = (this.calmProgress * 100) + '%'; }); extra.push(bar); }
      body.appendChild(this.stepContent(step, actions, extra, over));
    }
    if (over) requestAnimationFrame(() => this.placeStepArt(art, paperBox, col));
  }
  /** 그림이 바닥을 채우는 단계: 글 아래로 잉크가 56dp 이상 떨어지게 크기를 정한다(StageFloorIfWide) */
  placeStepArt(art, paperBox, col) {
    if (!paperBox.isConnected) return;
    const text = col.querySelector('.overtext'); const top = text ? text.offsetTop + text.offsetHeight + col.querySelector('.guide-body').offsetTop : 300;
    const fit = (HORIZON - top - 56) / (1 - (TEXT_CLEAR[art] || 0)), artH = Math.min(HORIZON - 88, fit), artW = Math.min(HU_W, artH * 16 / 9);
    const ri = RIGHT_INK[art];
    const img = h('img', { src: `guide/${artAsset(art)}.jpg`, alt: '', draggable: 'false', class: 'stepart', style: `width:${artW}px;${ri != null ? `right:${-artW * (1 - ri)}px` : `left:${(HU_W - artW) / 2}px`}` });
    paperBox.appendChild(img);
    if (artW < HU_W) paperBox.appendChild(h('div', { class: 'stepart-fade', style: `width:${artW * 0.1}px;${ri != null ? `right:${artW * ri - artW * 0.1 + (artW - artW)}px;left:auto;transform:translateX(${-artW + artW * 0.1}px)` : `left:${(HU_W - artW) / 2}px`}` }));
  }
  /** 일반 단계: 그림 + 제목 + 한 줄(+ 전화 상대, 용어 풀이, 상태) + 버튼 (StepContent + TextStep) */
  stepContent(step, actions, extra, over) {
    const p = this.persona, k = p.group === 'SENIOR' ? 1.2 : 1, tip = p.group === 'NEWBIE' ? step.tip : null, cf = this.cf;
    const text = [h('div', { class: 'headlineM', style: k !== 1 ? `font-size:${56 * k}px;line-height:${72 * k}px` : '' }, step.title)];
    if (p.mode !== 'VOICE') text.push(h('div', { class: 'bodyL sec' }, step.body)); else text.push(h('div', { class: 'labelM ter' }, '(음성 모드: 설명 글은 소리로만 — 웹은 위 "음성" 켜기)'));
    if (over && tip) text.push(h('div', { class: 'bodyM sec' }, tip));
    if (step.call && step.call !== '119' && step.call !== '112') text.push(h('div', { class: 'titleS' }, `${(step.callName || '전화').replace(/ \(가상\)$/, '')}  ·  ${step.call}`));
    if (over) {
      return h('div', { class: 'textstep over' }, h('div', { class: 'overtext' }, text), h('div', { style: 'flex:1' }),
        h('div', { class: 'actrow-wrap' }, h('div', { class: 'actrow', style: 'width:900px' }, actions), extra.length ? h('div', { class: 'rail' }, extra) : null));
    }
    const hasPic = HAS_PICTURE.has(step.art);
    const icon = !hasPic ? (() => { const la = lineArtOf(step.art); if (!la) return null; return lineArtEl(la, { size: 112, animate: this.calm, until: step.art === 'CALM' && !cf.record ? LineArts.CALM_RING : la.end }).el; })() : null;
    if (icon && step.art === 'CALM' && cf.record) { /* 저장되면 체크까지 */ }
    const left = h('div', { class: 'ts-left' }, h('div', { class: 'ts-text' }, icon && h('div', { style: 'margin-bottom:8px' }, icon), text, extra), h('div', { class: 'actrow' }, actions));
    const right = [];
    if (step.art === 'TRUNK') { const box = h('div', { class: 'carstage', style: 'aspect-ratio:16/10;display:flex;align-items:center;justify-content:center' }); const f = carFigureEl('rear', { deg: cf.incident ? cf.incident.impactDeg : null, pins: [{ anchor: 'trunk', art: 'TRUNK' }], pinSize: 72 }); f.style.width = '94%'; box.appendChild(f); right.push(box); }
    if (tip) right.push(card('용어 풀이', h('div', { class: 'bodyM sec' }, tip)));
    if (!hasPic) right.push(this.recordedFacts());
    return h('div', { class: 'textstep' }, left, right.length ? h('div', { class: 'ts-right' }, right) : null);
  }
  /** 차가 기록한 사실 카드: 감지 시각·장소·충격 방향[추정]·기록 상태 */
  recordedFacts() {
    const inc = this.cf.incident; if (!inc) return null;
    const fig = carFigureEl('top', { deg: inc.impactDeg }); fig.style.height = '360px';
    const row = (k, v, c) => h('div', { class: 'factrow' }, h('div', { class: 'bodyM ter', style: 'width:150px' }, k), h('div', { class: 'bodyM', style: `flex:1;${c ? 'color:' + c : ''}` }, v));
    const status = h('div', { class: 'bodyM', style: 'flex:1' });
    this.dyn.push(() => { if (status.textContent !== this.cf.status) status.textContent = this.cf.status; });
    return h('div', { class: 'factcard' },
      h('div', { class: 'carstage flat', style: 'height:400px;display:flex;align-items:center;justify-content:center;position:relative' }, fig, h('div', { class: 'bodyS ter', style: 'position:absolute;left:20px;top:20px' }, '차가 기록한 것')),
      h('div', { class: 'factrows' }, inc.impactDeg != null && row('충격 방향', `${PhotoGuide.part(inc.impactDeg)} [추정]`, PL.negative), row('감지 시각', tsKST(inc.t).slice(0, 8)),
        inc.roadKnown && row('장소', inc.highway ? '고속도로' : '시내'), h('div', { class: 'factrow' }, h('div', { class: 'bodyM ter', style: 'width:150px' }, '기록'), status)));
  }
  stayOut(connected) {
    if (this.cf.hurt) return ['안내는 휴대폰에 있어요.', '못 나왔다면 119에 그렇게 말하고 안내를 따라 주세요.'];
    return connected ? ['차로 돌아오지 마세요. 안내는 휴대폰에 있어요.'] : ['차로 돌아오지 말고 112나 119에 바로 전화해 주세요.'];
  }
  stayOutView() {
    const lines = this.stayOut(this.app.carSync.ok), inc = this.cf.incident;
    return h('div', { class: 'ts-left', style: 'max-width:1100px' }, h('div', { class: 'ts-text' },
      lineArtEl(LineArts.EVIDENCE, { size: 160, animate: this.calm, until: inc && this.app.carSync.recordAcked === inc.t ? LineArts.EVIDENCE.end : LineArts.EVIDENCE_SENT }).el,
      h('div', { class: 'headlineM' }, lines[0]), lines.slice(1).map(l => h('div', { class: 'bodyL sec' }, l)), h('div', null, pleosLink('사고 기록 보기', () => { this.stage = 'RECORD'; this.lastKey = ''; }))));
  }
  handoffView(steps, index, actions) {
    const cf = this.cf, step = steps[index], visible = steps.filter(s => !skipped(s.art, cf.near, cf.moved, cf.kind === 'PERSON')), atI = visible.indexOf(step);
    const rest = visible.slice(atI + 1).filter(s => s.art !== 'RECORD'), inc = cf.incident, ok = this.app.carSync.ok;
    const side = rest.length ? card('휴대폰에서 이어질 일', rest.slice(0, 3).map((st, k) => h('div', { class: 'numline' }, h('i', { class: 'labelM' }, atI + k + 2), h('div', { class: 'bodyM sec', style: 'flex:1' }, st.title))),
      rest.length > 3 && h('div', { class: 'bodyS ter' }, `그 밖에 ${rest.length - 3}단계`)) : null;
    const left = h('div', { class: 'ts-left' }, h('div', { class: 'ts-text' },
      lineArtEl(LineArts.EVIDENCE, { size: 160, animate: this.calm, until: inc && this.app.carSync.recordAcked === inc.t ? LineArts.EVIDENCE.end : LineArts.EVIDENCE_SENT }).el,
      h('div', { class: 'headlineM' }, step.title), h('div', { class: 'bodyL sec' }, ok ? step.body : '휴대폰 연결이 없으면 차에 저장된 기록을 그대로 쓰세요.')), h('div', { class: 'actrow' }, actions));
    return h('div', { class: 'textstep' }, left, side && h('div', { class: 'ts-right' }, side));
  }
  replayView(inc, step, actions) {
    const r = this.cf.record;
    const hero = h('div', { class: 'replay-hero' });
    if (!this.calm) hero.appendChild(h('div', { class: 'bodyL ter' }, '차가 멈추면 재현을 보여 드려요'));
    else if (!r) { const ring = h('div', { class: 'ring headlineS b' }); this.dyn.push(() => { const p = this.cf.progress; ring.style.setProperty('--p', p); ring.textContent = `${Math.trunc(p * 100)}%`; }); hero.appendChild(ring); }
    else hero.appendChild(trackViewEl(r.log, inc.t));
    const text = r ? [h('div', { class: 'headlineM' }, step.title), h('div', { class: 'bodyL sec' }, step.body)] : [h('div', { class: 'headlineM' }, '사고 장면을 그리고 있어요'), h('div', { class: 'bodyL sec' }, this.cf.status)];
    return h('div', { class: 'split' }, h('div', { class: 'split-row' }, h('div', { style: 'flex:1.5;height:100%' }, hero), h('div', { class: 'split-text' }, text)), h('div', { class: 'actrow' }, actions));
  }
  photoPages(w, inc) {
    const cf = this.cf, shots = PhotoGuide.shots(inc.impactDeg, cf.kind, inc.highway), i = clamp(this.shotIndex, 0, shots.length - 1), shot = shots[i];
    if (this.spokenShot !== shot.title) { this.spokenShot = shot.title; Voice.say(shot.title); }
    const progress = h('div', { class: 'stepprog' }); shots.forEach((_, k) => progress.appendChild(h('i', { class: k <= i ? 'on' : '' })));
    w.append(stageFloor(w, HORIZON), shotSchematicEl(shot, cf.kind, inc.impactDeg, at(HU_W - 664, 96, `width:600px;height:${HORIZON - 120}px`)),
      h('div', { class: 'guide-col', style: 'padding:16px 64px 0 40px' },
        h('div', { class: 'guide-head' }, pleosLink('‹  이전', () => { if (i === 0) this.stage = 'GUIDE'; else this.shotIndex = i - 1; this.lastKey = ''; }), progress, h('div', { class: 'labelM ter' }, `사진 ${i + 1} / ${shots.length}`), pleosLink(FireGuide.LINK, () => { this.fireOpen = true; this.lastKey = ''; })),
        h('div', { style: 'margin-top:32px;max-width:960px;display:flex;flex-direction:column;gap:12px' }, h('div', { class: 'headlineM' }, shot.title), h('div', { class: 'bodyL sec' }, shot.line), h('div', { class: 'labelM ter' }, '웹 시험장: 실제 사진은 찍지 않아요'))),
      h('div', { class: 'actrow', style: at(40, HORIZON + 28, 'width:900px') }, pleosBtn(i === shots.length - 1 ? '다 찍었어요' : '다음', 'PRIMARY', () => {
        if (i === shots.length - 1) { this.stage = 'GUIDE'; const st = this.stepsOf(inc); cf.guideStep = this.targetFrom(st, cf.guideStep, 1); } else this.shotIndex = i + 1; this.lastKey = '';
      }, { style: 'flex:1' })));
  }
  fireView() {
    Voice.say(`${FireGuide.TITLE}. ${FireGuide.BODY}`);
    return h('div', { class: 'fire', onclick: () => { } },
      pleosLink('‹  이전', () => { Voice.stop(); this.fireOpen = false; this.lastKey = ''; }),
      h('div', { class: 'textstep', style: 'margin-top:24px;padding-left:24px' }, h('div', { class: 'ts-left' }, h('div', { class: 'ts-text' }, lineArtEl(LineArts.FIRE, { size: 112, animate: this.calm }).el,
        h('div', { class: 'headlineM' }, FireGuide.TITLE), h('div', { class: 'bodyL sec' }, FireGuide.BODY)), h('div', { class: 'actrow' }, pleosBtn('119 전화', 'EMERGENCY', () => this.dial('119'), { style: 'flex:1' })))));
  }
  leaveDialog() {
    return h('div', { class: 'scrim' }, h('div', { class: 'popup' }, h('div', { class: 'titleL b', style: 'text-align:center' }, '안내를 여기서 멈출까요?'),
      h('div', { class: 'bodyL sec', style: 'text-align:center;margin-top:16px' }, '사고 기록은 저장되어 있어요. 알림을 눌러 언제든 다시 볼 수 있어요.'),
      h('div', { class: 'actrow', style: 'margin-top:48px' }, pleosBtn('계속 안내 받기', 'PRIMARY', () => { this.confirmLeave = false; this.lastKey = ''; }, { style: 'flex:1' }),
        pleosBtn('그만 볼게요', 'SECONDARY', () => { this.confirmLeave = false; this.finishCrash('사고 기록은 저장되어 있어요 · 홈에서 다시 볼 수 있어요'); }, { style: 'flex:1' }))));
  }
  callDialog() {
    const c = this.call, em = c.n === '119' || c.n === '112';
    return h('div', { class: 'scrim' }, h('div', { class: 'popup' },
      h('div', { class: 'callicon', style: `background:${em ? PL.negative : PL.buttonFilled}` }, svgIcon(PHONE_PATH, 56, 46, 0, '#fff', true)),
      h('div', { class: 'titleL b', style: 'text-align:center;margin-top:28px' }, `가상 통화 · ${c.name ? c.name.replace(/ \(가상\)$/, '') + ' ' : ''}${c.n}`),
      h('div', { class: 'bodyL sec', style: 'text-align:center;margin-top:16px' }, '웹 시험장은 전화를 걸지 않아요. 실제 앱은 여기서 전화 앱을 열고, 통화를 마치고 돌아오면 다음 단계로 넘어가요.'),
      h('div', { class: 'actrow', style: 'margin-top:48px' }, pleosBtn('통화 마치고 돌아오기', 'PRIMARY', () => { const then = c.then; this.call = null; this.lastKey = ''; if (then) setTimeout(then, 400); }, { style: 'flex:1' }),
        pleosBtn('걸지 않고 돌아오기', 'SECONDARY', () => { this.call = null; this.lastKey = ''; }, { style: 'flex:1' }))));
  }
  /** ⑤ 사고 기록(넓은 창 RecordFloor): 왼쪽 재현(2D 궤적), 오른쪽 기록지 한 장 */
  recordView(w) {
    const app = this.app, fs = app.fs, cf = this.cf;
    const r = this.pastDir ? this.past : cf.record, markDir = r ? r.dir : (!this.pastDir ? cf.dir : null);
    const falseAlarm = !!markDir && fs.exists(markDir + '/false_alarm');
    w.appendChild(stageFloor(w, HORIZON));
    const backBtn = h('button', { class: 'iconbtn press', style: at(80, 32), title: '뒤로', onclick: () => { if (this.pastDir) this.leavePast(); else if (cf.incident) { this.stage = 'GUIDE'; this.lastKey = ''; } else this.finishCrash(); } }, pic(PI.BACK, 48, PL.textPrimary));
    w.append(backBtn, h('div', { class: 'titleL', style: at(176, 30) }, '사고 기록'));
    if (!r) {
      w.appendChild(h('div', { class: 'pcard', style: at(176, 160, 'width:880px') }, h('div', { class: 'bodyL sec', style: 'white-space:pre-line' }, cf.incident || falseAlarm ? cf.status + '\n사고 후 10초까지 기록한 뒤 경위서를 만들어요.' : '저장된 사고 기록이 없어요.')));
      return;
    }
    let crashT = null; try { crashT = JSON.parse(fs.read(r.dir + '/impact.json')).t; } catch { }
    if (crashT == null) crashT = cf.incident ? cf.incident.t : ((r.log.length ? r.log[r.log.length - 1].t : 10) - 10);
    const lockedAt = fs.exists(r.dir + '/log.sha256') ? fs.mtime(r.dir + '/log.sha256') / 1000 : null;
    const hash = fs.exists(r.dir + '/log.sha256') ? fs.read(r.dir + '/log.sha256').slice(0, 16) : '';
    const statement = fs.exists(r.dir + '/statement.txt') ? [fs.read(r.dir + '/statement.txt'), fs.mtime(r.dir + '/statement.txt')] : null;
    const inc = !this.pastDir ? cf.incident : null, where = inc ? (inc.highway ? ' · 고속도로' : ' · 시내') : '';
    w.appendChild(h('div', { class: 'bodyM sec', style: at(176, 84) }, (tsKST(crashT).slice(0, 8) + where)));
    const stage = h('div', { class: 'rec-stage', style: `width:1096px;height:${HORIZON}px` });
    if (!this.calm) stage.appendChild(h('div', { class: 'bodyL ter' }, '차가 멈추면 재현을 보여 드려요'));
    else if (!this.parked) stage.appendChild(h('div', { class: 'bodyL ter' }, 'P단에 두면 재현을 보여 드려요'));
    else stage.appendChild(trackViewEl(r.log, crashT, true));
    w.insertBefore(stage, backBtn);
    // 기록지 행(ledgerRows)
    const crash = clockSeconds(tsKST(crashT)), near = r.log.reduce((b, s) => !b || Math.abs(s.t - crashT) < Math.abs(b.t - crashT) ? s : b, null), atCrash = near ? `${f0(near.speedKph)} km/h` : null;
    let rows = r.facts.filter(l => !l.includes('기록 시작') && !l.includes('기록 종료')).map(line => {
      const time = line.split(' ')[0], dt = clockSeconds(time) != null && crash != null ? clockSeconds(time) - crash : null;
      return dt != null && dt < -10 ? null : { time, text: plainFact(line.slice(line.indexOf(' ') + 1), r.impactDeg, atCrash), mark: line.includes('추정') ? 1 : 0 };
    }).filter(Boolean);
    if (lockedAt) rows.push({ time: tsKST(lockedAt).slice(0, 8), text: '기록을 잠갔어요. 이후 수정할 수 없어요', mark: 0 });
    if (statement) rows.push({ time: tsKST(statement[1] / 1000).slice(0, 8), text: `운전자: “${statement[0].trim()}”`, mark: 2 });
    rows.sort((a, b) => a.time < b.time ? -1 : a.time > b.time ? 1 : 0);
    const sheet = h('div', { class: 'sheet', style: at(1120, 24, `width:${HU_W - 1120}px;height:${HU_H - 48}px`) });
    const list = h('div', { class: 'sheet-list' });
    rows.forEach((row, i) => { if (i) list.appendChild(h('div', { class: 'hr' })); list.appendChild(h('div', { class: 'ledger' }, h('i', { class: 'mark m' + row.mark }), h('div', null, h('div', { class: 'titleS' }, row.time), h('div', { class: 'bodyM sec', style: 'white-space:pre-line' }, row.text)))); });
    if (hash) list.appendChild(h('div', { class: 'labelS ter', style: 'padding:16px 0' }, `기록 지문(SHA-256) ${hash}…`));
    sheet.append(list, h('div', { class: 'hr', style: 'margin:0 32px' }),
      h('div', { class: 'sheet-links' }, h('span', { class: 'bodyM sec press', onclick: () => this.toast('"기록으로 본 사고 모양"(비슷한 사고 유형)은 웹 시험장에서 아직 그리지 않아요') }, '기록으로 본 사고 모양'),
        !this.pastDir && h('span', { class: 'bodyM ter' }, '·'), !this.pastDir && h('span', { class: 'bodyM sec press', onclick: () => this.closeIncident(false) }, '안내 끝내기')),
      pleosBtn('휴대폰으로 이어 보기', 'PRIMARY', () => this.toast('PDF 기록서 공유는 웹에 없어요 · 오른쪽 "휴대폰" 창에서 받은 내용을 볼 수 있어요'), { icon: PI.DOC, style: 'margin:8px;width:calc(100% - 16px)' }));
    w.appendChild(sheet);
    // 말로 남기기(웹: 글자로) · 읽어 주기
    const iconAction = (label, icon, onClick, x, active) => h('div', { class: 'iconaction', style: at(x, HORIZON + 24) }, h('button', { class: 'iconbtn press' + (active ? ' active' : ''), onclick: onClick }, pic(icon, 44, active ? PL.basic00 : PL.textPrimary)), h('div', { class: 'labelM sec' }, label));
    w.append(iconAction(statement ? '다시 말하기' : '말로 남기기', PI.MIC, () => { this.statementEdit = !this.statementEdit; this.lastKey = ''; }, 80, this.statementEdit),
      iconAction('읽어 주기', PI.SPEAKER, () => { const was = Voice.on; Voice.on = true; Voice.say([r.report, ...r.vision].join('\n')); Voice.on = was; }, 240));
    if (falseAlarm) w.appendChild(pleosBtn('사고가 맞아요', 'PRIMARY', () => { if (this.reopen(markDir.split('/')[1])) { this.pastDir = null; } }, { large: false, style: at(760, HORIZON + 38) }));
    else w.appendChild(h('div', { class: 'labelM sec', style: at(760, HORIZON + 50, 'white-space:pre-line') }, '실선 = 차 기록 · 점선 = 추정\n도로선은 참고 그림'));
    if (this.statementEdit) {
      const ta = h('textarea', { class: 'bodyM', rows: 3, placeholder: '운전자 진술(웹: 마이크 대신 글자로)' }); if (statement) ta.value = statement[0];
      w.appendChild(h('div', { class: 'popup stmt', style: at(80, HORIZON - 330, 'width:900px') }, h('div', { class: 'titleS' }, '말로 남기기'), ta,
        h('div', { class: 'actrow', style: 'margin-top:16px' }, pleosBtn('저장', 'PRIMARY', () => {
          const text = ta.value.trim(); if (!text) return;
          try { fs.write(r.dir + '/statement.txt', text, app.clock.nowMs()); } catch (e) { this.toast('저장 실패: ' + e.message); return; }
          this.statementEdit = false; if (!this.pastDir) app.carSync.publish(); this.lastKey = ''; this.toast('진술을 기록에 붙였어요');
        }, { large: false, style: 'flex:1' }), pleosBtn('닫기', 'SECONDARY', () => { this.statementEdit = false; this.lastKey = ''; }, { large: false, style: 'flex:1' }))));
    }
  }
}

/** 2D 궤적(Art.kt TrackView): 마지막 10초, 진행 방향을 위로. full = 기록 화면(속도 색·충돌 표시) */
function trackViewEl(log, crashT, full = false) {
  const end = (log.find(s => s.airbag) || {}).t ?? (crashT != null ? crashT : (log.length ? log[log.length - 1].t : 0));
  const part = log.filter(s => s.t >= end - 10 && s.t <= (full ? end + 10 : end));
  const poses = trackPoses(part);
  const svg = document.createElementNS(SVGNS, 'svg'); svg.setAttribute('viewBox', '0 0 1000 800'); svg.classList.add('trackview');
  if (poses.length < 2) { const t = document.createElementNS(SVGNS, 'text'); t.setAttribute('x', 500); t.setAttribute('y', 400); t.setAttribute('text-anchor', 'middle'); t.setAttribute('class', 'bodyL'); t.setAttribute('fill', PL.textTertiary); t.textContent = '움직인 기록이 거의 없어요'; svg.appendChild(t); return svg; }
  const xs = poses.map(p => p.x), ys = poses.map(p => p.y), minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  const span = Math.max(maxX - minX, maxY - minY, 10), sc = 800 * 0.8 / span;
  const P = q => [500 - (q.y - (minY + maxY) / 2) * sc, 400 - (q.x - (minX + maxX) / 2) * sc];
  const d = poses.map((q, i) => (i ? 'L' : 'M') + P(q).map(v => v.toFixed(1)).join(' ')).join('');
  const path = document.createElementNS(SVGNS, 'path'); path.setAttribute('d', d); path.setAttribute('fill', 'none'); path.setAttribute('stroke', PL.positive); path.setAttribute('stroke-width', 8); path.setAttribute('stroke-linecap', 'round'); path.setAttribute('stroke-linejoin', 'round'); svg.appendChild(path);
  const dot = (q, r, c) => { const e = document.createElementNS(SVGNS, 'circle'); const [x, y] = P(q); e.setAttribute('cx', x); e.setAttribute('cy', y); e.setAttribute('r', r); e.setAttribute('fill', c); svg.appendChild(e); };
  dot(poses[0], 14, PL.textPrimary);
  const ci = full && crashT != null ? part.findIndex(s => s.t >= crashT) : -1;
  dot(poses[ci >= 0 ? Math.min(ci, poses.length - 1) : poses.length - 1], 18, PL.negative);
  return svg;
}
/** 사진 찍을 곳(ShotSchematic 간이판): 위에서 본 내 차와 서는 자리 번호 */
function shotSchematicEl(shot, kind, deg, style) {
  const box = h('div', { class: 'shotbox', style });
  const W = 600, H = parseFloat((style.match(/height:(\d+)/) || [0, 900])[1]), pxm = Math.min(W, H) / 34;
  const car = carFigureEl('top', { deg }); car.style.cssText = `position:absolute;height:${4.64 * pxm * 1.15}px;left:50%;top:50%;transform:translate(-50%,-50%)`;
  const x = W / 2 + shot.right * pxm, y = H / 2 - shot.fwd * pxm;
  box.append(car, h('div', { class: 'shotpin', style: `left:${clamp(x, 30, W - 30) - 28}px;top:${clamp(y, 30, H - 30) - 28}px` }, svgIcon('M4 8h3l2-3h6l2 3h3v11H4zM12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7', 34, 24, 1.8, '#fff')),
    h('div', { class: 'labelM ter', style: 'position:absolute;left:0;bottom:0' }, '위에서 본 자리 [참고 그림]'));
  return box;
}
