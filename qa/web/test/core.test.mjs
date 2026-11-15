// Port checks: the JS copy must give the same answers as the Kotlin unit tests (ReportTest etc.), times shifted to KST.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { loadParts, CORE } from './load.mjs';

const X = loadParts(CORE);
let pass = 0;
const t = (name, fn) => { fn(); pass++; console.log('ok  ', name); };
const arts = l => Array.from(l, s => s.art);
const seen = (l, near = false, moved = false, person = false) => Array.from(l).filter(s => !X.skipped(s.art, near, moved, person)).map(s => s.art);

t('sha256 matches node crypto', () => {
  for (const s of ['', 'abc', '한글 기록 🚗', 'x'.repeat(1000)]) assert.equal(X.sha256Hex(s), crypto.createHash('sha256').update(s).digest('hex'));
});

t('tsKST truncates tenths and uses KST', () => {
  assert.equal(X.tsKST(0), '09:00:00.0');
  assert.equal(X.tsKST(8.19), '09:00:08.1');
  assert.equal(X.dirName(1700000000), '20231115_071320');
  assert.equal(X.parseDirName('20231115_071320'), 1700000000);
});

// ReportTest: fake_drive 시나리오를 줄인 것
const log = Array.from({ length: 100 }, (_, i) => { const tt = i / 10; return X.Sample(tt, { speedKph: tt < 8 ? 50 : 0, steerDeg: tt >= 5 ? -8 : 0, brake: tt >= 7.5, signal: tt >= 3 ? 'L' : 'none', airbag: tt >= 8.1 }); });
const facts = X.FactTimeline.build(log);
t('timelineHasEveryEventInOrder', () => {
  const keys = ['기록 시작', '좌측 방향지시등', '좌측 조향', '브레이크 페달 밟음', '에어백', '기록 종료'];
  assert.equal(facts.length, keys.length);
  keys.forEach((k, i) => assert.ok(facts[i].includes(k), `${k} ∉ ${facts[i]}`));
  assert.ok(facts[4].startsWith('09:00:08.1'));
});
t('validatorRejectsInventedNumberAndJudgement', () => {
  assert.equal(X.Validator.problems('09:00:03.0에 좌측 방향지시등을 켠 뒤 09:00:08.1에 에어백이 전개되었다.', facts).length, 0);
  assert.ok(X.Validator.problems('속도 70 km/h로 주행', facts).length > 0);
  assert.ok(X.Validator.problems('운전자 과실로 보인다', facts).length > 0);
  assert.equal(X.Validator.choose('시속 99 km/h', facts), facts.join('\n'));
  assert.equal(X.Validator.problems('앞바퀴 각도 -8.0°로 좌측 조향', facts).length, 0);
  assert.ok(X.Validator.problems('앞바퀴 각도 8.0°로 좌측 조향', facts).length > 0);
});
t('impactIsAFactEvenWithoutAirbag', () => {
  const f = X.FactTimeline.build(log, 8.05, 3.42, 350);
  const i = f.findIndex(s => s.includes('충격 감지'));
  assert.equal(f[i], '09:00:08.0 충격 감지 (최대 3.4g, 앞 방향 [추정])');
  assert.ok(i < f.findIndex(s => s.includes('에어백')));
  assert.equal(X.claimSide(90), '오른쪽'); assert.equal(X.claimSide(-135), '왼쪽 뒤');
  const g = X.FactTimeline.build(log, 20);
  assert.ok(g[g.length - 2].endsWith('사고 감지 (가속도 센서 충격 기록 없음)'));
});
t('accelerationAndWarningLamps', () => {
  const l = Array.from({ length: 40 }, (_, i) => { const tt = i / 10; return X.Sample(tt, { speedKph: tt < 1 ? 0 : (tt - 1) * 10, lamps: (tt >= 2 && tt <= 2.9) ? 'ABS 작동' : '' }); });
  const f = X.FactTimeline.build(l);
  assert.equal(f.filter(s => s.includes('가속 시작')).length, 1);
  assert.ok(f.some(s => s.includes('경고등 켜짐: ABS 작동')) && f.some(s => s.includes('경고등 꺼짐: ABS 작동')));
});

const [haneul, junho, sunja] = X.Personas.all;
t('highwayOrder', () => {
  assert.deepEqual(seen(X.Guides.steps(true, junho)), ['HAZARD', 'TRUNK', 'WALK', 'PERSON', 'PHONE', 'WAIT', 'INSURER', 'CAMERA_FAR', 'CARD_LATER', 'EXCHANGE', 'REPLAY', 'RECORD']);
  assert.deepEqual(seen(X.Guides.steps(true, sunja)), ['HAZARD', 'TRUNK', 'WALK', 'PERSON', 'PHONE', 'WAIT', 'GUARDIAN', 'INSURER', 'CAMERA_FAR', 'CARD_LATER', 'EXCHANGE', 'REPLAY', 'RECORD']);
  const walk = X.Guides.steps(true, junho).find(s => s.art === 'WALK');
  assert.equal(walk.title, '휴대폰을 들고 가드레일 밖으로 대피해 주세요');
  const report = X.Guides.steps(true, junho).find(s => s.art === 'PHONE');
  assert.deepEqual(Array.from([report.call, report.call2, report.decline]), ['112', '1588-2504', '신고했어요']);
  assert.ok(X.Guides.steps(true, junho, true).find(s => s.art === 'PHONE').body.includes('차로 오는 전화를 받으러 돌아가지 마세요.'));
});
t('cityOrder', () => {
  assert.deepEqual(seen(X.Guides.steps(false, junho)), ['HAZARD', 'PERSON', 'MOVE_RISK', 'WALK', 'HANDOFF', 'POLICE', 'EXCHANGE', 'INSURER', 'CARD', 'CAMERA', 'MOVE_LATER', 'REPLAY', 'RECORD']);
  assert.deepEqual(seen(X.Guides.steps(false, haneul)), ['CALM', 'HAZARD', 'PERSON', 'MOVE_RISK', 'WALK', 'HANDOFF', 'POLICE', 'EXCHANGE', 'GUARDIAN', 'INSURER', 'CARD', 'CAMERA', 'MOVE_LATER', 'REPLAY', 'RECORD']);
  assert.deepEqual(seen(X.Guides.steps(false, junho, false, 'PERSON'), false, false, true), ['HAZARD', 'PERSON', 'DONT_MOVE', 'WALK', 'HANDOFF', 'EXCHANGE', 'INSURER', 'CARD', 'CAMERA', 'REPLAY', 'RECORD']);
  assert.deepEqual(seen(X.Guides.steps(false, junho, false, 'UNKNOWN', false, false, true), true), ['HAZARD', 'PERSON', 'DONT_MOVE', 'WALK', 'HANDOFF', 'EXCHANGE', 'INSURER', 'CARD_LATER', 'CAMERA', 'REPLAY', 'RECORD']);
});
t('hurtPath + airbagPath', () => {
  assert.deepEqual(arts(X.Guides.steps(false, haneul, false, 'UNKNOWN', true)), ['HURT_CALL', 'HAZARD', 'WALK', 'HANDOFF', 'EXCHANGE', 'GUARDIAN', 'INSURER', 'CARD_LATER', 'CAMERA', 'REPLAY', 'RECORD']);
  assert.deepEqual(arts(X.Guides.steps(true, junho, false, 'UNKNOWN', true)), ['HURT_CALL', 'HAZARD', 'TRUNK', 'WALK', 'PHONE', 'WAIT', 'INSURER', 'CAMERA_FAR', 'CARD_LATER', 'EXCHANGE', 'REPLAY', 'RECORD']);
  const c = X.Guides.steps(false, haneul, true);
  assert.equal(c[0].title, '차로 오는 전화를 받아 주세요');
  assert.deepEqual(arts(c).slice(0, 3), ['PHONE', 'CALM', 'HAZARD']);
  assert.equal(X.Guides.steps(true, haneul, true)[0].art, 'HAZARD');
  assert.equal(X.Guides.steps(false, X.Personas.guest)[0].art, 'CALM');
});
t('inferKindUsesOnlyCarData', () => {
  const at = (speed, gear = 'D') => [X.Sample(99, { speedKph: speed, gear }), X.Sample(100, { speedKph: 0, gear })];
  assert.equal(X.inferKind(at(0), 100, 0)?.kind, 'VEHICLE');
  assert.equal(X.inferKind(at(40), 100, 180)?.kind, 'VEHICLE');
  assert.equal(X.inferKind(at(5, 'R'), 100, 180), null);
  assert.equal(X.inferKind(at(40), 100, 0), null);
});
t('slowSignalIsNotSuddenDrop + impactFromDeg', () => {
  assert.ok(X.suddenDrop(20, 0.1)); assert.ok(!X.suddenDrop(18, 1.0)); assert.ok(X.suddenDrop(80, 1.0)); assert.ok(!X.suddenDrop(10, 0.1));
  assert.ok(Math.abs(X.impactFromDeg(0, -30) - 0) < 1e-9); assert.ok(Math.abs(X.impactFromDeg(-30, 0) - 90) < 1e-9); assert.ok(Math.abs(X.impactFromDeg(0, 30) - 180) < 1e-9);
});
t('reverseGoesBackward + trackTurnsLeft', () => {
  const s = Array.from({ length: 20 }, (_, i) => X.Sample(i * 0.1, { speedKph: 36, gear: 'R' }));
  assert.ok(Math.abs(X.trackPoses(s).at(-1).x + 19) < 0.5);
  assert.ok(X.trackPoses(log).at(-1).y > 0);
});
t('recStage', () => {
  assert.equal(X.recStage(7, false, false, false, false), 'RECORDING');
  assert.equal(X.recStage(10.5, false, false, false, false), 'SAVING');
  assert.equal(X.recStage(12, true, true, true, false), 'ARRIVED');
  assert.equal(X.recStage(3, false, false, true, false), 'RECORDING');
});
t('ImpactSensor TC-01 rows (fresh sensor each row)', () => {
  const G = X.GRAVITY_EARTH, mul = (a, b) => Math.fround(Math.fround(a) * Math.fround(b));
  const rows = [['A', [0, G, mul(2.54, G)], 2.4892, 0], ['B', [0, G, mul(2.56, G)], 2.5088, 1], ['C', [0, G, mul(-2.56, G)], 2.5088, 1], ['D', [0, Math.fround(G + mul(2.56, G)), 0], 2.5088, 1]];
  for (const [name, v, g, n] of rows) {
    const clock = new X.SimClock(1700000000);
    const calls = [];
    const s = new X.ImpactSensor({ clock }, gg => calls.push(gg));
    s.onSensorChanged('accel', [0, G, 0], 0);
    assert.equal(s.lastG, 0); assert.equal(calls.length, 0);
    clock.t = 0.02;
    s.onSensorChanged('accel', v, 20e6);
    assert.ok(Math.abs(s.lastG - g) <= 1e-5, `${name} g=${s.lastG}`);
    assert.equal(calls.length, n, name);
  }
});
console.log(`\n${pass} checks passed`);
