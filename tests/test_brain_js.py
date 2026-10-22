"""Brain no mide la física de una pista que este frame no ha visto nada.

Cuando un peatón sale del cuadro, su pista sigue unos frames con la caja
quieta mientras agota el margen. Medir ahí la física daba un objeto parado:
cada salida dejaba un evento «detenido» falso.
"""

import json
import pathlib
import shutil
import subprocess

import pytest

RAIZ = pathlib.Path(__file__).resolve().parents[1]
CEREBRO = RAIZ / "argos" / "api" / "static" / "brain.js"

PROGRAMA = r"""
global.performance = require('perf_hooks').performance;
const mod = {exports: {}};
(function (module, exports) {
  eval(require('fs').readFileSync(process.argv[2], 'utf8'));
})(mod, mod.exports);
const {Brain} = mod.exports;
const t = {id: 1, hits: 10, miss: 0, klass: 'person', klassSrc: 'det',
           box: {x1: 10, y1: 10, x2: 20, y2: 40}};
const eng = {sourceW: 320, sourceH: 180, small: {width: 320, height: 180},
             tracker: {tracks: [t], minHits: 4}};
const b = new Brain(eng);
// Anda 3 s y luego desaparece: 20 frames sin verse, caja quieta.
for (let f = 0; f < 110; f++) {
  if (f < 90) { t.box.x1 += 1; t.box.x2 += 1; t.miss = 0; } else t.miss++;
  b._updatePhysics(f / 30);
}
console.log(JSON.stringify(b.events.map(e => e.kind)));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="sin node")
def test_salir_del_cuadro_no_es_detenerse(tmp_path):
    prog = tmp_path / "p.js"
    prog.write_text(PROGRAMA)
    out = subprocess.run(["node", str(prog), str(CEREBRO)], capture_output=True,
                         text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    eventos = json.loads(out.stdout.strip().splitlines()[-1])
    assert "detenido" not in eventos, eventos
