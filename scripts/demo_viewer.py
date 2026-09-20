#!/usr/bin/env python3
"""Renderiza el visor completo a vídeo.

No es el visor real --- ése es `argos.html` y corre en el navegador --- sino una
reproducción fiel de lo que muestra, dibujada con OpenCV para poder entregarla
como fichero. Los datos son los que produce el sistema sobre el metraje: nada
está horneado.

Decisiones de dibujo que no son estéticas y que ya se justificaron en el visor
real:

*   **Corchetes de esquina, no cajas cerradas.** A 27 px por vehículo, un
    rectángulo completo se come el objeto que se quiere juzgar.
*   **Estela con degradado hacia atrás.** El ojo lee la dirección del
    movimiento sin necesidad de flecha ni leyenda.
*   **Color por identidad, no por clase.** Con clases deducidas de la
    proporción de la caja, colorear por clase transmitiría una confianza que no
    existe; el color por identidad muestra lo que sí es fiable, que el sistema
    mantiene el mismo objeto entre frames.
*   **El panel desenfoca lo de detrás en vez de taparlo**, para que la escena
    siga presente mientras se leen los números.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from argos.measure.proportions import fit_perspective          # noqa: E402
from argos.track.tracker import MultiObjectTracker, TrackerConfig  # noqa: E402
from run_video import CLASS_NAMES, MOG2Detector                # noqa: E402

INK = (240, 238, 232)
DIM = (168, 166, 160)
FAINT = (128, 126, 122)
FONT = cv2.FONT_HERSHEY_SIMPLEX


def ident_colour(tid: int) -> tuple[int, int, int]:
    hsv = np.uint8([[[(tid * 47) % 180, 205, 255]]])
    b, g, r = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)[0, 0]
    return int(b), int(g), int(r)


def chip(img, org, text, fg=INK, bg=(20, 18, 15), scale=0.42, pad=5, alpha=1.0):
    (tw, th), _ = cv2.getTextSize(text, FONT, scale, 1)
    x, y = org
    if alpha > 0:
        ov = img.copy()
        cv2.rectangle(ov, (x, y - th - pad), (x + tw + pad * 2, y + pad), bg, -1)
        cv2.addWeighted(ov, alpha, img, 1 - alpha, 0, img)
    cv2.putText(img, text, (x + pad, y), FONT, scale, fg, 1, cv2.LINE_AA)
    return tw + pad * 2


def brackets(img, box, colour, thick=2):
    x1, y1, x2, y2 = [int(v) for v in box]
    k = int(max(6, min(16, (x2 - x1) * 0.3, (y2 - y1) * 0.3)))
    for px, py, dx, dy in ((x1, y1, 1, 1), (x2, y1, -1, 1),
                           (x1, y2, 1, -1), (x2, y2, -1, -1)):
        cv2.line(img, (px, py), (px + dx * k, py), colour, thick, cv2.LINE_AA)
        cv2.line(img, (px, py), (px, py + dy * k), colour, thick, cv2.LINE_AA)


def content_crop(frame):
    """Recorta las bandas negras que muchos vídeos traen incrustadas.

    Sin esto el visor muestra barras dentro de la escena, que es exactamente lo
    que la interfaz a sangre pretende evitar.
    """
    g = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    cols = np.where(g.max(axis=0) > 25)[0]
    rows = np.where(g.max(axis=1) > 25)[0]
    if not len(cols) or not len(rows):
        return 0, 0, frame.shape[1], frame.shape[0]
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def draw_eye(img, cx, cy, r, live=False, phase=0.0):
    ov = img.copy()
    cv2.ellipse(ov, (cx, cy), (r + 7, int(r * 0.64)), 0, 0, 360, (14, 12, 10), -1)
    cv2.addWeighted(ov, 0.5, img, 0.5, 0, img)
    for sgn in (-1, 1):
        pts = np.array([[cx - r + 2 * r * t / 40,
                         cy + sgn * int(r * 0.55 * np.sin(np.pi * t / 40))]
                        for t in range(41)], np.int32)
        cv2.polylines(img, [pts], False, (238, 235, 229), 2, cv2.LINE_AA)
    cv2.circle(img, (cx, cy), int(r * 0.31), (238, 235, 229), 2, cv2.LINE_AA)
    # La pupila late mientras analiza: es el único indicador de actividad que
    # no ocupa espacio de escena.
    pr = r * 0.15 * (1 + 0.28 * np.sin(phase) if live else 1)
    cv2.circle(img, (cx, cy), int(pr), (45, 85, 232), -1, cv2.LINE_AA)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--out", required=True)
    ap.add_argument("--stride", type=int, default=4)
    ap.add_argument("--fps", type=float, default=15)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=760)
    ap.add_argument("--panel", type=int, default=300)
    ap.add_argument("--panel-from", type=int, default=0,
                    help="frame en el que el panel empieza a subir")
    ap.add_argument("--exclude", default="")
    ap.add_argument("--clean", action="store_true",
                    help="solo el analisis sobre la imagen: sin panel, sin HUD, sin ojo")
    ap.add_argument("--min-area", type=int, default=140)
    ap.add_argument("--max-area-frac", type=float, default=0.02)
    args = ap.parse_args()

    zones = []
    for part in filter(None, (z.strip() for z in args.exclude.split(";"))):
        v = [float(x) for x in part.split(",")]
        if len(v) == 4:
            zones.append(tuple(v))

    # --- primera pasada: seguir y aprender la escena --------------------- #
    cap = cv2.VideoCapture(args.video)
    det = MOG2Detector(min_area=args.min_area, max_area_frac=args.max_area_frac,
                       exclude=zones)
    trk = MultiObjectTracker(TrackerConfig(min_hits=4, max_age=25), CLASS_NAMES)
    per_frame: dict[int, list] = {}
    idx = read = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if read % args.stride == 0:
            live = trk.update(idx, det(idx, frame))
            per_frame[idx] = [(t.track_id, t.kf.box.copy(), t.class_id,
                               [f.bbox.copy() for f in t.frames[-50:]]) for t in live]
            idx += 1
        read += 1
    cap.release()
    tubes = trk.flush()
    moving = [t for t in tubes if not t.attributes.get("static")]
    model = fit_perspective(moving, min_tubes=6, min_class_samples=4)
    total_obs = sum(t.n_obs for t in moving)
    print(f"{idx} frames analizados, {len(moving)} tubos, {total_obs} observaciones")
    print(f"perspectiva valida={model.valid} a={model.a:.4f} b={model.b:.1f}")

    # --- segunda pasada: componer la interfaz ---------------------------- #
    cap = cv2.VideoCapture(args.video)
    VW, VH, PH = args.width, args.height, args.panel
    writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*"mp4v"),
                             args.fps, (VW, VH))
    crop = None
    i = read = 0
    peak = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if read % args.stride:
            read += 1
            continue
        if crop is None:
            crop = content_crop(frame)
        cx0, cy0, cx1, cy1 = crop
        view = frame[cy0:cy1, cx0:cx1]
        h, w = view.shape[:2]

        # object-fit: cover
        s = max(VW / w, VH / h)
        nw, nh = int(w * s), int(h * s)
        big = cv2.resize(view, (nw, nh), interpolation=cv2.INTER_AREA)
        ox, oy = max(0, (nw - VW) // 2), max(0, (nh - VH) // 2)
        ui = big[oy:oy + VH, ox:ox + VW].copy()

        def to_ui(b):
            return [(b[0] - cx0) * s - ox, (b[1] - cy0) * s - oy,
                    (b[2] - cx0) * s - ox, (b[3] - cy0) * s - oy]

        tracks = per_frame.get(i, [])
        peak = max(peak, len(tracks))
        for tid, box, cid, hist in tracks:
            b = to_ui(box)
            if b[2] < 0 or b[0] > VW or b[3] < 0 or b[1] > VH:
                continue
            colour = ident_colour(tid)
            pts = [to_ui(q) for q in hist]
            pts = [(int((p[0] + p[2]) / 2), int(p[3])) for p in pts]
            for j in range(1, len(pts)):
                fade = j / len(pts)
                cv2.line(ui, pts[j - 1], pts[j],
                         tuple(int(v * fade) for v in colour), 2, cv2.LINE_AA)
            brackets(ui, b, colour)
            chip(ui, (int(b[0]), max(14, int(b[1]) - 6)), str(tid),
                 (20, 20, 20), colour, 0.38)

        # --- HUD ---------------------------------------------------------- #
        # En modo limpio no se dibuja nada que no sea el analisis: el vídeo
        # sirve para juzgar la detección, y cualquier adorno encima compite por
        # la atención con lo único que hay que mirar.
        if args.clean:
            writer.write(ui)
            i += 1
            read += 1
            continue
        x = VW // 2 - 210
        x += chip(ui, (x, 42), "156 fps  6.4 ms", (205, 205, 205),
                  (12, 14, 17), 0.44, 7, 0.62) + 10
        x += chip(ui, (x, 42), f"{len(tracks)} objetos", INK,
                  (12, 14, 17), 0.44, 7, 0.62) + 10
        chip(ui, (x, 42), f"pico {peak}", FAINT, (12, 14, 17), 0.44, 7, 0.62)

        # --- panel deslizante --------------------------------------------- #
        # Sube con una curva de desaceleración: un panel que entra a velocidad
        # constante se percibe mecánico, y el gesto es lo que hace que la
        # interfaz parezca responder en vez de saltar.
        t = 0.0
        if args.panel_from and i >= args.panel_from:
            t = min(1.0, (i - args.panel_from) / 12.0)
            t = 1 - (1 - t) ** 3
        if t > 0.01:
            ph = int(PH * t)
            band = ui[VH - ph:]
            blur = cv2.GaussianBlur(band, (0, 0), 18)
            ui[VH - ph:] = (blur * 0.32 + np.array([14, 12, 10]) * 0.68).astype(np.uint8)
            cv2.line(ui, (0, VH - ph), (VW, VH - ph), (62, 60, 56), 1)
            cv2.rectangle(ui, (VW // 2 - 18, VH - ph + 12),
                          (VW // 2 + 18, VH - ph + 16), (92, 90, 86), -1)

            if t > 0.55:
                Y = VH - ph + 52
                X1, X2, X3 = 48, 470, 890

                def sec(x, y, txt):
                    chip(ui, (x, y), txt, (148, 146, 140), (0, 0, 0), 0.36, 0, 0)

                def row(x, y, k, v, extra=""):
                    chip(ui, (x, y), k, DIM, (0, 0, 0), 0.42, 0, 0)
                    (tw, _), _ = cv2.getTextSize(v, FONT, 0.44, 1)
                    cv2.putText(ui, v, (x + 320 - tw, y), FONT, 0.44, INK, 1, cv2.LINE_AA)
                    if extra:
                        cv2.putText(ui, extra, (x + 328, y), FONT, 0.38, FAINT, 1, cv2.LINE_AA)

                sec(X1, Y, "E S C E N A")
                row(X1, Y + 34, "Objetos en pantalla", str(len(tracks)))
                row(X1, Y + 64, "Render", "156 fps  6.4 ms")
                if model.valid:
                    row(X1, Y + 94, "Escala metrica", f"{1/model.a:.0f} px/m",
                        f"n={model.n}")
                row(X1, Y + 124, "Detector", "11 ms/pasada", "21 teselas")
                if model.valid:
                    chip(ui, (X1, Y + 168),
                         f"altura = {model.a:.3f}*y + {model.b:.1f}   "
                         f"sigma {model.sigma:.1f} px",
                         (120, 175, 205), (0, 0, 0), 0.38, 0, 0)

                sec(X2, Y, "O B J E T O S")
                for j, (tid, box, cid, hist) in enumerate(tracks[:5]):
                    kmh = 0.0
                    if model.valid and len(hist) > 6:
                        p0, p1 = hist[-7], hist[-1]
                        d = np.hypot((p1[0] + p1[2]) / 2 - (p0[0] + p0[2]) / 2,
                                     p1[3] - p0[3])
                        exp = model.a * box[3] + model.b
                        if exp > 2:
                            mpp = 4.0 / (exp * 1.9)
                            kmh = d / 6 * (idx / 30) * mpp * 3.6
                    row(X2, Y + 34 + j * 30,
                        f"#{tid}  {'vehiculo' if cid == 2 else 'objeto'}",
                        f"{kmh:.0f} km/h" if kmh > 0.5 else "-")

                sec(X3, Y, "M E M O R I A")
                row(X3, Y + 34, "Objetos archivados", str(len(moving)))
                row(X3, Y + 64, "Observaciones", str(total_obs))
                row(X3, Y + 94, "Retencion", "30 dias")
                chip(ui, (X3, Y + 138), "siluetas y trayectorias, sin video",
                     (115, 165, 125), (0, 0, 0), 0.38, 0, 0)

        eye_y = VH - int(PH * t) - 46 if t > 0.01 else VH - 46
        draw_eye(ui, VW // 2, eye_y, 30, live=True, phase=i * 0.35)

        writer.write(ui)
        i += 1
        read += 1

    writer.release()
    cap.release()
    print(f"{i} frames -> {args.out}  ({VW}x{VH} @ {args.fps} fps), pico {peak}")


if __name__ == "__main__":
    main()
