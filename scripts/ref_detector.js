// Referencia JS del detector para la paridad con Detector.kt.
//
// Corre el camino REAL del bucle principal (`detectIncremental`) sobre los
// RGBA que deja `prep_detector_ref.py`, con un lienzo falso que entrega esos
// píxeles: ni drawImage ni getImageData intervienen, así que la entrada es
// byte a byte la misma que recibe Kotlin. Dos modos: cuadro entero y teselas
// ×2 con la pasada completa.
//
//   node scripts/ref_detector.js DIR [ruta/a/onnxruntime-web]
//   DIR con yolox_nano.onnx, meta.json y *.rgba  ->  DIR/ref_det_js.txt
const fs = require('fs'), path = require('path');
const DIR = process.argv[2];
const ORT = process.argv[3] || 'onnxruntime-web';
const ort = require(ORT);
ort.env.wasm.numThreads = 1;
const DETJS = path.join(__dirname, '..', 'argos', 'api', 'static', 'detector.js');
let cur = null;
globalThis.document = {createElement: () => ({width: 0, height: 0, getContext: () => ({
  // drawImage recibe el origen del recorte; getImageData lo usa.
  drawImage(src, sx, sy) { cur.ox = sx; cur.oy = sy; },
  getImageData: (x, y, w, h) => {
    // La tesela sale del búfer completo, como la recortaría el lienzo.
    const out = new Uint8ClampedArray(w * h * 4);
    for (let r = 0; r < h; r++)
      out.set(cur.data.subarray(((cur.oy + r) * cur.w + cur.ox) * 4,
                                ((cur.oy + r) * cur.w + cur.ox + w) * 4), r * w * 4);
    return {data: out, width: w, height: h};
  },
})})};
const mod = {exports: {}};
(function (module, exports) { eval(fs.readFileSync(DETJS, 'utf8')); })(mod, mod.exports);
const {YoloxDetector} = mod.exports;
(async () => {
  const det = await YoloxDetector.load(fs.readFileSync(path.join(DIR, 'yolox_nano.onnx')), ort, {});
  const meta = JSON.parse(fs.readFileSync(path.join(DIR, 'meta.json')));
  const lines = [];
  for (const [modo, zoom] of [['completo', 'completo'], ['x2', 2]]) {
    for (const [n, [w, h]] of Object.entries(meta)) {
      cur = {data: new Uint8ClampedArray(fs.readFileSync(path.join(DIR, n + '.rgba'))), w, h, ox: 0, oy: 0};
      det.setZoom(zoom);
      det.canvas = null; det._sched = null;
      const r = await det.detectIncremental({width: w, height: h}, ort,
                                            {width: w, height: h, budget: 1e6});
      for (const d of r.dets)
        lines.push([modo, n, d.c, d.fine, d.score, ...d.b].join(' '));
      console.log(modo, n, r.dets.length, 'obj,', r.nTiles, 'teselas ·',
                  r.dets.map(d => `${d.c} ${d.score.toFixed(3)}`).join(', '));
    }
  }
  fs.writeFileSync(path.join(DIR, 'ref_det_js.txt'), lines.join('\n') + '\n');
})().catch(e => { console.error('FALLA', e); process.exit(1); });
