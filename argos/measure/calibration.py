"""Camera self-calibration from the people already walking through the scene.

The stature estimator in :mod:`anthropometry` needs a calibrated camera. Asking
a technician to survey four ground points and measure a doorway on every camera
of a 200-camera site is the reason these features go unused in practice.

There is a much better option, and it has been known since Lv/Zhao/Nevatia
(2002): **pedestrians are the calibration target**. A standing person is a
vertical segment of roughly known length standing on the ground plane, and a
camera watching a walkway for an hour sees hundreds of them.

Three quantities come out, in order:

1.  **Vertical vanishing point** ``v``. Every head-to-foot segment is a vertical
    line in the world, so their images all pass through ``v``. Stack the line
    vectors and take the null space.
2.  **Horizon line** ``l``. Two people of equal height produce a foot-line and a
    head-line that meet *on the horizon*. People are not all equal height, so
    this needs robust fitting --- hence RANSAC over pairs.
3.  **Scale** ``alpha``. This is the only part that needs external information,
    and population statistics supply it: set the scale so that the *median*
    estimated stature of the observed crowd equals the known median stature of
    the relevant population. No tape measure, no reference object.

With ``v``, ``l`` and ``alpha``, Criminisi's formula gives metric height
directly, without ever computing a ground homography:

    Z = - |b x t| / ( alpha (l . b) |v x t| )

**Where this is weak, stated plainly.** The population anchor transfers the
error of the assumed median stature straight into every measurement: if the site
median is really 1.74 m and you assumed 1.70 m, every result is 2.3 % short.
That is a systematic offset, so it does not average away, and it cannot be
detected from the video. Self-calibration is therefore the right default for
*search and triage*, and a surveyed reference object remains mandatory before
any figure goes into evidence. The class records which mode produced it so that
distinction survives into the database.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


def _h(p: np.ndarray) -> np.ndarray:
    """To homogeneous, normalised."""
    p = np.asarray(p, np.float64).ravel()
    if p.size == 2:
        return np.array([p[0], p[1], 1.0])
    return p / p[2] if abs(p[2]) > 1e-12 else p


# --------------------------------------------------------------------------- #
#  Camera
# --------------------------------------------------------------------------- #


@dataclass
class HorizonCamera:
    """Metric height from a vanishing point, a horizon and a scale."""

    v: np.ndarray                    # vertical vanishing point (homogeneous)
    horizon: np.ndarray              # ground-plane vanishing line
    alpha: float = 1.0
    mode: str = "self"               # 'self' (population-anchored) | 'surveyed'
    anchor_stature_m: float | None = None
    n_calibration_samples: int = 0
    residual_std_m: float = float("nan")

    def __post_init__(self):
        self.v = np.asarray(self.v, np.float64).ravel()
        self.horizon = np.asarray(self.horizon, np.float64).ravel()
        n = np.linalg.norm(self.horizon[:2])
        if n > 1e-12:
            self.horizon = self.horizon / n

    def height(self, base_px, top_px) -> float:
        b, t = _h(base_px), _h(top_px)
        num = np.linalg.norm(np.cross(b, t))
        den = self.alpha * float(self.horizon @ b) * np.linalg.norm(np.cross(self.v, t))
        if abs(den) < 1e-12:
            return float("nan")
        return float(-num / den)

    @property
    def evidential(self) -> bool:
        """Only a surveyed calibration may back a figure used as evidence."""
        return self.mode == "surveyed"

    def provenance(self) -> dict:
        return {
            "mode": self.mode,
            "anchor_stature_m": self.anchor_stature_m,
            "n_samples": self.n_calibration_samples,
            "residual_std_m": round(self.residual_std_m, 4),
            "evidential": self.evidential,
        }


# --------------------------------------------------------------------------- #
#  Estimation
# --------------------------------------------------------------------------- #


@dataclass
class SelfCalibrationConfig:
    ransac_iters: int = 800
    horizon_inlier_px: float = 12.0
    min_pairs: int = 40
    min_segment_px: float = 40.0
    assumed_median_stature_m: float = 1.70
    seed: int = 0


def estimate_vertical_vp(feet: np.ndarray, heads: np.ndarray) -> np.ndarray:
    """Null space of the stacked head-foot line vectors.

    Each observation contributes ``l_i = foot_i x head_i``; the vanishing point
    is the point on all of them. Weighting by segment length matters: a person
    30 px tall constrains the direction far more weakly than one 300 px tall,
    and unweighted least squares lets the distant crowd dominate.
    """
    L = []
    for f, h in zip(feet, heads):
        line = np.cross(_h(f), _h(h))
        n = np.linalg.norm(line[:2])
        if n < 1e-9:
            continue
        w = float(np.linalg.norm(np.asarray(h) - np.asarray(f)))
        L.append(line / n * w)
    if len(L) < 2:
        raise ValueError("not enough segments")
    _, _, Vt = np.linalg.svd(np.stack(L))
    v = Vt[-1]
    return v / v[2] if abs(v[2]) > 1e-12 else v


def estimate_horizon(feet: np.ndarray, heads: np.ndarray,
                     cfg: SelfCalibrationConfig) -> tuple[np.ndarray, np.ndarray]:
    """RANSAC over person pairs.

    Two people of *equal* height give a foot-line and a head-line meeting on the
    horizon. Real crowds have a stature spread of roughly +-9 cm, so most pairs
    produce a point slightly off the true line; RANSAC finds the consensus.
    """
    rng = np.random.default_rng(cfg.seed)
    n = len(feet)
    if n < cfg.min_pairs:
        raise ValueError(f"need >= {cfg.min_pairs} observations, got {n}")

    pts = []
    for _ in range(min(4000, n * 12)):
        i, j = rng.choice(n, 2, replace=False)
        fl = np.cross(_h(feet[i]), _h(feet[j]))
        hl = np.cross(_h(heads[i]), _h(heads[j]))
        p = np.cross(fl, hl)
        if abs(p[2]) < 1e-9:
            continue
        pts.append(p / p[2])
    if len(pts) < 10:
        raise ValueError("degenerate configuration")
    pts = np.stack(pts)

    best_line, best_inl = None, -1
    for _ in range(cfg.ransac_iters):
        a, b = rng.choice(len(pts), 2, replace=False)
        line = np.cross(pts[a], pts[b])
        nn = np.linalg.norm(line[:2])
        if nn < 1e-9:
            continue
        line = line / nn
        d = np.abs(pts @ line)
        inl = int((d < cfg.horizon_inlier_px).sum())
        if inl > best_inl:
            best_line, best_inl = line, inl
    if best_line is None:
        raise ValueError("horizon fit failed")

    # Refit on the consensus set with total least squares.
    d = np.abs(pts @ best_line)
    inliers = pts[d < cfg.horizon_inlier_px]
    if len(inliers) >= 2:
        c = inliers[:, :2].mean(0)
        _, _, Vt = np.linalg.svd(inliers[:, :2] - c)
        nvec = Vt[-1]
        best_line = np.array([nvec[0], nvec[1], -float(nvec @ c)])
        best_line = best_line / np.linalg.norm(best_line[:2])
    return best_line, inliers


def calibrate_from_pedestrians(feet, heads,
                               cfg: SelfCalibrationConfig | None = None,
                               references: list[tuple] | None = None) -> HorizonCamera:
    """Full self-calibration. Supply ``references`` to get an evidential camera.

    ``references`` is ``[(base_px, top_px, height_m), ...]``. When present the
    scale comes from surveyed objects and the population assumption is not used
    at all --- the resulting camera reports ``mode='surveyed'``.
    """
    cfg = cfg or SelfCalibrationConfig()
    feet = np.asarray(feet, np.float64)
    heads = np.asarray(heads, np.float64)

    keep = np.linalg.norm(heads - feet, axis=1) >= cfg.min_segment_px
    feet, heads = feet[keep], heads[keep]

    v = estimate_vertical_vp(feet, heads)
    horizon, _ = estimate_horizon(feet, heads, cfg)
    cam = HorizonCamera(v, horizon, alpha=1.0)

    # A line and its negation are the same line, so the sign of (l . b) --- and
    # therefore of every height --- is arbitrary out of the fit. Orient it once,
    # here, using the fact that people have positive height. Without this the
    # estimator silently discards every sample whenever the RANSAC happens to
    # settle on the opposite orientation, which is roughly half the time.
    probe = np.array([cam.height(f, h) for f, h in zip(feet, heads)])
    probe = probe[np.isfinite(probe)]
    if probe.size and np.median(probe) < 0:
        cam.horizon = -cam.horizon

    if references:
        ratios = []
        for base, top, known in references:
            raw = cam.height(base, top)
            if np.isfinite(raw) and known > 0:
                ratios.append(raw / known)
        if not ratios:
            raise ValueError("no usable references")
        cam.alpha = float(np.median(ratios))
        cam.mode = "surveyed"
        cam.anchor_stature_m = None
    else:
        raw = np.array([cam.height(f, h) for f, h in zip(feet, heads)])
        raw = raw[np.isfinite(raw) & (raw > 0)]
        # A separate, lower floor from `min_pairs`: that one guards the horizon
        # RANSAC, which needs many pairs. Anchoring a single scalar to the
        # sample median is a far easier statistical job.
        if raw.size < max(10, cfg.min_pairs // 4):
            raise ValueError(
                f"too few usable height samples ({raw.size}) to anchor the scale")
        cam.alpha = float(np.median(raw) / cfg.assumed_median_stature_m)
        cam.mode = "self"
        cam.anchor_stature_m = cfg.assumed_median_stature_m

    final = np.array([cam.height(f, h) for f, h in zip(feet, heads)])
    final = final[np.isfinite(final)]
    cam.n_calibration_samples = int(final.size)
    cam.residual_std_m = float(np.std(final))
    return cam


# --------------------------------------------------------------------------- #
#  Collecting the training pairs from tubes
# --------------------------------------------------------------------------- #


def pairs_from_tubes(tubes, image_size: tuple[int, int], class_name: str = "person",
                     border_px: float = 6.0, min_h_px: float = 40.0,
                     per_tube: int = 6, seed: int = 0):
    """Harvest clean head/foot observations from already-tracked people.

    Sampling only a few frames per tube on purpose: consecutive frames of one
    person are near-identical and would let a single long track dominate the
    fit, which is exactly how a self-calibration ends up describing one corner
    of the scene very well and the rest badly.
    """
    W, H = image_size
    rng = np.random.default_rng(seed)
    feet, heads = [], []
    for t in tubes:
        if class_name and t.class_name != class_name:
            continue
        cand = []
        for f in t.frames:
            x1, y1, x2, y2 = [float(x) for x in f.bbox]
            if (y2 - y1) < min_h_px:
                continue
            if (x1 <= border_px or y1 <= border_px
                    or x2 >= W - border_px or y2 >= H - border_px):
                continue
            aspect = (x2 - x1) / max(1e-6, y2 - y1)
            if not (0.12 <= aspect <= 0.75):
                continue
            cx = (x1 + x2) / 2
            cand.append((np.array([cx, y2]), np.array([cx, y1])))
        if not cand:
            continue
        idx = rng.choice(len(cand), size=min(per_tube, len(cand)), replace=False)
        for i in idx:
            feet.append(cand[i][0])
            heads.append(cand[i][1])
    return np.array(feet), np.array(heads)
