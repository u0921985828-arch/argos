// Referencia de la escena para la paridad con Scene.kt: SceneScale.fit y
// Physics.update de brain.js sobre conjuntos deterministas. Entradas y
// salidas van al mismo fichero, así Kotlin no necesita reproducir el azar.
//
//   node scripts/ref_scene.js [salida]   -> android-native/build/scene_ref.txt
const fs = require('fs'), path = require('path');
global.performance = require('perf_hooks').performance;
const SAL = process.argv[2] || path.join(__dirname, '..', 'android-native', 'build', 'scene_ref.txt');
const mod = {exports: {}};
(function (module, exports) {
  eval(fs.readFileSync(path.join(__dirname, '..', 'argos', 'api', 'static', 'brain.js'), 'utf8'));
})(mod, mod.exports);
const {SceneScale, Physics} = mod.exports;

let s = 20251022 >>> 0;
const rnd = () => ((s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296);
const out = [];
const f = (v) => (Number.isFinite(v) ? v.toPrecision(17) : 'NaN');

function conjunto(nombre, n, gen) {
  const pts = [];
  for (let i = 0; i < n; i++) pts.push(gen(i));
  out.push(`SET ${nombre} ${pts.length}`);
  for (const p of pts) out.push(`${f(p.x)} ${f(p.y)} ${f(p.h)} ${p.cls}`);
  const sc = new SceneScale().fit(pts);
  out.push(`FIT ${f(sc.a)} ${f(sc.b)} ${f(sc.sigma)} ${sc.n} ${sc.valid} ${f(sc.cx)} ${sc.usesX} ` +
           `${f(sc.colinearity)} ${f(sc.ySpan)} ${f(sc.classScale.get('person') ?? NaN)} ` +
           `${f(sc.classScale.get('vehicle') ?? NaN)} ${f(sc.metersPerPixel(500, 300))} ${f(sc.z(500, 160))}`);
  return sc;
}

// Peatones en perspectiva con ruido y algún atípico (la estatua, el cartel).
const peatones = conjunto('peatones', 34, (i) => {
  const y = 200 + rnd() * 500, x = rnd() * 1200;
  const atipico = i % 11 === 5;
  return {x, y, h: 0.3 * y + 20 + (rnd() - 0.5) * 8 + (atipico ? 90 : 0), cls: 'person'};
});
// Plaza ancha con alabeo: el término en x existe y se debe adoptar.
conjunto('alabeo', 60, (i) => {
  const y = 150 + rnd() * 600, x = rnd() * 1280;
  const cls = i % 4 === 0 ? 'vehicle' : 'person';
  return {x, y, h: (0.25 * y + 0.05 * x + 10) * (cls === 'vehicle' ? 1.8 : 1) + (rnd() - 0.5) * 6, cls};
});
// Calle en diagonal: x e y casi colineales, el plano NO debe adoptarse.
conjunto('diagonal', 50, () => {
  const y = 150 + rnd() * 600;
  return {x: y * 1.5 + (rnd() - 0.5) * 20, y, h: 0.28 * y + 15 + (rnd() - 0.5) * 6, cls: 'person'};
});
// Todos a la misma distancia: sin recorrido vertical no hay escala.
conjunto('plano', 20, () => {
  const y = 400 + rnd() * 30;
  return {x: rnd() * 1000, y, h: 140 + (rnd() - 0.5) * 10, cls: 'person'};
});

// Física: un peatón que anda, se para y vuelve a andar, con la escala de arriba.
out.push('PHYS 60');
const p = new Physics();
let x = 100, y = 520, t = 0;
for (let i = 0; i < 60; i++) {
  t += 0.033 + rnd() * 0.01;
  if (i < 25 || i > 40) x += 3 + (rnd() - 0.5); y += (rnd() - 0.5) * 0.6;
  const h = 0.3 * y + 20;
  p.setStopThreshold(h);
  p.update(x, y, t, peatones);
  out.push(`${f(x)} ${f(y)} ${f(t)} ${f(h)} | ${f(p.speedPx)} ${f(p.speedMs)} ${f(p.heading)} ` +
           `${f(p.dwellS)} ${f(p.straightness)} ${p.stopped}`);
}
fs.mkdirSync(path.dirname(SAL), {recursive: true});
fs.writeFileSync(SAL, out.join('\n') + '\n');
console.log('escena: ' + out.length + ' líneas -> ' + SAL);
