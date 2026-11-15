// ===== 45-lineart.js — shared/LineArt.kt(시안 "직접 그린 SVG 모션")와 CarFigure.kt 의 웹 사본 =====
// 선 그림은 같은 경로·같은 시각표(ms)로 SVG 에 그린다. animate=false 면 until 시각의 모습으로 멈춘다(차가 달리는 중·작은 아이콘).

function cubicBezier(x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx, cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const sx = t => ((ax * t + bx) * t + cx) * t, sy = t => ((ay * t + by) * t + cy) * t, dx = t => (3 * ax * t + 2 * bx) * t + cx;
  return x => {
    if (x <= 0) return 0; if (x >= 1) return 1;
    let t = x;
    for (let i = 0; i < 8; i++) { const e = sx(t) - x, d = dx(t); if (Math.abs(e) < 1e-6 || Math.abs(d) < 1e-6) break; t -= e / d; }
    t = clamp(t, 0, 1);
    return sy(t);
  };
}
const EASE_D = cubicBezier(.25, .1, .25, 1), EASE_FOLD = cubicBezier(.16, 1, .3, 1), FOSI = cubicBezier(.4, 0, .2, 1), PLEOS_EASE = EASE_FOLD;
const LTone = { INK: 'INK', SOFT: 'SOFT', SIGNAL: 'SIGNAL' };
const LMove = (from, to, o = {}) => Object.assign({ from, to, dx: 0, dy: 0, scale: 1, cx: 0, cy: 0, ease: FOSI }, o);
/** Seg(d, tone, w, from, to, ease, {morph, toTone, change:[a,b], changeEase, move, fadeIn:[a,b], fadeOut:[a,b], dash:[[a,b],[c,d]], rotate}) */
function LSeg(d, tone, w, from = 0, to = 0, ease = EASE_D, o = {}) {
  if (typeof from === 'object') { o = from; from = 0; to = 0; ease = EASE_D; }
  else if (typeof ease === 'object' && ease) { o = ease; ease = EASE_D; }
  const s = Object.assign({ d, tone, w, from, to, ease, morph: null, toTone: null, change: null, changeEase: FOSI, move: null, fadeIn: null, fadeOut: null, dash: null, rotate: 0 }, o);
  s.end = Math.max(from, to, s.change ? s.change[1] : 0, s.move ? s.move.to : 0, s.fadeIn ? s.fadeIn[1] : 0, s.fadeOut ? s.fadeOut[1] : 0);
  return s;
}
function LDrawing(box, segs, o = {}) {
  const a = Object.assign({ box, segs, pop: null, dy: 0, pivot: [24, 24] }, o);
  a.end = Math.max(...segs.map(s => s.end), a.pop ? a.pop.to : 0);
  return a;
}
const lcircle = (cx, cy, r) => `M${cx} ${cy - r}A${r} ${r} 0 1 1 ${cx} ${cy + r}A${r} ${r} 0 1 1 ${cx} ${cy - r}`;
const LNUM = /-?\d*\.?\d+/g;
function lmorph(a, b, f) { const to = (b.match(LNUM) || []).map(Number); let i = 0; return a.replace(LNUM, m => { const x = +m, y = to[i++] ?? x; return String(x + (y - x) * f); }); }

const PHONE_PATH = 'M13.3454 32.1181C17.87 36.6427 21.6151 39.5317 27.1791 42.436C29.0287 43.3991 30.97 44.2398 32.9878 44.9276C35.644 45.8358 38.6438 45.6875 40.6472 43.7211C42.8115 41.5966 44.6697 38.796 45.401 36.2698C45.6064 35.5603 45.2886 34.8247 44.6944 34.386L34.8468 27.1143C34.0439 26.5214 32.9269 26.6112 32.229 27.3246L27.9587 31.6901C24.6875 29.5653 22.7615 28.266 19.9795 25.484C17.1975 22.702 15.8982 20.7913 13.7734 17.5048L18.1389 13.2345C18.8523 12.5366 18.9421 11.4196 18.3492 10.6167L11.0775 0.769137C10.6388 0.174936 9.90326 -0.142923 9.19375 0.0624842C6.66749 0.79385 3.86693 2.65202 1.74245 4.81635C-0.223992 6.81968 -0.372311 9.81955 0.535875 12.4758C1.22374 14.4935 2.06446 16.4195 3.02747 18.2844C5.93179 23.8484 8.8361 27.6088 13.3454 32.1181Z';

const LineArts = (() => {
  const BODY_R = 'M24 6C27 6 29.4 6.6 30.6 8.4C31.5 9.6 31.8 12 31.8 15V31.2C31.8 34.8 30 36.6 27 36.6L24 36.6';
  const BODY_L = 'M24 6C21 6 18.6 6.6 17.4 8.4C16.5 9.6 16.2 12 16.2 15V31.2C16.2 34.8 18 36.6 21 36.6L24 36.6';
  const HIT_R = 'M24 6C27 6 29.4 6.6 30.6 8.4C31.5 9.6 31.8 12 31.8 15V31.2C31.8 33.6 30.6 35.1 28.2 35.4L24 34.2';
  const HIT_L = 'M24 6C21 6 18.6 6.6 17.4 8.4C16.5 9.6 16.2 12 16.2 15V31.2C16.2 33.6 17.4 35.1 19.8 35.4L24 34.2';
  const WINDSHIELD = 'M16.2 18C20.4 16.2 27.6 16.2 31.8 18', MIRRORS = 'M31.8 15.6L35.4 14.4M16.2 15.6L12.6 14.4';
  const impactCache = new Map();
  /** 충격 기록: 위에서 본 차 + 꺾쇠가 아래서 올라와 범퍼가 눌리는 순간 빨개지고 불꽃 (deg 만큼 돌림) */
  function impact(deg) {
    const key = deg == null ? 'none' : Math.round(deg);
    if (impactCache.has(key)) return impactCache.get(key);
    const rot = deg != null ? deg - 180 : 0, rear = deg != null && deg >= 135 && deg <= 225, hit = rear ? [648, 750] : null;
    const segs = [
      LSeg(BODY_R, 'INK', 1.2, 0, 450, FOSI, { morph: rear ? HIT_R : null, change: hit }),
      LSeg(BODY_L, 'INK', 1.2, 0, 450, FOSI, { morph: rear ? HIT_L : null, change: hit }),
      LSeg(WINDSHIELD, 'INK', 1.2, 150, 450, FOSI), LSeg(MIRRORS, 'INK', 1.2, 348, 498, FOSI),
    ];
    if (deg != null) segs.push(
      LSeg('M19.8 54.6L24 50.4L28.2 54.6', 'INK', 1.8, 498, 498, { move: LMove(498, 648, { dy: -12 }), fadeIn: [498, 648], toTone: 'SIGNAL', change: [648, 660], rotate: rot }),
      LSeg('M36 31.2L42 31.2M12 31.2L6 31.2M35.4 37.8L39.6 42M12.6 37.8L8.4 42', 'SIGNAL', 1.2, 648, 840, { fadeOut: [840, 960], rotate: rot }));
    const a = LDrawing(48, segs, { pivot: [24, 21.3] });
    impactCache.set(key, a);
    return a;
  }
  const fly = LMove(1200, 1800, { dx: 22.8, scale: 0.4, cx: 15, cy: 22.5 }), gone = [1680, 1800];
  const EVIDENCE = LDrawing(48, [
    LSeg('M34.2 9H41.4A3 3 0 0 1 44.4 12V33A3 3 0 0 1 41.4 36H34.2A3 3 0 0 1 31.2 33V12A3 3 0 0 1 34.2 9Z', 'INK', 1.2),
    LSeg('M6 9H17.4L24 15.6V36H6Z', 'INK', 1.2, 0, 450, { move: fly, fadeOut: gone }),
    LSeg('M10.2 21H19.8M10.2 25.8H19.8', 'INK', 1.2, 150, 450, { move: fly, fadeOut: gone }),
    LSeg('M10.2 30.6H15.6', 'INK', 1.2, 348, 498, { move: fly, fadeOut: gone }),
    LSeg('M34.8 22.8L37.2 25.2L41.4 19.8', 'INK', 1.2, 1800, 1800, { fadeIn: [1800, 1980] }),
  ]);
  const CALM = LDrawing(48, [LSeg(lcircle(24, 24, 20), 'INK', 2.4, 0, 960), LSeg('M14.4 24.6L21 31.2L33.6 17.4', 'INK', 2.4, 1200, 1620)]);
  const TRI_R = 'M24 8.4C25.5 8.4 27 9.3 27.7 10.6L41.4 35.8C42.9 38.6 40.9 42 37.8 42L24 42', TRI_L = 'M24 8.4C22.5 8.4 21 9.3 20.3 10.6L6.6 35.8C5.1 38.6 7.1 42 10.2 42L24 42';
  const HAZARD_OK = LDrawing(48, [
    LSeg(TRI_R, 'INK', 1.2), LSeg(TRI_L, 'INK', 1.2), LSeg(TRI_R, 'SIGNAL', 1.2, 0, 420, FOSI), LSeg(TRI_L, 'SIGNAL', 1.2, 0, 420, FOSI),
    LSeg('M14.4 35.4L33.6 35.4L24 18L14.4 35.4', 'SIGNAL', 1.2, { morph: 'M17.4 31.2L22.2 36L30 25.8L30 25.8', toTone: 'INK', change: [480, 840], changeEase: EASE_FOLD }),
  ], { pop: { from: 360, peak: 540, to: 780, scale: 1.04 } });
  const up = LMove(840, 1140, { dy: -13, ease: EASE_FOLD });
  const CARD = LDrawing(48, [
    LSeg('M9 37.8H39', 'SOFT', 1.2), LSeg('M12.6 37.8V33.6H35.4V37.8', 'SOFT', 1.2),
    LSeg('M18 32.4V14.4H26.4L30 18V32.4', 'INK', 1.2, { move: up }), LSeg('M21 14.4V18.6M24 14.4V18.6', 'INK', 1.2, { move: up }),
    LSeg('M24 3.6V9', 'SIGNAL', 1.8, 540, 540, { fadeIn: [540, 720], move: LMove(720, 840, { dy: 1.8 }), fadeOut: [840, 1020] }),
  ]);
  const FIRE = LDrawing(48, [LSeg('M24 5.4C25.2 13.2 36 18.6 36 30A12 12 0 0 1 12 30C12 24 15.6 20.4 17.4 15.6C19.2 18.6 21 19.8 22.8 20.4C22.2 15 22.8 9.6 24 5.4Z', 'INK', 1.2, 0, 600)]);
  const WAIT = LDrawing(48, [LSeg(lcircle(24, 24, 18), 'INK', 1.2, 0, 600), LSeg('M24 13.2V24L31.2 28.2', 'INK', 1.2, 480, 780)]);
  const CALL = LDrawing(45.4635, [LSeg(PHONE_PATH, 'INK', 1.137)]);
  const WALL = LDrawing(48, [LSeg('M6 13.5h36V38H6zM6 21.7h36M6 29.8h36M15 13.5v8.2M27 13.5v8.2M39 13.5v8.2M9 21.7v8.1M21 21.7v8.1M33 21.7v8.1M15 29.8V38M27 29.8V38M39 29.8V38', 'INK', 1.2)]);
  const TRUNK_PIN = LDrawing(48, [LSeg('M12 36V22.8C12 19.8 13.8 18 16.8 18H31.2C34.2 18 36 19.8 36 22.8V36M12 30H36', 'INK', 1.2), LSeg('M19.8 13.2L24 9L28.2 13.2', 'INK', 1.8)]);
  const KIND_VEHICLE = LDrawing(48, [LSeg('M9.2 32H4.6v-5.4c0-2 1.1-3.2 3-3.7l6-1.3 5.4-6.4c1-1.2 2.3-1.8 3.9-1.8h10.3c1.7 0 3.1.7 4.1 2.1l3.9 5.9c1.5.7 2.3 2.2 2.3 4V32h-3.1M17.8 32h13.3', 'INK', 1.2), LSeg(lcircle(13.5, 32, 4.3), 'INK', 1.2), LSeg(lcircle(35.4, 32, 4.3), 'INK', 1.2), LSeg('M15.8 21.5h23.6M27.2 13.6v7.9', 'INK', 1.2)]);
  const KIND_PERSON = LDrawing(48, [LSeg(lcircle(24, 10.5, 5), 'INK', 1.2), LSeg('M24 17v13M13.5 25.5L24 19.5l10.5 6M24 30l-7.5 13M24 30l7.5 13', 'INK', 1.2)]);
  const kind = k => k === 'VEHICLE' ? KIND_VEHICLE : k === 'OBJECT' ? WALL : KIND_PERSON;
  return { impact, EVIDENCE, EVIDENCE_SENT: 1200, CALM, CALM_RING: 960, HAZARD_OK, CARD, FIRE, WAIT, CALL, WALL, TRUNK_PIN, KIND_VEHICLE, KIND_PERSON, kind };
})();

/** 작은 아이콘 자리의 선 그림(Art.kt lineArtOf) */
function lineArtOf(art) {
  switch (art) {
    case 'RECORD': case 'HANDOFF': case 'REPLAY': return LineArts.EVIDENCE;
    case 'CALM': return LineArts.CALM;
    case 'PHONE': case 'POLICE': case 'EXCHANGE': return LineArts.CALL;
    case 'CARD_LATER': return LineArts.CARD;
    case 'CAMERA_FAR': case 'ROAD': case 'MOVE_RISK': case 'MOVE_LATER': return LineArts.KIND_VEHICLE;
    case 'FIRE': return LineArts.FIRE;
    case 'WAIT': return LineArts.WAIT;
    case 'FACILITY': return LineArts.WALL;
    case 'TRUNK': return LineArts.TRUNK_PIN;
    default: return null;
  }
}
const HAS_PICTURE = new Set(['HURT_CALL', 'HAZARD', 'TRUNK', 'CAMERA', 'CARD', 'WALK', 'INSURER', 'GUARDIAN', 'PERSON', 'DONT_MOVE']);
const fillsStage = art => HAS_PICTURE.has(art) && art !== 'TRUNK';
const artAsset = art => art === 'DONT_MOVE' ? 'person' : art.toLowerCase();
const RIGHT_INK = { HAZARD: 0.969, CARD: 0.968, GUARDIAN: 0.967, HURT_CALL: 0.989, WALK: 1, CAMERA: 1 };
const TEXT_CLEAR = { WALK: 0.612, INSURER: 0.483, HAZARD: 0.125, CAMERA: 0.268, CARD: 0.09, GUARDIAN: 0.439, PERSON: 0.24, DONT_MOVE: 0.24, HURT_CALL: 0.315 };

const SVGNS = 'http://www.w3.org/2000/svg';
const LINEART_LIVE = new Set(); // 움직이는 그림들(한 rAF 에서 함께 그림)
/** 선 그림 하나를 SVG 로. 반환 = { el, setUntil(ms), setInk(color) } */
function lineArtEl(art, { size = 96, animate = false, until = art.end, ink = null, stroke = 1 } = {}) {
  const svg = document.createElementNS(SVGNS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${art.box} ${art.box}`); svg.setAttribute('width', size); svg.setAttribute('height', size); svg.classList.add('lineart');
  const root = document.createElementNS(SVGNS, 'g'); svg.appendChild(root);
  const segEls = art.segs.map(s => {
    const g = document.createElementNS(SVGNS, 'g'); root.appendChild(g);
    const paths = s.d.split(/(?=M)/).filter(x => x.trim()).map(part => {
      const p = document.createElementNS(SVGNS, 'path');
      p.setAttribute('d', part); p.setAttribute('fill', 'none'); p.setAttribute('stroke-linecap', 'round'); p.setAttribute('stroke-linejoin', 'round');
      p.setAttribute('stroke-width', s.w * stroke);
      if (!s.dash) p.setAttribute('pathLength', '1');
      g.appendChild(p); return p;
    });
    return { s, g, paths };
  });
  const st = { now: animate ? 0 : until, until, start: null, ink };
  const color = tone => tone === 'INK' ? (st.ink || PL.textPrimary) : tone === 'SOFT' ? PL.textTertiary : PL.negative;
  const prog = (now, a, b) => b <= a ? (now >= a ? 1 : 0) : clamp((now - a) / (b - a), 0, 1);
  function draw() {
    const now = st.now, pv = art.pivot;
    let pop = 1;
    if (art.pop) { const p = art.pop; pop = now < p.from ? 1 : now < p.peak ? 1 + (p.scale - 1) * (now - p.from) / (p.peak - p.from) : now < p.to ? p.scale - (p.scale - 1) * (now - p.peak) / (p.to - p.peak) : 1; }
    root.setAttribute('transform', `translate(${art.box / 2} ${art.box / 2}) scale(${pop}) translate(${-art.box / 2} ${-art.box / 2 + art.dy})`);
    for (const { s, g, paths } of segEls) {
      const f = prog(now, s.from, s.to);
      let alpha = (s.fadeIn ? prog(now, s.fadeIn[0], s.fadeIn[1]) : 1) * (1 - (s.fadeOut ? prog(now, s.fadeOut[0], s.fadeOut[1]) : 0));
      if (f <= 0 && !s.dash) alpha = 0;
      g.style.opacity = alpha;
      if (alpha <= 0) continue;
      const p = s.ease(f), c = s.change ? s.changeEase(prog(now, s.change[0], s.change[1])) : 0;
      const col = s.toTone ? mixColor(color(s.tone), color(s.toTone), c) : color(s.tone);
      const m = s.move ? s.move.ease(prog(now, s.move.from, s.move.to)) : 0;
      const tr = [];
      if (s.rotate) tr.push(`rotate(${s.rotate} ${pv[0]} ${pv[1]})`);
      if (s.move) { const sc = 1 + (s.move.scale - 1) * m; tr.push(`translate(${s.move.dx * m} ${s.move.dy * m})`, `translate(${s.move.cx} ${s.move.cy}) scale(${sc}) translate(${-s.move.cx} ${-s.move.cy})`); }
      g.setAttribute('transform', tr.join(' '));
      const parts = s.morph && c > 0 ? lmorph(s.d, s.morph, c).split(/(?=M)/).filter(x => x.trim()) : null;
      paths.forEach((el, i) => {
        el.setAttribute('stroke', col);
        if (parts) el.setAttribute('d', parts[i] ?? parts[parts.length - 1]);
        if (s.dash) { const [a, b] = s.dash; el.setAttribute('stroke-dasharray', `${a[0] + (b[0] - a[0]) * p} ${a[1] + (b[1] - a[1]) * p}`); }
        else el.setAttribute('stroke-dasharray', p >= 0.999 ? 'none' : `${p} 1`);
      });
    }
  }
  const obj = {
    el: svg,
    tick(t) { if (st.start == null) st.start = t - st.now; const target = st.until; st.now = Math.min(target, t - st.start); if (st.now >= target) { st.now = target; LINEART_LIVE.delete(obj); } draw(); },
    setUntil(ms, anim = animate) { if (ms === st.until) return; st.until = ms; if (anim && ms > st.now) { st.start = null; LINEART_LIVE.add(obj); } else { st.now = ms; draw(); } },
    setInk(c) { st.ink = c; draw(); },
  };
  draw();
  if (animate && st.until > 0) LINEART_LIVE.add(obj);
  return obj;
}
function tickLineArts(t) { for (const o of [...LINEART_LIVE]) { if (!o.el.isConnected) { LINEART_LIVE.delete(o); continue; } o.tick(t); } }

// ── CarFigure.kt: 미리 렌더한 3D 차 그림 + 충격 꺾쇠 + 동그라미 표시 ──
let CAR_FIGS = null; // car_figures.json
const CAR_EDGE = { rear: [.093, .041, .033, .042, .027, .175, .341, .323], front: [.044, .17, .366, .283, .1, .055, .038, .045] };
function impactPoint(points, deg) {
  const d = ((deg % 360) + 360) % 360, a = Math.floor(d / 45) * 45, b = (a + 45) % 360;
  const pa = points['impact_' + a]; if (!pa) return null;
  const pb = points['impact_' + b]; if (!pb) return pa;
  const t = (d - a) / 45;
  return [pa[0] + (pb[0] - pa[0]) * t, pa[1] + (pb[1] - pa[1]) * t];
}
function edgeAt(view, deg) { const e = CAR_EDGE[view]; if (!e) return 0; const d = ((deg % 360) + 360) % 360, i = Math.floor(d / 45), f = (d - i * 45) / 45; return e[i] + (e[(i + 1) % 8] - e[i]) * f; }
/** carBox: 그림의 투명 여백을 뺀 차체가 폭 w 를 채우게 (x, y = 차체 왼쪽 위) → {left, top, width} */
function carBox(view, x, y, w) { const [l, t, k] = view === 'front' ? [.0326, .0317, 1.058] : [.028, .0307, 1.057]; return { left: x - w * l, top: y - w * t, width: w * k }; }
/** 차 그림 요소. opts: { deg, pins:[{anchor, art|number}], pinSize, animate } */
function carFigureEl(view, opts = {}) {
  const meta = CAR_FIGS && CAR_FIGS[view];
  const box = document.createElement('div'); box.className = 'carfig';
  if (!meta) return box;
  box.style.aspectRatio = `${meta.w} / ${meta.h}`;
  const img = document.createElement('img'); img.src = `car/car_${view}.png`; img.alt = ''; img.draggable = false; box.appendChild(img);
  const deg = opts.deg, hit = deg != null ? impactPoint(meta.points, deg) : null, center = meta.points.center;
  if (hit && center) {
    const svg = document.createElementNS(SVGNS, 'svg'); svg.setAttribute('viewBox', `0 0 1000 ${1000 * meta.h / meta.w}`); svg.classList.add('carfig-mark'); box.appendChild(svg);
    const W = 1000, H = 1000 * meta.h / meta.w, p = [hit[0] * W, hit[1] * H], c = [center[0] * W, center[1] * H];
    const L = Math.max(1, Math.hypot(p[0] - c[0], p[1] - c[1])), dir = [(p[0] - c[0]) / L, (p[1] - c[1]) / L], a = Math.atan2(dir[1], dir[0]);
    const path = document.createElementNS(SVGNS, 'path'); path.setAttribute('fill', 'none'); path.setAttribute('stroke-linecap', 'round'); path.setAttribute('stroke-linejoin', 'round'); svg.appendChild(path);
    let tip, arm, wid, spread;
    if (view !== 'top') { tip = [p[0] + dir[0] * W * (edgeAt(view, deg) + .0545) + W * .011, p[1] + dir[1] * W * (edgeAt(view, deg) + .0545) + W * .008]; arm = W * .0653; wid = W * .01366; spread = .82; }
    else { const u = Math.min(W, H); tip = [p[0] + dir[0] * u * (0.25 - 0.07 * Math.abs(dir[0])), p[1] + dir[1] * u * (0.25 - 0.07 * Math.abs(dir[0]))]; arm = u * 0.12; wid = u * 0.024; spread = .8; }
    path.setAttribute('d', `M${tip[0] + arm * Math.cos(a + spread)} ${tip[1] + arm * Math.sin(a + spread)}L${tip[0]} ${tip[1]}L${tip[0] + arm * Math.cos(a - spread)} ${tip[1] + arm * Math.sin(a - spread)}`);
    path.setAttribute('stroke-width', wid); path.setAttribute('stroke', view === 'top' ? PL.negative : '#FF4B39');
    if (opts.animate) path.classList.add('chev-in');
  }
  for (const pin of opts.pins || []) {
    const at = pin.anchor === 'impact' ? (deg != null ? impactPoint(meta.points, deg) : null) : meta.points[pin.anchor];
    if (!at) continue;
    const s = opts.pinSize || 56, el = document.createElement('div');
    el.className = 'carpin' + (pin.number != null ? ' num' : '');
    el.style.cssText = `left:calc(${at[0] * 100}% - ${s / 2}px);top:calc(${at[1] * 100}% - ${s / 2}px);width:${s}px;height:${s}px`;
    if (pin.number != null) el.textContent = pin.number;
    else if (pin.art) { const la = lineArtOf(pin.art); if (la) el.appendChild(lineArtEl(la, { size: s * 0.86 }).el); }
    box.appendChild(el);
  }
  return box;
}
