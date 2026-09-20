"""Stitching: turn a :class:`SynopsisPlan` back into pixels.

Three things separate a convincing synopsis from an obvious cut-and-paste:

*   **A clean plate.** Background is estimated by temporal median over a sample
    of frames, recomputed per segment so gradual lighting change (sunset,
    clouds, IR cut-over) does not leave objects sitting on a plate from a
    different hour. Each synopsis frame draws its plate from the *time band the
    majority of its objects came from*, which keeps shadows plausible.
*   **Depth ordering.** Objects are composited back-to-front by the y coordinate
    of their base, so a pedestrian nearer the camera correctly occludes one
    further away instead of the last-written-wins artefact.
*   **Feathered alpha.** A distance-transform ramp on the silhouette edge kills
    the cut-out halo that makes composites read as fake.

Every object carries its **original wall-clock timestamp** rendered next to it.
Without that the output is unusable as evidence: the whole point is that objects
on screen together were not there together.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from ..core.types import SynopsisPlan, Tube
from ..tubes.patchstore import PatchSource


@dataclass(slots=True)
class RenderConfig:
    feather: int = 3            # px of alpha ramp on silhouette edges
    draw_labels: bool = True
    draw_trails: bool = False
    trail_length: int = 40
    label_scale: float = 0.42
    dim_background: float = 1.0
    palette_seed: int = 7


def estimate_background(frames: list[np.ndarray]) -> np.ndarray:
    """Temporal median clean plate from a sample of frames."""
    if not frames:
        raise ValueError("no frames supplied")
    stack = np.stack(frames[:64]).astype(np.uint8)
    return np.median(stack, axis=0).astype(np.uint8)


def background_bands(frames: dict[int, np.ndarray], n_bands: int = 6) -> list[tuple[int, np.ndarray]]:
    """Clean plates for successive time bands, so lighting tracks the source."""
    if not frames:
        return []
    idxs = sorted(frames)
    per = max(1, len(idxs) // n_bands)
    bands: list[tuple[int, np.ndarray]] = []
    for b in range(0, len(idxs), per):
        chunk = idxs[b: b + per]
        if not chunk:
            continue
        bands.append((chunk[len(chunk) // 2], estimate_background([frames[i] for i in chunk])))
    return bands


def _tube_color(tube_id: int, seed: int) -> tuple[int, int, int]:
    rng = np.random.default_rng(tube_id * 9973 + seed)
    hsv = np.uint8([[[rng.integers(0, 180), 210, 255]]])
    b, g, r = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)[0, 0]
    return int(b), int(g), int(r)


class SynopsisRenderer:
    def __init__(self, tubes: list[Tube], plan: SynopsisPlan,
                 cfg: RenderConfig | None = None):
        self.tubes = {t.tube_id: t for t in tubes}
        self.plan = plan
        self.cfg = cfg or RenderConfig()
        self._dense = {t.tube_id: t.densify() for t in tubes}
        self._schedule = self._build_schedule()

    def _build_schedule(self) -> dict[int, list[tuple[int, int]]]:
        """synopsis_frame -> [(tube_id, source_frame_idx), ...]"""
        sched: dict[int, list[tuple[int, int]]] = {}
        for tid, pl in self.plan.placements.items():
            if pl.dropped:
                continue
            tube = self.tubes[tid]
            for src_idx in self._dense[tid]:
                out = pl.map_frame(tube, src_idx)
                if 0 <= out < self.plan.duration:
                    sched.setdefault(out, []).append((tid, src_idx))
        return sched

    def occupancy_profile(self) -> np.ndarray:
        """Objects visible per synopsis frame --- the readability curve."""
        return np.array([len(self._schedule.get(i, ())) for i in range(self.plan.duration)])

    # ------------------------------------------------------------------ #

    def compose(self, out_idx: int, plate: np.ndarray,
                source: PatchSource) -> np.ndarray:
        """Render one synopsis frame onto ``plate``."""
        canvas = plate.copy()
        if self.cfg.dim_background < 1.0:
            canvas = (canvas * self.cfg.dim_background).astype(np.uint8)
        H, W = canvas.shape[:2]

        items = self._schedule.get(out_idx, [])
        # Back-to-front by base y: objects lower in frame are nearer the camera.
        items = sorted(items, key=lambda it: self._dense[it[0]][it[1]].bbox[3])

        for tid, src_idx in items:
            tf = self._dense[tid][src_idx]
            patch = source.patch(tid, src_idx)
            if patch is None:
                continue
            x1, y1, x2, y2 = [int(round(v)) for v in tf.bbox]
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(W, x2), min(H, y2)
            if x2 <= x1 or y2 <= y1:
                continue
            if patch.shape[0] != (y2 - y1) or patch.shape[1] != (x2 - x1):
                patch = cv2.resize(patch, (x2 - x1, y2 - y1), interpolation=cv2.INTER_LINEAR)
            m = tf.mask()
            if m is None:
                alpha = np.ones((y2 - y1, x2 - x1), np.float32)
            else:
                m = cv2.resize(m.astype(np.uint8), (x2 - x1, y2 - y1),
                               interpolation=cv2.INTER_NEAREST)
                alpha = self._feather(m)

            a3 = alpha[:, :, None]
            roi = canvas[y1:y2, x1:x2].astype(np.float32)
            canvas[y1:y2, x1:x2] = (roi * (1 - a3) + patch.astype(np.float32) * a3).astype(np.uint8)

            if self.cfg.draw_labels:
                self._label(canvas, tid, src_idx, (x1, y1, x2, y2))
        return canvas

    def _feather(self, m: np.ndarray) -> np.ndarray:
        f = self.cfg.feather
        if f <= 0 or m.sum() == 0:
            return m.astype(np.float32)
        d = cv2.distanceTransform(m, cv2.DIST_L2, 3)
        return np.clip(d / float(f), 0.0, 1.0).astype(np.float32)

    def _label(self, canvas: np.ndarray, tid: int, src_idx: int,
               box: tuple[int, int, int, int]) -> None:
        tube = self.tubes[tid]
        col = _tube_color(tid, self.cfg.palette_seed)
        x1, y1, x2, y2 = box
        cv2.rectangle(canvas, (x1, y1), (x2, y2), col, 1, cv2.LINE_AA)
        wt = tube.wall_time(src_idx)
        txt = wt.strftime("%H:%M:%S") if wt else f"f{src_idx}"
        txt = f"{tube.class_name} {txt}"
        (tw, th), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, self.cfg.label_scale, 1)
        ly = max(th + 3, y1 - 3)
        cv2.rectangle(canvas, (x1, ly - th - 3), (x1 + tw + 4, ly + 2), col, -1)
        cv2.putText(canvas, txt, (x1 + 2, ly), cv2.FONT_HERSHEY_SIMPLEX,
                    self.cfg.label_scale, (16, 16, 16), 1, cv2.LINE_AA)

    # ------------------------------------------------------------------ #

    def render(self, source: PatchSource, plate: np.ndarray,
               path: str, fps: float = 25.0) -> str:
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        H, W = plate.shape[:2]
        vw = cv2.VideoWriter(path, fourcc, fps, (W, H))
        for i in range(self.plan.duration):
            vw.write(self.compose(i, plate, source))
        vw.release()
        return path
