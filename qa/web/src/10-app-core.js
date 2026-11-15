// ===== 10-app-core.js — 괜차나 앱의 순수 로직을 JS 로 옮김 (기준 커밋 1fac695) =====
// 각 블록 위에 Kotlin 원본 위치를 적었다. 원본을 고치면 여기도 같이 고친다 (웹 모델은 원본의 사본이다).

// ── shared/java/com/askthecar/Recorder.kt ──
function Sample(t, o) {
  return Object.assign({ t, speedKph: 0, steerDeg: 0, brake: false, signal: 'none', gear: 'D', cruise: false, lat: null, lon: null, airbag: false, lamps: '', yawRate: null }, o || {});
}
/** 최근 windowSec 초만 기억하는 링버퍼 */
class Recorder {
  constructor(windowSec = 60) { this.windowSec = windowSec; this.buf = []; }
  add(s) { this.buf.push(s); while (this.buf[0].t < s.t - this.windowSec) this.buf.shift(); }
  clear() { this.buf.length = 0; }
  between(from, to) { return this.buf.filter(s => s.t >= from && s.t <= to); }
}
/** 속도 + 회전(자이로, 없으면 앞바퀴 각도) 추측항법. x = 시작 방향 앞, y = 왼쪽, yaw = 반시계 */
function trackPoses(samples, wheelbaseM = 2.9) {
  let x = 0, y = 0, yaw = 0;
  const out = [{ x: 0, y: 0, yaw: 0 }];
  for (let i = 0; i + 1 < samples.length; i++) {
    const a = samples[i], b = samples[i + 1], dt = b.t - a.t;
    const v = Math.abs(a.speedKph) / 3.6 * (a.gear === 'R' ? -1 : 1);
    if (Math.abs(v) > 0.3) yaw += (a.yawRate ?? (v * Math.tan(toRad(-a.steerDeg)) / wheelbaseM)) * dt;
    x += v * Math.cos(yaw) * dt; y += v * Math.sin(yaw) * dt;
    out.push({ x, y, yaw });
  }
  return out;
}
function easeSteps(samples) {
  function eased(get) {
    const out = samples.map(get);
    let start = 0;
    for (let i = 1; i < samples.length; i++) {
      if (get(samples[i]) === get(samples[i - 1])) continue;
      let from = start;
      while (samples[i].t - samples[from].t > 1.5) from++;
      for (let j = from; j < i; j++) out[j] = get(samples[from]) + (get(samples[i]) - get(samples[from])) * (samples[j].t - samples[from].t) / (samples[i].t - samples[from].t);
      start = i;
    }
    return out;
  }
  const v = eased(s => s.speedKph), w = eased(s => s.yawRate ?? NaN);
  return samples.map((s, i) => Object.assign({}, s, { speedKph: v[i], yawRate: (s.yawRate == null || Number.isNaN(w[i])) ? s.yawRate : w[i] }));
}
const GPS_LAG_SEC = 1.0, MOVED_IN_LAG_M = 1.5;
function fitAfterCrash(samples, poses, crashIdx) {
  const first = samples.find(s => s.lat != null && s.lon != null);
  if (!first) return poses;
  const mPerLon = 111320 * Math.cos(toRad(first.lat));
  const en = s => [(s.lon - first.lon) * mPerLon, (s.lat - first.lat) * 110950];
  const fixes = [];
  for (const s of samples) if (s.lat != null && s.lon != null && (fixes.length === 0 || fixes[fixes.length - 1].lat !== s.lat || fixes[fixes.length - 1].lon !== s.lon)) fixes.push(s);
  const at = samples[crashIdx].t + GPS_LAG_SEC;
  let before = null;
  for (const f of fixes) if (f.t <= at) before = f;
  if (!before) return poses;
  const after = fixes.find(f => f.t > at);
  const [be, bn] = en(before);
  let ge = be, gn = bn;
  if (after) { const [ae, an] = en(after), f = (at - before.t) / (after.t - before.t); ge = be + (ae - be) * f; gn = bn + (an - bn) * f; }
  const [ee, en2] = en(fixes[fixes.length - 1]);
  const c = poses[crashIdx], e = poses[poses.length - 1];
  const trackDist = Math.hypot(e.x - c.x, e.y - c.y);
  if (trackDist < 1) return poses;
  const k = Math.min(1, (Math.hypot(ee - ge, en2 - gn) + MOVED_IN_LAG_M) / trackDist);
  return poses.map((p, i) => i <= crashIdx ? p : { x: c.x + (p.x - c.x) * k, y: c.y + (p.y - c.y) * k, yaw: p.yaw });
}
/** 끊겨 들어온 값이 급감처럼 보이지 않게 앞 값이 머문 시간까지 따진다 */
const suddenDrop = (drop, heldSec, minDrop = 15) => drop >= minDrop && drop / Math.max(0.3, heldSec) >= minDrop / 0.3;
/** 충격이 들어온 방향(앞 0, 오른쪽 90, 뒤 180, 왼쪽 270). right/forward = 충격 순간 가속도 */
const impactFromDeg = (right, forward) => (toDeg(Math.atan2(-right, -forward)) + 360) % 360;
function ownEdge(impactDeg, lenM = 4.5, widthM = 1.85) {
  const r = toRad(impactDeg);
  return Math.min(lenM / 2 / Math.max(Math.abs(Math.cos(r)), 1e-6), widthM / 2 / Math.max(Math.abs(Math.sin(r)), 1e-6));
}
function estimateOther(own, impactDeg, carLenM = 4.5) {
  const dir = own.yaw - toRad(impactDeg);
  return { x: own.x + carLenM * Math.cos(dir), y: own.y + carLenM * Math.sin(dir), yaw: dir + Math.PI };
}

// ── shared/java/com/askthecar/CrashTypes.kt ──
const KIND_LABEL = { VEHICLE: '차', OBJECT: '벽·시설물', PERSON: '사람·자전거', UNKNOWN: '확인 안 됨' };
const KIND_CHOICES = ['VEHICLE', 'OBJECT', 'PERSON'];
const isPhoto = art => art === 'CAMERA' || art === 'CAMERA_FAR';
function skipped(art, near, moved, person) {
  switch (art) {
    case 'DONT_MOVE': return !near && !person;
    case 'MOVE_RISK': case 'POLICE': return near;
    case 'MOVE_LATER': return near || moved;
    default: return false;
  }
}
function guideTarget(arts, from, dir, skip) {
  let i = from + dir;
  while (i >= 0 && i < arts.length && arts[i] != null && skip(arts[i])) i += dir;
  return i;
}
const FireGuide = {
  LINK: '불이나 연기가 나면',
  TITLE: '바로 차에서 멀어져 주세요',
  BODY: '시동을 끄고 사람부터 멀리 떨어져 주세요. 고속도로면 가드레일 밖으로요. 터널이면 연기 반대쪽으로 유도등을 따라 나가 주세요. 피한 뒤 119에 전화해 전기차인지도 말해 주세요. 다른 사람이 차에 다가오지 못하게 하고, 새는 액체는 만지지 말고, 담배나 불은 쓰지 마세요.',
};
const PhotoGuide = {
  part(deg) { return ['앞', '앞 오른쪽', '오른쪽', '뒤 오른쪽', '뒤', '뒤 왼쪽', '왼쪽', '앞 왼쪽'][Math.trunc(((deg + 22.5) % 360) / 45)]; },
  shots(impactDeg, kind, highway) {
    const r = toRad(impactDeg ?? 0), f = Math.cos(r), s = Math.sin(r);
    const side = impactDeg != null ? this.part(impactDeg) + ' ' : '';
    const far = '차도로 나가지 않고 멀리서, 앞뒤 좌우 방향으로요.';
    const shot = (art, title, line, fwd, right) => ({ art, title, line, fwd, right });
    const overview = shot('OVERVIEW', '먼저 사고 현장 전체를 한 장 남겨 주세요', kind === 'PERSON' ? `${far} 다친 분의 얼굴은 찍지 마세요.` : far, f * 2.3 - s * 14, s * 2.3 + f * 14);
    const damage = shot('DAMAGE', `${side}부딪힌 곳을 가까이서 찍어 주세요`, '1m쯤 떨어져 2~3장, 긁힌 자국이 잘 보이게요.', f * 3.6, s * 2.6);
    if (highway) return [
      shot('SAFE', '가드레일 밖 안전한 곳에서만 찍으세요', '차도에는 들어가지 마세요. 위험하면 찍지 않아도 돼요.', -12, 6),
      Object.assign({}, overview, { line: '가드레일 밖에서 사고 차가 다 나오게 찍어 주세요.' }),
    ].concat(kind === 'VEHICLE' ? [shot('PLATE', '상대 차 번호판을 찍어 주세요', '안전할 때만, 가드레일 밖에서 확대해서요.', f * 9, s * 9 + 4)] : []);
    const lanes = shot('LANES', '차선과 신호등이 함께 나오게 찍어 주세요', '교차로나 차선을 바꾸던 중이었다면 특히 중요해요.', -10, 0);
    const wheel = shot('WHEEL', '앞바퀴 방향을 찍어 주세요', '핸들을 돌리기 전에요.', 3.4, 3.2);
    const marks = shot('MARKS', '타이어 자국, 부서진 조각, 기름·냉각수 흔적', '차도에 들어가지 않고 보이는 것만요.', f * 2 - s * 2.5, s * 2 + f * 2.5);
    if (kind === 'OBJECT') return [Object.assign({}, overview, { line: '차와 부딪힌 시설물이 한 화면에 나오게, 차도로 나가지 않고 멀리서요.' }), damage, wheel,
      shot('FACILITY', '부서진 시설물을 찍어 주세요', '가드레일·벽이 부서진 곳과 주변 표지판이 나오게요.', f * 6, s * 6 + 2), marks];
    if (kind === 'PERSON') return [overview, lanes, damage, wheel, marks];
    return [overview, lanes, damage, wheel, shot('PLATE', '상대 차 번호판을 찍어 주세요', '부딪힌 곳과 함께 나오면 더 좋아요.', f * 8.5, s * 8.5), marks];
  },
};

// ── shared/java/com/askthecar/ClaimPdf.kt (ClaimText.side) ──
const claimSide = deg => ['앞', '오른쪽 앞', '오른쪽', '오른쪽 뒤', '뒤', '왼쪽 뒤', '왼쪽', '왼쪽 앞'][Math.trunc((((deg + 22.5) % 360 + 360) % 360) / 45)];

// ── app/src/main/java/com/askthecar/Report.kt ──
const FactTimeline = {
  ts: tsKST,
  side: claimSide,
  /** impactT·peakG·impactDeg = 감지기가 가진 사고 순간 값(impact.json) */
  build(log, impactT = null, peakG = 0, impactDeg = null, manual = false) {
    if (log.length === 0) return [];
    const ts = tsKST, n = n1;
    const dir = d => Math.abs(d) < 2.0 ? 0 : (d < 0 ? -1 : 1);
    const first = log[0];
    const lastAtOrBefore = t => { for (let i = log.length - 1; i >= 0; i--) if (log[i].t <= t) return log[i]; return first; };
    const facts = [`${ts(first.t)} 기록 시작: 속도 ${n(first.speedKph)} km/h, 앞바퀴 각도 ${n(first.steerDeg)}°`];
    let impactLine = impactT == null ? null
      : manual ? `${ts(impactT)} 운전자가 사고 기록을 시작함`
        : peakG >= 1.0 ? `${ts(impactT)} 충격 감지 (최대 ${n(peakG)}g${impactDeg != null ? `, ${claimSide(impactDeg)} 방향 [추정]` : ''})`
          : `${ts(impactT)} 사고 감지 (가속도 센서 충격 기록 없음)`;
    const accelerating = c => c.speedKph - lastAtOrBefore(c.t - 1.0).speedKph >= 4.0;
    const lampSet = s => new Set(s.split(',').filter(x => x.trim() !== ''));
    for (let i = 0; i + 1 < log.length; i++) {
      const p = log[i], c = log[i + 1];
      if (impactLine != null && c.t >= impactT) { facts.push(impactLine); impactLine = null; }
      if (accelerating(c) && !accelerating(p)) facts.push(`${ts(c.t)} 가속 시작 (속도 ${n(c.speedKph)} km/h)`);
      const pl = lampSet(p.lamps), cl = lampSet(c.lamps);
      for (const x of cl) if (!pl.has(x)) facts.push(`${ts(c.t)} 계기판 경고등 켜짐: ${x}`);
      for (const x of pl) if (!cl.has(x)) facts.push(`${ts(c.t)} 계기판 경고등 꺼짐: ${x}`);
      if (c.signal !== p.signal) facts.push(c.signal === 'L' ? `${ts(c.t)} 좌측 방향지시등 켜짐` : c.signal === 'R' ? `${ts(c.t)} 우측 방향지시등 켜짐` : `${ts(c.t)} 방향지시등 꺼짐`);
      if (c.brake !== p.brake) facts.push(`${ts(c.t)} 브레이크 페달 ${c.brake ? '밟음' : '뗌'} (속도 ${n(c.speedKph)} km/h)`);
      if (dir(c.steerDeg) !== dir(p.steerDeg)) {
        const d = dir(c.steerDeg);
        facts.push(d === 0 ? `${ts(c.t)} 조향 직진으로 복귀` : d === -1 ? `${ts(c.t)} 좌측 조향 시작 (앞바퀴 각도 ${n(c.steerDeg)}°)` : `${ts(c.t)} 우측 조향 시작 (앞바퀴 각도 ${n(c.steerDeg)}°)`);
      }
      if (c.cruise !== p.cruise) facts.push(`${ts(c.t)} 크루즈 컨트롤 ${c.cruise ? '켜짐' : '꺼짐'}`);
      if (c.gear !== p.gear) facts.push(`${ts(c.t)} 기어 ${c.gear}단으로 변경`);
      if (c.airbag && !p.airbag) {
        const before = lastAtOrBefore(c.t - 1.0);
        facts.push(`${ts(c.t)} 에어백 전개 신호 (1초 전 속도 ${n(before.speedKph)} km/h → 현재 ${n(c.speedKph)} km/h, 앞바퀴 각도 ${n(c.steerDeg)}°)`);
      }
    }
    if (impactLine != null) facts.push(impactLine);
    facts.push(`${ts(log[log.length - 1].t)} 기록 종료: 속도 ${n(log[log.length - 1].speedKph)} km/h`);
    return facts;
  },
};
/** LLM 문장이 사실 목록을 벗어나지 않았는지 (웹 버전엔 LLM 이 없어 사실 목록이 그대로 경위서) */
const Validator = {
  FORBIDDEN: ['과실', '잘못', '책임', '가해', '피해자', '부주의', '과속', '위반', '탓', '급하게', '세게'],
  numbers(s) {
    const TIME = /\d{2}:\d{2}:\d{2}\.\d/g, NUM = /-?\d+(?:\.\d+)?/g;
    const out = new Set((s.match(TIME) || []));
    for (const m of (s.replace(TIME, ' ').match(NUM) || [])) { const v = Number(m); out.add(Object.is(v, -0) ? '-0.0' : Number.isInteger(v) ? v + '.0' : String(v)); }
    return out;
  },
  problems(llmText, facts) {
    const allowed = new Set(); facts.forEach(f => this.numbers(f).forEach(x => allowed.add(x)));
    const bad = [...this.numbers(llmText)].filter(x => !allowed.has(x));
    return bad.map(x => `로그에 없는 숫자: ${x}`).concat(this.FORBIDDEN.filter(w => llmText.includes(w)).map(w => `판단 단어: ${w}`));
  },
  choose(llmText, facts) { return (llmText && llmText.trim() && this.problems(llmText, facts).length === 0) ? llmText : facts.join('\n'); },
};

// ── app/src/main/java/com/askthecar/Guide.kt ──
const GuideMode = { DETAIL: '상세', BRIEF: '간결', VOICE: '음성' };
const Group = { NEWBIE: '사회초년생', WORKER: '사회인', SENIOR: '고령자' };
const contact = (name, phone) => ({ name, phone });
function personaSummary(p) {
  return [p.name, p.age > 0 ? `${p.age}세` : null, p.job !== Personas.guest.job ? p.job : null, p.rental ? '렌터카' : '자차'].filter(Boolean).join(' · ');
}
const Personas = {
  all: [
    { id: 'haneul', name: '김하늘', age: 26, job: '신입 디자이너', group: 'NEWBIE', rental: true, insurance: '렌터카 자차손해면책 가입', mode: 'DETAIL',
      insurer: null, rentalCompany: contact('바로카셰어 사고접수 (가상)', '02-000-2002'), guardian: contact('어머니', '010-0000-1111') },
    { id: 'junho', name: '박준호', age: 41, job: '영업팀장', group: 'WORKER', rental: false, insurance: '개인용 자동차보험 + 긴급출동 특약', mode: 'BRIEF',
      insurer: contact('한결손해보험 사고접수 (가상)', '02-000-1001'), rentalCompany: null, guardian: null },
    { id: 'sunja', name: '이순자', age: 72, job: '은퇴', group: 'SENIOR', rental: false, insurance: '개인용 자동차보험', mode: 'VOICE',
      insurer: contact('한결손해보험 사고접수 (가상)', '02-000-1001'), rentalCompany: null, guardian: contact('딸 박지은', '010-0000-2222') },
  ],
  guest: { id: 'guest', name: '운전자', age: 0, job: '미등록', group: 'NEWBIE', rental: false, insurance: '미등록', mode: 'DETAIL', insurer: null, rentalCompany: null, guardian: null },
  byId(id) { return this.all.find(p => p.id === id) || this.guest; },
};

/** 실차 데이터로 추정한 사고 대상 (모르면 null = 운전자가 고름) */
function inferKind(log, crashT, impactDeg, seen = null) {
  if (seen && Math.hypot(seen.fwd, seen.right) < 15) {
    const person = ['person', 'bicycle', 'motorcycle'].includes(seen.label);
    if (!person) return { kind: 'VEHICLE', why: '카메라에 차가 가까이 보여요' };
    if (seen.score >= 0.6) return { kind: 'PERSON', why: '카메라에 사람·자전거가 가까이 보여요' };
  }
  let before = null;
  for (let i = log.length - 1; i >= 0; i--) if (log[i].t <= crashT - 0.3) { before = log[i]; break; }
  if (!before) return null;
  const rear = impactDeg != null && impactDeg >= 135 && impactDeg <= 225;
  if (before.speedKph < 3 || before.gear === 'P') return { kind: 'VEHICLE', why: '멈춰 있을 때 충격이 기록됐어요' };
  if (rear && before.gear === 'R') return null;
  if (rear) return { kind: 'VEHICLE', why: '앞으로 가는 중 뒤쪽 충격이 기록됐어요' };
  return null;
}

const Guides = (() => {
  const step = (art, title, body, o = {}) => Object.assign({ art, title, body, voice: `${title}. ${body}`, call: null, brief: false, tip: null, decline: null, callName: null, next: null, call2: null }, o);
  const operatorCall = step('PHONE', '차로 오는 전화를 받아 주세요',
    '에어백이 터지면 차의 긴급 구난 센터에서 전화가 올 수 있어요. 안 오면 기다리지 말고 휴대폰으로 119에 전화해 주세요. 흰 가루는 에어백에서 나온 거예요. 창문을 열고, 운전대 안쪽은 뜨거우니 만지지 마세요.', { brief: true });
  const calm = step('CALM', '괜찮아요. 지금부터는 차가 함께할게요', '해야 할 일을 하나씩, 순서대로 알려 드릴게요.',
    { voice: '괜찮아요. 지금부터는 차가 함께할게요. 해야 할 일을 하나씩, 순서대로 알려 드릴게요.', next: '시작할게요' });
  const hurtCall = highway => step('HURT_CALL', '119에 먼저 전화해 주세요',
    highway ? "움직일 수 있으면 가드레일 밖으로 나가서 전화해 주세요. 못 나가면 '고속도로 차 안인데 못 나간다'고 먼저 말해 주세요. 말하기 어려우면 119로 문자를 보내 주세요."
      : '위치와 다친 곳을 말하고 전화를 끊지 말아 주세요. 주변이 위험하지 않으면 움직이지 마세요. 말하기 어려우면 119로 문자를 보내 주세요.',
    { call: '119', decline: '전화했어요', brief: true });
  const roadAsk = step('ROAD', '지금 고속도로나 자동차전용도로인가요?', '', { voice: '지금 고속도로나 자동차전용도로인가요?', next: '네', decline: '아니요', brief: true });
  const hazard = body => step('HAZARD', '비상등을 켜 주세요', body, { brief: true, tip: '비상등은 보통 운전대 근처의 빨간 삼각형 버튼이에요.', next: '켰어요' });
  const cityHazard = hazard('뒤에서 오는 차가 이 차를 알아볼 수 있어요.');
  const highwayHazard = hazard('뒤에서 오는 차가 이 차를 알아볼 수 있어요. 차가 움직이면 갓길로 옮겨 세워 주세요.');
  const trunk = step('TRUNK', '트렁크를 열어 두세요', '뒤차가 사고 차임을 알아보기 쉬워져요. 버튼을 누르면 차가 열어 드려요.', { brief: true });
  const highwayWalk = step('WALK', '휴대폰을 들고 가드레일 밖으로 대피해 주세요',
    '동승자도 함께요. 차 안, 차 앞뒤, 갓길은 위험해요. 가드레일이 없으면 차도에서 최대한 떨어져 주세요. 다음 안내는 휴대폰에서 이어져요.', { brief: true, next: '휴대폰으로 이어 볼게요' });
  const cityWalk = kind => step('WALK', '도로 밖 안전한 곳으로 나와 주세요',
    '차도에는 서 있지 마세요. 현장을 떠나지는 말아 주세요.' + (kind === 'OBJECT' ? ' 끊어진 전선이 차에 닿아 있으면 내리지 말고 119에 전화해 주세요.' : ''), { brief: true, next: '나왔어요' });
  const handoff = step('HANDOFF', '이제 휴대폰으로 이어서 볼게요', '차에서 내리면 휴대폰에 같은 안내와 사고 기록이 보여요.', { brief: true, next: '차에서 계속 볼게요' });
  const report = severe => step('PHONE', '가드레일 밖에서 신고해 주세요',
    '이정표, 출구 번호, 도로 번호로 위치를 말해 주세요. 사람이 다쳤으면 112에 꼭 알려 주세요. 긴급견인은 1588-2504에 부탁할 수 있어요. 삼각대는 위험 없이 세울 수 있을 때만 세우고, 못 세웠으면 신고할 때 말해 주세요.' +
    (severe ? ' 차로 오는 전화를 받으러 돌아가지 마세요.' : ''), { call: '112', call2: '1588-2504', decline: '신고했어요', brief: true });
  const wait = step('WAIT', '차로 돌아가지 말고 기다려 주세요',
    '경찰이나 견인 기사, 구조대가 올 때까지 가드레일 밖에 있어 주세요. 차에 못 나온 사람이 있으면 119에 바로 말하고 안내를 따라 주세요.', { brief: true, next: '알겠어요' });
  const injured = step('PERSON', '다친 사람이 있나요?', '119에 위치, 다친 사람 수, 의식과 호흡, 심한 출혈, 갇힌 사람이 있는지 말해 주세요. 전화를 끊지 말고 안내를 따라 주세요.', { call: '119', decline: '없어요', brief: true });
  const dontMove = step('DONT_MOVE', '다친 분은 옮기지 마세요', '불이 나거나 2차 사고 위험이 없으면 그대로 두고 곁에 있어 주세요. 피가 많이 나면 깨끗한 천으로 세게 눌러 주세요. 상대가 괜찮다고 해도 떠나지 말고 112에 알려 주세요.',
    { call: '112', decline: '알렸어요', brief: true });
  const moveRisk = step('MOVE_RISK', '차가 위험한 자리에 있나요?', '뒤차에 부딪힐 것 같으면 사진보다 먼저 길 가장자리로 옮겨 주세요.', { next: '옮겼어요', decline: '위험하지 않아요', brief: true });
  const moveLater = step('MOVE_LATER', '차를 길 가장자리로 옮겨 주세요', '차가 움직일 때만요. 경찰이 기다리라고 했으면 따라 주세요.', { next: '옮겼어요', decline: '옮길 수 없어요', brief: true });
  const facility = step('FACILITY', '부서진 시설물이 있나요?', '혼자 부딪혔어도 가드레일·벽·신호등이 부서졌다면 112에 알려 주세요.', { call: '112', decline: '없어요', brief: true });
  const police = step('POLICE', '경찰에 알릴지 확인해 주세요',
    '신고하지 않아도 되는 건 차만 부서진 게 분명하고 차를 안전하게 정리했을 때뿐이에요. 조금이라도 다쳤거나, 시설물이 부서졌거나, 잘 모르겠으면 112에 알려 주세요.', { call: '112', decline: '해당 없어요', brief: true });
  const EXCHANGE_BODY = '이름, 전화번호, 주소를 알려 주세요. 상대 차 번호와 보험사도 적어 두세요. 본 사람이 있으면 연락처를 받아 두세요. 누구 잘못인지는 지금 정하지 않아도 돼요. 경찰이 아닌 사람이 면허증을 달라거나 잘못을 인정하는 글을 쓰라고 하면 거절해도 돼요.';
  const exchange = highway => step('EXCHANGE', '상대방과 연락처를 주고받아 주세요', (highway ? '경찰이나 견인 기사가 온 뒤 안전한 곳에서 해 주세요. ' : '') + EXCHANGE_BODY, { brief: true });
  const guardianStep = c => step('GUARDIAN', `${c.name}에게 알려 주세요`, '사고가 났다는 것과 지금 있는 곳만 말해도 충분해요.', { call: c.phone, decline: '나중에 할게요', callName: c.name });
  const NO_NUMBER = '번호는 보험 증권이나 대여 계약서, 앱에서 찾아 주세요.';
  const RENTAL_BODY = '사고가 나면 바로 알려 주세요. 합의나 견인, 수리할 곳은 회사와 먼저 상의해 주세요.';
  const INSURER_BODY = '견인이 필요하면 긴급출동도 함께 요청하세요. 낯선 견인차나 렌터카는 현장에서 바로 정하지 않아도 돼요. 보험사에 알리지 않고 합의하지 마세요.';
  function contactStep(p) {
    if (p.rental) return step('INSURER', '렌터카 회사에 알려 주세요', RENTAL_BODY + ' ' + (p.rentalCompany ? '대여 계약서에 있는 사고 접수 번호예요.' : NO_NUMBER),
      { call: p.rentalCompany?.phone ?? null, decline: p.rentalCompany ? '이미 알렸어요' : null, brief: true, callName: p.rentalCompany?.name ?? null, tip: '자차손해면책은 사고 때 내가 낼 수리비를 줄여 주는 렌터카 계약이에요.' });
    return step('INSURER', '보험사에 사고를 접수해 주세요', p.insurer ? `${p.insurer.name} · ${INSURER_BODY}` : `${INSURER_BODY} ${NO_NUMBER}`,
      { call: p.insurer?.phone ?? null, decline: p.insurer ? '이미 접수했어요' : null, brief: true, callName: p.insurer?.name ?? null });
  }
  const cityPhoto = step('CAMERA', '차를 옮기기 전에 사진을 남겨 주세요', '차도에 들어가야 하거나 위험하면 찍지 않아도 돼요. 블랙박스가 있어도 사진은 따로 남겨 두면 좋아요.', { brief: true, next: '찍을 곳 알려 주세요', decline: '건너뛰기' });
  const highwayPhoto = step('CAMERA_FAR', '사진은 꼭 찍지 않아도 돼요', '지금은 안전이 먼저예요. 가드레일 밖에서 차도에 들어가지 않고 보이는 것만 찍어 주세요. 가까이 가서 찍지 마세요. 주행 기록은 저장되어 있지만 사진을 대신하지는 않아요.', { brief: true, next: '가드레일 밖에서 찍기', decline: '찍지 않을게요' });
  const cityBlackbox = step('CARD', '블랙박스가 녹화됐는지 확인해 주세요', '차 옆이 안전할 때만요. 영상은 지우지 말고, 차를 맡기기 전에 카드를 챙겨 주세요.', { brief: true, next: '확인했어요', decline: '나중에 할게요' });
  const laterBlackbox = step('CARD_LATER', '블랙박스 카드는 나중에 챙겨 주세요', '지금은 차로 돌아가지 마세요. 차가 안전한 곳으로 옮겨진 뒤, 수리나 폐차로 맡기기 전에 챙겨 주세요.', { brief: true, next: '알겠어요' });
  const replay = step('REPLAY', '기록으로 다시 그린 사고 장면이에요', '영상이 아니라 주행 기록으로 그린 [추정] 장면이에요.', { brief: true });
  const record = step('RECORD', '기록은 안전하게 저장해 두었어요', '경위 설명문과 기록서를 언제든 다시 볼 수 있어요.', { brief: true });
  function forHurt(s, highway) {
    const body = highway ? '움직일 수 있으면 가드레일 밖으로 나와 주세요. 못 나오면 119에 그렇게 말하고 안내를 따라 주세요. 불이나 연기가 보이면 바로 차에서 멀어져 주세요.'
      : '주변이 위험하지 않으면 움직이지 말고 119 안내를 따라 주세요. 불이나 연기가 보이면 바로 차에서 멀어져 주세요.';
    return Object.assign({}, s, { body, voice: `${s.title}. ${body}`, next: highway ? s.next : '다음' });
  }
  function steps(highway, p = Personas.guest, severe = false, kind = 'UNKNOWN', hurt = false, askRoad = false, near = false) {
    const person = kind === 'PERSON';
    let walk = highway ? highwayWalk : cityWalk(kind);
    if (hurt) walk = forHurt(walk, highway);
    const injury = [hurt ? null : injured, (!hurt || person) ? dontMove : null].filter(Boolean);
    const canMove = !hurt && !person;
    const hasOther = kind !== 'OBJECT';
    const card = (highway || hurt || severe || near) ? laterBlackbox : cityBlackbox;
    const guardian = p.guardian ? guardianStep(p.guardian) : null;
    const head = [hurt ? hurtCall(highway) : null, askRoad ? roadAsk : null].filter(Boolean);
    const body = highway
      ? [highwayHazard, trunk, walk, ...injury, ...[report(severe), kind === 'OBJECT' ? facility : null, wait, guardian, contactStep(p), highwayPhoto, card, hasOther ? exchange(true) : null].filter(Boolean)]
      : [...[severe ? operatorCall : null, (p.group === 'NEWBIE' && !hurt) ? calm : null, cityHazard].filter(Boolean), ...injury,
        ...[canMove ? moveRisk : null, walk, handoff, kind === 'OBJECT' ? facility : (canMove ? police : null),
          hasOther ? exchange(false) : null, guardian, contactStep(p), card, cityPhoto, canMove ? moveLater : null].filter(Boolean)];
    const all = [...head, ...body, replay, record];
    return p.mode === 'BRIEF' ? all.filter(s => s.brief) : all;
  }
  return { steps };
})();

/** 참고 화면의 "비슷한 사고 유형" 후보 (판단 없이 유형 이름과 근거 사실만) */
function typeCandidates(log, crashT, impactDeg, kind, chosen = true) {
  const ts = tsKST;
  const recent = log.filter(s => s.t >= crashT - 5 && s.t <= crashT);
  const last = recent[recent.length - 1] || null;
  const fastPool = log.filter(s => s.t >= crashT - 2 && s.t <= crashT);
  const fast = fastPool.reduce((m, s) => (m == null || s.speedKph > m.speedKph) ? s : m, null);
  const signal = recent.find(s => s.signal !== 'none') || null;
  const steerMax = recent.reduce((m, s) => (m == null || Math.abs(s.steerDeg) > Math.abs(m.steerDeg)) ? s : m, null);
  const steer = steerMax && Math.abs(steerMax.steerDeg) >= 2 ? steerMax : null;
  const bigSteer = steer && Math.abs(steer.steerDeg) >= 10 ? steer : null;
  const deg = impactDeg == null ? null : ((impactDeg % 360) + 360) % 360;
  const front = deg != null && (deg <= 45 || deg >= 315), rear = deg != null && deg >= 135 && deg <= 225, side = deg != null && !front && !rear;
  const reverse = last?.gear === 'R', forward = !reverse && (fast?.speedKph ?? 0) >= 5;
  const impactFact = deg != null ? `${ts(crashT)} 충격 ${claimSide(deg)} 방향 [추정]` : null;
  const speedFact = fast ? `${ts(fast.t)} 내 차 속도 ${f0(fast.speedKph)} km/h` : null;
  const kindFact = kind !== 'UNKNOWN' ? `사고 종류: ${KIND_LABEL[kind]} (${chosen ? '운전자 선택' : '기록으로 추정'})` : null;
  const steerFact = steer ? `${ts(steer.t)} 앞바퀴 ${steer.steerDeg < 0 ? '좌측' : '우측'} ${f1(Math.abs(steer.steerDeg))}°` : null;
  const signalFact = signal ? `${ts(signal.t)} 방향지시등 ${signal.signal === 'L' ? '좌측' : '우측'} 켜짐` : null;
  const c = (name, ...f) => ({ name, facts: f.filter(Boolean) });
  return [
    kind === 'PERSON' ? c('차와 보행자 사고 유형', kindFact, impactFact) : null,
    reverse ? c('후진 중 사고 유형', last ? `${ts(last.t)} 기어 R단` : null, impactFact, speedFact) : null,
    (bigSteer && (front || side) && !reverse && kind !== 'PERSON') ? c('교차로 회전 중 사고 유형', steerFact, impactFact, speedFact) : null,
    (side && (signal || steer) && !bigSteer && !reverse && kind !== 'PERSON') ? c('진로 변경 중 접촉 유형', signalFact, steerFact, impactFact) : null,
    (front && forward && kind !== 'OBJECT' && kind !== 'PERSON') ? c('앞차와의 추돌 유형', impactFact, speedFact, kindFact) : null,
    (rear && !reverse && kind !== 'OBJECT' && kind !== 'PERSON') ? c('뒤차에 추돌된 유형', impactFact, speedFact, kindFact) : null,
    (kind === 'OBJECT' && deg != null) ? c('벽·시설물과 부딪힌 유형', kindFact, impactFact, speedFact) : null,
  ].filter(Boolean).slice(0, 3);
}

// ── app/src/main/java/com/askthecar/CarHome.kt ──
const RecStage = { RECORDING: 'RECORDING', SAVING: 'SAVING', SAVED: 'SAVED', UPLOADED: 'UPLOADED', ARRIVED: 'ARRIVED', FAILED: 'FAILED' };
const AFTER_SEC = 10, BEFORE_SEC = 30;
function recStage(elapsed, saved, acked, arrived, failed) {
  if (failed) return RecStage.FAILED;
  if (!saved) return elapsed < AFTER_SEC ? RecStage.RECORDING : RecStage.SAVING;
  if (!acked) return RecStage.SAVED;
  if (!arrived) return RecStage.UPLOADED;
  return RecStage.ARRIVED;
}
function recLabel(stage, elapsed, savedAt) {
  switch (stage) {
    case RecStage.RECORDING: return `기록 중 0:${pad2(clamp(Math.trunc(elapsed), 0, 9))}`;
    case RecStage.SAVING: return '저장 중';
    case RecStage.SAVED: return '차에 저장됨' + (savedAt ? ' ' + savedAt : '');
    case RecStage.UPLOADED: return '서버에 올라감';
    case RecStage.ARRIVED: return '휴대폰에 도착';
    default: return '기록 저장에 실패했어요';
  }
}
/** 충격 난 면이 보이는 3/4 렌더: 오른쪽·뒤 = 뒷모습, 왼쪽·앞 = 앞모습 */
function carViewFor(deg) {
  if (deg == null) return 'rear';
  const d = ((deg % 360) + 360) % 360;
  return (d >= 22.5 && d <= 202.5) ? 'rear' : 'front';
}
function pastCrashTitle(kind, deg) {
  const sideText = d => PhotoGuide.part(d).replace(/쪽$/, '') + '쪽 충격';
  if (kind && deg != null) return `${KIND_LABEL[kind]} · ${sideText(deg)}`;
  if (kind) return KIND_LABEL[kind];
  if (deg != null) return sideText(deg);
  return '충격 기록';
}
