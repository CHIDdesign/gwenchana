// ===== 40-render.js — 시험장 3D 화면 (three.js). 시뮬레이터 좌표 x=동, y=북 → three X = x, Z = −y, 차 rotation.y = yaw =====
// 이 파일만 THREE 를 쓴다(브라우저 전용). 물리·앱은 30-sim.js·20-app-runtime.js 에 있다.

class WorldView {
  constructor(THREE, canvas, loaders) {
    this.T = THREE; this.canvas = canvas; this.loaders = loaders;
    const T = THREE;
    this.renderer = new T.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.shadowMap.enabled = true; this.renderer.shadowMap.type = T.PCFShadowMap;
    this.renderer.outputColorSpace = T.SRGBColorSpace; this.renderer.toneMapping = T.ACESFilmicToneMapping; this.renderer.toneMappingExposure = 0.95;
    this.scene = new T.Scene();
    this.scene.background = new T.Color(0xdfe6ee); this.scene.fog = new T.Fog(0xdfe6ee, 220, 1100);
    this.camera = new T.PerspectiveCamera(58, 16 / 9, 0.5, 2500);
    this.hemi = new T.HemisphereLight(0xf2f6ff, 0x8a8f86, 1.0); this.scene.add(this.hemi);
    this.sun = new T.DirectionalLight(0xfff6ea, 1.2); this.sun.castShadow = true;
    Object.assign(this.sun.shadow.camera, { left: -70, right: 70, top: 70, bottom: -70, near: 1, far: 400 });
    this.sun.shadow.mapSize.set(2048, 2048); this.sun.shadow.bias = -0.0004; this.sun.shadow.normalBias = 0.02;
    this.scene.add(this.sun, this.sun.target);
    this.camMode = 'chase'; this.camPos = new T.Vector3(); this.camLook = new T.Vector3(); this.camInit = false;
    this.cars = new Map(); this.models = {}; this.modelsReady = false; this.tempGroup = new T.Group(); this.scene.add(this.tempGroup); this.tempKey = '';
    this.effects = []; this.world = null; this.sim = null;
    this.blob = this.makeBlobTexture();
    this.loadModels();
  }
  makeBlobTexture() {
    const c = document.createElement('canvas'); c.width = c.height = 128; const g = c.getContext('2d');
    const r = g.createRadialGradient(64, 64, 8, 64, 64, 64); r.addColorStop(0, 'rgba(0,0,0,0.55)'); r.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = r; g.fillRect(0, 0, 128, 128); const t = new this.T.CanvasTexture(c); t.colorSpace = this.T.SRGBColorSpace; return t;
  }
  loadModels() {
    const T = this.T, loader = new this.loaders.GLTFLoader();
    const list = Object.keys(MODEL_SIZE);
    // 모델은 models/<이름>.glb.json(base64) 로 싣는다: 배포 서버가 .glb 를 내주지 않아서. 없으면 .glb 를 직접
    const load = k => fetch(`models/${k}.glb.json`).then(r => { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(j => { const bin = atob(j.base64), buf = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i); return new Promise((ok, no) => loader.parse(buf.buffer, '', ok, no)); })
      .catch(() => loader.loadAsync(`models/${k}.glb`));
    Promise.allSettled(list.map(k => load(k).then(g => { this.models[k] = this.fit(g.scene, MODEL_SIZE[k][0]); })))
      .then(r => { r.forEach((x, i) => { if (x.status === 'rejected') console.warn('3D 모델 못 읽음', list[i], x.reason); }); this.modelsReady = true; for (const g of this.cars.values()) this.scene.remove(g); this.cars.clear(); });
  }
  /** 길이를 맞추고 바닥에 세운다. 모델은 +Z 를 바라봄 → +X(시뮬레이터 앞)로 돌림 */
  fit(obj, len) {
    const T = this.T;
    obj.rotation.y = Math.PI / 2;
    let b = new T.Box3().setFromObject(obj), s = b.getSize(new T.Vector3());
    obj.scale.multiplyScalar(len / s.x);
    b = new T.Box3().setFromObject(obj); const c = b.getCenter(new T.Vector3());
    obj.position.set(-c.x, -b.min.y, -c.z);
    const max = this.renderer.capabilities.getMaxAnisotropy();
    obj.traverse(o => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = false; for (const k of ['map', 'normalMap']) if (o.material[k]) o.material[k].anisotropy = max; } });
    const g = new T.Group(); g.add(obj); return g;
  }
  /** 바닥·도로·건물·나무를 한 번 만든다 */
  build(world) {
    if (this.world === world) return;
    this.world = world;
    const T = this.T, S = this.scene;
    const mat = (color, o = {}) => new T.MeshLambertMaterial(Object.assign({ color }, o));
    // 겹친 평면은 그리는 순서로 정한다(깊이 정밀도가 낮은 GPU 에서 바닥이 도로를 덮던 문제): 바닥 → 블록 → 도로 → 차선
    const ground = new T.Mesh(new T.PlaneGeometry(5000, 5000), mat(0xb4bcb2, { polygonOffset: true, polygonOffsetFactor: 4, polygonOffsetUnits: 4 })); ground.rotation.x = -Math.PI / 2; ground.position.y = -0.02; ground.receiveShadow = true; ground.renderOrder = -3; S.add(ground);
    // 블록(인도 판)·공원
    const blocks = [], parks = [];
    for (let i = 0; i + 1 < CITY_XS.length; i++) for (let j = 0; j + 1 < CITY_YS.length; j++) {
      const park = (i === 2 && j === 2) || (i === 0 && j === 4) || (i === 4 && j === 1);
      (park ? parks : blocks).push([CITY_XS[i] + 10, CITY_XS[i + 1] - 10, CITY_YS[j] + 10, CITY_YS[j + 1] - 10]);
    }
    const slab = (list, color, y) => {
      const m = new T.InstancedMesh(new T.BoxGeometry(1, 0.15, 1), mat(color), list.length); m.receiveShadow = true; m.renderOrder = -2;
      const o = new T.Object3D();
      list.forEach((b, k) => { o.position.set((b[0] + b[1]) / 2, y, -(b[2] + b[3]) / 2); o.scale.set(b[1] - b[0], 1, b[3] - b[2]); o.updateMatrix(); m.setMatrixAt(k, o.matrix); });
      S.add(m);
    };
    slab(blocks, 0xc9cdd2, 0.075); slab(parks, 0x9fb88c, 0.075);
    // 도로
    const asphalt = mat(0x4a4d54), hwyMat = mat(0x45484f);
    for (const r of world.roads) {
      const L = Math.hypot(r.bx - r.ax, r.by - r.ay), ang = Math.atan2(r.by - r.ay, r.bx - r.ax);
      const m = new T.Mesh(new T.BoxGeometry(L, 0.06, r.w), r.kind === 'highway' ? hwyMat : asphalt);
      m.position.set((r.ax + r.bx) / 2, 0.03 + (r.kind === 'ramp' ? 0.002 : 0), -(r.ay + r.by) / 2); m.rotation.y = ang; m.receiveShadow = true; m.renderOrder = r.kind === 'city' ? -1 : 0; S.add(m);
    }
    // 차선(점선·실선) = 얇은 상자 묶음
    const white = [], yellow = [];
    const line = (arr, x1, y1, x2, y2, w = 0.15) => arr.push([x1, y1, x2, y2, w]);
    const dashed = (arr, x1, y1, x2, y2, on = 3, off = 6, skip = null) => {
      const L = Math.hypot(x2 - x1, y2 - y1), ux = (x2 - x1) / L, uy = (y2 - y1) / L;
      for (let s = 0; s < L; s += on + off) { const a = s, b = Math.min(L, s + on), mx = x1 + ux * (a + b) / 2, my = y1 + uy * (a + b) / 2; if (skip && skip(mx, my)) continue; arr.push([x1 + ux * a, y1 + uy * a, x1 + ux * b, y1 + uy * b, 0.15]); }
    };
    const nearX = (v, list, r) => list.some(c => Math.abs(v - c) < r);
    for (const r of world.roads) {
      if (r.kind === 'city') {
        const vert = r.ax === r.bx, c = vert ? r.ax : r.ay;
        const skip = (mx, my) => vert ? nearX(my, CITY_YS, 12) : nearX(mx, CITY_XS, 12);
        for (const off of [-0.2, 0.2]) dashed(yellow, vert ? c + off : r.ax, vert ? r.ay : c + off, vert ? c + off : r.bx, vert ? r.by : c + off, 1000, 0, skip);
        for (const off of [-5.25, 5.25]) dashed(white, vert ? c + off : r.ax, vert ? r.ay : c + off, vert ? c + off : r.bx, vert ? r.by : c + off, 3, 6, skip);
        for (const off of [-9.5, 9.5]) dashed(white, vert ? c + off : r.ax, vert ? r.ay : c + off, vert ? c + off : r.bx, vert ? r.by : c + off, 1000, 0, skip);
      } else if (r.kind === 'ramp') dashed(yellow, r.ax, r.ay, r.bx, r.by, 1000, 0);
      else {
        for (const k of [1, 2, 3]) for (const sgn of [-1, 1]) dashed(white, r.ax, HWY_Y + sgn * (0.5 + 3.5 * k), r.bx, HWY_Y + sgn * (0.5 + 3.5 * k), 8, 12);
        for (const sgn of [-1, 1]) { line(white, r.ax, HWY_Y + sgn * 14.6, r.bx, HWY_Y + sgn * 14.6); line(yellow, r.ax, HWY_Y + sgn * 0.6, r.bx, HWY_Y + sgn * 0.6); }
      }
    }
    // 횡단보도(교차로 네 면)
    for (const x of CITY_XS) for (const y of CITY_YS) for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      for (let k = -4; k <= 4; k++) {
        const cx = x + dx * 13 + (dy ? k * 1.9 : 0), cy = y + dy * 13 + (dx ? k * 1.9 : 0);
        if (Math.abs(cx) > 362 || Math.abs(cy) > 362) continue;
        if (dx) white.push([cx - 1.6, cy, cx + 1.6, cy, 0.9]); else white.push([cx, cy - 1.6, cx, cy + 1.6, 0.9]);
      }
    }
    const lines = (arr, color) => {
      const m = new T.InstancedMesh(new T.BoxGeometry(1, 0.02, 1), new T.MeshLambertMaterial({ color }), arr.length), o = new T.Object3D();
      arr.forEach((l, k) => { const L = Math.hypot(l[2] - l[0], l[3] - l[1]); o.position.set((l[0] + l[2]) / 2, 0.071, -(l[1] + l[3]) / 2); o.rotation.set(0, Math.atan2(l[3] - l[1], l[2] - l[0]), 0); o.scale.set(L, 1, l[4]); o.updateMatrix(); m.setMatrixAt(k, o.matrix); });
      m.receiveShadow = true; m.renderOrder = 1; S.add(m);
    };
    lines(white, 0xf2f2f0); lines(yellow, 0xe8c547);
    // 과속방지턱
    for (const b of world.bumps) {
      const m = new T.Mesh(new T.BoxGeometry(b.hd, 0.08, b.hw * 2), new T.MeshLambertMaterial({ color: 0xe0b83a })); m.position.set(b.x, 0.07, -b.y); m.receiveShadow = true; S.add(m);
      for (let k = -4; k <= 4; k += 2) { const s = new T.Mesh(new T.BoxGeometry(b.hd + 0.01, 0.085, 1), new T.MeshLambertMaterial({ color: 0x2b2b2b })); s.position.set(b.x, 0.07, -b.y + k * 1.1); S.add(s); }
    }
    // 건물: 창문은 세계 좌표로 그리는 셰이더 무늬(지붕 빼고)
    const bs = world.statics.filter(s => s.kind === 'building');
    const bMat = new T.MeshLambertMaterial({ color: 0xffffff });
    bMat.onBeforeCompile = sh => {
      sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nvarying vec3 vWP; varying vec3 vWN;')
        .replace('#include <worldpos_vertex>', '#include <worldpos_vertex>\nvec4 wp4 = modelMatrix * vec4(transformed, 1.0);\n#ifdef USE_INSTANCING\nwp4 = modelMatrix * instanceMatrix * vec4(transformed, 1.0);\n#endif\nvWP = wp4.xyz; vWN = normalize(mat3(modelMatrix) * objectNormal);');
      sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\nvarying vec3 vWP; varying vec3 vWN;')
        .replace('#include <color_fragment>', `#include <color_fragment>
          if (abs(vWN.y) < 0.5) {
            float u = abs(vWN.x) > 0.5 ? vWP.z : vWP.x;
            float fy = fract(vWP.y / 3.4), fu = fract(u / 2.6);
            float win = step(0.28, fy) * step(fy, 0.82) * step(0.18, fu) * step(fu, 0.82) * step(1.2, vWP.y);
            diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.28, 0.34, 0.42) + 0.08 * fract(sin(dot(floor(vec2(u / 2.6, vWP.y / 3.4)), vec2(12.9898, 78.233))) * 43758.5453), win * 0.85);
          } else diffuseColor.rgb *= 0.86;`);
    };
    const bm = new T.InstancedMesh(new T.BoxGeometry(1, 1, 1), bMat, bs.length), o = new T.Object3D(), col = new T.Color();
    const tones = [0xe9e6e0, 0xd9dde3, 0xc9ced6, 0xe2d8cc, 0xbfc6cf, 0xd3d0c8, 0xaeb6c0];
    bs.forEach((s, k) => { o.position.set(s.cx, s.h / 2, -s.cy); o.rotation.set(0, 0, 0); o.scale.set(s.hx * 2, s.h, s.hy * 2); o.updateMatrix(); bm.setMatrixAt(k, o.matrix); bm.setColorAt(k, col.set(tones[Math.floor(s.tone * tones.length) % tones.length])); });
    bm.castShadow = true; bm.receiveShadow = true; S.add(bm);
    // 나무
    const trunk = new T.InstancedMesh(new T.CylinderGeometry(0.18, 0.24, 2.4, 6), mat(0x7a5c42), world.trees.length);
    const crown = new T.InstancedMesh(new T.IcosahedronGeometry(1.9, 0), mat(0x7f9e6a, { flatShading: true }), world.trees.length);
    world.trees.forEach((t, k) => {
      o.position.set(t.x, 1.2 * t.s, -t.y); o.scale.setScalar(t.s); o.rotation.set(0, t.x * 7.1, 0); o.updateMatrix(); trunk.setMatrixAt(k, o.matrix);
      o.position.set(t.x, 3.6 * t.s, -t.y); o.updateMatrix(); crown.setMatrixAt(k, o.matrix); crown.setColorAt(k, col.setHSL(0.26 + (t.s - 1) * 0.08, 0.28, 0.42 + (t.x % 7) * 0.012));
    });
    trunk.castShadow = crown.castShadow = true; S.add(trunk, crown);
    // 가드레일·분리대·볼라드
    for (const s of world.statics) {
      if (s.kind === 'rail' || s.kind === 'median') {
        const m = new T.Mesh(new T.BoxGeometry(s.hx * 2, s.h, s.kind === 'median' ? 0.6 : s.hy * 2), mat(s.kind === 'median' ? 0xc8c8c4 : 0xb9bec4)); m.position.set(s.cx, s.h / 2, -s.cy); m.castShadow = true; S.add(m);
      } else if (s.kind === 'bollard') S.add(this.bollard(s));
    }
  }
  bollard(s) {
    const T = this.T, g = new T.Group();
    const m = new T.Mesh(new T.CylinderGeometry(s.r, s.r, s.h, 12), new T.MeshLambertMaterial({ color: 0x3a3d42 })); m.position.y = s.h / 2; m.castShadow = true; g.add(m);
    const band = new T.Mesh(new T.CylinderGeometry(s.r * 1.02, s.r * 1.02, 0.12, 12), new T.MeshLambertMaterial({ color: 0xf2c230 })); band.position.y = s.h * 0.8; g.add(band);
    g.position.set(s.cx, 0, -s.cy); return g;
  }
  /** 장면마다 덧붙인 물체(시험용 볼라드·벽) */
  syncTemp(sim) {
    const key = (sim.extraStatics || []).map(s => `${s.kind}${s.cx}${s.cy}`).join('|');
    if (key === this.tempKey) return;
    this.tempKey = key; const T = this.T;
    this.tempGroup.clear();
    for (const s of sim.extraStatics || []) {
      if (s.type === 'circle') this.tempGroup.add(this.bollard(s));
      else { const m = new T.Mesh(new T.BoxGeometry(s.hx * 2, s.h, s.hy * 2), new T.MeshLambertMaterial({ color: 0xd2d5d9 })); m.position.set(s.cx, s.h / 2, -s.cy); m.castShadow = true; this.tempGroup.add(m); }
    }
  }
  /** 차 한 대의 3D 몸체(모델 + 그림자 + 등) */
  carMesh(c) {
    const T = this.T, g = new T.Group();
    const base = this.models[c.model];
    if (base) {
      const m = base.clone(true);
      m.traverse(o => {
        if (!o.isMesh) return;
        o.material = o.material.clone();
        if (!c.isPlayer && (o.material.name === 'paint')) o.material.color.set(c.color);
        if (o.material.name === 'tail') { o.material.emissive = new T.Color(0x000000); g.userData.tail = (g.userData.tail || []).concat(o.material); }
      });
      g.add(m);
    } else {
      const m = new T.Mesh(new T.BoxGeometry(c.len, 1.4, c.wid), new T.MeshLambertMaterial({ color: c.isPlayer ? 0xd9dadc : c.color })); m.position.y = 0.75; m.castShadow = true; g.add(m);
    }
    const sh = new T.Mesh(new T.PlaneGeometry(c.len * 1.25, c.wid * 1.6), new T.MeshBasicMaterial({ map: this.blob, transparent: true, depthWrite: false }));
    sh.rotation.x = -Math.PI / 2; sh.position.y = 0.075; g.add(sh);
    // 등: 브레이크(빨강), 방향지시·비상(호박색), 후진(흰색)
    const lamp = (color, x, z, w = 0.22, hgt = 0.12) => { const m = new T.Mesh(new T.BoxGeometry(0.05, hgt, w), new T.MeshBasicMaterial({ color, transparent: true, opacity: 0 })); m.position.set(x, 0.85, z); g.add(m); return m; };
    const hl = c.len / 2 + 0.02, hw = c.wid / 2 - 0.25;
    g.userData.brake = [lamp(0xff2a1a, -hl, hw), lamp(0xff2a1a, -hl, -hw)];
    g.userData.left = [lamp(0xffa21a, hl, -hw - 0.05, 0.16, 0.1), lamp(0xffa21a, -hl, -hw - 0.05, 0.16, 0.1)];
    g.userData.right = [lamp(0xffa21a, hl, hw + 0.05, 0.16, 0.1), lamp(0xffa21a, -hl, hw + 0.05, 0.16, 0.1)];
    g.userData.rev = [lamp(0xffffff, -hl, 0, 0.3, 0.08)];
    g.dispose = () => { };
    this.scene.add(g);
    return g;
  }
  /** 충돌 순간 표시(짧게 퍼지는 고리) */
  flash(x, y) {
    const T = this.T, m = new T.Mesh(new T.RingGeometry(0.4, 0.7, 32), new T.MeshBasicMaterial({ color: 0xff4b39, transparent: true, opacity: 0.9, side: T.DoubleSide, depthWrite: false }));
    m.rotation.x = -Math.PI / 2; m.position.set(x, 0.6, -y); this.scene.add(m); this.effects.push({ m, t0: performance.now() });
  }
  setSim(sim) {
    if (this.sim === sim) return;
    for (const g of this.cars.values()) this.scene.remove(g);
    this.cars.clear(); this.sim = sim; this.camInit = false; this.tempKey = '__';
    sim.onImpact = () => { const p = sim.player; if (p) this.flash(p.x, p.y); };
  }
  resize(w, h) { this.renderer.setSize(w, h, false); this.camera.aspect = w / h; this.camera.updateProjectionMatrix(); }
  render(blinkOn) {
    const T = this.T, sim = this.sim; if (!sim || !this.world) return;
    this.syncTemp(sim);
    const seen = new Set();
    for (const c of sim.cars) {
      let g = this.cars.get(c);
      if (!g) { g = this.carMesh(c); this.cars.set(c, g); }
      seen.add(c);
      g.position.set(c.x, 0, -c.y); g.rotation.y = c.yaw;
      const ud = g.userData, br = c.brake > 0.1 && c.gear !== 'P', hz = c.hazard || (c.crashed && !c.isPlayer);
      ud.brake.forEach(m => m.material.opacity = br ? 0.95 : 0);
      (ud.tail || []).forEach(mt => mt.emissive.setRGB(br ? 0.9 : 0.12, 0, 0));
      const left = hz || c.signal === 'L', right = hz || c.signal === 'R';
      ud.left.forEach(m => m.material.opacity = left && blinkOn ? 1 : 0); ud.right.forEach(m => m.material.opacity = right && blinkOn ? 1 : 0);
      ud.rev.forEach(m => m.material.opacity = c.gear === 'R' ? 0.9 : 0);
    }
    for (const [c, g] of this.cars) if (!seen.has(c)) { this.scene.remove(g); this.cars.delete(c); }
    const now = performance.now();
    this.effects = this.effects.filter(e => { const k = (now - e.t0) / 450; if (k >= 1) { this.scene.remove(e.m); return false; } e.m.scale.setScalar(1 + k * 6); e.m.material.opacity = 0.9 * (1 - k); return true; });
    const p = sim.player; if (!p) return;
    const fx = Math.cos(p.yaw), fz = -Math.sin(p.yaw), cam = this.camera;
    let pos, look;
    if (this.camMode === 'top') { pos = new T.Vector3(p.x - fx * 6, 85, -p.y - fz * 6); look = new T.Vector3(p.x + fx * 4, 0, -p.y + fz * 4); cam.fov = 50; }
    else if (this.camMode === 'hood') { pos = new T.Vector3(p.x + fx * 0.3, 1.35, -p.y + fz * 0.3); look = new T.Vector3(p.x + fx * 30, 1.0, -p.y + fz * 30); cam.fov = 70; }
    else { const back = 8.5 + Math.min(4, Math.abs(p.vf) * 0.08); pos = new T.Vector3(p.x - fx * back, 3.4, -p.y - fz * back); look = new T.Vector3(p.x + fx * 4, 1.0, -p.y + fz * 4); cam.fov = 58; }
    if (!this.camInit || this.camMode === 'hood') { this.camPos.copy(pos); this.camLook.copy(look); this.camInit = true; }
    else { this.camPos.lerp(pos, this.camMode === 'top' ? 0.2 : 0.12); this.camLook.lerp(look, 0.25); }
    cam.position.copy(this.camPos); cam.lookAt(this.camLook); cam.updateProjectionMatrix();
    this.sun.position.set(p.x + 60, 120, -p.y + 40); this.sun.target.position.set(p.x, 0, -p.y); this.sun.target.updateMatrixWorld();
    this.renderer.render(this.scene, cam);
  }
}
