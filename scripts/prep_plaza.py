#!/usr/bin/env python3
"""Escena de personas pequeñas para el banco del zoom automático.

Una «plaza» de 1280×960: suelo texturado y copias reducidas de zidane (÷12) y
bus (÷8), de modo que cada persona ocupa ~50-60 px de alto en la fuente. En
cuadro entero eso llega al modelo a ~17-20 px —la franja «irregular» de la
tabla de presupuesto de píxeles— y con teselas ×2 a ~100. Es el caso para el
que existen las teselas, construido a partir de fotos reales.

    python3 scripts/prep_plaza.py DIR bus.jpg zidane.jpg   ->  DIR/plaza.rgba, plaza.json
"""
import json
import pathlib
import sys

import cv2
import numpy as np


def main():
    out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    bus = cv2.imread(sys.argv[2]); zid = cv2.imread(sys.argv[3])
    if bus is None or zid is None:
        sys.exit("no se pudieron leer las fotos")
    W, H = 1280, 960
    rng = np.random.default_rng(7)
    suelo = rng.integers(90, 140, size=(H // 16, W // 16), dtype=np.uint8)
    suelo = cv2.resize(suelo, (W, H), interpolation=cv2.INTER_NEAREST)
    img = cv2.cvtColor(suelo, cv2.COLOR_GRAY2BGR)
    z = cv2.resize(zid, (zid.shape[1] // 12, zid.shape[0] // 12), interpolation=cv2.INTER_AREA)
    b = cv2.resize(bus, (bus.shape[1] // 8, bus.shape[0] // 8), interpolation=cv2.INTER_AREA)
    for k, (x, y) in enumerate([(60, 80), (420, 120), (800, 90), (1100, 200), (150, 420),
                                (560, 480), (940, 430), (300, 760), (700, 780), (1080, 700)]):
        pieza = z if k % 2 == 0 else b
        h, w = pieza.shape[:2]
        x = min(x, W - w); y = min(y, H - h)
        img[y:y + h, x:x + w] = pieza
    rgba = cv2.cvtColor(img, cv2.COLOR_BGR2RGBA)
    (out / "plaza.rgba").write_bytes(rgba.tobytes())
    (out / "plaza.json").write_text(json.dumps({"plaza": [W, H]}))
    print("plaza", W, H)


if __name__ == "__main__":
    main()
