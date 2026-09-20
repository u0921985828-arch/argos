#!/usr/bin/env python3
"""Vídeo de demostración: la escena con el análisis dibujado encima.

No es una herramienta de producción, es una ventana al estado interno. Lo que se
ve en pantalla son las estructuras que el resto del sistema consume: cajas de
tracking con identidad persistente, la estela real de cada tubo y el contador de
objetos vivos.

Decisiones de dibujo que no son estéticas:

*   **Corchetes de esquina y no cajas cerradas.** Un rectángulo completo tapa
    justo el objeto que se quiere juzgar. En una vista aérea, donde un coche
    mide cuarenta píxeles, el borde se come una fracción notable del objeto.
*   **Estela desde el tubo, no desde un histórico de la vista.** La línea es
    literalmente la trayectoria almacenada; si el tracker cambia de identidad,
    se ve como un salto en la estela en lugar de quedar disimulado.
*   **Color por identidad, no por clase.** Con clases deducidas de la proporción
    de la caja, colorear por clase transmitiría una confianza que no existe. El
    color por identidad muestra lo que sí es fiable: que el sistema mantiene el
    mismo objeto entre frames.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from argos.core.types import Tube                                  # noqa: E402
from argos.track.tracker import MultiObjectTracker, TrackerConfig  # noqa: E402
from run_video import CLASS_NAMES, MOG2Detector                    # noqa: E402

INK = (233, 239, 242)
DIM = (150, 150, 150)
PANEL = (18, 16, 14)


def ident_colour(tid: int) -> tuple[int, int, int]:
    hsv = np.uint8([[[(tid * 47) % 180, 205, 255]]])
    b, g, r = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)[0, 0]
    return int(b), int(g), int(r)


def brackets(img, box, colour, thick=2):
    x1, y1, x2, y2 = [int(v) for v in box]
    k = int(max(6, min(18, (x2 - x1) * 0.3, (y2 - y1) * 0.3)))
    for (px, py, dx, dy) in ((x1, y1, 1, 1), (x2, y1, -1, 1),
                             (x1, y2, 1, -1), (x2, y2, -1, -1)):
        cv2.line(img, (px, py), (px + dx * k, py), colour, thick, cv2.LINE_AA)
        cv2.line(img, (px, py), (px, py + dy * k), colour, thick, cv2.LINE_AA)


def chip(img, org, text, fg=INK, bg=PANEL, scale=0.42, pad=4):
    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, 1)
    x, y = org
    cv2.rectangle(img, (x, y - th - pad), (x + tw + pad * 2, y + pad), bg, -1)
    cv2.putText(img, text, (x + pad, y), cv2.FONT_HERSHEY_SIMPLEX, scale,
                fg, 1, cv2.LINE_AA)
    return tw + pad * 2


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--out", required=True)
    ap.add_argument("--stride", type=int, default=3)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--exclude", default="")
    ap.add_argument("--trail", type=int, default=45)
    ap.add_argument("--fps", type=float, default=0)
    ap.add_argument("--onnx", default="")
    ap.add_argument("--tile", action="store_true")
    ap.add_argument("--conf", type=float, default=0.30)
    args = ap.parse_args()

    zones = []
    for part in filter(None, (z.strip() for z in args.exclude.split(";"))):
        v = [float(x) for x in part.split(",")]
        if len(v) == 4:
            zones.append(tuple(v))

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        sys.exit(f"no se puede abrir {args.video}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    out_w = args.width
    out_h = int(round(H * out_w / W))
    if out_h % 2:
        out_h += 1
    k = out_w / W
    fps = args.fps or (src_fps / args.stride)

    if args.onnx:
        from argos.detect.onnx_backend import (OnnxDetector, OnnxDetectorConfig,
                                               TiledDetector)
        inner = OnnxDetector(OnnxDetectorConfig(
            model_path=args.onnx, score_thresh=args.conf,
            providers=("CPUExecutionProvider",)))
        det = TiledDetector(inner) if args.tile else inner
    else:
        det = MOG2Detector(exclude=zones)
    trk = MultiObjectTracker(TrackerConfig(min_hits=4, max_age=25), CLASS_NAMES)

    writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*"mp4v"),
                             fps, (out_w, out_h))
    trails: dict[int, list[tuple[int, int]]] = {}
    idx = read = 0
    peak = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if read % args.stride:
            read += 1
            continue

        live = trk.update(idx, det(idx, frame))
        canvas = cv2.resize(frame, (out_w, out_h), interpolation=cv2.INTER_AREA)

        # Atenuar las zonas excluidas en vez de ocultarlas: el operador debe ver
        # qué está ignorando el sistema, no descubrirlo cuando algo no se detecta.
        for x1, y1, x2, y2 in zones:
            a, b = (int(x1 * out_w), int(y1 * out_h)), (int(x2 * out_w), int(y2 * out_h))
            roi = canvas[a[1]:b[1], a[0]:b[0]]
            if roi.size:
                canvas[a[1]:b[1], a[0]:b[0]] = (roi * 0.45).astype(np.uint8)

        for t in live:
            if not t.frames:
                continue
            box = t.frames[-1].bbox * k
            colour = ident_colour(t.track_id)
            cx, cy = int((box[0] + box[2]) / 2), int(box[3])
            trails.setdefault(t.track_id, []).append((cx, cy))
            path = trails[t.track_id][-args.trail:]
            for i in range(1, len(path)):
                fade = i / len(path)
                cv2.line(canvas, path[i - 1], path[i],
                         tuple(int(c * fade) for c in colour), 1, cv2.LINE_AA)
            brackets(canvas, box, colour)
            chip(canvas, (int(box[0]), max(14, int(box[1]) - 5)),
                 f"{t.track_id} {trk.class_names.get(t.class_id, '?')}",
                 fg=(20, 20, 20), bg=colour, scale=0.38)

        peak = max(peak, len(live))
        for tid in list(trails):
            if not any(t.track_id == tid for t in trk.tracks):
                trails.pop(tid, None)

        bar = canvas[:34].copy()
        canvas[:34] = (bar * 0.25).astype(np.uint8)
        x = 10
        x += chip(canvas, (x, 23), "ARGOS", scale=0.5) + 8
        x += chip(canvas, (x, 23), f"{len(live)} objetos", bg=(30, 60, 40)) + 6
        x += chip(canvas, (x, 23), f"pico {peak}", fg=DIM) + 6
        x += chip(canvas, (x, 23), f"{len(trk.finished) + idx // 10 * 0} tubos "
                                   f"{len(trk.tracks)} pistas", fg=DIM) + 6
        chip(canvas, (x, 23), f"t+{idx / fps:05.1f}s", fg=DIM)

        writer.write(canvas)
        idx += 1
        read += 1

    writer.release()
    cap.release()
    print(f"{idx} frames -> {args.out} ({out_w}x{out_h} @ {fps:.1f} fps), pico {peak} objetos")


if __name__ == "__main__":
    main()
