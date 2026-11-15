// Print GLB scene extents (POSITION accessor min/max with node transforms ignored) and node names.
import fs from 'node:fs';
for (const f of process.argv.slice(2)) {
  const b = fs.readFileSync(f);
  const len = b.readUInt32LE(12);
  const json = JSON.parse(b.slice(20, 20 + len).toString('utf8'));
  let min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  for (const m of json.meshes || []) for (const p of m.primitives) {
    const a = json.accessors[p.attributes.POSITION];
    if (a.min) for (let i = 0; i < 3; i++) { min[i] = Math.min(min[i], a.min[i]); max[i] = Math.max(max[i], a.max[i]); }
  }
  const nodes = (json.nodes || []).slice(0, 8).map(n => `${n.name || '?'}${n.rotation ? ' rot=' + n.rotation.map(v => v.toFixed(2)) : ''}${n.scale ? ' s=' + n.scale.map(v => v.toFixed(2)) : ''}${n.translation ? ' t=' + n.translation.map(v => v.toFixed(2)) : ''}`);
  console.log(f.split('/').pop(), 'min', min.map(v => v.toFixed(2)).join(','), 'max', max.map(v => v.toFixed(2)).join(','), 'meshes', (json.meshes || []).length, 'materials', (json.materials || []).length);
  console.log('   nodes:', nodes.join(' | '));
}
