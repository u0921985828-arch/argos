"""El camino del bucle principal con detector, ejecutado en el motor JS real.

`analyze()` con detecciones es lo que corre en cada frame en cuanto el
detector ha visto algo. Se rompió al partir `process()` en dos y ninguna
prueba lo pisaba: la paridad serie/Worker y la de Kotlin corren SIN detector.
En el navegador el fallo no lanzaba nada --- un `<div id="roi">` hacía que el
nombre suelto resolviera a ese elemento --- y las cajas salían NaN. Por eso
esta prueba siembra también un `roi` global: así reproduce la variante muda,
que es la peligrosa, además de la que lanza.
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
if (process.argv[3] === 'dom') globalThis.roi = {};   // como un <div id="roi">
const mod = {exports: {}};
(function (module, exports) {
  eval(require('fs').readFileSync(process.argv[2], 'utf8'));
})(mod, mod.exports);
const {Engine} = mod.exports;
const e = new Engine();
const W = 320, H = 180;
const data = new Uint8ClampedArray(W * H * 4).fill(90);
const det = [{b: [500, 200, 600, 500], c: 'person', score: 0.9}];
let r;
// Los primeros frames el tracker aún no confirma: se repite hasta que salga.
for (let i = 0; i < 6; i++)
  r = e.analyze({data, w: W, h: H, rx: 100, ry: 50, rw: 1280, rh: 720},
                {detections: det});
console.log(JSON.stringify(r.boxes));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="sin node")
@pytest.mark.parametrize("entorno", ["node", "dom"])
def test_analyze_con_detecciones_da_cajas_finitas(tmp_path, entorno):
    prog = tmp_path / "p.js"
    prog.write_text(PROGRAMA)
    out = subprocess.run(["node", str(prog), str(MOTOR), entorno],
                         capture_output=True, text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    cajas = json.loads(out.stdout.strip().splitlines()[-1])
    assert cajas, "el detector vio una persona y el seguimiento no devolvió nada"
    for c in cajas:
        assert all(isinstance(v, (int, float)) for v in c["b"]), c
        assert c["c"] == "person" and c["cs"] == "det", c
    # La caja vuelve a coordenadas de la fuente con el desplazamiento de la
    # región aplicado UNA vez: ni perdido ni duplicado.
    x1, y1, x2, y2 = cajas[0]["b"]
    assert abs(x1 - 500) < 5 and abs(y1 - 200) < 5, cajas[0]["b"]
    assert abs(x2 - 600) < 5 and abs(y2 - 500) < 5, cajas[0]["b"]
