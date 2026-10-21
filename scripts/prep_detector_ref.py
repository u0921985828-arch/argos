#!/usr/bin/env python3
"""Prepara la paridad del detector: fotos -> RGBA crudo + meta.json.

Las dos puntas (detector.js y Detector.kt) leen EXACTAMENTE estos bytes. La
foto se decodifica una sola vez y aquí, para que la comparación mida el
detector y no dos decodificadores JPEG distintos.

    python3 scripts/prep_detector_ref.py DIR foto1.jpg foto2.jpg ...
"""
import json
import pathlib
import sys

import cv2


def main():
    out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    meta = {}
    for f in sys.argv[2:]:
        p = pathlib.Path(f)
        im = cv2.imread(str(p), cv2.IMREAD_COLOR)
        if im is None:
            sys.exit(f"no se pudo leer {p}")
        rgba = cv2.cvtColor(im, cv2.COLOR_BGR2RGBA)
        (out / f"{p.stem}.rgba").write_bytes(rgba.tobytes())
        meta[p.stem] = [int(rgba.shape[1]), int(rgba.shape[0])]
    (out / "meta.json").write_text(json.dumps(meta))
    print(json.dumps(meta))


if __name__ == "__main__":
    main()
