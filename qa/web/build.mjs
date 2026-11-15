// dist/ 만들기: src 를 template.html 에 넣고, 그림·글꼴·3D 모델은 저장소 안 앱 자산에서 복사한다(따로 커밋하지 않음).
// node qa/web/build.mjs  →  qa/web/dist/index.html(CDN three.js) · index-local.html(저장소의 three.js)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const ROOT = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(ROOT, '..', '..');
const DIST = path.join(ROOT, 'dist');
const PARTS = ['00-util.js', '10-app-core.js', '20-app-runtime.js', '30-sim.js', '40-render.js', '45-lineart.js', '50-headunit.js', '60-qa.js', '70-main.js'];
const code = PARTS.map(p => `// ── src/${p} ──\n` + fs.readFileSync(path.join(ROOT, 'src', p), 'utf8')).join('\n');
if (/<\/script/i.test(code)) throw new Error('code contains </script');
const cases = fs.readFileSync(path.join(ROOT, 'qa-cases.json'), 'utf8');
JSON.parse(cases);
const template = fs.readFileSync(path.join(ROOT, 'template.html'), 'utf8');

// 앱 자산 복사: 앱이 쓰는 파일과 같은 것을 쓴다
const copyDir = (from, to, keep = () => true) => {
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(from)) if (fs.statSync(path.join(from, f)).isFile() && keep(f)) fs.copyFileSync(path.join(from, f), path.join(to, f));
};
const assets = path.join(REPO, 'app', 'src', 'main', 'assets');
copyDir(path.join(assets, 'crash3d'), path.join(DIST, 'lib'), f => f.endsWith('.js'));
copyDir(path.join(assets, 'crash3d', 'models', 'car'), path.join(DIST, 'models'), f => f.endsWith('.glb'));
copyDir(path.join(assets, 'car'), path.join(DIST, 'car'));
copyDir(path.join(assets, 'guide'), path.join(DIST, 'guide'), f => /\.(jpg|png)$/.test(f)); // 영상(mp4)은 웹 사본에서 안 씀
copyDir(path.join(REPO, 'shared', 'res', 'font'), path.join(DIST, 'font'), f => f === 'asta_sans.ttf');
copyDir(path.join(ROOT, 'licenses'), path.join(DIST, 'licenses'));
// 일부 호스팅은 .glb 를 내려주지 않는다 → base64 JSON 도 함께(페이지는 이것을 먼저 읽음)
for (const f of fs.readdirSync(path.join(DIST, 'models')).filter(f => f.endsWith('.glb'))) {
  const b = fs.readFileSync(path.join(DIST, 'models', f));
  fs.writeFileSync(path.join(DIST, 'models', f + '.json'), JSON.stringify({ name: f, bytes: b.length, base64: b.toString('base64') }));
}

const CDN = 'https://cdn.jsdelivr.net/npm/three@0.186.0'; // 저장소 crash3d 의 three.module.js 와 같은 r186
const variants = {
  'index.html': { imports: { three: `${CDN}/build/three.module.js`, 'three/addons/': `${CDN}/examples/jsm/` }, font: '' },
  'index-local.html': { imports: { three: './lib/three.module.js', 'three/addons/loaders/GLTFLoader.js': './lib/GLTFLoader.js' }, font: "@font-face{font-family:'Asta Sans';src:url(font/asta_sans.ttf) format('truetype');font-weight:300 800;font-display:swap}" },
};
let html = '';
for (const [file, v] of Object.entries(variants)) {
  html = template.replace('/*IMPORTMAP*/', () => `<script type="importmap">${JSON.stringify({ imports: v.imports })}</script>`).replace('/*FONTFACE*/', () => v.font)
    .replace('/*QA_CASES*/', () => cases.replace(/<\//g, '<\\/')).replace('/*APP_CODE*/', () => code);
  fs.writeFileSync(path.join(DIST, file), html);
}
fs.copyFileSync(path.join(ROOT, 'qa-cases.json'), path.join(DIST, 'qa-cases.json'));
console.log('dist/index.html', (html.length / 1024).toFixed(0), 'KB');
