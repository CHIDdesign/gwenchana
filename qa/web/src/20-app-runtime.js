// ===== 20-app-runtime.js — 앱이 도는 환경(시계·작업·파일·서버·휴대폰)과 상태를 가진 부분의 JS 이식 =====
// 안드로이드 스레드·파일·HTTP 대신 "가상 시계 + 협력형 스레드(제너레이터) + 가상 파일 + 메모리 서버"를 쓴다.
// 그래서 시험이 실제 sleep 없이 시각을 앞당기고(advanceTo), 특정 지점에서 작업을 멈출 수 있다(barrier).

class SimClock {
  constructor(epochBase) { this.epochBase = epochBase; this.t = 0; }
  now() { return this.epochBase + this.t; }                 // System.currentTimeMillis()/1000.0 자리
  nowMs() { return Math.floor((this.epochBase + this.t) * 1000); }
  nanos() { return Math.round(this.t * 1e9); }               // SystemClock.elapsedRealtimeNanos() 자리
}

/** 예약 작업 + 협력형 스레드. yield {sleep:ms} | {net:req} | {barrier:name} | {cb:fn} 로 기다린다 */
class Runtime {
  constructor(clock) { this.clock = clock; this.tasks = []; this.seq = 0; this.barriers = new Map(); this.net = null; this.threadErrors = []; }
  at(due, fn, label = '') { const k = { due, fn, label, seq: this.seq++, cancelled: false }; this.tasks.push(k); return k; }
  after(dt, fn, label) { return this.at(this.clock.t + dt, fn, label); }
  nextDue(limitT) {
    let best = null;
    for (const k of this.tasks) if (!k.cancelled && k.due <= limitT + 1e-9 && (!best || k.due < best.due || (k.due === best.due && k.seq < best.seq))) best = k;
    return best;
  }
  take(k) { this.tasks.splice(this.tasks.indexOf(k), 1); if (!k.cancelled) k.fn(); }
  /** 지금 시각까지 실행할 수 있는 일만 (시각을 앞당기지 않는다) */
  runReady(limit = 200000) {
    for (let n = 0; n < limit; n++) { const k = this.nextDue(this.clock.t); if (!k) return; this.take(k); }
    throw new Error('runReady: 작업이 끝나지 않음(무한 반복?)');
  }
  /** 목표 시각까지, 예약된 일을 시각 순서대로 */
  advanceTo(t, limit = 2000000) {
    for (let n = 0; n < limit; n++) {
      const k = this.nextDue(t);
      if (!k) { this.clock.t = Math.max(this.clock.t, t); return; }
      if (k.due > this.clock.t) this.clock.t = k.due;
      this.take(k);
    }
    throw new Error('advanceTo: 작업이 끝나지 않음');
  }
  spawn(name, genFn) {
    const th = { name, gen: genFn(), done: false, paused: false, error: null };
    this.at(this.clock.t, () => this.resume(th, undefined), 'start ' + name);
    return th;
  }
  resume(th, value) {
    if (th.done) return;
    let r;
    try { r = th.gen.next(value); } catch (e) { th.done = true; th.error = e; this.threadErrors.push({ name: th.name, error: e }); return; }
    if (r.done) { th.done = true; return; }
    const w = r.value || {};
    const again = v => this.at(this.clock.t, () => this.resume(th, v), th.name);
    if (w.sleep != null) this.after(w.sleep / 1000, () => this.resume(th, undefined), th.name + ' sleep');
    else if (w.barrier) {
      const b = this.barriers.get(w.barrier);
      if (b && !b.released) { th.paused = true; b.reached = true; b.waiting = th; } else again(undefined);
    } else if (w.net) this.net.send(w.net, resp => again(resp));
    else if (w.cb) w.cb(v => again(v));
    else again(undefined);
  }
  installBarrier(name) { const b = { released: false, reached: false, waiting: null }; this.barriers.set(name, b); return b; }
  releaseBarrier(name) {
    const b = this.barriers.get(name);
    if (!b) return;
    b.released = true;
    if (b.waiting) { const th = b.waiting; b.waiting = null; th.paused = false; this.at(this.clock.t, () => this.resume(th, undefined), th.name); }
  }
}

/** Executors.newSingleThreadExecutor 자리: 한 줄로 세워 순서대로 */
class SerialExecutor {
  constructor(rt, name) { this.rt = rt; this.name = name; this.q = []; this.running = false; }
  execute(job) {
    this.q.push(job);
    if (this.running) return;
    this.running = true;
    const self = this;
    this.rt.spawn(this.name, function* () {
      while (self.q.length) { const j = self.q.shift(); try { yield* j(); } catch (e) { self.rt.threadErrors.push({ name: self.name, error: e }); } }
      self.running = false;
    });
  }
}

/** 기기 저장소 자리. 앱을 다시 켜도(restart) 같은 객체를 넘기면 남는다 */
class VFS {
  constructor() { this.files = new Map(); this.dirs = new Set(); this.mtimes = new Map(); this.failNext = null; }
  parent(p) { const i = p.lastIndexOf('/'); return i < 0 ? '' : p.slice(0, i); }
  mkdirs(p) { const parts = p.split('/'); for (let i = 1; i <= parts.length; i++) this.dirs.add(parts.slice(0, i).join('/')); return true; }
  isDir(p) { return this.dirs.has(p); }
  exists(p) { return this.files.has(p) || this.dirs.has(p); }
  write(p, text, nowMs = 0) {
    const par = this.parent(p);
    if (par && !this.dirs.has(par)) throw new Error(`FileNotFoundException: ${p} (상위 폴더 없음)`);
    if (this.failNext && this.failNext(p)) { this.failNext = null; throw new Error(`IOException: 쓰기 실패 주입 ${p}`); }
    this.files.set(p, String(text)); this.mtimes.set(p, nowMs);
  }
  writeAtomic(p, text, nowMs) { const tmp = p + '.tmp'; this.write(tmp, text, nowMs); if (!this.rename(tmp, p)) throw new Error('rename failed: ' + p); }
  read(p) { if (!this.files.has(p)) throw new Error(`FileNotFoundException: ${p}`); return this.files.get(p); }
  rename(a, b) {
    if (!this.files.has(a)) return false;
    const par = this.parent(b);
    if (par && !this.dirs.has(par)) return false;
    this.files.set(b, this.files.get(a)); this.mtimes.set(b, this.mtimes.get(a)); this.files.delete(a); this.mtimes.delete(a);
    return true;
  }
  delete(p) { return this.files.delete(p) || this.dirs.delete(p); }
  deleteRecursively(p) {
    for (const k of [...this.files.keys()]) if (k === p || k.startsWith(p + '/')) { this.files.delete(k); this.mtimes.delete(k); }
    for (const d of [...this.dirs]) if (d === p || d.startsWith(p + '/')) this.dirs.delete(d);
  }
  list(dir) {
    const pre = dir ? dir + '/' : '', out = new Set();
    for (const k of [...this.files.keys(), ...this.dirs]) if (k.startsWith(pre)) { const rest = k.slice(pre.length); if (rest) out.add(rest.split('/')[0]); }
    return [...out].sort();
  }
  filesUnder(dir) { return [...this.files.keys()].filter(k => k.startsWith(dir + '/')).map(k => k.slice(dir.length + 1)).sort(); }
  mtime(p) { return this.mtimes.get(p) ?? 0; }
}

/** game/bridge/sync_server.py 의 사고 동기화 부분 (계정당 최신 사고 1건, 메모리). 두 차 연결은 이 웹 버전에 없음 */
class SyncServer {
  constructor() { this.accounts = new Map(); this.waiters = []; this.oneAccount = true; }
  key(a) { return (this.oneAccount && !a.startsWith('other') && a !== 'family') ? 'demo' : a; }
  state(a) {
    const k = this.key(a);
    if (!this.accounts.has(k)) this.accounts.set(k, { version: 0, incident: null, t: null, step: null, stepBy: null, closed: false, wiped: false, notAccident: false, seen: null });
    return this.accounts.get(k);
  }
  view(s) { return { version: s.version, incident: s.incident, step: s.step, stepBy: s.stepBy, closed: s.closed, wiped: s.wiped, notAccident: s.notAccident, seen: s.seen }; }
  apply(a, what, body) {
    const s = this.state(a);
    if (what === 'wipe') Object.assign(s, { incident: null, t: null, step: null, stepBy: null, closed: false, wiped: true, notAccident: false, seen: null });
    else if (what === 'incident') {
      const t = Number(JSON.parse(body).t);
      if (s.t !== t) Object.assign(s, { t, step: null, stepBy: null, closed: false, notAccident: false, seen: null });
      s.incident = body; s.wiped = false;
    } else {
      const o = JSON.parse(body);
      if (Number(o.t) !== s.t) return 200; // 옛 사고에 대한 늦은 요청(서버는 200, 바뀐 것 없음)
      if (what === 'step') Object.assign(s, { step: o.index, stepBy: o.by });
      else if (what === 'close') { s.closed = true; s.notAccident = !!o.notAccident; }
      else if (what === 'reopen') { s.closed = false; s.notAccident = false; }
      else if (what === 'seen') s.seen = Number(o.t);
      else return 400;
    }
    s.version++;
    const k = this.key(a);
    for (const w of [...this.waiters]) if (w.key === k && s.version > w.since) { this.waiters.splice(this.waiters.indexOf(w), 1); w.cb(this.view(s)); }
    return 200;
  }
  poll(a, since, cb) {
    const s = this.state(a);
    if (s.version > since) { cb(this.view(s)); return null; }
    const w = { key: this.key(a), since, cb };
    this.waiters.push(w);
    return w;
  }
  dropWaiter(w) { const i = this.waiters.indexOf(w); if (i >= 0) this.waiters.splice(i, 1); return i >= 0; }
  caseCount(a) { return this.state(a).incident ? 1 : 0; }
}

/** 네트워크 자리. auto = 메모리 서버가 latency 뒤 답함, manual = 시험이 직접 응답(요청을 붙잡아 둠) */
class Network {
  constructor(rt, server) { this.rt = rt; this.server = server; this.mode = 'auto'; this.latency = 0.05; this.pending = []; this.log = []; this.uploadStatus = null; this.online = true; }
  send(req, done) {
    req.at = this.rt.clock.t;
    if (req.what !== 'poll') this.log.push(req);
    if (req.what === 'poll') {
      if (this.mode === 'manual' || !this.online) { this.rt.after(2, () => done({ status: 0 })); return; }
      let finished = false;
      const w = this.server.poll(req.account, req.since, st => { if (finished) return; finished = true; this.rt.after(this.latency, () => done({ status: 200, state: st }), 'poll answer'); });
      if (w) this.rt.after(25, () => { if (finished) return; finished = true; this.server.dropWaiter(w); done({ status: 200, state: this.server.view(this.server.state(req.account)) }); }, 'poll timeout');
      return;
    }
    if (this.mode === 'manual') { this.pending.push({ req, done }); return; }
    this.rt.after(this.latency, () => done(this.handle(req)), 'net ' + req.what);
  }
  handle(req) {
    if (!this.online) return { status: 0 };
    if (req.what === 'incident' && this.uploadStatus != null && this.uploadStatus !== 200) return { status: this.uploadStatus };
    return { status: this.server.apply(req.account, req.what, req.body) };
  }
}

/** shared/HandoffData.kt HandoffCodec 의 웹 사본(JSON). 차·휴대폰이 같은 모양을 쓴다 */
const HandoffCodec = {
  encode(d) { return JSON.stringify(d); },
  decode(s) { try { const o = JSON.parse(s); return (o && typeof o.t === 'number') ? o : null; } catch { return null; } },
};

/** app/.../Handoff.kt Handoff.data */
function handoffData(app, t, facts, hash, kind, chosen, impactDeg, highway, severe, p, record, statement, pre) {
  const cf = app.crashFlow;
  const steps = Guides.steps(highway, p, severe, kind, cf.hurt, cf.incident?.roadKnown === false, cf.near);
  const idx = steps.findIndex(s => s.art === 'HANDOFF' || (highway && s.art === 'WALK'));
  return {
    t, kind: KIND_LABEL[kind], kindChosen: chosen, impactDeg, highway, hash: hash ? hash.slice(0, 16) : null, facts,
    steps: steps.map(s => ({ art: s.art, title: s.title, body: s.body, call: s.call, callName: s.callName, decline: s.decline, next: s.next, call2: s.call2 })),
    phoneFrom: idx < 0 ? steps.length : idx,
    shots: PhotoGuide.shots(impactDeg, kind, highway).map(s => ({ art: s.art, title: s.title })),
    contacts: [p.insurer, p.rentalCompany, p.guardian].filter(Boolean),
    samples: (record ? record.log : pre).filter(s => s.t >= t - 10 && s.t <= t + 5),
    report: record ? record.report : null, statement, mode: p.mode, guardian: p.guardian, rental: p.rental && p.rentalCompany != null,
    lampsRecorded: app.carState.telltalesSeen, near: cf.near, moved: cf.moved, hurt: cf.hurt, severe, stopped: cf.halted,
  };
}

// ── app/.../Sources.kt CarState ──
class CarState {
  constructor(app) { this.app = app; this.now = Sample(0); this.hazard = false; this.telltalesSeen = false; this._limit = 0; this._limitAt = -1e15; }
  update(f) { this.now = f(this.now); }
  get roadLimitKph() { return this.app.clock.nowMs() - this._limitAt > 10000 ? 0 : this._limit; }
  set roadLimitKph(v) { this._limit = v; this._limitAt = this.app.clock.nowMs(); }
}

const IMPACT_G = 2.5;
// ── app/.../Crash.kt ImpactSensor: 중력은 느린 평균으로 빼고 세 축 크기만 본다 ──
class ImpactSensor {
  constructor(app, onImpact) {
    this.app = app; this.onImpact = onImpact; this.gravity = [0, 0, 0]; this.primed = false; this.recent = [];
    this.gyroRate = null; this.gyroAlive = false; this.gyroAngle = 0; this.gyroAt = 0; this.readAngle = 0; this.readAt = 0;
    this.available = true; this.lastG = 0; this.lastD = [0, 0, 0];
  }
  yawRate() {
    if (this.gyroRate == null) return null;
    const now = this.app.clock.nanos();
    const angle = this.gyroAngle + this.gyroRate * (now - this.gyroAt) / 1e9;
    const mean = this.readAt === 0 ? this.gyroRate : (angle - this.readAngle) / ((now - this.readAt) / 1e9);
    this.readAngle = angle; this.readAt = now;
    return mean;
  }
  onGyro(rate, at) { if (this.gyroRate != null) this.gyroAngle += this.gyroRate * (at - this.gyroAt) / 1e9; this.gyroRate = rate; this.gyroAt = at; }
  peak(from, to) {
    let best = null;
    for (const r of this.recent) if (r[0] >= from && r[0] <= to && (!best || r[1] > best[1])) best = r;
    return best ? [best[1], best[2]] : [0, null];
  }
  /** type 'accel' | 'gyro', values = 센서 값 3개(Float 로 바꿔 받음), atNs = 이벤트 시각 */
  onSensorChanged(type, values, atNs) {
    if (type === 'gyro') {
      const r = Math.fround(values[1]);
      if (Math.abs(r) > 0.005) this.gyroAlive = true;
      if (this.gyroAlive) this.onGyro(r, atNs);
      return;
    }
    const v = [Math.fround(values[0]), Math.fround(values[1]), Math.fround(values[2])];
    if (!this.primed) { for (let i = 0; i < 3; i++) this.gravity[i] = v[i]; this.primed = true; }
    const d = [0, 0, 0];
    for (let i = 0; i < 3; i++) { this.gravity[i] += (v[i] - this.gravity[i]) * 0.02; d[i] = v[i] - this.gravity[i]; }
    const g = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) / GRAVITY_EARTH;
    this.lastG = g; this.lastD = d;
    const now = this.app.clock.nowMs() / 1000;
    this.recent.push([now, g, impactFromDeg(d[0], d[2])]); // RIGHT_AXIS = x, FORWARD_AXIS = z, 부호 +1
    while (this.recent[0][0] < now - 3) this.recent.shift();
    if (g >= IMPACT_G) this.onImpact(g);
  }
}

// ── app/.../Crash.kt CrashFlow (지금 진행 중인 사고) ──
class CrashFlowState {
  constructor(app) {
    this.app = app; this.incident = null; this.record = null; this.status = '주행 기록 중';
    this.hurt = false; this.halted = false; this.saveFailed = null; this.near = false; this.moved = false; this.guideStep = 0;
    this.kind = 'UNKNOWN'; this.dir = null; this.kindChosen = false; this.progress = 0; this.closed = 0; this.screenOpen = false;
  }
  setIncident(f) { this.incident = f(this.incident); }
  /** 운전자가 고른 사고 종류를 kind.txt 로 (스레드). 시험용 정지 지점: barrier 'kind-write:beforeMkdir' */
  chooseKind(k, byDriver = true) {
    if (byDriver) this.kindChosen = true;
    this.kind = k;
    this.app.carSync.publish();
    const d = this.dir, app = this.app;
    const text = k + (this.kindChosen ? '' : ' guess');
    if (d) this.lastKindWrite = app.rt.spawn('kind-write', function* () {
      yield { barrier: 'kind-write:beforeMkdir' };
      try {
        if (VARIANT.fixed) {
          // 수정본: 지운 사고 폴더는 다시 만들지 않는다. 지워졌으면 끝내고, 쓸 때는 삭제와 같은 잠금(Records) 안에서 다시 확인
          if (!app.fs.exists(d) && app.crashFlow.dir !== d) return;
          app.records.write(d, () => app.fs.write(d + '/kind.txt', text, app.clock.nowMs()));
        } else { app.fs.mkdirs(d); app.fs.write(d + '/kind.txt', k + (app.crashFlow.kindChosen ? '' : ' guess'), app.clock.nowMs()); } // 공개본: 지운 폴더도 다시 만든다(TC-04 실패)
      } catch (e) { app.log('종류 기록 실패: ' + e.message); }
    });
  }
}

// ── app/.../Consent.kt Records (수정본): 사고 폴더 쓰기와 삭제의 잠금 ──
// 동의 철회·"모두 지우기" 뒤 늦게 끝난 쓰기가 지운 폴더를 다시 만들지 않게. JS 는 한 줄로 돌아 잠금 자체는 필요 없고 확인만 옮긴다
class Records {
  constructor(app) { this.app = app; }
  /** 쓸 수 있으면 block 결과, 지워졌거나 동의가 꺼져 있으면 null(아무것도 쓰지 않음) */
  write(dir, block) {
    const app = this.app, fs = app.fs;
    if (!app.consent.allowed('DRIVE')) return null;
    if (!fs.isDir(dir) && (app.crashFlow.dir !== dir || !fs.mkdirs(dir))) return null;
    return block();
  }
}

// ── app/.../Consent.kt ──
const CONSENT_ITEMS = ['DRIVE', 'LOCATION', 'VOICE', 'CAMERA', 'CLOUD'];
function consentExpired(name, lastModifiedMs, short, nowMs) {
  const t = parseDirName(name);
  return nowMs - (t != null ? t * 1000 : lastModifiedMs) > (short ? 7 : 30) * 86400000;
}
class Consent {
  constructor(app) { this.app = app; this.on = new Map(); }
  init() { const p = this.app.prefs('consent'); for (const it of CONSENT_ITEMS) this.on.set(it, p.get(it) ?? false); this.app.carSync.init(); this.app.carSync.flushPending(); }
  allowed(it) { return this.on.get(it) ?? false; }
  set(it, on) {
    const was = this.on.get(it) === true;
    this.on.set(it, on); this.app.prefs('consent').set(it, on);
    if (on || !was) return;
    this.deleteRecords();
    if (it === 'DRIVE') this.app.stopService();
  }
  deleteRecords() {
    const cf = this.app.crashFlow;
    cf.dir = null; // 먼저 비운다: 대기 중인 저장이 폴더를 다시 만들지 않게 (종류 기록 스레드는 이미 폴더를 잡고 있을 수 있음)
    this.app.fs.deleteRecursively('crashes');
    cf.record = null; cf.setIncident(() => null); cf.closed++;
    this.app.carSync.wipe();
  }
  cleanup(nowMs) {
    const fs = this.app.fs;
    for (const name of fs.list('crashes')) {
      const d = 'crashes/' + name, short = fs.exists(d + '/false_alarm') || fs.exists(d + '/practice');
      if (consentExpired(name, fs.mtime(d + '/log.json'), short, nowMs)) fs.deleteRecursively(d);
    }
  }
}

// ── app/.../Handoff.kt CarSync ──
class CarSync {
  constructor(app) { this.app = app; this.ok = false; this.recordAcked = null; this.seen = null; this.send = new SerialExecutor(app.rt, 'car-sync'); this.retrying = false; this.inited = false; }
  init() { this.inited = true; }
  allowed() { return this.inited && !!this.app.syncUrl && this.app.consent.allowed('DRIVE'); }
  arrived(t) { return this.seen != null && Math.abs(this.seen - t) < 0.001; }
  account() { return this.app.account; }
  pending() { return this.app.prefs('sync_wipe'); }
  closes() { return this.app.prefs('sync_close'); }
  submit(f) {
    if (!this.allowed()) return;
    const self = this;
    this.send.execute(function* () { yield* self.tryPending(); let ok = false; try { ok = yield* f(); } catch (e) { ok = false; } self.ok = !!ok; });
  }
  /** 지금 사고 상태를 서버로(사고 직후·기록 저장·종류 변경 때) */
  publish() {
    const self = this;
    this.submit(function* () {
      const app = self.app, cf = app.crashFlow, inc = cf.incident;
      if (!inc) return self.ok;
      const r = (cf.record && cf.record.dir === cf.dir) ? cf.record : null;
      const hash = r && app.fs.exists(r.dir + '/log.sha256') ? app.fs.read(r.dir + '/log.sha256').trim() : null;
      const statement = r && app.fs.exists(r.dir + '/statement.txt') ? app.fs.read(r.dir + '/statement.txt') : null;
      const d = handoffData(app, inc.t, r ? r.facts : [], hash, cf.kind, cf.kindChosen, inc.impactDeg, inc.highway, inc.severe, app.persona, r, statement, inc.preLog);
      const resp = yield { net: { what: 'incident', account: self.account(), body: HandoffCodec.encode(d), from: 'car' } };
      const sent = resp && resp.status === 200;
      if (sent && hash != null) self.recordAcked = inc.t;
      return sent;
    });
  }
  step(t, index) { const self = this; this.submit(function* () { const r = yield { net: { what: 'step', account: self.account(), body: JSON.stringify({ t, index, by: 'car' }), from: 'car' } }; return r.status === 200; }); }
  close(t, notAccident = false) { if (!this.allowed()) return; this.closes().set(this.account(), `${t} ${notAccident}`); this.flushPending(); }
  wipe() { this.closes().clear(); this.pending().set(this.account(), true); this.flushPending(); }
  flushPending() { const self = this; this.send.execute(function* () { yield* self.tryPending(); }); }
  *tryPending() {
    for (const a of [...this.pending().keys()]) { const r = yield { net: { what: 'wipe', account: a, body: '{}', from: 'car' } }; if (r.status === 200) this.pending().delete(a); }
    for (const [a, v] of [...this.closes().entries()]) {
      const [t, na] = v.split(' ');
      const r = yield { net: { what: 'close', account: a, body: JSON.stringify({ t: Number(t), notAccident: na === 'true' }), from: 'car' } };
      if (r.status === 200) this.closes().delete(a);
    }
    if ((this.pending().size || this.closes().size) && !this.retrying) {
      this.retrying = true;
      this.app.rt.after(30, () => { this.retrying = false; this.flushPending(); }, 'car-sync retry 30s');
    }
  }
  /** 서버 상태를 받음 (CarSync.followSeen / CrashActivity.followPhone) */
  receive(s) { this.ok = true; this.seen = s.seen; }
  startPolling() {
    const self = this, app = this.app;
    app.rt.spawn('car-poll', function* () {
      let since = 0;
      while (app.alive) {
        if (!self.allowed() || !app.crashFlow.incident) { yield { sleep: 2000 }; continue; }
        const r = yield { net: { what: 'poll', account: self.account(), since } };
        if (!r || r.status !== 200) { self.ok = false; yield { sleep: 2000 }; continue; }
        since = r.state.version; self.receive(r.state);
        if (r.state.incident == null && app.crashFlow.incident) { self.publish(); continue; } // 서버가 다시 켜져 비었으면 다시 올린다
        app.onServerState && app.onServerState(r.state);
      }
    });
  }
}

const REPEAT_SEC = 60, SUDDEN_DROP_KPH = 15, MANUAL = 'manual', PHOTO_LABEL = '충돌 직후';
const isHalted = s => s.speedKph < 1 || s.gear === 'P';
const logToJson = log => JSON.stringify(log.map(s => ({ t: s.t, speed: s.speedKph, steer: s.steerDeg, brake: s.brake, signal: s.signal, gear: s.gear, cruise: s.cruise, airbag: s.airbag, lat: s.lat, lon: s.lon, lamps: s.lamps, yawRate: s.yawRate })));
const logFromJson = text => JSON.parse(text).map(o => Sample(o.t, { speedKph: o.speed ?? 0, steerDeg: o.steer ?? 0, brake: !!o.brake, signal: o.signal ?? 'none', gear: o.gear ?? 'D', cruise: !!o.cruise, lat: o.lat, lon: o.lon, airbag: !!o.airbag, lamps: o.lamps ?? '', yawRate: o.yawRate ?? null }));

// ── app/.../Crash.kt CrashWatcher: 10Hz 기록, 사고 감지, 10초 뒤 전 30초 + 후 10초 저장 ──
class CrashWatcher {
  constructor(app, onLeftReverse, onIncident) {
    this.app = app; this.onLeftReverse = onLeftReverse; this.onIncident = onIncident;
    this.recorder = new Recorder(60);
    this.impact = new ImpactSensor(app, g => this.trigger(`충격 감지 (${f1(g)}g)`, false));
    this.running = false; this.pendingSince = null; this.peakAtCrash = [0, null]; this.latestT = 0; this.loopTask = null;
  }
  start() {
    this.running = true;
    const app = this.app, cs = app.carState, cf = app.crashFlow;
    const started = app.clock.nowMs() / 1000;
    let prev = cs.now, heldSince = 0, heldSec = 0;
    const loop = () => {
      if (!this.running) return;
      const s = Object.assign({}, cs.now, { t: app.clock.nowMs() / 1000, yawRate: this.impact.yawRate() });
      this.recorder.add(s);
      const before = this.recorder.between(s.t - 0.35, s.t - 0.25)[0];
      if (s.speedKph !== prev.speedKph) { heldSec = s.t - heldSince; heldSince = s.t; }
      if (prev.gear === 'R' && s.gear !== 'R') this.onLeftReverse();
      if (cf.incident && !cf.halted && isHalted(s)) { cf.halted = true; app.carSync.publish(); }
      if (s.airbag && !prev.airbag && s.t - started > 3 && ((VARIANT.fixed && this.pendingSince != null) || s.t - started > REPEAT_SEC || s.t - this.lastIncidentT() > REPEAT_SEC)) this.trigger('에어백 전개', true); // 수정본: 저장 대기 중이면 언제든 큰 사고로 올림
      else if (before && suddenDrop(before.speedKph - s.speedKph, heldSec, SUDDEN_DROP_KPH) && (!this.impact.available || this.impact.peak(s.t - 1.0, s.t)[0] >= 1.5))
        this.trigger(`속도 급감 (${f0(before.speedKph)}→${f0(s.speedKph)} km/h)`, false);
      if (this.pendingSince != null && s.t - this.pendingSince >= AFTER_SEC) {
        const t = this.pendingSince;
        try { this.finish(t); } catch (e) { app.log('사고 기록 저장 실패: ' + e.message); cf.saveFailed = t; }
      }
      prev = s;
      this.loopTask = app.rt.after(0.1, loop, 'recorder');
    };
    this.loopTask = app.rt.after(0.1, loop, 'recorder');
  }
  stop() { this.running = false; if (this.loopTask) this.loopTask.cancelled = true; }
  dirFor(t) { return 'crashes/' + dirName(t); }
  lastIncidentT() {
    if (this.latestT > 0) return this.latestT;
    const names = this.app.fs.list('crashes');
    if (!names.length) return 0;
    return parseDirName(names[names.length - 1]) ?? 0;
  }
  isHighway(t) {
    const limit = this.app.carState.roadLimitKph;
    if (limit > 0) return limit >= 80;
    const v = this.recorder.between(t - 30, t).map(s => s.speedKph);
    return v.length > 0 && v.reduce((a, b) => a + b, 0) / v.length >= 80;
  }
  saveImpact(dir, t, peakG, deg) { this.app.fs.write(dir + '/impact.json', JSON.stringify({ t, peakG, deg: deg ?? null }), this.app.clock.nowMs()); }
  /** practice = 연습 사고 신호, manual = 운전자가 홈에서 직접 시작 */
  trigger(reason, severe, practice = false, manual = false) {
    const app = this.app, cf = app.crashFlow, cs = app.carState, fs = app.fs;
    if (this.pendingSince != null && cf.incident) {
      if (severe) { cf.setIncident(cur => (cur && !cur.severe) ? Object.assign({}, cur, { severe: true, reason }) : cur); app.carSync.publish(); app.onIncidentChanged && app.onIncidentChanged(); }
      return;
    }
    const t = app.clock.nowMs() / 1000;
    this.pendingSince = t; this.latestT = t;
    const pre = this.recorder.between(t - BEFORE_SEC, t);
    const peakNow = this.impact.peak(t - 1.5, t);
    const deg = peakNow[0] >= 1.0 ? peakNow[1] : null;
    this.peakAtCrash = peakNow;
    const self = this, dir0 = this.dirFor(t);
    if (VARIANT.fixed) cf.dir = dir0; // 수정본: 저장 스레드보다 먼저. 폴더는 "지금 사고 폴더"일 때만 만든다
    app.rt.spawn('pre-save', function* () {
      try {
        const dir = dir0;
        const body = () => {
          if (practice) fs.write(dir + '/practice', '', app.clock.nowMs());
          if (manual) fs.write(dir + '/' + MANUAL, '', app.clock.nowMs());
          fs.writeAtomic(dir + '/log.json', logToJson(pre), app.clock.nowMs());
          fs.write(dir + '/log.sha256', sha256Hex(fs.read(dir + '/log.json')), app.clock.nowMs());
          self.saveImpact(dir, t, peakNow[0], deg);
        };
        if (VARIANT.fixed) app.records.write(dir, body); else { fs.mkdirs(dir); body(); }
      } catch (e) { app.log('충돌 전 기록 저장 실패: ' + e.message); }
    });
    app.rt.spawn('repeak', function* () {
      yield { sleep: 1000 };
      self.peakAtCrash = self.impact.peak(t - 1.5, t + 1.0);
      const [g, d] = self.peakAtCrash;
      cf.setIncident(cur => (cur && cur.t === t && g >= 1.0) ? Object.assign({}, cur, { impactDeg: d }) : cur);
      if (g >= 1.0) self.suggest(t, inferKind(self.recorder.between(t - 10, t), t, d));
      app.onIncidentChanged && app.onIncidentChanged();
    });
    const guess = inferKind(this.recorder.between(t - 10, t), t, deg);
    const incident = { t, reason, severe, highway: this.isHighway(t), impactDeg: deg, preLog: this.recorder.between(t - 10, t), suggestWhy: guess ? guess.why : null, roadKnown: cs.roadLimitKph > 0 };
    if (!VARIANT.fixed) cf.dir = this.dirFor(t); cf.kindChosen = false; cf.hurt = false; cf.halted = isHalted(cs.now); cf.saveFailed = null; cf.near = false; cf.moved = false; cf.guideStep = 0;
    cf.kind = guess ? guess.kind : 'UNKNOWN'; cf.progress = 0.05; cf.setIncident(() => incident); cf.record = null; cf.status = `사고 감지: ${reason}`;
    app.log(`사고 감지: ${reason}`);
    this.onIncident(incident);
  }
  suggest(t, g) {
    const cf = this.app.crashFlow;
    if (!g || cf.kindChosen || !cf.incident || cf.incident.t !== t) return;
    cf.setIncident(cur => (cur && cur.t === t) ? Object.assign({}, cur, { suggestWhy: g.why }) : cur);
    cf.chooseKind(g.kind, false);
  }
  cancel() {
    const cf = this.app.crashFlow;
    this.pendingSince = null; this.latestT = 0; this.recorder.clear();
    if (cf.incident) this.app.carSync.close(cf.incident.t, true);
    cf.setIncident(() => null); cf.closed++;
  }
  finish(t) {
    const app = this.app, cf = app.crashFlow, fs = app.fs;
    this.pendingSince = null;
    const current = () => this.latestT === t;
    const log = this.recorder.between(t - BEFORE_SEC, t + AFTER_SEC);
    const dir = this.dirFor(t);
    if (cf.dir !== dir) return; // 기다리는 사이 지워진 사고: 다시 저장하지 않는다
    const [peakG, peakDeg] = this.peakAtCrash;
    const impactDeg = peakG >= 1.0 ? peakDeg : null;
    const saveLog = () => {
      fs.writeAtomic(dir + '/log.json', logToJson(log), app.clock.nowMs());
      fs.write(dir + '/log.sha256', sha256Hex(fs.read(dir + '/log.json')), app.clock.nowMs());
      this.saveImpact(dir, t, peakG, impactDeg);
      return true;
    };
    if (VARIANT.fixed) { if (app.records.write(dir, saveLog) == null) return; } else { fs.mkdirs(dir); saveLog(); }
    if (current()) { cf.progress = 0.25; cf.status = '기록 저장 완료. 경위서 만드는 중…'; }
    app.rt.spawn('report', function* () {
      try {
        const facts = FactTimeline.build(log, t, peakG, impactDeg, fs.exists(dir + '/' + MANUAL));
        const publish = report => { if (current() && fs.exists(dir)) { cf.record = { dir, log, report, facts, vision: [], impactDeg, seen: null }; app.carSync.publish(); app.onRecord && app.onRecord(); } };
        const writeReport = text => VARIANT.fixed ? app.records.write(dir, () => fs.write(dir + '/report.txt', text, app.clock.nowMs())) : fs.write(dir + '/report.txt', text, app.clock.nowMs());
        writeReport(facts.join('\n'));
        publish(facts.join('\n'));
        // 웹 버전: 중계 서버(Claude)·차 카메라 사진·여러 카메라 영상이 없다 → 공개 APK(-Ppublic)처럼 사실 목록이 그대로 경위서
        if (current()) cf.progress = 0.8;
        const report = Validator.choose(null, facts);
        writeReport(report);
        publish(report);
        if (current()) { cf.progress = 1; cf.status = '경위서 완성'; }
      } catch (e) { app.log('경위서 만들기 중단: ' + e.message); }
    });
  }
}

// ── phone/.../Sync.kt (휴대폰 앱의 저장·저장 확인 부분) ──
class PhoneApp {
  constructor({ rt, fs, prefsStore, account, clock }) {
    this.rt = rt; this.fs = fs; this.prefsStore = prefsStore; this.account = account; this.clock = clock;
    this.cases = []; this.state = null; this.acking = new Set(); this.sends = new SerialExecutor(rt, 'phone-sends'); this.alerts = []; this.log = []; this.alive = true; this.lastError = null;
  }
  prefs(name) { if (!this.prefsStore.has(name)) this.prefsStore.set(name, new Map()); return this.prefsStore.get(name); }
  flag(name, t) { return this.prefs('claim').get(`${name}:${t}`) === true; }
  setFlag(name, t, v = true) { this.prefs('claim').set(`${name}:${t}`, v); }
  save(d) {
    try {
      this.fs.mkdirs('cases');
      const f = `cases/${d.t}.json`, text = HandoffCodec.encode(d);
      if (!this.fs.exists(f) || this.fs.read(f) !== text) {
        const tmp = `cases/${d.t}.tmp`;
        this.fs.write(tmp, text, this.clock.nowMs());
        if (!this.fs.rename(tmp, f)) throw new Error('rename failed');
        this.cases = this.readCases();
      }
      return true;
    } catch (e) { this.lastError = e.message; return false; }
  }
  readCases() {
    return this.fs.list('cases').filter(n => n.endsWith('.json')).map(n => { try { return HandoffCodec.decode(this.fs.read('cases/' + n)); } catch { return null; } })
      .filter(Boolean).sort((a, b) => b.t - a.t);
  }
  apply(s) {
    if (!this.account) return;
    if (s.wiped) { this.forget(); return; }
    this.state = s;
    const d = s.incident ? HandoffCodec.decode(s.incident) : null;
    if (!d) return;
    const family = this.account === 'family';
    const saved = this.save(d);
    this.setFlag('closed', d.t, s.closed); this.setFlag('notAccident', d.t, s.notAccident);
    if (d.hash && !this.flag('locked', d.t)) { this.setFlag('locked', d.t); this.log.push({ at: this.clock.now(), text: `기록 잠김 · 지문 ${d.hash.slice(0, 6)}…` }); }
    if (saved && s.version >= 0 && !family) this.ackSeen(d.t, s.seen !== d.t);
    if (s.notAccident || (s.closed && !family)) this.alerts = this.alerts.filter(a => a.t !== d.t);
    else if (s.version >= 0 && !this.flag('alerted', d.t) && (d.stopped || d.severe)) { this.alerts.push({ t: d.t, at: this.clock.now() }); this.setFlag('alerted', d.t); }
  }
  /** 파일에 저장한 사고를 서버에 알린다(차의 "휴대폰 도착"). 사고당 한 번 */
  ackSeen(t, again = false) {
    if ((!again && this.flag('seen', t)) || this.acking.has(t)) return;
    this.acking.add(t);
    const self = this;
    this.sends.execute(function* () {
      try {
        for (let i = 0; i < 3; i++) {
          const r = yield { net: { what: 'seen', account: self.account, body: JSON.stringify({ t }), from: 'phone' } };
          if (r && r.status === 200) { self.setFlag('seen', t); return; }
          yield { sleep: 2000 };
        }
      } finally { self.acking.delete(t); }
    });
  }
  forget() { this.prefs('claim').clear(); this.fs.deleteRecursively('cases'); this.state = null; this.cases = []; this.alerts = []; }
  startPolling() {
    const self = this;
    this.rt.spawn('phone-poll', function* () {
      let since = -1;
      while (self.alive) {
        const r = yield { net: { what: 'poll', account: self.account, since } };
        if (!r || r.status !== 200) { yield { sleep: 2000 }; continue; }
        since = r.state.version;
        self.apply(r.state);
      }
    });
  }
}

/** 차 앱 한 대(서비스 + 상태). 같은 fs·prefsStore 로 다시 만들면 "앱 재시작" */
class GwenApp {
  constructor({ clock, rt, fs, prefsStore, personaId = 'guest', account = 'demo', syncUrl = 'sim://sync' }) {
    this.clock = clock; this.rt = rt; this.fs = fs; this.prefsStore = prefsStore; this.personaId = personaId; this.account = account; this.syncUrl = syncUrl;
    this.carState = new CarState(this); this.crashFlow = new CrashFlowState(this); this.consent = new Consent(this); this.records = new Records(this); this.carSync = new CarSync(this);
    this.watcher = null; this.alive = true; this.events = [];
  }
  prefs(name) { if (!this.prefsStore.has(name)) this.prefsStore.set(name, new Map()); return this.prefsStore.get(name); }
  get persona() { return Personas.byId(this.personaId); }
  log(msg) { this.events.push({ t: this.clock.now(), msg }); if (this.events.length > 400) this.events.shift(); this.onLog && this.onLog(msg); }
  /** CarService.onCreate */
  startService() {
    this.consent.init();
    if (!this.consent.allowed('DRIVE')) { this.log('주행 기록 동의 없음 — 기록 안 함'); return false; }
    this.carSync.init();
    this.watcher = new CrashWatcher(this, () => {}, inc => { this.onIncident && this.onIncident(inc); this.carSync.publish(); });
    this.watcher.start();
    const app = this;
    this.rt.spawn('load-record', function* () {
      app.consent.cleanup(app.clock.nowMs());
      if (!app.crashFlow.record) {
        const r = app.loadLatest();
        if (r && !app.crashFlow.record) { app.crashFlow.record = r; if (r.kind) { app.crashFlow.kind = r.kind; app.crashFlow.kindChosen = r.kindChosen; } }
      }
    });
    return true;
  }
  stopService() { if (this.watcher) this.watcher.stop(); this.watcher = null; }
  shutdown() { this.alive = false; this.stopService(); }
  readKind(dir) {
    if (!this.fs.exists(dir + '/kind.txt')) return [null, false];
    const w = this.fs.read(dir + '/kind.txt').trim().split(' ');
    return [KIND_LABEL[w[0]] ? w[0] : null, w[1] !== 'guess'];
  }
  load(dir) {
    try {
      const fs = this.fs;
      const log = logFromJson(fs.read(dir + '/log.json'));
      const impact = fs.exists(dir + '/impact.json') ? JSON.parse(fs.read(dir + '/impact.json')) : null;
      const impactDeg = impact && impact.deg != null ? impact.deg : null;
      const facts = FactTimeline.build(log, impact ? impact.t : null, impact ? (impact.peakG ?? 0) : 0, impactDeg, fs.exists(dir + '/' + MANUAL));
      if (!fs.exists(dir + '/report.txt')) fs.write(dir + '/report.txt', facts.join('\n'), this.clock.nowMs());
      const parts = fs.read(dir + '/report.txt').split('\n\n');
      const [kind, chosen] = this.readKind(dir);
      return { dir, log, report: parts.filter(p => !p.startsWith('[영상 분석 추정]')).join('\n\n'), facts, vision: parts.filter(p => p.startsWith('[영상 분석 추정]')), impactDeg, seen: null, kind, kindChosen: chosen };
    } catch (e) { this.log('지난 사고 기록 읽기 실패: ' + e.message); return null; }
  }
  loadLatest() {
    const fs = this.fs;
    const names = fs.list('crashes').filter(n => fs.exists(`crashes/${n}/log.json`) && !fs.exists(`crashes/${n}/practice`) && !fs.exists(`crashes/${n}/false_alarm`));
    return names.length ? this.load('crashes/' + names[names.length - 1]) : null;
  }
  /** CarHome.pastCrashes: 기록 파일이 있는 사고 폴더, 최신순 */
  pastCrashes() {
    const fs = this.fs;
    return fs.list('crashes').filter(n => fs.exists(`crashes/${n}/log.json`)).sort().reverse().map(n => {
      const d = 'crashes/' + n, [kind, chosen] = this.readKind(d);
      let deg = null; try { const o = JSON.parse(fs.read(d + '/impact.json')); deg = o.deg ?? null; } catch { }
      const k = (chosen && kind && kind !== 'UNKNOWN') ? kind : null;
      return { name: n, kind: k, deg, falseAlarm: fs.exists(d + '/false_alarm'), title: pastCrashTitle(k, deg), note: deg != null ? '[센서 추정]' : '기록 보관' };
    });
  }
  /** 차 홈·사고 화면의 기록 띠 (CarHome.recStageOf) */
  recStageNow() {
    const inc = this.crashFlow.incident;
    if (!inc) return null;
    const cf = this.crashFlow, elapsed = this.clock.nowMs() / 1000 - inc.t;
    const saved = cf.dir != null && cf.record != null && cf.record.dir === cf.dir;
    const stage = recStage(elapsed, saved, this.carSync.recordAcked === inc.t, this.carSync.arrived(inc.t), cf.saveFailed === inc.t);
    const savedAt = saved && this.fs.exists(cf.dir + '/log.sha256') ? tsKST(this.fs.mtime(cf.dir + '/log.sha256') / 1000).slice(0, 8) : null;
    return { stage, elapsed, text: recLabel(stage, elapsed, savedAt) };
  }
}
