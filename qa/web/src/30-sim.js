// ===== 30-sim.js — 운전 시뮬레이터 (BeamNG 대신 쓰는 웹 시험장) =====
// 원칙(실차 대응표): 앱에는 실제 차에서 받을 수 있는 값만 넣는다 — 속도·기어·조향·브레이크·지시등·경고등·에어백·가속도·자이로·위치·제한속도.
// 손상·상대 차 위치 같은 "정답"은 시뮬레이터 안에만 있고 채점에만 쓴다.
const BASE_LAT = 37.5, BASE_LON = 127.03; // game/bridge/fake_drive.py 와 같은 가짜 기준점
const CITY_XS = [-350, -210, -70, 70, 210, 350], CITY_YS = [-350, -210, -70, 70, 210, 350];
const HWY_Y = 560;
const toLatLon = (x, y) => ({ lat: BASE_LAT + y / 110950, lon: BASE_LON + x / (111320 * Math.cos(toRad(BASE_LAT))) });
const moveToward = (v, target, maxStep) => v < target ? Math.min(target, v + maxStep) : Math.max(target, v - maxStep);
const MODEL_SIZE = { ioniq5: [4.64, 1.9, 1900], sedan: [4.5, 1.82, 1500], suv: [4.7, 1.9, 1900], van: [5.0, 1.95, 2300], truck: [7.0, 2.45, 7000], bus: [11.0, 2.5, 12000] };

function buildWorld(seed = 7) {
  const rng = mulberry32(seed);
  const roads = [];
  for (const x of CITY_XS) roads.push({ id: `ns${x}`, ax: x, ay: -362, bx: x, by: 362, w: 20, limit: 50, kind: 'city' });
  for (const y of CITY_YS) roads.push({ id: `ew${y}`, ax: -362, ay: y, bx: 362, by: y, w: 20, limit: 50, kind: 'city' });
  roads.push({ id: 'rampW', ax: -350, ay: 360, bx: -350, by: HWY_Y - 15, w: 12, limit: 60, kind: 'ramp' });
  roads.push({ id: 'rampE', ax: 350, ay: 360, bx: 350, by: HWY_Y - 15, w: 12, limit: 60, kind: 'ramp' });
  roads.push({ id: 'hwy', ax: -1500, ay: HWY_Y, bx: 1500, by: HWY_Y, w: 30, limit: 100, kind: 'highway' });
  const statics = [], trees = [];
  for (let i = 0; i + 1 < CITY_XS.length; i++) for (let j = 0; j + 1 < CITY_YS.length; j++) {
    const x0 = CITY_XS[i] + 15, x1 = CITY_XS[i + 1] - 15, y0 = CITY_YS[j] + 15, y1 = CITY_YS[j + 1] - 15;
    const park = (i === 2 && j === 2) || (i === 0 && j === 4) || (i === 4 && j === 1);
    if (park) { for (let k = 0; k < 26; k++) trees.push({ x: x0 + 6 + rng() * (x1 - x0 - 12), y: y0 + 6 + rng() * (y1 - y0 - 12), s: 0.8 + rng() * 0.6 }); continue; }
    const mx = (x0 + x1) / 2, my = (y0 + y1) / 2, central = 1 - Math.hypot((x0 + x1) / 2, (y0 + y1) / 2) / 600;
    for (const [ax, bx] of [[x0, mx - 4], [mx + 4, x1]]) for (const [ay, by] of [[y0, my - 4], [my + 4, y1]]) {
      if (rng() < 0.12) { trees.push({ x: (ax + bx) / 2, y: (ay + by) / 2, s: 1.1 }); continue; }
      const ix = 1 + rng() * 5, iy = 1 + rng() * 5;
      statics.push({ type: 'box', kind: 'building', cx: (ax + bx) / 2, cy: (ay + by) / 2, hx: (bx - ax) / 2 - ix, hy: (by - ay) / 2 - iy, h: 9 + rng() * 22 + central * rng() * 55, tone: rng() });
    }
  }
  // 고속도로 가드레일(남쪽은 램프 자리만 비움)·중앙 분리대
  const rail = (x1, x2, y, kind = 'rail') => statics.push({ type: 'box', kind, cx: (x1 + x2) / 2, cy: y, hx: (x2 - x1) / 2, hy: 0.2, h: 0.8 });
  rail(-1500, -357, HWY_Y - 15); rail(-343, 343, HWY_Y - 15); rail(357, 1500, HWY_Y - 15);
  rail(-1500, 1500, HWY_Y + 15); rail(-1500, 1500, HWY_Y, 'median');
  // 교차로 모퉁이 볼라드 몇 개
  for (const x of [-70, 70]) for (const y of [-70, 70]) for (const [sx, sy] of [[1, 1], [-1, -1]]) statics.push({ type: 'circle', kind: 'bollard', cx: x + sx * 11.5, cy: y + sy * 11.5, r: 0.18, h: 0.9 });
  // 과속방지턱: ew-210 의 x = -150, -130 (도로 전체 폭)
  const bumps = [{ x: -150, y: -210, hw: 10, hd: 1.6 }, { x: -130, y: -210, hw: 10, hd: 1.6 }];
  return { roads, statics, trees, bumps, grid: buildGrid(statics) };
}

function staticAabb(s) { return s.type === 'circle' ? [s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r] : [s.cx - s.hx, s.cy - s.hy, s.cx + s.hx, s.cy + s.hy]; }
function buildGrid(statics, cell = 40) {
  const g = new Map();
  const add = s => { const [a, b, c, d] = staticAabb(s); for (let i = Math.floor(a / cell); i <= Math.floor(c / cell); i++) for (let j = Math.floor(b / cell); j <= Math.floor(d / cell); j++) { const k = i + ',' + j; if (!g.has(k)) g.set(k, []); g.get(k).push(s); } };
  statics.forEach(add);
  return { cell, g, add };
}
function nearbyStatics(world, x, y, r = 8) {
  const { cell, g } = world.grid, out = new Set();
  for (let i = Math.floor((x - r) / cell); i <= Math.floor((x + r) / cell); i++) for (let j = Math.floor((y - r) / cell); j <= Math.floor((y + r) / cell); j++) (g.get(i + ',' + j) || []).forEach(s => out.add(s));
  return out;
}
function segDist(x, y, r) {
  const dx = r.bx - r.ax, dy = r.by - r.ay, L2 = dx * dx + dy * dy;
  const u = clamp(((x - r.ax) * dx + (y - r.ay) * dy) / L2, 0, 1);
  return Math.hypot(x - (r.ax + u * dx), y - (r.ay + u * dy));
}
function roadAt(world, x, y) {
  let best = null, bd = Infinity;
  for (const r of world.roads) { const d = segDist(x, y, r); if (d <= r.w / 2 + 0.5 && d < bd) { bd = d; best = r; } }
  return best;
}

class SimCar {
  constructor(o) {
    const [len, wid, mass] = MODEL_SIZE[o.model || 'sedan'];
    Object.assign(this, { x: 0, y: 0, yaw: 0, vf: 0, vl: 0, w: 0, steer: 0, steerCmd: 0, gear: 'D', throttle: 0, brake: 0, len, wid, mass, model: 'sedan',
      isPlayer: false, ai: null, script: null, crashed: false, hazard: false, signal: 'none', airbag: false, color: 0xcfd3d8, parked: false, id: 0 }, o);
    this.I = this.mass * (this.len * this.len + this.wid * this.wid) / 12; this.L = 2.9;
    this.acc = { fwd: 0, left: 0 }; this.pulses = []; this.contactUntil = new Map(); this.lastImpactT = -1e9; this.damage = 0;
  }
  get vx() { return this.vf * Math.cos(this.yaw) - this.vl * Math.sin(this.yaw); }
  get vy() { return this.vf * Math.sin(this.yaw) + this.vl * Math.cos(this.yaw); }
  setVel(vx, vy) { const c = Math.cos(this.yaw), s = Math.sin(this.yaw); this.vf = vx * c + vy * s; this.vl = -vx * s + vy * c; }
  box() {
    const c = Math.cos(this.yaw), s = Math.sin(this.yaw), hl = this.len / 2, hw = this.wid / 2;
    return { cx: this.x, cy: this.y, axes: [[c, s], [-s, c]], h: [hl, hw], corners: [[hl, hw], [hl, -hw], [-hl, -hw], [-hl, hw]].map(([a, b]) => [this.x + a * c - b * s, this.y + a * s + b * c]) };
  }
}
const aabbBox = s => ({ cx: s.cx, cy: s.cy, axes: [[1, 0], [0, 1]], h: [s.hx, s.hy], corners: [[s.cx + s.hx, s.cy + s.hy], [s.cx + s.hx, s.cy - s.hy], [s.cx - s.hx, s.cy - s.hy], [s.cx - s.hx, s.cy + s.hy]] });
function proj(corners, ax) { let mn = Infinity, mx = -Infinity; for (const c of corners) { const p = c[0] * ax[0] + c[1] * ax[1]; if (p < mn) mn = p; if (p > mx) mx = p; } return [mn, mx]; }
function insideBox(B, p) { const dx = p[0] - B.cx, dy = p[1] - B.cy; return Math.abs(dx * B.axes[0][0] + dy * B.axes[0][1]) <= B.h[0] + 1e-6 && Math.abs(dx * B.axes[1][0] + dy * B.axes[1][1]) <= B.h[1] + 1e-6; }
/** 두 상자(SAT). n = B → A 방향, depth, 접촉점 p */
function satBoxes(A, B) {
  let depth = Infinity, n = null;
  for (const ax of [...A.axes, ...B.axes]) {
    const [a0, a1] = proj(A.corners, ax), [b0, b1] = proj(B.corners, ax), o = Math.min(a1, b1) - Math.max(a0, b0);
    if (o <= 0) return null;
    if (o < depth) { depth = o; n = ax; }
  }
  if ((A.cx - B.cx) * n[0] + (A.cy - B.cy) * n[1] < 0) n = [-n[0], -n[1]];
  const pts = A.corners.filter(c => insideBox(B, c)).concat(B.corners.filter(c => insideBox(A, c)));
  const p = pts.length ? [pts.reduce((s, c) => s + c[0], 0) / pts.length, pts.reduce((s, c) => s + c[1], 0) / pts.length] : [(A.cx + B.cx) / 2, (A.cy + B.cy) / 2];
  return { n, depth, p };
}
function satCircle(A, s) {
  const dx = s.cx - A.cx, dy = s.cy - A.cy;
  const lx = dx * A.axes[0][0] + dy * A.axes[0][1], ly = dx * A.axes[1][0] + dy * A.axes[1][1];
  const qx = clamp(lx, -A.h[0], A.h[0]), qy = clamp(ly, -A.h[1], A.h[1]);
  const ex = lx - qx, ey = ly - qy, d = Math.hypot(ex, ey);
  if (d >= s.r) return null;
  const q = [A.cx + qx * A.axes[0][0] + qy * A.axes[1][0], A.cy + qx * A.axes[0][1] + qy * A.axes[1][1]];
  if (d < 1e-6) return { n: [-A.axes[0][0] * Math.sign(lx || 1), -A.axes[0][1] * Math.sign(lx || 1)], depth: s.r, p: q };
  const wx = ex * A.axes[0][0] + ey * A.axes[1][0], wy = ex * A.axes[0][1] + ey * A.axes[1][1];
  return { n: [-wx / d, -wy / d], depth: s.r - d, p: q };
}

class DriveSim {
  constructor(world, clock, rt) {
    this.world = world; this.clock = clock; this.rt = rt; this.cars = []; this.player = null; this.app = null; this.rng = mulberry32(1);
    this.dt = 0.01; this.sensorEvery = 0.02; this.nextSensor = 0; this.nextVhal = 0; this.nextGps = 0; this.nextNavi = 0;
    this.input = { up: false, down: false, left: false, right: false, brake: false };
    this.trail = []; this.impacts = []; this.bumpState = new Map(); this.autopilot = null; this.reverseHold = 0; this.hazard = false; this.signal = 'none';
    this.lampsSent = null; this.trunkOpen = false; this.posHistory = []; this.extraStatics = []; this.onImpact = null;
  }
  addCar(o) { const c = new SimCar(Object.assign({ id: this.cars.length + 1 }, o)); this.cars.push(c); return c; }
  addStatic(s) { this.world.grid.add(s); this.extraStatics.push(s); s.temp = true; return s; }
  clearTemp() { for (const [k, list] of this.world.grid.g) this.world.grid.g.set(k, list.filter(s => !s.temp)); this.extraStatics = []; }
  placePlayer(x, y, yaw, kph = 0, model = 'ioniq5') {
    this.cars = this.cars.filter(c => !c.isPlayer);
    this.player = this.addCar({ x, y, yaw, vf: kph / 3.6, model, isPlayer: true, color: 0xd9dadc });
    this.airbagFired = false; this.impacts = []; this.posHistory = []; this.trunkOpen = false; this.lampsSent = null;
    this.firstHit = null; this.ev = null; this.phase = 0; this.done = false; this.t0 = this.clock.t; this.hazard = false; this.signal = 'none';
    return this.player;
  }
  /** 앱 한 대를 붙인다: 센서·차량 값이 실차와 같은 통로(CarState·ImpactSensor)로 들어간다 */
  attach(app) { this.app = app; this.lampsSent = null; this.nextSensor = this.nextVhal = this.nextGps = this.nextNavi = this.clock.t; }

  step() {
    const dt = this.dt, T = this.clock.t + dt;
    this.rt.advanceTo(T); // 앱의 예약 작업(10Hz 기록 등)
    this.clock.t = T;
    const p = this.player;
    if (p) {
      if (this.autopilot) this.autopilot(this, p, dt); else this.playerControls(p, dt);
      p.hazard = this.hazard; p.signal = this.hazard ? 'none' : this.signal;
    }
    for (const c of this.cars) if (!c.isPlayer) this.aiControls(c, dt);
    for (const c of this.cars) this.integrate(c, dt);
    this.collide();
    if (p) { this.bumps(p); this.posHistory.push([T, p.x, p.y]); if (this.posHistory.length > 400) this.posHistory.shift(); }
    if (p && this.app) this.feedApp(p, T);
  }

  playerControls(c, dt) {
    const i = this.input, still = Math.abs(c.vf) < 0.5;
    if (c.gear === 'P') { c.throttle = 0; c.brake = 1; if ((i.up || i.down) && still) c.gear = i.down ? 'R' : 'D'; }
    else if (c.gear === 'R') {
      c.throttle = i.down ? 0.6 : 0; c.brake = i.up ? 1 : (i.brake ? 1 : 0);
      if (i.up && still) { this.reverseHold += dt; if (this.reverseHold > 0.25) { c.gear = 'D'; this.reverseHold = 0; } } else if (!i.up) this.reverseHold = 0;
    } else {
      c.throttle = i.up ? 1 : 0; c.brake = i.down ? 1 : (i.brake ? 1 : 0);
      if (i.down && still) { this.reverseHold += dt; if (this.reverseHold > 0.35) { c.gear = 'R'; this.reverseHold = 0; } } else if (!i.down) this.reverseHold = 0;
    }
    const target = (i.right ? 1 : 0) - (i.left ? 1 : 0); // 오른쪽 + (Pleos 규약: 왼쪽이 음수)
    c.steerCmd = moveToward(c.steerCmd, target, (target === 0 ? 4 : 2.2) * dt);
  }

  aiControls(c, dt) {
    if (c.script) { c.script(this, c, dt); return; }
    if (c.parked || c.crashed || !c.ai) { c.throttle = 0; c.brake = 1; c.steerCmd = 0; if (c.crashed) c.hazard = true; return; }
    const r = c.ai, wp = r.route[r.i];
    const dx = wp[0] - c.x, dy = wp[1] - c.y;
    if (Math.hypot(dx, dy) < 9) { r.i = (r.i + 1) % r.route.length; }
    const nx = r.route[r.i][0] - c.x, ny = r.route[r.i][1] - c.y;
    const herr = wrapPi(Math.atan2(ny, nx) - c.yaw);
    c.steerCmd = clamp(-herr * 2.2, -1, 1); // 왼쪽으로 돌려면 음수
    let target = r.kph / 3.6;
    const nxt = r.route[(r.i + 1) % r.route.length], turn = Math.abs(wrapPi(Math.atan2(nxt[1] - r.route[r.i][1], nxt[0] - r.route[r.i][0]) - Math.atan2(ny, nx)));
    if (Math.hypot(nx, ny) < 28 && turn > 0.7) target = Math.min(target, 4.5);
    for (const o of this.cars) {
      if (o === c) continue;
      const ox = o.x - c.x, oy = o.y - c.y, ahead = ox * Math.cos(c.yaw) + oy * Math.sin(c.yaw), lat = -ox * Math.sin(c.yaw) + oy * Math.cos(c.yaw);
      if (ahead > 0 && ahead < 26 && Math.abs(lat) < 2.4) target = Math.min(target, Math.max(0, (ahead - 7.5) * 1.1));
    }
    const err = target - c.vf;
    c.throttle = clamp(err * 0.6, 0, 1); c.brake = clamp(-err * 0.5, 0, 1); c.gear = 'D';
  }

  integrate(c, dt) {
    const vf0 = c.vf, vl0 = c.vl;
    const maxDeg = 35 / (1 + Math.abs(c.vf) / 15);
    c.steer = moveToward(c.steer, c.steerCmd * maxDeg, 70 * dt);
    let vf = c.vf;
    if (c.gear !== 'P' && c.gear !== 'N' && c.throttle > 0) {
      const dir = c.gear === 'R' ? -1 : 1, maxV = c.gear === 'R' ? 20 / 3.6 : 190 / 3.6;
      if (vf * dir < maxV) vf += dir * c.throttle * Math.min(4.5, 165000 / (c.mass * Math.max(Math.abs(vf), 1))) * dt;
    }
    const resist = (c.gear === 'P' ? 8 : c.brake * 9.0) + 0.15 + 0.00023 * vf * vf;
    vf = moveToward(vf, 0, resist * dt);
    c.vf = vf;
    c.vl = moveToward(c.vl, 0, 9.0 * dt);
    let wT = c.vf * Math.tan(toRad(-c.steer)) / c.L;
    if (Math.abs(c.vf) > 0.1) wT = clamp(wT, -8.5 / Math.abs(c.vf), 8.5 / Math.abs(c.vf));
    const w0 = c.w;
    c.w = moveToward(c.w, wT, (Math.abs(c.vl) > 0.6 ? 3 : 10) * dt);
    // 차체 기준 관성 가속도(충돌 펄스 제외): 앞 = dvf/dt − ω·vl, 왼쪽 = dvl/dt + ω·vf
    c.acc.fwd = (c.vf - vf0) / dt - w0 * vl0;
    c.acc.left = (c.vl - vl0) / dt + w0 * c.vf;
    c.x += c.vx * dt; c.y += c.vy * dt; c.yaw = wrapPi(c.yaw + c.w * dt);
  }

  collide() {
    for (let i = 0; i < this.cars.length; i++) {
      const A = this.cars[i], bA = A.box();
      for (const s of nearbyStatics(this.world, A.x, A.y, A.len)) {
        const hit = s.type === 'circle' ? satCircle(bA, s) : satBoxes(bA, aabbBox(s));
        if (hit) this.resolve(A, null, hit, s.kind === 'bollard' ? 0.10 : 0.08, s.kind);
      }
      for (let j = i + 1; j < this.cars.length; j++) {
        const B = this.cars[j];
        if (Math.abs(A.x - B.x) > 14 || Math.abs(A.y - B.y) > 14) continue;
        const hit = satBoxes(bA, B.box());
        if (hit) this.resolve(A, B, hit, 0.12, 'car');
      }
    }
  }
  resolve(A, B, hit, tau, what) {
    const { n, depth, p } = hit, e = 0.15, mu = 0.35;
    const iMA = 1 / A.mass, iIA = 1 / A.I, iMB = B ? 1 / B.mass : 0, iIB = B ? 1 / B.I : 0, tot = iMA + iMB;
    A.x += n[0] * depth * iMA / tot; A.y += n[1] * depth * iMA / tot;
    if (B) { B.x -= n[0] * depth * iMB / tot; B.y -= n[1] * depth * iMB / tot; }
    const rA = [p[0] - A.x, p[1] - A.y], rB = B ? [p[0] - B.x, p[1] - B.y] : [0, 0];
    const vA = [A.vx - A.w * rA[1], A.vy + A.w * rA[0]], vB = B ? [B.vx - B.w * rB[1], B.vy + B.w * rB[0]] : [0, 0];
    const vr = [vA[0] - vB[0], vA[1] - vB[1]], vn = vr[0] * n[0] + vr[1] * n[1];
    if (vn >= 0) return;
    const cA = rA[0] * n[1] - rA[1] * n[0], cB = rB[0] * n[1] - rB[1] * n[0];
    const j = -(1 + e) * vn / (iMA + iMB + cA * cA * iIA + cB * cB * iIB);
    const tv = [vr[0] - vn * n[0], vr[1] - vn * n[1]], tl = Math.hypot(tv[0], tv[1]);
    let jt = 0, tn = [0, 0];
    if (tl > 1e-6) { tn = [tv[0] / tl, tv[1] / tl]; const tA = rA[0] * tn[1] - rA[1] * tn[0], tB = rB[0] * tn[1] - rB[1] * tn[0]; jt = clamp(-tl / (iMA + iMB + tA * tA * iIA + tB * tB * iIB), -mu * j, mu * j); }
    const Jx = j * n[0] + jt * tn[0], Jy = j * n[1] + jt * tn[1];
    A.setVel(A.vx + Jx * iMA, A.vy + Jy * iMA); A.w += (rA[0] * Jy - rA[1] * Jx) * iIA;
    this.impulse(A, [Jx * iMA, Jy * iMA], tau, what, B);
    if (B) { B.setVel(B.vx - Jx * iMB, B.vy - Jy * iMB); B.w -= (rB[0] * Jy - rB[1] * Jx) * iIB; this.impulse(B, [-Jx * iMB, -Jy * iMB], tau, 'car', A); }
  }
  /** 충돌 한 번의 속도 변화 → 가속도 센서의 반 사인 펄스(넓이 = Δv), 에어백 판단(합성, 아래 규칙) */
  impulse(c, dv, tau, what, other) {
    const T = this.clock.t, mag = Math.hypot(dv[0], dv[1]);
    if (mag < 0.3) return;
    const f = [Math.cos(c.yaw), Math.sin(c.yaw)], l = [-Math.sin(c.yaw), Math.cos(c.yaw)];
    const dfwd = dv[0] * f[0] + dv[1] * f[1], dleft = dv[0] * l[0] + dv[1] * l[1];
    c.pulses.push({ t0: T, tau, pf: Math.PI / 2 * dfwd / tau, pl: Math.PI / 2 * dleft / tau, pu: 0 });
    c.damage += mag;
    if (!c.isPlayer) { if (mag > 1.5) c.crashed = true; return; }
    if (this.firstHit == null) this.firstHit = T;
    const ev = (this.ev && T - this.ev.last < 0.15) ? this.ev : (this.ev = { t0: T, dv: [0, 0], last: T, what });
    ev.dv[0] += dfwd; ev.dv[1] += dleft; ev.last = T;
    const evMag = Math.hypot(ev.dv[0], ev.dv[1]), deg = impactFromDeg(-ev.dv[1], ev.dv[0]);
    const frontal = deg <= 45 || deg >= 315, side = (deg > 45 && deg < 135) || (deg > 225 && deg < 315);
    if (!this.airbagFired && ((frontal && evMag >= 20 / 3.6) || (side && evMag >= 18 / 3.6))) { this.airbagFired = true; c.airbag = true; }
    this.impacts.push({ t: T, dv: mag, deg, what, airbag: c.airbag });
    this.onImpact && this.onImpact({ t: T, dv: mag, deg, what });
  }
  bumps(c) {
    const kph = Math.abs(c.vf) * 3.6;
    const axles = [[c.L / 2, 'f'], [-c.L / 2, 'r']];
    for (const b of this.world.bumps) for (const [off, k] of axles) {
      const ax = c.x + off * Math.cos(c.yaw), ay = c.y + off * Math.sin(c.yaw);
      const inside = Math.abs(ax - b.x) <= b.hd / 2 && Math.abs(ay - b.y) <= b.hw;
      const key = b.x + ',' + b.y + k, was = this.bumpState.get(key);
      if (inside && !was && kph > 3) c.pulses.push({ t0: this.clock.t, tau: 0.08, pf: 0, pl: 0, pu: GRAVITY_EARTH * clamp(0.08 * kph, 0, 6) * (k === 'f' ? 1 : 0.9) });
      this.bumpState.set(key, inside);
    }
  }
  pulseSum(c, T) {
    let f = 0, l = 0, u = 0;
    c.pulses = c.pulses.filter(p => T - p.t0 <= p.tau + 0.001);
    for (const p of c.pulses) { const k = Math.sin(Math.PI * clamp((T - p.t0) / p.tau, 0, 1)); f += p.pf * k; l += p.pl * k; u += p.pu * k; }
    return [f, l, u];
  }
  lamps(c) {
    const on = [];
    if (c.brake >= 0.85 && Math.abs(c.vf) > 3 && c.gear !== 'P') on.push('ABS 작동');
    if (c.airbag) on.push('에어백 경고등');
    if (this.trunkOpen) on.push('트렁크 열림');
    if (c.hazard) on.push('비상등');
    return on.join(',');
  }
  /** 실차 통로: VHAL 속도·기어(20Hz/변화 때), Pleos 조향·브레이크·지시등·에어백·경고등, 가속도·자이로 50Hz, 위치 1Hz(1초 늦게), 제한속도 1Hz */
  feedApp(c, T) {
    const app = this.app, cs = app.carState;
    if (T + 1e-9 >= this.nextSensor) {
      this.nextSensor += this.sensorEvery;
      if (app.watcher) {
        const [pf, pl, pu] = this.pulseSum(c, T), nz = () => gauss(this.rng) * 0.05;
        const aF = c.acc.fwd + pf + nz(), aL = c.acc.left + pl + nz(), aU = pu + gauss(this.rng) * 0.12;
        app.watcher.impact.onSensorChanged('accel', [-aL, GRAVITY_EARTH + aU, aF], app.clock.nanos());
        app.watcher.impact.onSensorChanged('gyro', [0, c.w + gauss(this.rng) * 0.002, 0], app.clock.nanos());
      }
    }
    if (T + 1e-9 >= this.nextVhal) {
      this.nextVhal += 0.05;
      const steer = Math.round(c.steer * 10) / 10, brake = c.brake > 0.05, lamps = this.lamps(c);
      cs.update(s => Object.assign({}, s, { speedKph: Math.abs(c.vf) * 3.6, gear: c.gear, steerDeg: steer === 0 ? 0 : steer, brake, signal: c.signal, airbag: c.airbag }));
      if (lamps !== this.lampsSent) { this.lampsSent = lamps; cs.hazard = c.hazard; cs.telltalesSeen = true; cs.update(s => Object.assign({}, s, { lamps })); }
    }
    if (T + 1e-9 >= this.nextGps) {
      this.nextGps += 1.0;
      const old = this.posHistory.find(h => h[0] >= T - 1.0) || [T, c.x, c.y];
      if (app.consent.allowed('LOCATION')) { const ll = toLatLon(old[1], old[2]); cs.update(s => Object.assign({}, s, { lat: ll.lat, lon: ll.lon })); }
    }
    if (T + 1e-9 >= this.nextNavi) {
      this.nextNavi += 1.0;
      const r = roadAt(this.world, c.x, c.y);
      if (r) cs.roadLimitKph = r.limit;
    }
  }
}

// ── 주변 차: 블록을 도는 경로(우측 통행) ──
function blockLoop(xa, xb, ya, yb) {
  const o = 1.75; // 안쪽 차로
  return [[xb + o, ya + 12], [xb + o, yb - 12], [xb - 12, yb + o], [xa + 12, yb + o], [xa - o, yb - 12], [xa - o, ya + 12], [xa + 12, ya - o], [xb - 12, ya - o]];
}
function spawnTraffic(sim, seed = 3) {
  const rng = mulberry32(seed), models = ['sedan', 'suv', 'van', 'sedan', 'truck', 'suv', 'bus', 'sedan'];
  const colors = [0x8a9099, 0x2f3338, 0xe9e9ea, 0x6d7782, 0xb7bcc2, 0x4b5560, 0xdedfe0, 0x9aa3ab];
  const loops = [[-210, -70, -350, -210], [70, 210, -210, -70], [-350, -210, 70, 210], [210, 350, 210, 350], [-70, 70, 210, 350], [70, 210, 70, 210], [-210, -70, -70, 70], [210, 350, -350, -210]];
  loops.forEach((lp, k) => {
    const route = blockLoop(...lp), i0 = Math.floor(rng() * route.length), a = route[i0], b = route[(i0 + 1) % route.length];
    sim.addCar({ model: models[k], color: colors[k], x: a[0], y: a[1], yaw: Math.atan2(b[1] - a[1], b[0] - a[0]), ai: { route, i: (i0 + 1) % route.length, kph: 30 + rng() * 15 } });
  });
  // 길가에 세워 둔 차
  [[78.25, -150, Math.PI / 2], [78.25, -120, Math.PI / 2], [-150, 61.75, Math.PI], [219.5, 130, -Math.PI / 2]].forEach(([x, y, yaw], k) => sim.addCar({ model: ['sedan', 'suv', 'van', 'sedan'][k], color: colors[k + 2], x, y, yaw, parked: true, gear: 'P' }));
}

// ── 시험용 자동 운전 도구 ──
function laneHold(c, px, py, yaw0, gain = 1) {
  const lx = -Math.sin(yaw0), ly = Math.cos(yaw0), eLeft = (c.x - px) * lx + (c.y - py) * ly, h = wrapPi(c.yaw - yaw0);
  return clamp((0.12 * eLeft + 1.8 * h) * gain, -1, 1);
}
function speedTo(c, kph) { const err = kph / 3.6 - c.vf; c.throttle = clamp(err * 0.7, 0, 1); c.brake = clamp(-err * 0.6, 0, 1); }
/** 부딪힌 뒤 사람처럼: 바로 발을 떼고 0.3초 뒤 브레이크, 멈추고 3초 뒤 P */
function afterHit(sim, c, hitT) {
  const T = sim.clock.t; c.throttle = 0; c.steerCmd = 0;
  c.brake = T - hitT > 0.3 ? 0.7 : 0;
  if (Math.abs(c.vf) < 0.3) { c.stillSince = c.stillSince ?? T; if (T - c.stillSince > 3) c.gear = 'P'; } else c.stillSince = null;
}
const NB = -70 + 1.75; // ns-70 북쪽 방향 안쪽 차로
/** 장면: 시험 케이스 JSON 의 scenario 이름과 params. 설정 후 매 단계 autopilot 을 부른다 */
const SCENARIOS = {
  frontParked(sim, p) {
    sim.placePlayer(NB, -335, Math.PI / 2);
    sim.addCar({ model: 'sedan', x: NB, y: -335 + p.gap, yaw: Math.PI / 2, parked: true, gear: 'P', color: 0x6d7782 });
    return { autopilot: (s, c) => { if (s.firstHit != null) return afterHit(s, c, s.firstHit); c.steerCmd = laneHold(c, NB, 0, Math.PI / 2); speedTo(c, p.kph); }, maxSec: 30 };
  },
  rearHitWhileStopped(sim, p) {
    sim.placePlayer(NB, -290, Math.PI / 2);
    sim.addCar({ model: 'sedan', x: NB, y: -342, yaw: Math.PI / 2, color: 0x2f3338, script: (s, c) => { if (c.damage > 0) { c.throttle = 0; c.brake = 1; return; } c.steerCmd = laneHold(c, NB, 0, Math.PI / 2); speedTo(c, p.kph); } });
    return { autopilot: (s, c) => { c.throttle = 0; c.brake = s.firstHit != null ? 0.7 : 0.5; c.steerCmd = 0; if (s.firstHit != null) afterHit(s, c, s.firstHit); }, maxSec: 30 };
  },
  sideHitRight(sim, p) {
    const yL = -210 + 1.75;
    sim.placePlayer(NB, yL, Math.PI / 2);
    sim.addCar({ model: 'suv', x: -18, y: yL, yaw: Math.PI, color: 0x8a9099, script: (s, c) => { if (c.damage > 0) { c.throttle = 0; c.brake = 1; return; } c.steerCmd = laneHold(c, 0, yL, Math.PI); speedTo(c, p.kph); } });
    return { autopilot: (s, c) => { c.throttle = 0; c.brake = 0.5; c.steerCmd = 0; if (s.firstHit != null) afterHit(s, c, s.firstHit); }, maxSec: 30 };
  },
  reversePole(sim, p) {
    sim.placePlayer(NB, -290, Math.PI / 2);
    sim.addStatic({ type: 'circle', kind: 'bollard', cx: NB, cy: -290 - 2.32 - p.gap, r: 0.18, h: 0.9 });
    return { autopilot: (s, c) => { if (s.firstHit != null) return afterHit(s, c, s.firstHit); c.gear = 'R'; c.steerCmd = 0; const err = p.kph / 3.6 + c.vf; c.throttle = clamp(err * 0.7, 0, 1); c.brake = clamp(-err * 0.6, 0, 1); }, maxSec: 30 };
  },
  hardBrake(sim, p) {
    sim.placePlayer(NB, -345, Math.PI / 2);
    return { autopilot: (s, c, dt) => {
      c.steerCmd = laneHold(c, NB, 0, Math.PI / 2);
      if (!s.phase) { speedTo(c, p.kph); if (c.vf * 3.6 > p.kph - 0.5) { s.phase = 1; s.phaseAt = s.clock.t; } }
      else if (s.clock.t - s.phaseAt < 1) speedTo(c, p.kph);
      else { c.throttle = 0; c.brake = 1; if (Math.abs(c.vf) < 0.2) { c.stillSince = c.stillSince ?? s.clock.t; if (s.clock.t - c.stillSince > 3) { c.gear = 'P'; s.done = true; } } }
      void dt;
    }, maxSec: 30 };
  },
  slalom(sim, p) {
    const y = -70 - 1.75;
    sim.placePlayer(-345, y, 0);
    return { autopilot: (s, c) => {
      if (!s.phase) { c.steerCmd = laneHold(c, 0, y, 0); speedTo(c, p.kph); if (c.vf * 3.6 > p.kph - 0.5) { s.phase = 1; s.phaseAt = s.clock.t; } return; }
      const k = s.clock.t - s.phaseAt;
      speedTo(c, p.kph);
      if (k < p.sec) { const seg = Math.floor((k + p.period / 2) / p.period); c.steerCmd = (seg % 2 ? 1 : -1) * p.steer; }
      else { c.steerCmd = laneHold(c, 0, c.y, 0, 0.5); c.throttle = 0; c.brake = 0.6; if (Math.abs(c.vf) < 0.2) { c.stillSince = c.stillSince ?? s.clock.t; if (s.clock.t - c.stillSince > 3) { c.gear = 'P'; s.done = true; } } }
    }, maxSec: 40 };
  },
  speedBump(sim, p) {
    const y = -210 - 1.75;
    sim.placePlayer(-150 - p.kph / 3.6 * 4 - 15, y, 0); // 턱 앞에서 정한 속도에 닿을 만큼만 떨어져 출발
    return { autopilot: (s, c) => {
      if (s.firstHit != null) return afterHit(s, c, s.firstHit);
      c.steerCmd = laneHold(c, 0, y, 0);
      if (c.x < -105) speedTo(c, p.kph);
      else { c.throttle = 0; c.brake = 0.6; if (Math.abs(c.vf) < 0.2) { c.stillSince = c.stillSince ?? s.clock.t; if (s.clock.t - c.stillSince > 3) { c.gear = 'P'; s.done = true; } } }
    }, maxSec: 45 };
  },
  highwayRearEnd(sim, p) {
    const y = HWY_Y - 0.5 - 1.75 - 3.5;
    sim.placePlayer(-1000, y, 0, p.kph);
    sim.addCar({ model: 'truck', x: -1000 + p.gap, y, yaw: 0, vf: p.truckKph / 3.6, color: 0xdedfe0, script: (s, c) => { if (c.damage > 0) { c.throttle = 0; c.brake = 0.8; return; } c.steerCmd = laneHold(c, 0, y, 0); speedTo(c, p.truckKph); } });
    return { autopilot: (s, c) => { if (s.firstHit != null) return afterHit(s, c, s.firstHit); c.steerCmd = laneHold(c, 0, y, 0); speedTo(c, p.kph); }, maxSec: 40 };
  },
  wallScrape(sim, p) {
    const y = -70 - 1.75, wallY = y - 1.75 - 0.25;
    sim.addStatic({ type: 'box', kind: 'barrier', cx: -250, cy: wallY - 0.25, hx: 50, hy: 0.25, h: 0.9 });
    sim.placePlayer(-310, y, toRad(-p.angle));
    return { autopilot: (s, c) => {
      const k = s.clock.t - s.t0;
      if (k < p.sec) { speedTo(c, p.kph); c.steerCmd = 0; }
      else { c.throttle = 0; c.brake = 0.6; c.steerCmd = 0; if (Math.abs(c.vf) < 0.2) { c.stillSince = c.stillSince ?? s.clock.t; if (s.clock.t - c.stillSince > 3) { c.gear = 'P'; s.done = true; } } }
    }, maxSec: 30 };
  },
};
