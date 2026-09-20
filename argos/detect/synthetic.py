"""A synthetic camera scene.

Used to exercise the full pipeline --- and, more importantly, to *benchmark the
solver against ground truth* --- without needing GPU weights or a real video.
Because we know exactly where every object was, we can measure the true
collision rate of a synopsis rather than eyeballing it.

Geometry mimics a typical fixed-camera view: a road band with vehicles moving
horizontally, and two pedestrian walkways, plus a slow global illumination
drift so the background estimator has something to cope with.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

import cv2
import numpy as np

from ..core.types import Tube, TubeFrame


@dataclass(slots=True)
class SceneConfig:
    width: int = 960
    height: int = 540
    duration: int = 9000        # source frames
    fps: float = 25.0
    n_objects: int = 140
    max_dwell_s: float = 14.0   # objects leave the scene (doorways, turnings)
    seed: int = 11
    t0: datetime = datetime(2026, 8, 19, 6, 0, 0)


class SyntheticScene:
    def __init__(self, cfg: SceneConfig | None = None):
        self.cfg = cfg or SceneConfig()
        self.rng = np.random.default_rng(self.cfg.seed)
        self.plate = self._make_plate()
        self.tubes: list[Tube] = []
        self._paths: dict[int, list[tuple[int, np.ndarray, str]]] = {}
        self._build()

    # ------------------------------------------------------------------ #

    def _make_plate(self) -> np.ndarray:
        c = self.cfg
        img = np.zeros((c.height, c.width, 3), np.uint8)
        img[:, :] = (78, 92, 74)                                  # grass
        cv2.rectangle(img, (0, int(c.height * .42)), (c.width, int(c.height * .70)), (58, 58, 60), -1)  # road
        for x in range(0, c.width, 70):                           # lane markings
            cv2.rectangle(img, (x, int(c.height * .56) - 2), (x + 34, int(c.height * .56) + 2), (180, 180, 175), -1)
        cv2.rectangle(img, (0, int(c.height * .70)), (c.width, int(c.height * .78)), (120, 116, 110), -1)  # pavement
        cv2.rectangle(img, (0, int(c.height * .30)), (c.width, int(c.height * .42)), (110, 106, 100), -1)
        for i in range(0, c.width, 130):                          # buildings
            h = int(c.height * (.10 + .16 * ((i // 130) % 3) / 3))
            cv2.rectangle(img, (i + 8, int(c.height * .30) - h), (i + 110, int(c.height * .30)), (96, 88, 84), -1)
        return cv2.GaussianBlur(img, (3, 3), 0)

    def _build(self) -> None:
        c = self.cfg
        rng = self.rng
        # Activity is front-loaded into bursts, as real footage is: this is what
        # makes naive "uniform speed-up" fast-forward useless and synopsis useful.
        burst_centres = rng.uniform(0, c.duration, size=6)
        for oid in range(c.n_objects):
            if rng.random() < 0.7:
                t0 = int(np.clip(rng.normal(rng.choice(burst_centres), c.duration * 0.03), 0, c.duration - 60))
            else:
                t0 = int(rng.uniform(0, c.duration - 60))
            kind = "car" if rng.random() < 0.42 else "person"
            tube = self._make_tube(oid, kind, t0)
            if tube is not None and tube.n_obs >= 8:
                self.tubes.append(tube)

    def _make_tube(self, oid: int, kind: str, t0: int) -> Tube | None:
        c, rng = self.cfg, self.rng
        if kind == "car":
            lane = rng.choice([0.47, 0.53, 0.60, 0.65])
            y = c.height * lane
            w, h = rng.uniform(70, 110), rng.uniform(28, 40)
            speed = rng.uniform(5.0, 11.0) * (1 if rng.random() < .5 else -1)
            wob = 1.2
        else:
            walk = rng.choice([0.345, 0.735])
            y = c.height * walk
            w, h = rng.uniform(14, 22), rng.uniform(38, 56)
            speed = rng.uniform(1.1, 2.4) * (1 if rng.random() < .5 else -1)
            wob = 3.0

        # Objects do not all traverse the full frame: they emerge from doorways,
        # turn off, park. Real archives are dominated by short dwell times, which
        # is precisely the regime where good packing beats uniform spreading.
        if rng.random() < 0.45:
            x = float(rng.uniform(0.1, 0.9) * c.width)
        else:
            x = -w if speed > 0 else c.width + w
        dwell = int(rng.uniform(0.25, 1.0) * c.max_dwell_s * c.fps)
        frames: list[TubeFrame] = []
        phase = rng.uniform(0, 6.28)
        for k in range(4000):
            fi = t0 + k
            if fi >= c.duration or k >= dwell:
                break
            cx = x + speed * k
            if cx < -w * 1.5 or cx > c.width + w * 1.5:
                break
            cy = y + wob * np.sin(0.08 * k + phase)
            bob = 2.0 * np.sin(0.35 * k + phase) if kind == "person" else 0.0
            bbox = np.array([cx - w / 2, cy - h / 2 + bob, cx + w / 2, cy + h / 2 + bob], np.float32)
            mask = self._silhouette(kind, int(max(4, w)), int(max(4, h)), k)
            blob, shape = TubeFrame.pack_mask(mask)
            frames.append(TubeFrame(fi, bbox, 0.9, blob, shape))
        if len(frames) < 8:
            return None

        tube = Tube(
            tube_id=oid,
            class_id=2 if kind == "car" else 0,
            class_name=kind,
            frames=frames,
            camera_id="cam-synth-01",
            t0_wall=c.t0 + timedelta(seconds=t0 / c.fps),
            fps=c.fps,
        )
        tube.attributes = {
            "direction": "E" if speed > 0 else "W",
            "speed_px_s": abs(speed) * c.fps,
            "colour": self._colour_name(oid),
        }
        self._paths[oid] = [(f.frame_idx, f.bbox, kind) for f in frames]
        return tube

    @staticmethod
    def _silhouette(kind: str, w: int, h: int, k: int) -> np.ndarray:
        m = np.zeros((h, w), np.uint8)
        if kind == "car":
            cv2.rectangle(m, (0, int(h * .35)), (w - 1, h - 1), 1, -1)
            cv2.rectangle(m, (int(w * .22), 0), (int(w * .74), int(h * .40)), 1, -1)
        else:
            cv2.ellipse(m, (w // 2, int(h * .16)), (int(w * .30), int(h * .15)), 0, 0, 360, 1, -1)
            cv2.rectangle(m, (int(w * .22), int(h * .28)), (int(w * .78), int(h * .70)), 1, -1)
            sw = int(w * .18) + int(3 * abs(np.sin(0.35 * k)))
            cv2.rectangle(m, (int(w * .30) - sw // 3, int(h * .68)), (int(w * .46), h - 1), 1, -1)
            cv2.rectangle(m, (int(w * .54), int(h * .68)), (int(w * .70) + sw // 3, h - 1), 1, -1)
        return m.astype(bool)

    def _colour_name(self, oid: int) -> str:
        names = ["red", "blue", "white", "black", "silver", "green", "yellow"]
        return names[oid % len(names)]

    def _colour_bgr(self, oid: int) -> tuple[int, int, int]:
        table = {
            "red": (36, 40, 190), "blue": (185, 92, 40), "white": (225, 228, 230),
            "black": (34, 34, 36), "silver": (168, 170, 172), "green": (60, 150, 70),
            "yellow": (40, 200, 225),
        }
        return table[self._colour_name(oid)]

    # ------------------------------------------------------------------ #

    def frame(self, idx: int) -> np.ndarray:
        """Render one source frame (objects drawn onto the plate)."""
        c = self.cfg
        drift = 1.0 + 0.22 * np.sin(2 * np.pi * idx / max(1, c.duration))
        img = np.clip(self.plate.astype(np.float32) * drift, 0, 255).astype(np.uint8)
        for tube in self.tubes:
            if not (tube.start <= idx <= tube.end):
                continue
            tf = next((f for f in tube.frames if f.frame_idx == idx), None)
            if tf is None:
                continue
            x1, y1, x2, y2 = [int(round(v)) for v in tf.bbox]
            X1, Y1 = max(0, x1), max(0, y1)
            X2, Y2 = min(c.width, x2), min(c.height, y2)
            if X2 <= X1 or Y2 <= Y1:
                continue
            m = tf.mask()
            m = cv2.resize(m.astype(np.uint8), (x2 - x1, y2 - y1), interpolation=cv2.INTER_NEAREST)
            m = m[Y1 - y1: Y2 - y1, X1 - x1: X2 - x1].astype(bool)
            col = np.array(self._colour_bgr(tube.tube_id), np.uint8)
            roi = img[Y1:Y2, X1:X2]
            shade = np.linspace(0.82, 1.12, roi.shape[0], dtype=np.float32)[:, None, None]
            shaded = np.clip(col.astype(np.float32)[None, None, :] * shade, 0, 255).astype(np.uint8)
            shaded = np.broadcast_to(shaded, roi.shape)
            roi[m] = shaded[m]
        return img

    def source_frames(self, idxs) -> dict[int, np.ndarray]:
        return {i: self.frame(i) for i in idxs}
