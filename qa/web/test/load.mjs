// Load the DOM-free source parts into one Node vm context (same scope as the browser build).
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export function loadParts(parts, extra = []) {
  const code = parts.map(p => fs.readFileSync(path.join(ROOT, 'src', p), 'utf8')).join('\n;\n')
    + '\n;globalThis.__x = { ' + [...EXPORTS, ...extra].join(', ') + ' };';
  const ctx = vm.createContext({ console, TextEncoder, Math, Date, JSON, Map, Set, Number, String, Object, Array, Error, Promise, setTimeout });
  vm.runInContext(code, ctx, { filename: 'gwenchana-web.js' });
  return ctx.__x;
}
const EXPORTS = ['sha256Hex', 'tsKST', 'dirName', 'parseDirName', 'Sample', 'Recorder', 'trackPoses', 'fitAfterCrash', 'suddenDrop', 'impactFromDeg', 'FactTimeline',
  'Validator', 'Guides', 'Personas', 'skipped', 'guideTarget', 'inferKind', 'typeCandidates', 'recStage', 'PhotoGuide', 'claimSide', 'GRAVITY_EARTH',
  'VARIANT', 'SimClock', 'Runtime', 'VFS', 'SyncServer', 'Network', 'GwenApp', 'PhoneApp', 'ImpactSensor', 'HandoffCodec', 'easeSteps'];
export const CORE = ['00-util.js', '10-app-core.js', '20-app-runtime.js'];
export const SIM_QA = [...CORE, '30-sim.js', '60-qa.js'];
