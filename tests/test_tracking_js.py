"""El detector dice QUÉ; la resta de fondo, DÓNDE. Prueba de comportamiento.

Escena: una persona que anda 3 px/frame y que el detector ve una vez cada 10
frames, y un coche que cruza sin que el detector lo vea nunca. Antes del
arreglo, en cuanto había una caja del detector el seguimiento recibía SOLO esa
caja, repetida en todos los frames: la persona quedaba congelada donde la vio
el detector y el coche desaparecía. Se exige lo contrario.
"""

import json
import pathlib
import shutil
import subprocess

import pytest

RAIZ = pathlib.Path(__file__).resolve().parents[1]
MOTOR = RAIZ / "argos" / "api" / "static" / "engine.js"

PROGRAMA = r"""
global.performance = require('perf_hooks').performance;
const mod = {exports: {}};
(function (module, exports) {
  eval(require('fs').readFileSync(process.argv[2], 'utf8'));
})(mod, mod.exports);
const {Engine} = mod.exports;
const W = 320, H = 180, S = 4;                 // fuente 1280x720
const e = new Engine();
const fondo = new Uint8ClampedArray(W * H * 4);
for (let i = 0; i < W * H; i++) { const v = 70 + (i * 7919 % 23);
  fondo[i*4] = v; fondo[i*4+1] = v; fondo[i*4+2] = v + 4; fondo[i*4+3] = 255; }
function pinta(d, x, y, w, h, c) {
  for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) {
    const p = (yy * W + xx) * 4; d[p] = c; d[p+1] = c - 10; d[p+2] = c - 20; } }
let r;
for (let f = 0; f < 150; f++) {
  const d = new Uint8ClampedArray(fondo);
  const px = 20 + Math.floor(f * 0.75), py = 60;        // persona, 10x26
  const cx = 300 - Math.floor(f * 1.5), cy = 130;       // coche, 30x14
  if (f >= 70) { pinta(d, px, py, 10, 26, 225); pinta(d, cx, cy, 30, 14, 215); }
  const det = (f >= 70 && f % 10 === 0)
    ? [{b: [px * S, py * S, (px + 10) * S, (py + 26) * S], c: 'person', score: 0.9}] : null;
  r = e.analyze({data: d, w: W, h: H, rx: 0, ry: 0, rw: W * S, rh: H * S}, {detections: det});
}
const f = 149;
console.log(JSON.stringify({boxes: r.boxes,
  persona: [(20 + Math.floor(f * 0.75)) * S, 60 * S],
  coche: [(300 - Math.floor(f * 1.5)) * S, 130 * S]}));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="sin node")
def test_entre_pasadas_sigue_el_fondo_y_se_conserva_lo_no_detectado(tmp_path):
    prog = tmp_path / "p.js"
    prog.write_text(PROGRAMA)
    out = subprocess.run(["node", str(prog), str(MOTOR)], capture_output=True,
                         text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout.strip().splitlines()[-1])
    cajas = r["boxes"]
    personas = [b for b in cajas if b["c"] == "person" and b["cs"] == "det"]
    assert personas, f"la persona perdió la clase del detector: {cajas}"
    # Frame 149: la última pasada del detector fue en el 140, nueve frames y
    # ~27 px de fuente atrás. La caja tiene que estar donde está la persona
    # AHORA, no donde la vio el detector.
    px, py = r["persona"]
    mejor = min(personas, key=lambda b: abs(b["b"][0] - px))
    assert abs(mejor["b"][0] - px) < 12, (mejor["b"], r["persona"])
    # Y el coche, que el detector nunca vio, sigue en la escena.
    cx, cy = r["coche"]
    assert any(b["cs"] == "forma" and abs(b["b"][0] - cx) < 16 and abs(b["b"][1] - cy) < 16
               for b in cajas), f"el coche no detectado desapareció: {cajas}"
