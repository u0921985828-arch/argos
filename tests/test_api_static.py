"""El cliente que este servidor sirve, cargado de verdad.

`index.html` pide trece módulos con `<script src>`, y cada uno necesita dos
cosas que se rompieron a la vez sin que ninguna prueba lo notara:

* **una ruta en `main.py`** --- `ask.js` y `ops.js` se añadieron al cliente y
  no al servidor, así que servida por FastAPI la app abría con el cuadro de
  mando multicámara y la consulta en lenguaje natural en 404. El bundle las
  inlinea, y por eso ni el APK ni el escritorio lo enseñaban;
* **estar en `PUBLIC_PATHS`** --- con token configurado, `behaviour.js`,
  `cameras.js`, `groundplane.js` y `detector-worker.js` devolvían 401: la
  interfaz se quedaba sin comportamiento aprendido, sin catálogo de cámaras,
  sin rectificación métrica y con el detector en el hilo principal. Y el token
  solo se configura precisamente cuando el servidor importa.

La lista de módulos se lee del HTML, no de una copia: una copia se
desincroniza, y desincronizarse es exactamente lo que pasó.

El tercer caso es el que más cuesta ver a ojo: el módulo del servidor **no se
podía importar** (`AUTH` se usaba antes de asignarse), así que
`uvicorn argos.api.main:app` moría con `NameError` y ninguna prueba lo pisaba
porque ninguna importaba `main.py`. Importarlo es, por sí solo, la prueba.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys

import pytest

fastapi = pytest.importorskip("fastapi", reason="el servidor es opcional")

RAIZ = pathlib.Path(__file__).resolve().parents[1]
HTML = RAIZ / "argos" / "api" / "static" / "index.html"


def modulos_del_cliente() -> list[str]:
    """Las rutas `<script src>` del HTML, tal como las pide el navegador."""
    srcs = re.findall(r'<script\s+src="([^"]+)"', HTML.read_text(encoding="utf-8"))
    assert len(srcs) >= 10, f"solo se encontraron {len(srcs)} módulos: ¿cambió el HTML?"
    return srcs


def test_el_servidor_se_puede_importar():
    """`import argos.api.main` a secas: si falla, no hay servidor que arrancar."""
    from argos.api.main import app

    assert app is not None


def test_se_sirven_todos_los_modulos_del_cliente():
    from fastapi.testclient import TestClient

    from argos.api.main import app

    cliente = TestClient(app)
    for src in modulos_del_cliente():
        r = cliente.get(src)
        assert r.status_code == 200, f"{src} -> {r.status_code}"
        assert r.content, f"{src} vacío"
        assert "javascript" in r.headers.get("content-type", ""), \
            f"{src}: content-type {r.headers.get('content-type')}"


# El mismo recorrido, pero en un proceso aparte con token: `AUTH` se resuelve
# al importar el módulo, así que el modo con credencial no se puede probar
# recargando el ya importado sin mentir sobre el arranque real.
PROGRAMA = r"""
import json, sys
from fastapi.testclient import TestClient
from argos.api.main import app

c = TestClient(app)
salida = {p: c.get(p).status_code for p in json.loads(sys.argv[1])}
salida["/api/health"] = c.get("/api/health").status_code
salida["/api/sources"] = c.get("/api/sources").status_code
print(json.dumps(salida))
"""


def test_con_token_la_interfaz_completa_carga_y_la_api_pide_llave():
    rutas = modulos_del_cliente()
    entorno = dict(os.environ)
    entorno.update({
        "PYTHONPATH": os.pathsep.join(
            [str(RAIZ)] + ([entorno["PYTHONPATH"]] if entorno.get("PYTHONPATH") else [])),
        "ARGOS_TOKEN": "t" * 32,
        # Fuera de loopback con token: el arranque debe aceptarse (sin token se
        # niega, que es lo que prueba test_security).
        "ARGOS_HOST": "0.0.0.0",
    })
    salida = subprocess.run(
        [sys.executable, "-c", PROGRAMA, json.dumps(rutas)],
        cwd=RAIZ, env=entorno, capture_output=True, text=True, timeout=180)
    assert salida.returncode == 0, salida.stderr[-2000:]

    estados = json.loads(salida.stdout.strip().splitlines()[-1])
    for src in rutas:
        assert estados[src] == 200, (
            f"{src} -> {estados[src]} con token: falta en PUBLIC_PATHS de auth.py")
    assert estados["/api/health"] == 200
    assert estados["/api/sources"] == 401, "sin token, la API debe pedir llave"
