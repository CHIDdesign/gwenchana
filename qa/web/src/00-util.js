// ===== 00-util.js — 공용 도구 (DOM 없음, Node 시험에서도 그대로 씀) =====
const KST_SEC = 9 * 3600;
/** 어떤 앱 코드를 흉내 낼지. fixed = 수정본(TC-04·DRV-12 고침), original = 공개본 main 5ffe61e(심사용 APK와 같은 코드) */
const VARIANT = { fixed: true, get id() { return this.fixed ? 'fixed' : 'original'; }, get label() { return this.fixed ? '수정본' : '공개본 5ffe61e'; } };
/** android.hardware.SensorManager.GRAVITY_EARTH (float 상수) */
const GRAVITY_EARTH = Math.fround(9.80665);
const pad2 = n => String(n).padStart(2, '0');
function kstParts(t) {
  const d = new Date(Math.trunc(t * 1000) + KST_SEC * 1000);
  return { Y: d.getUTCFullYear(), M: d.getUTCMonth() + 1, D: d.getUTCDate(), h: d.getUTCHours(), m: d.getUTCMinutes(), s: d.getUTCSeconds(), ms: d.getUTCMilliseconds() };
}
/** FactTimeline.ts: "HH:mm:ss.S", 한국 시각, 1/10초는 버림 (Java DateTimeFormatter 와 같음) */
function tsKST(t) { const p = kstParts(t); return `${pad2(p.h)}:${pad2(p.m)}:${pad2(p.s)}.${Math.floor(p.ms / 100)}`; }
/** 사고 폴더 이름 yyyyMMdd_HHmmss (기기 시간대 Asia/Seoul) */
function dirName(t) { const p = kstParts(t); return `${p.Y}${pad2(p.M)}${pad2(p.D)}_${pad2(p.h)}${pad2(p.m)}${pad2(p.s)}`; }
function parseDirName(name) {
  const m = /^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})$/.exec(name);
  return m ? Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) / 1000 - KST_SEC : null;
}
/** Kotlin "%.1f"/"%.0f" (HALF_UP, 정확한 이진값 기준) = toFixed */
const f1 = v => v.toFixed(1), f0 = v => v.toFixed(0);
/** FactTimeline 의 n(): "-0.0°" 방지 */
const n1 = v => f1(Math.abs(v) < 0.05 ? 0 : v);
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}
function gauss(rng) { let u = 0, v = 0; while (u === 0) u = rng(); while (v === 0) v = rng(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const wrapPi = a => Math.atan2(Math.sin(a), Math.cos(a));
const toDeg = r => r * 180 / Math.PI, toRad = d => d * Math.PI / 180;

// SHA-256 (동기). 기록 지문(log.sha256) 계산용. Node 시험에서 crypto 결과와 대조함
const SHA_K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const l = bytes.length, buf = new Uint8Array(((l + 9 + 63) >> 6) << 6);
  buf.set(bytes); buf[l] = 0x80;
  const dv = new DataView(buf.buffer), bits = l * 8;
  dv.setUint32(buf.length - 4, bits >>> 0); dv.setUint32(buf.length - 8, Math.floor(bits / 4294967296));
  const ror = (x, n) => (x >>> n) | (x << (32 - n));
  let H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const W = new Uint32Array(64);
  for (let o = 0; o < buf.length; o += 64) {
    for (let i = 0; i < 16; i++) W[i] = dv.getUint32(o + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = ror(W[i - 15], 7) ^ ror(W[i - 15], 18) ^ (W[i - 15] >>> 3), s1 = ror(W[i - 2], 17) ^ ror(W[i - 2], 19) ^ (W[i - 2] >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + SHA_K[i] + W[i]) >>> 0;
      const t2 = ((ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H = [H[0] + a, H[1] + b, H[2] + c, H[3] + d, H[4] + e, H[5] + f, H[6] + g, H[7] + h].map(x => x >>> 0);
  }
  return H.map(x => x.toString(16).padStart(8, '0')).join('');
}
