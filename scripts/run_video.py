#!/usr/bin/env python3
"""Run the whole ARGOS pipeline over a video file.

    python3 scripts/run_video.py mi_video.mp4 --out salida/

Detector selection, in order of preference:

*   ``--detector yolo`` uses Ultralytics YOLO segmentation if it is installed.
    Best masks, needs a download, and note the AGPL-3.0 licence --- fine for
    testing on your own machine, a problem only if you ship or host it.
*   ``--detector mog2`` (the default when nothing else is available) uses
    OpenCV's MOG2 background subtractor. **No model, no download, no network.**

MOG2 is not a toy choice here. For a *fixed* camera --- which is the only kind
this system is for --- background subtraction gives genuinely usable silhouettes,
and it gives them for free. What it does not give is class labels, so objects are
classified crudely by aspect ratio and clearly marked as such. It also breaks on
camera shake, sudden light changes, and objects that stop moving (they dissolve
into the background model). Those failure modes are visible in the output rather
than hidden, which is the point of offering it.

Put the camera somewhere fixed --- a window ledge, a tripod, a phone taped to a
frame --- and record 10-20 minutes. Do not hand-hold it: every algorithm
downstream assumes a static view.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from argos.core.types import Detection                                    # noqa: E402
from argos.measure.anthropometry import StatureEstimator                  # noqa: E402
from argos.measure.calibration import (SelfCalibrationConfig,             # noqa: E402
                                       calibrate_from_pedestrians, pairs_from_tubes)
from argos.render.silhouette import (SilhouetteStyle, activity_plate,     # noqa: E402
                                     figures_for_plate, figures_for_strip,
                                     rasterize_figures, trajectory_strip)
from argos.synopsis.optimizer import SolverConfig, SynopsisSolver         # noqa: E402
from argos.synopsis.renderer import RenderConfig, SynopsisRenderer        # noqa: E402
from argos.track.tracker import MultiObjectTracker, TrackerConfig         # noqa: E402
from argos.tubes.patchstore import build_patch_store                      # noqa: E402


# --------------------------------------------------------------------------- #
#  Detectors
# --------------------------------------------------------------------------- #


class MOG2Detector:
    """Background subtraction for a fixed camera. Zero dependencies beyond OpenCV."""

    def __init__(self, min_area: int = 220, warmup: int = 60, shadows: bool = True,
                 max_area_frac: float = 0.02, exclude: list | None = None):
        self.sub = cv2.createBackgroundSubtractorMOG2(
            history=600, varThreshold=28, detectShadows=shadows)
        self.min_area = min_area
        # Techo de área. En una vista fija, una detección que ocupa más del 2 %
        # del cuadro no es un objeto: es fondo cambiando. La causa más habitual
        # es la exposición automática haciendo oscilar el brillo de una
        # estructura inmóvil --- una barandilla, un cartel, un tejado --- que la
        # sustracción de fondo marca obedientemente como movimiento.
        self.max_area_frac = max_area_frac
        # Zonas de exclusión en fracciones del cuadro [(x1,y1,x2,y2), ...].
        # Es lo primero que se configura en cualquier cámara real: el borde del
        # propio edificio, la copa de un árbol, una pantalla publicitaria.
        self.exclude = exclude or []
        self.warmup = warmup
        self.n = 0
        self._mask = None

    def __call__(self, frame_idx: int, frame: np.ndarray) -> list[Detection]:
        fg = self.sub.apply(frame)
        self.n += 1
        if self.n <= self.warmup:
            return []                     # let the background model settle first

        # MOG2 marks shadows with 127. Keeping them roughly doubles every
        # object's apparent width and wrecks both the silhouettes and any
        # stature estimate, so they go.
        fg = (fg > 200).astype(np.uint8)
        if self.exclude:
            if self._mask is None or self._mask.shape != fg.shape:
                h, w = fg.shape
                self._mask = np.ones((h, w), np.uint8)
                for x1, y1, x2, y2 in self.exclude:
                    self._mask[int(y1 * h):int(y2 * h), int(x1 * w):int(x2 * w)] = 0
            fg *= self._mask
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
        fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, k)
        fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11)))

        n, lab, stats, _ = cv2.connectedComponentsWithStats(fg, 8)
        frame_area = fg.shape[0] * fg.shape[1]
        dets = []
        for i in range(1, n):
            x, y, w, h, area = stats[i]
            if area < self.min_area or w < 8 or h < 8:
                continue
            if w * h > self.max_area_frac * frame_area:
                continue
            mask = (lab[y:y + h, x:x + w] == i)
            fill = mask.mean()
            if fill < 0.25:               # sprawling blob: usually a lighting change
                continue
            ar = h / max(1e-6, w)
            cls = 0 if ar > 1.5 else (2 if ar < 0.65 else 99)
            # Confidence from how blob-like it is; the tracker's min_hits does
            # the real filtering.
            score = float(np.clip(0.35 + 0.5 * fill, 0.05, 0.95))
            dets.append(Detection(frame_idx,
                                  np.array([x, y, x + w, y + h], np.float32),
                                  score, cls, mask=mask))
        return dets


class YoloSegDetector:
    def __init__(self, weights: str = "yolo11n-seg.pt", conf: float = 0.20):
        from ultralytics import YOLO
        self.model = YOLO(weights)
        self.conf = conf
        self.keep = {0: 0, 1: 1, 2: 2, 3: 3, 5: 5, 7: 7, 15: 15, 16: 16}

    def __call__(self, frame_idx: int, frame: np.ndarray) -> list[Detection]:
        r = self.model.predict(frame, conf=self.conf, verbose=False)[0]
        if r.boxes is None or len(r.boxes) == 0:
            return []
        dets = []
        masks = r.masks.data.cpu().numpy() if r.masks is not None else None
        for j, b in enumerate(r.boxes):
            cid = int(b.cls.item())
            if cid not in self.keep:
                continue
            x1, y1, x2, y2 = [float(v) for v in b.xyxy[0].tolist()]
            m = None
            if masks is not None and j < len(masks):
                full = cv2.resize(masks[j], (frame.shape[1], frame.shape[0]))
                xi1, yi1 = max(0, int(x1)), max(0, int(y1))
                xi2, yi2 = min(frame.shape[1], int(x2)), min(frame.shape[0], int(y2))
                if xi2 > xi1 and yi2 > yi1:
                    m = full[yi1:yi2, xi1:xi2] > 0.5
            dets.append(Detection(frame_idx, np.array([x1, y1, x2, y2], np.float32),
                                  float(b.conf.item()), cid, mask=m))
        return dets


CLASS_NAMES = {0: "person", 1: "bicycle", 2: "car", 3: "motorcycle",
               5: "bus", 7: "truck", 15: "cat", 16: "dog", 99: "object"}


# --------------------------------------------------------------------------- #


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--out", default="argos_out")
    ap.add_argument("--detector", choices=["auto", "mog2", "yolo", "onnx"], default="auto")
    ap.add_argument("--weights", default="yolo11n-seg.pt")
    ap.add_argument("--onnx", default="", help="ruta a un modelo YOLOX .onnx")
    ap.add_argument("--hybrid", action="store_true",
                    help="cajas del detector + siluetas por sustracción de fondo")
    ap.add_argument("--tile", action="store_true",
                    help="inferencia por teselas: imprescindible con objetos pequeños")
    ap.add_argument("--conf", type=float, default=0.15)
    ap.add_argument("--overlap-tiles", type=float, default=0.45,
                    help="solape entre teselas; por debajo de 0.4 se pierden "
                         "objetos en los bordes")
    ap.add_argument("--stride", type=int, default=1, help="process every Nth frame")
    ap.add_argument("--max-frames", type=int, default=0)
    ap.add_argument("--overlap", type=float, default=0.012,
                    help="clutter budget: lower = longer, more readable synopsis")
    ap.add_argument("--exclude", default="",
                    help="zonas a ignorar, 'x1,y1,x2,y2' en fracciones, ';' entre zonas")
    ap.add_argument("--max-area", type=float, default=0.02,
                    help="fracción máxima del cuadro que puede ocupar un objeto")
    ap.add_argument("--stature", action="store_true",
                    help="self-calibrate and estimate heights (needs pedestrians)")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        sys.exit(f"cannot open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    eff_fps = fps / max(1, args.stride)
    print(f"video: {W}x{H} @ {fps:.1f} fps, {total} frames "
          f"({total / max(1, fps) / 60:.1f} min), stride {args.stride}")

    det = None
    if args.onnx or args.detector == "onnx":
        from argos.detect.onnx_backend import (OnnxDetector, OnnxDetectorConfig,
                                               TiledDetector)
        inner = OnnxDetector(OnnxDetectorConfig(
            model_path=args.onnx, score_thresh=args.conf,
            providers=("CUDAExecutionProvider", "CPUExecutionProvider")))
        if args.hybrid:
            from argos.detect.hybrid import HybridDetector
            det = HybridDetector(inner, overlap=args.overlap_tiles)
            print(f"detector: híbrido ONNX {Path(args.onnx).name} "
                  "(cajas de red + siluetas de fondo)")
        else:
            det = TiledDetector(inner, overlap=args.overlap_tiles) if args.tile else inner
            print(f"detector: ONNX {Path(args.onnx).name}"
                  + (" con teselas" if args.tile else ""))
    if det is None and args.detector in ("auto", "yolo"):
        try:
            det = YoloSegDetector(args.weights)
            print("detector: YOLO-seg")
        except Exception as e:
            if args.detector == "yolo":
                sys.exit(f"YOLO unavailable: {e}")
            print(f"detector: MOG2 (YOLO unavailable: {type(e).__name__})")
    if det is None:
        zones = []
        for part in filter(None, (z.strip() for z in args.exclude.split(";"))):
            try:
                v = [float(x) for x in part.split(",")]
                if len(v) == 4:
                    zones.append(tuple(v))
            except ValueError:
                sys.exit(f"zona de exclusión mal formada: {part!r}")
        det = MOG2Detector(max_area_frac=args.max_area, exclude=zones)
        if zones:
            print(f"zonas excluidas: {len(zones)}")
        print("detector: MOG2 background subtraction (no model required)")

    trk = MultiObjectTracker(TrackerConfig(min_hits=4, max_age=int(eff_fps)), CLASS_NAMES)

    idx = 0
    read = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if read % args.stride == 0:
            trk.update(idx, det(idx, frame))
            if hasattr(det, "set_hints"):
                # Realimentación: las cajas vivas del tracker dirigen la atención
                # del detector al frame siguiente, de modo que un objeto que se
                # detiene sigue mirándose aunque deje de generar movimiento.
                det.set_hints([t.kf.box for t in trk.tracks])
            idx += 1
            if idx % 250 == 0:
                print(f"  {idx} frames procesados, {len(trk.tracks)} pistas activas")
            if args.max_frames and idx >= args.max_frames:
                break
        read += 1
    cap.release()

    tubes = trk.flush()
    static = [t for t in tubes if t.attributes.get("static")]
    tubes = [t for t in tubes if not t.attributes.get("static")]
    if static:
        print(f"descartados del sinopsis {len(static)} objetos inmóviles "
              f"(decorado): {sorted({t.class_name for t in static})}")
    for t in tubes:
        t.fps = eff_fps
        t.camera_id = Path(args.video).stem
        t.t0_wall = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    n_obs = sum(t.n_obs for t in tubes)
    print(f"\n{len(tubes)} tubos, {n_obs} observaciones, "
          f"concurrencia {n_obs / max(1, idx):.2f} obj/frame")
    if not tubes:
        sys.exit("no se detectó nada: prueba a bajar --stride o revisa que la cámara esté fija")

    # ---- synopsis -------------------------------------------------------- #
    solver = SynopsisSolver(tubes, SolverConfig(scale=6, iterations=25000,
                                                max_overlap_ratio=args.overlap))
    plan = solver.auto()
    print(f"sinopsis: {plan.summary()}")

    cap = cv2.VideoCapture(args.video)
    cache: dict[int, np.ndarray] = {}

    def getter(i: int):
        if i in cache:
            return cache[i]
        cap.set(cv2.CAP_PROP_POS_FRAMES, i * args.stride)
        ok, f = cap.read()
        if ok and len(cache) < 400:
            cache[i] = f
        return f if ok else None

    store = build_patch_store(tubes, getter)
    print(f"patch store: {len(store)} recortes, {store.nbytes / 1e6:.1f} MB")

    sample = [getter(i) for i in np.linspace(0, max(0, idx - 1), 40).astype(int)]
    sample = [s for s in sample if s is not None]
    plate = np.median(np.stack(sample), axis=0).astype(np.uint8)
    cap.release()
    cv2.imwrite(str(out / "plate.png"), plate)

    rend = SynopsisRenderer(tubes, plan, RenderConfig(feather=2, draw_labels=True))
    rend.render(store, plate, str(out / "sinopsis.mp4"), fps=eff_fps)
    occ = rend.occupancy_profile()
    print(f"sinopsis.mp4: {plan.duration} frames, "
          f"{occ.mean():.1f} objetos/frame de media, máx {occ.max()}")

    # ---- silhouettes ----------------------------------------------------- #
    style = SilhouetteStyle()
    (out / "activity_plate.svg").write_text(activity_plate(tubes, (W, H), 2, style))
    cv2.imwrite(str(out / "activity_plate.png"),
                rasterize_figures(figures_for_plate(tubes, style, 2), (W, H), style))

    dark = SilhouetteStyle(outline_only=True, background="#101216",
                           stroke="#E9E4D8", stroke_px=2.2)
    cv2.imwrite(str(out / "activity_outline.png"),
                rasterize_figures(figures_for_plate(tubes, dark, 2), (W, H), dark))

    longest = max(tubes, key=lambda t: t.n_obs)
    (out / "trajectory_strip.svg").write_text(trajectory_strip(longest, (W, H), 10, style))
    cv2.imwrite(str(out / "trajectory_strip.png"),
                rasterize_figures(figures_for_strip(longest, style, 10), (W, H), style))
    print(f"siluetas: activity_plate.svg/.png, trajectory_strip.svg/.png "
          f"(tubo {longest.tube_id}, {longest.n_obs} obs)")

    # ---- stature (optional) ---------------------------------------------- #
    stats = {"tubes": len(tubes), "observations": n_obs, "frames": idx,
             "synopsis_frames": plan.duration,
             "compression": round(plan.compression, 2),
             "classes": {}}
    for t in tubes:
        stats["classes"][t.class_name] = stats["classes"].get(t.class_name, 0) + 1

    MIN_PEDS_FOR_CALIB = 12
    if args.stature:
        peds = [t for t in tubes if t.class_name == "person"]
        # Self-calibration anchors the scale on the *median* of the observed
        # crowd. With a handful of people that median is meaningless, and the
        # estimator will happily return a confident 2.73 m. Refusing is the
        # correct output; a number here would be worse than nothing.
        if len(peds) < MIN_PEDS_FOR_CALIB:
            print(f"\nestatura: omitida -- {len(peds)} peatones detectados, "
                  f"hacen falta >= {MIN_PEDS_FOR_CALIB} para autocalibrar.")
            if isinstance(det, MOG2Detector):
                print("  (MOG2 clasifica por proporción de la caja, no reconoce clases: "
                      "usa --detector yolo para etiquetas fiables)")
            peds = []
        try:
            feet, heads = pairs_from_tubes(peds, (W, H), per_tube=6)
            cam = calibrate_from_pedestrians(feet, heads, SelfCalibrationConfig())
            est = StatureEstimator(cam, (W, H))
            rows = []
            for t in peds:
                e = est.estimate(t)
                # 'poor' means the estimator itself does not trust the figure.
                # Printing it anyway just launders an unreliable number.
                if np.isfinite(e.height_m) and e.quality in ("good", "fair"):
                    rows.append({"tube": t.tube_id, "m": round(e.height_m, 2),
                                 "ci": [round(e.ci_low, 2), round(e.ci_high, 2)],
                                 "calidad": e.quality})
            stats["calibration"] = cam.provenance()
            stats["stature"] = rows
            print(f"\nestatura: {len(rows)}/{len(peds)} personas con medida utilizable "
                  f"(calibración '{cam.mode}', NO evidencial -- altura con calzado)")
            for r in rows[:8]:
                print(f"  tubo {r['tube']}: {r['m']} m "
                      f"[{r['ci'][0]}-{r['ci'][1]}] {r['calidad']}")
        except Exception as e:
            if peds:
                print(f"estatura no disponible: {e}")

    from argos.measure.proportions import analyse, fit_perspective
    model = fit_perspective(tubes + static)
    if model.valid:
        verdicts = analyse(tubes + static, model)
        stats["perspective"] = {"a": round(model.a, 4), "b": round(model.b, 2),
                                "sigma_px": round(model.sigma, 2), "n": model.n}
        stats["shapes"] = [v.as_dict() for v in verdicts]
        flagged = [v for v in verdicts if v.flags]
        print(f"\nperspectiva: altura = {model.a:.3f}·y + {model.b:.1f} px "
              f"(sigma {model.sigma:.1f} px sobre {model.n} tubos)")
        counts: dict[str, int] = {}
        for v in verdicts:
            for f in v.flags:
                counts[f] = counts.get(f, 0) + 1
        if counts:
            print(f"marcas: {counts}")
        cls: dict[str, int] = {}
        for v in verdicts:
            cls[v.suggested_class] = cls.get(v.suggested_class, 0) + 1
        print(f"clase por forma: {cls}")

    (out / "stats.json").write_text(json.dumps(stats, indent=2, ensure_ascii=False))
    print(f"\ntodo en {out.resolve()}/")


if __name__ == "__main__":
    main()
