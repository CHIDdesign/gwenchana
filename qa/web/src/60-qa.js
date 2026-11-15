// ===== 60-qa.js — 시험 케이스 실행기 (DOM 없음: 브라우저와 Node 에서 같은 코드) =====
// TC-01..04 = QaSampleTest.kt 의 순서를 그대로 따르는 웹 모델용 어댑터(Harness). DRV-* = 시뮬레이터 자동 운전.
// 판정 문구: PASS / FAIL_REQUIREMENT(실제 경로를 돌렸으나 기대값 불일치) / BLOCKED_ADAPTER(이 웹 모델로 연결 못 함)

const UNOBSERVABLE = { unobservable: true };
const qaF32 = (expr, G) => {
  if (typeof expr === 'number') return Math.fround(expr);
  const s = String(expr).replace(/\s/g, '');
  let m;
  if (s === 'G') return G;
  if ((m = /^(-?[\d.]+)\*G$/.exec(s))) return Math.fround(Math.fround(+m[1]) * G);
  if ((m = /^G\+(-?[\d.]+)\*G$/.exec(s))) return Math.fround(G + Math.fround(Math.fround(+m[1]) * G));
  throw new Error('센서 값 식을 읽을 수 없음: ' + expr);
};

/** 합성 주행 기록(완성본·시드 자료용): 사고 시각 기준 from~to 초, 10Hz, 직진 40km/h 뒤 충돌 */
function qaSynthLog(t0, from, to) {
  const out = [];
  for (let k = Math.round(from * 10); k <= Math.round(to * 10); k++) {
    const t = t0 + k / 10;
    out.push(Sample(t, { speedKph: k < 0 ? 40 : 0, brake: k >= -5, airbag: false, lat: 37.5 + k * 1e-5, lon: 127.03 }));
  }
  return out;
}

function qaEnv(fx, { mode = 'auto', latency = 0.05, personaId = 'guest', account } = {}) {
  const clock = new SimClock(fx.INCIDENT_EPOCH_SECONDS), rt = new Runtime(clock), server = new SyncServer(), net = new Network(rt, server);
  server.oneAccount = false; net.mode = mode; net.latency = latency; rt.net = net;
  const env = { fx, clock, rt, server, net, carFs: new VFS(), carPrefs: new Map(), phoneFs: new VFS(), phonePrefs: new Map(), account: account || fx.ACCOUNT, personaId };
  env.carPrefs.set('consent', new Map(CONSENT_ITEMS.map(k => [k, k !== 'CLOUD'])));
  env.newCar = () => new GwenApp({ clock, rt, fs: env.carFs, prefsStore: env.carPrefs, personaId, account: env.account });
  env.newPhone = () => new PhoneApp({ rt, fs: env.phoneFs, prefsStore: env.phonePrefs, account: env.account, clock });
  return env;
}

/** 시험 어댑터가 차 쪽 로컬 자료를 준비한다(실제 발행 경로를 부르기 전). 임시 자료엔 지문이 없다 */
function qaPrepareCarCase(app, doc, { withReport = true } = {}) {
  const cf = app.crashFlow, fs = app.fs, t0 = doc.incidentEpochSeconds, dir = 'crashes/' + dirName(t0);
  if (!cf.incident) {
    cf.setIncident(() => ({ t: t0, reason: '충격 감지 (3.0g)', severe: false, highway: false, impactDeg: 180, preLog: qaSynthLog(t0, -10, 0), suggestWhy: null, roadKnown: true }));
    cf.dir = dir; cf.halted = isHalted(app.carState.now); // CrashWatcher.trigger 와 같게(멈춰 있으면 '정차' 재발행 없음)
  }
  fs.mkdirs(dir);
  fs.write(dir + '/statement.txt', doc.statement, app.clock.nowMs());
  if (doc.finalRecord) {
    const log = qaSynthLog(t0, -30, 10);
    fs.writeAtomic(dir + '/log.json', logToJson(log), app.clock.nowMs());
    fs.write(dir + '/log.sha256', sha256Hex(fs.read(dir + '/log.json')), app.clock.nowMs());
    fs.write(dir + '/impact.json', JSON.stringify({ t: t0, peakG: 3.0, deg: 180 }), app.clock.nowMs());
    const facts = FactTimeline.build(log, t0, 3.0, 180);
    if (withReport) fs.write(dir + '/report.txt', facts.join('\n'), app.clock.nowMs());
    cf.record = { dir, log, report: facts.join('\n'), facts, vision: [], impactDeg: 180, seen: null };
  } else {
    // ⚠ 어댑터가 만든 상태: 현재 앱은 진술을 CrashFlow.record 가 있을 때만 싣는다. 임시 자료에 진술을 실으려면 '지문 없는 기록'이 필요 → 실제 앱 흐름엔 없는 상태
    cf.record = { dir, log: [], report: null, facts: [], vision: [], impactDeg: 180, seen: null };
  }
  return dir;
}
const qaDocOf = (req) => {
  const d = HandoffCodec.decode(req.body);
  return d ? { account: req.account, incidentEpochSeconds: d.t, semanticVersion: d.hash ? 2 : 1, statement: d.statement ?? null, finalRecord: !!d.hash } : null;
};

class QaSensorHarness {
  constructor(fx) { this.clock = new SimClock(fx.INCIDENT_EPOCH_SECONDS); this.calls = []; this.sensor = new ImpactSensor({ clock: this.clock }, g => this.calls.push(g)); }
  feed(atMs, x, y, z) { this.clock.t = atMs / 1000; this.sensor.onSensorChanged('accel', [x, y, z], atMs * 1e6); }
  snapshot() { return { correctedG: this.sensor.lastG, impactCallbacksG: [...this.calls], gravity: [...this.sensor.gravity] }; }
}

class QaVirtualHarness {
  constructor(env) { this.env = env; }
  nowMs() { return Math.round(this.env.clock.t * 1000); }
  runReady() { this.env.rt.runReady(); }
  advanceTo(ms) { this.env.rt.advanceTo(ms / 1000); }
}

class QaDeliveryHarness extends QaVirtualHarness {
  constructor(fx) {
    super(qaEnv(fx, { mode: 'manual' }));
    this.car = this.env.newCar(); this.car.consent.init(); this.car.carSync.init();
    this.phone = this.env.newPhone();
    this.t0 = fx.INCIDENT_EPOCH_SECONDS; this.notes = ['임시 자료의 진술은 어댑터가 만든 "지문 없는 기록"으로만 실을 수 있음(현재 앱 흐름엔 없는 상태)'];
  }
  publishFromCar(doc) { qaPrepareCarCase(this.car, doc); this.car.carSync.publish(); }
  acceptPendingUpload() {
    const ups = this.env.net.pending.filter(p => p.req.what === 'incident');
    if (ups.length !== 1) throw new Error(`연결 오류: 대기 중 업로드가 ${ups.length}개`);
    const p = ups[0]; this.env.net.pending.splice(this.env.net.pending.indexOf(p), 1);
    p.done({ status: this.env.server.apply(p.req.account, 'incident', p.req.body) });
  }
  failNextPhoneWrite() { this.env.phoneFs.failNext = path => path.startsWith('cases/'); }
  deliverServerDocumentToPhone() { this.phone.apply(this.env.server.view(this.env.server.state(this.env.account))); }
  forwardPendingPhoneAcknowledgements() {
    const acks = this.env.net.pending.filter(p => p.req.what === 'seen' && p.req.from === 'phone');
    if (!acks.length) throw new Error('연결 오류: 보류된 폰 ACK 없음');
    for (const p of acks) { this.env.net.pending.splice(this.env.net.pending.indexOf(p), 1); p.done({ status: this.env.server.apply(p.req.account, 'seen', p.req.body) }); }
    this.car.carSync.receive(this.env.server.view(this.env.server.state(this.env.account)));
  }
  snapshot() {
    const f = `cases/${this.t0}.json`, d = this.env.phoneFs.exists(f) ? HandoffCodec.decode(this.env.phoneFs.read(f)) : null;
    const cf = this.car.crashFlow, rs = this.car.recStageNow();
    return {
      phoneStoredStatement: d ? (d.statement ?? null) : null,
      phoneCaseCount: this.phone.readCases().length,
      acknowledgementRequests: this.env.net.log.filter(r => r.what === 'seen' && r.from === 'phone').length,
      carRecordSaved: cf.dir != null && cf.record != null && cf.record.dir === cf.dir && this.car.fs.exists(cf.dir + '/log.sha256'),
      carServerAccepted: this.car.carSync.recordAcked === this.t0,
      carShowsFinalArrived: !!rs && rs.stage === RecStage.ARRIVED,
      carStatusText: rs ? rs.text : '(사고 없음)',
    };
  }
}

class QaRetryHarness extends QaVirtualHarness {
  constructor(fx) {
    super(qaEnv(fx, { mode: 'auto', latency: 0 }));
    this.car = this.env.newCar(); this.car.startService();
    this.t0 = fx.INCIDENT_EPOCH_SECONDS; this.dir = null; this.notes = [];
  }
  respondToUploadsWith(status) { this.env.net.uploadStatus = status; }
  saveAndStartUpload(doc) { this.dir = qaPrepareCarCase(this.car, doc); this.car.carSync.publish(); }
  restartApp() {
    this.car.shutdown();
    for (const k of this.env.rt.tasks) k.cancelled = true; // 옛 스레드·예약을 끝낸다(디스크·시계·요청 기록은 남김)
    this.env.rt.tasks = [];
    this.car = this.env.newCar(); this.car.startService();
  }
  snapshot() {
    const ups = this.env.net.log.filter(r => r.what === 'incident' && r.from === 'car');
    const st = this.env.server.state(this.env.account), sd = st.incident ? HandoffCodec.decode(st.incident) : null;
    const rs = this.car.recStageNow(), fs = this.car.fs;
    return {
      nowMs: this.nowMs(),
      attempts: ups.map(r => ({ startedAtMs: Math.round(r.at * 1000), document: qaDocOf(r) })),
      localStatement: this.dir && fs.exists(this.dir + '/statement.txt') ? fs.read(this.dir + '/statement.txt') : null,
      serverCaseCount: this.env.server.caseCount(this.env.account),
      serverStoredStatement: sd ? (sd.statement ?? null) : null,
      uploadQueueSize: Object.assign({ why: '현재 제품에는 일반 업로드 대기열이 없음(CarSync.publish 는 한 번 보내고 끝)' }, UNOBSERVABLE),
      carServerAccepted: this.car.carSync.recordAcked === this.t0,
      carShowsFinalArrived: !!rs && rs.stage === RecStage.ARRIVED,
    };
  }
}

class QaDeletionHarness extends QaVirtualHarness {
  constructor(fx) {
    super(qaEnv(fx, { mode: 'auto', latency: 0.01 }));
    this.car = this.env.newCar(); this.car.startService();
    this.t0 = fx.INCIDENT_EPOCH_SECONDS; this.dir = 'crashes/' + dirName(this.t0); this.notes = [];
  }
  seedConsentedCase(doc) { qaPrepareCarCase(this.car, doc); }
  startKindWriteAndPauseBeforeMkdir() { this.barrier = this.env.rt.installBarrier('kind-write:beforeMkdir'); this.car.crashFlow.chooseKind('OBJECT'); this.thread = this.car.crashFlow.lastKindWrite; }
  withdrawDriveConsent() { this.car.consent.set('DRIVE', false); }
  releaseKindWrite() { this.env.rt.releaseBarrier('kind-write:beforeMkdir'); }
  awaitKindWriteFinished() {
    const until = this.env.clock.t + 5;
    while (this.thread && !this.thread.done && this.env.clock.t < until) { this.env.rt.runReady(); if (!this.thread.done) this.env.rt.advanceTo(Math.min(until, this.env.clock.t + 0.05)); }
    if (this.thread && !this.thread.done) throw new Error('BLOCKED_ENVIRONMENT: 종류 저장 작업이 5초 안에 끝나지 않음');
  }
  restartApp() { this.car.shutdown(); for (const k of this.env.rt.tasks) k.cancelled = true; this.env.rt.tasks = []; this.car = this.env.newCar(); this.car.startService(); }
  snapshot() {
    const fs = this.car.fs;
    return {
      driveConsent: this.car.consent.allowed('DRIVE'),
      kindWritePausedBeforeMkdir: !!(this.barrier && this.barrier.reached && this.thread && this.thread.paused),
      kindWriteFinished: !!(this.thread && this.thread.done),
      incidentDirectoryExists: fs.isDir(this.dir),
      incidentFiles: fs.filesUnder(this.dir),
      visibleIncidentCount: this.car.pastCrashes().length,
      currentIncidentEpochSeconds: this.car.crashFlow.incident ? this.car.crashFlow.incident.t : null,
    };
  }
}

function qaGet(obs, field) {
  if (field === 'attempts.startedAtMs') return obs.attempts.map(a => a.startedAtMs);
  if (field === 'attempts.count') return obs.attempts.length;
  return obs[field];
}
function qaSame(a, b) {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => qaSame(x, b[i]));
  if (a && b && typeof a === 'object' && typeof b === 'object') { const ka = Object.keys(a), kb = Object.keys(b); return ka.length === kb.length && ka.every(k => qaSame(a[k], b[k])); }
  return false;
}
function qaShow(v) { return v && v.unobservable ? `관찰 불가 (${v.why})` : JSON.stringify(v); }

/** TC-01..04 실행. JUnit 처럼 첫 실패가 판정을 정한다(status·failure·junitStopsAt).
 *  keepGoing = 첫 실패 뒤의 단계도 계속 돌려 참고로 보여 준다(after: true). JUnit 은 거기서 멈춘다 */
function runHarnessCase(c, fx, { keepGoing = true } = {}) {
  const trace = [], result = { id: c.id, title: c.title, type: c.type, trace, status: 'PASS', failure: null, notes: [], drivable: false, failClass: null, junitStopsAt: null };
  const G = qaF32(fx.GRAVITY, 0);
  let failed = false, stop = false;
  const push = (kind, text, extra) => trace.push(Object.assign({ kind, text, after: failed && kind !== 'fail-first' }, extra || {}));
  const check = (ok, text, failure) => {
    if (ok) { push('pass', text); return; }
    if (!failed) {
      result.status = 'FAIL_REQUIREMENT'; result.failure = Object.assign({ text: failure.text }, failure); result.junitStopsAt = failure.step || null; result.failClass = c.failClass || null;
      trace.push({ kind: 'fail', text, after: false, first: true }); failed = true; if (!keepGoing) stop = true;
    } else push('fail', text);
  };
  const blocked = (status, msg) => {
    if (!failed) { result.status = status; result.failure = { text: msg }; trace.push({ kind: 'fail', text: msg, after: false, first: true }); }
    else push('note', '여기서 더 진행할 수 없음: ' + msg);
    stop = true;
  };
  try {
    if (c.type === 'sensor') {
      for (const row of c.rows) {
        if (stop) break;
        const h = new QaSensorHarness(fx);
        push('step', `${row.name}: 새 ImpactSensor · t=0ms 에 (0, G, 0)`);
        h.feed(0, 0, G, 0);
        const b = h.snapshot();
        check(Math.abs(b.correctedG) <= fx.G_TOLERANCE && b.impactCallbacksG.length === 0, `${row.name}: 초기 보정 ${b.correctedG.toFixed(7)}g, 콜백 ${b.impactCallbacksG.length}회 (기대 0g · 0회)`, { step: row.name, text: `${row.name}: 초기화가 0g·콜백 0회가 아님` });
        if (stop) break;
        const x = qaF32(row.x, G), y = qaF32(row.y, G), z = qaF32(row.z, G);
        h.feed(20, x, y, z);
        const r = h.snapshot();
        const ok = Math.abs(r.correctedG - row.expectedG) <= fx.G_TOLERANCE && r.impactCallbacksG.length === row.expectedCallbacks && r.impactCallbacksG.every(g => Math.abs(g - row.expectedG) <= fx.G_TOLERANCE);
        check(ok, `${row.name}: t=20ms 입력 (${x.toFixed(5)}, ${y.toFixed(5)}, ${z.toFixed(5)}) → 중력 추정 (${r.gravity.map(v => v.toFixed(5)).join(', ')}) · 보정 ${r.correctedG.toFixed(7)}g (기대 ${row.expectedG} ± ${fx.G_TOLERANCE}) · 콜백 ${r.impactCallbacksG.length}회 (기대 ${row.expectedCallbacks})`,
          { step: row.name, text: `${row.name}: 보정 g 또는 콜백 불일치`, expected: row.expectedG, actual: r.correctedG });
      }
      return result;
    }
    const H = { delivery: QaDeliveryHarness, retry: QaRetryHarness, deletion: QaDeletionHarness }[c.type];
    if (!H) { blocked('BLOCKED_ADAPTER', '이 종류의 시험 어댑터가 웹 모델에 없음'); return result; }
    const h = new H(fx);
    result.notes = h.notes;
    for (const s of c.steps) {
      if (stop) break;
      if (s.do) {
        const arg = s.doc ? fx.documents[s.doc] : (s.ms != null ? s.ms : s.status);
        push('step', `${s.do}(${s.doc || (s.ms != null ? s.ms + 'ms' : (s.status ?? ''))})`);
        try { h[s.do](arg); }
        catch (e) { blocked(e.message.startsWith('BLOCKED_ENVIRONMENT') ? 'BLOCKED_ENVIRONMENT' : 'BLOCKED_ADAPTER', `${s.do}: ${e.message}`); break; }
        if (h.env.rt.threadErrors.length) { push('note', '앱 작업 오류: ' + h.env.rt.threadErrors.map(x => `${x.name}: ${x.error.message}`).join('; ')); h.env.rt.threadErrors.length = 0; }
        continue;
      }
      const obs = h.snapshot();
      push('observe', `관찰 · ${s.label}`, { observation: obs });
      const checks = (s.expect || []).slice();
      if (s.deleted) checks.push({ field: 'driveConsent', equals: false, msg: '동의 상태' }, { field: 'incidentDirectoryExists', equals: false, msg: '사고 폴더 재생성 금지' },
        { field: 'incidentFiles', equals: [], msg: '파일이 남음' }, { field: 'visibleIncidentCount', equals: 0, msg: '화면 카드' }, { field: 'currentIncidentEpochSeconds', equals: null, msg: '현재 사고' });
      for (const k of checks) {
        if (stop) break;
        let actual = qaGet(obs, k.field), ok;
        if (k.field === 'attempts.allDocuments') { const want = fx.documents[k.equals]; actual = obs.attempts.map(a => a.document); ok = actual.length > 0 && actual.every(d => qaSame(d, want)); }
        else if (actual && actual.unobservable) ok = false;
        else if (k.nonEmpty) ok = Array.isArray(actual) && actual.length > 0;
        else ok = qaSame(actual, k.equals);
        const want = k.nonEmpty ? '비어 있지 않음' : (k.field === 'attempts.allDocuments' ? `모두 ${k.equals} 문서와 같음` : JSON.stringify(k.equals));
        check(ok, `[${s.label}] ${k.msg ? k.msg + ' · ' : ''}${k.field}: 기대 ${want}, 실제 ${qaShow(actual)}`,
          { step: s.label, text: `${s.label}: ${k.msg || k.field}`, field: k.field, expected: k.equals, actual: qaShow(actual) });
      }
    }
  } catch (e) { blocked('BLOCKED_ADAPTER', '웹 어댑터 오류: ' + String(e && e.message || e)); }
  if (result.status !== 'PASS' && c.gap) result.gap = c.gap;
  return result;
}

/** 자동 운전 시험 한 건. 브라우저는 tick 을 프레임마다, Node 는 끝날 때까지.
 *  warmupSec: 앱(서비스)을 켜 두고 기다린 뒤 장면을 시작한다. 기본 65초 = "시동 걸고 1분 넘게 지난 차"(실차에서 흔한 경우).
 *  Crash.kt 의 에어백 경로는 켠 지 60초 안에는 최근 사고 억제에 걸리므로, 시동 직후 사고는 DRV-12 처럼 따로 시험한다 */
class DrivingRun {
  constructor(world, c, fx) {
    this.c = c; this.fx = fx;
    const clock = new SimClock(1762900000), rt = new Runtime(clock), server = new SyncServer(), net = new Network(rt, server);
    rt.net = net;
    this.clock = clock; this.rt = rt; this.server = server; this.net = net;
    const prefs = new Map([['consent', new Map(CONSENT_ITEMS.map(k => [k, k !== 'CLOUD']))]]);
    this.app = new GwenApp({ clock, rt, fs: new VFS(), prefsStore: prefs, personaId: c.persona || 'guest', account: 'demo' });
    this.phone = new PhoneApp({ rt, fs: new VFS(), prefsStore: new Map(), account: 'demo', clock });
    this.lastIncident = null; this.maxG = 0; this.done = false; this.doneAt = null;
    this.app.onIncident = inc => { this.lastIncident = inc; };
    this.app.onIncidentChanged = () => { if (this.app.crashFlow.incident) this.lastIncident = this.app.crashFlow.incident; };
    this.app.startService();
    this.app.carSync.startPolling(); this.phone.startPolling();
    world.grid.g.forEach((list, k) => world.grid.g.set(k, list.filter(s => !s.temp)));
    this.sim = new DriveSim(world, clock, rt);
    this.sim.rng = mulberry32(c.seed || 1);
    const sc = SCENARIOS[c.scenario];
    if (!sc) throw new Error('없는 장면: ' + c.scenario);
    this.spec = sc(this.sim, c.params || {});
    this.sim.autopilot = this.spec.autopilot;
    // 준비 시간: 장면의 첫 상태(속도·기어)를 앱에 한 번 알리고 앱만 돌린다(10Hz 기록·폴링). 그동안 세상은 멈춰 있다
    this.warmupSec = c.warmupSec ?? 65;
    const p0 = this.sim.player;
    this.app.carState.update(s => Object.assign({}, s, { speedKph: Math.abs(p0.vf) * 3.6, gear: p0.gear, steerDeg: 0, brake: false, signal: 'none', airbag: false }));
    rt.advanceTo(this.warmupSec);
    this.sim.t0 = clock.t;
    this.sim.attach(this.app);
    this.t0 = clock.t;
  }
  /** 사고 기준 시각(시뮬레이터 초): 실제 첫 충돌 또는 앱이 잡은 사고 중 이른 쪽 */
  anchor() {
    const a = [];
    if (this.sim.firstHit != null) a.push(this.sim.firstHit);
    if (this.lastIncident) a.push(this.lastIncident.t - this.clock.epochBase);
    return a.length ? Math.min(...a) : null;
  }
  tick(n) {
    for (let i = 0; i < n && !this.done; i++) {
      this.sim.step();
      const w = this.app.watcher; if (w && w.impact.lastG > this.maxG) this.maxG = w.impact.lastG;
      const T = this.clock.t, s = this.sim, a = this.anchor();
      if (s.done && this.doneAt == null) this.doneAt = T;
      if (a != null) { if (T - a >= AFTER_SEC + 3) this.done = true; }        // 저장(10초)과 경위서까지 기다림
      else if (this.doneAt != null && T - this.doneAt >= 1.5) this.done = true;
      if (T - this.t0 >= (this.spec.maxSec || 40) + (a != null ? AFTER_SEC + 3 : 0)) this.done = true;
    }
    return this.done;
  }
  get elapsed() { return this.clock.t - this.t0; }
  observe() {
    const app = this.app, fs = app.fs, cf = app.crashFlow;
    const inc = cf.incident || this.lastIncident;
    const dirs = fs.list('crashes');
    const dir = dirs.length ? 'crashes/' + dirs[dirs.length - 1] : null;
    let recordSaved = false, facts = [], peakG = null;
    if (dir) {
      const need = ['log.json', 'log.sha256', 'impact.json', 'report.txt'].every(f => fs.exists(dir + '/' + f));
      recordSaved = need && sha256Hex(fs.read(dir + '/log.json')) === fs.read(dir + '/log.sha256').trim();
      const r = app.load(dir); facts = r ? r.facts : [];
      try { peakG = JSON.parse(fs.read(dir + '/impact.json')).peakG; } catch { }
    }
    const persona = Personas.byId(this.c.persona || 'guest');
    const steps = inc ? Guides.steps(inc.highway, persona, inc.severe, cf.kind, false, !inc.roadKnown, false).filter(s => !skipped(s.art, false, false, cf.kind === 'PERSON')).map(s => s.art) : [];
    const truth = this.sim.impacts.length ? this.sim.impacts[0] : null;
    return {
      detected: dirs.length > 0 || !!inc, reason: inc ? inc.reason : null, severe: inc ? inc.severe : null, impactDeg: inc ? inc.impactDeg : null, highway: inc ? inc.highway : null,
      kind: cf.kind, kindChosen: cf.kindChosen, notAccidentButton: inc ? !inc.severe : null, firstSteps: steps, recordSaved, facts, peakG, maxG: this.maxG,
      delay: (inc && this.sim.firstHit != null) ? inc.t - (this.clock.epochBase + this.sim.firstHit) : null, truthDv: truth ? truth.dv * 3.6 : null, truthDeg: truth ? truth.deg : null,
      airbagSynth: this.sim.airbagFired, phoneCases: this.phone.readCases().length, recText: app.recStageNow() ? app.recStageNow().text : null,
    };
  }
  evaluate() {
    const e = this.c.expect, o = this.observe(), items = [];
    const add = (key, expected, actual, ok, note) => items.push({ key, expected, actual, ok, note });
    const angle = (a, b) => Math.abs(((a - b + 540) % 360) - 180);
    if ('detected' in e) add('사고 감지', e.detected ? '감지됨' : '감지 안 됨', o.detected ? `감지됨 (${o.reason})` : '감지 안 됨', o.detected === e.detected);
    if (o.detected && e.detected) {
      if ('severe' in e) add('큰 사고(에어백)', e.severe ? '예' : '아니오', o.severe ? '예' : '아니오', o.severe === e.severe);
      if (e.impactDeg) add('충격 방향', `${e.impactDeg.center}° ± ${e.impactDeg.tol}°`, o.impactDeg == null ? '없음' : `${o.impactDeg.toFixed(0)}° (${claimSide(o.impactDeg)})`, o.impactDeg != null && angle(o.impactDeg, e.impactDeg.center) <= e.impactDeg.tol);
      if ('highway' in e) add('고속도로 판단', e.highway ? '고속도로' : '시내', o.highway ? '고속도로' : '시내', o.highway === e.highway);
      if ('kind' in e) add('대상 추정', KIND_LABEL[e.kind], `${KIND_LABEL[o.kind]}${o.kindChosen ? ' (운전자 선택)' : ''}`, o.kind === e.kind);
      if ('notAccidentButton' in e) add("'사고가 아니에요' 버튼", e.notAccidentButton ? '보임' : '안 보임', o.notAccidentButton ? '보임' : '안 보임', o.notAccidentButton === e.notAccidentButton);
      if (e.firstSteps) add('안내 첫 단계', e.firstSteps.join(' → '), o.firstSteps.slice(0, e.firstSteps.length).join(' → '), qaSame(o.firstSteps.slice(0, e.firstSteps.length), e.firstSteps));
      if ('recordSaved' in e) add('기록 저장·지문', '저장 + SHA-256 일치', o.recordSaved ? '저장 + 일치' : '없음/불일치', o.recordSaved === e.recordSaved);
      if (e.factsInclude) add('사실 목록', e.factsInclude.join(', ') + ' 포함', e.factsInclude.filter(k => o.facts.some(f => f.includes(k))).join(', ') || '없음', e.factsInclude.every(k => o.facts.some(f => f.includes(k))));
    }
    const ok = items.every(i => i.ok);
    return { id: this.c.id, title: this.c.title, type: 'driving', status: ok ? 'PASS' : 'FAIL_REQUIREMENT', items, obs: o, basis: this.c.basis, drivable: true };
  }
}
