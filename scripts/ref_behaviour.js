// Referencia del modelo de comportamiento para la paridad con Behaviour.kt.
//
// Una acera de doble sentido aprendida de 240 recorridos (ida a y≈250, vuelta
// a y≈330, en celdas distintas; gente a ~1,3 m/s y, solo a la vuelta, alguna
// bici), y cinco
// recorridos que puntuar: uno normal, uno a contramano, uno corriendo, un
// vehículo por la acera y uno por una zona sin historial.
//
//   node scripts/ref_behaviour.js [salida]  -> android-native/build/behaviour_ref.txt
const fs = require('fs'), path = require('path');
const SAL = process.argv[2] || path.join(__dirname, '..', 'android-native', 'build', 'behaviour_ref.txt');
const mod = {exports: {}};
(function (module, exports) {
  eval(fs.readFileSync(path.join(__dirname, '..', 'argos', 'api', 'static', 'behaviour.js'), 'utf8'));
})(mod, mod.exports);
const {BehaviourModel} = mod.exports;
let s = 77 >>> 0;
const rnd = () => ((s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296);
const f = (v) => (Number.isFinite(v) ? v.toPrecision(17) : 'NaN');
const out = [];

function recorrido(x0, x1, y, v, cls, n = 24) {
  const pts = [], speeds = [];
  for (let i = 0; i < n; i++) {
    pts.push([x0 + (x1 - x0) * i / (n - 1), y + (rnd() - 0.5) * 6]);
    speeds.push(v + (rnd() - 0.5) * 0.2);
  }
  return {path: pts, speeds, cls};
}
const linea = (t) => `${t.cls} ${t.path.length} ` + t.path.map((p, i) => `${f(p[0])},${f(p[1])},${f(t.speeds[i])}`).join(';');

const m = new BehaviourModel(1280, 720);
out.push('LEARN 240');
for (let k = 0; k < 240; k++) {
  const ida = k % 2 === 0;
  const bici = !ida && k % 10 === 3;
  const t = ida ? recorrido(40, 1240, 250 + (rnd() - 0.5) * 20, 1.3, 'person')
                : recorrido(1240, 40, 330 + (rnd() - 0.5) * 20, bici ? 4.5 : 1.3, bici ? 'bike' : 'person');
  out.push(linea(t));
  m.learn(t);
}
const mt = m.maturity();
out.push(`MAT ${mt.cells_total} ${mt.cells_touched} ${mt.cells_mature} ${mt.cells_directional} ${mt.observations} ${mt.ready}`);
for (const [x, y] of [[200, 250], [700, 330], [1100, 330], [600, 650]]) {
  const p = m.profile(x, y);
  out.push(p ? `PROF ${x} ${y} ${p.n} ${f(p.flow.concentration)} ${p.modes.length} ` +
               `${p.modes.map((mo) => `${f(mo.heading)},${f(mo.concentration)},${f(mo.share)}`).join(';')} ` +
               `${p.speed ? f(p.speed.p05) + ',' + f(p.speed.p50) + ',' + f(p.speed.p95) + ',' + f(p.speed.width) : 'null'} ` +
               `${f(p.classes.person ?? NaN)}`
             : `PROF ${x} ${y} null`);
}
const pruebas = {
  normal: recorrido(100, 1100, 250, 1.3, 'person'),
  contramano: recorrido(1100, 100, 250, 1.3, 'person'),
  corriendo: recorrido(100, 1100, 250, 4.0, 'person'),
  vehiculo: recorrido(100, 1100, 250, 1.3, 'vehicle'),
  desconocido: recorrido(100, 1100, 650, 1.3, 'person'),
};
for (const [n, t] of Object.entries(pruebas)) {
  const sc = m.score(t);
  out.push(`SCORE ${n} ${linea(t)}`);
  out.push(`RES ${f(sc.score)} ${sc.mature} ${f(sc.coverage)} ` +
           (sc.detail ? `${f(sc.detail.direction)} ${f(sc.detail.speed_excess)} ${f(sc.detail.class_share)} ${f(sc.detail.unknown_frac)}` : 'NaN NaN NaN NaN') +
           ` | ${sc.reasons.join(' / ')}`);
}
fs.mkdirSync(path.dirname(SAL), {recursive: true});
fs.writeFileSync(SAL, out.join('\n') + '\n');
console.log(out.filter((l) => /^(MAT|RES)/.test(l)).join('\n'));
