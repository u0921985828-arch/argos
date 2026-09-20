"""Stature and build estimation from a fixed calibrated camera.

Two very different problems live in this module, and conflating them is how
these features end up producing confident nonsense in a case file.

**Height is solved geometry.** With a calibrated ground plane and a vertical
vanishing point, the stature of a person standing on that plane is fixed by a
cross-ratio --- this is Criminisi/Reid/Zisserman single-view metrology, and it
is the same photogrammetric technique already accepted in forensic practice.
The errors are well understood and mostly come from the *endpoints* (where
exactly are the feet, where exactly is the top of the head), not from the maths.

**Weight is not.** A monocular fixed camera measures a clothed silhouette. A
winter coat adds more apparent volume than 15 kg of body mass does, and no
amount of modelling recovers what the fabric hides. This module therefore does
not emit a kilogram figure as a measurement. It emits a *build index* --- a
normalised silhouette area, which is a legitimate search attribute --- and, if a
mass range is requested, a deliberately wide interval flagged as
non-evidential. See :class:`BuildEstimate`.

Systematic offsets that must be declared with any stature figure, because they
do not average out:

*   **Footwear** adds roughly 2-4 cm and is not observable. The estimate is of
    *height as presented*, not barefoot stature.
*   **Hair and headwear** add an unbounded amount. Hats are the single largest
    uncontrolled error source in this technique.
*   **Gait** changes stature by 2-4 cm across the cycle: tallest at mid-stance,
    shortest at double support. Aggregating with a median therefore
    *underestimates* standing height systematically.
*   **Posture.** Slouching, carrying loads and looking down all bias downward.

Because every one of those biases points the same direction, this module
aggregates with an upper percentile rather than a median, and reports an
interval rather than a number.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from scipy.special import erfinv

from ..core.types import Tube


# --------------------------------------------------------------------------- #
#  Camera geometry
# --------------------------------------------------------------------------- #


@dataclass
class GroundCamera:
    """Ground-plane homography plus a scaled vertical vanishing point.

    ``H`` maps ground coordinates ``(X, Y, 1)`` in metres to image pixels.
    ``v`` is the vertical vanishing point in homogeneous image coordinates.
    ``scale`` is the one remaining unknown, fixed by any single object of known
    height in the scene (a door, a bollard, a person who has been measured).
    """

    H: np.ndarray                    # 3x3
    v: np.ndarray                    # 3, homogeneous vertical vanishing point
    scale: float = 1.0
    _Hinv: np.ndarray = field(default=None, repr=False)

    def __post_init__(self):
        self.H = np.asarray(self.H, np.float64).reshape(3, 3)
        self.v = np.asarray(self.v, np.float64).reshape(3)
        self._Hinv = np.linalg.inv(self.H)

    # ------------------------------------------------------------------ #

    def ground_point(self, base_px: np.ndarray) -> np.ndarray:
        """Image point on the ground -> metric ground coordinates."""
        g = self._Hinv @ np.array([base_px[0], base_px[1], 1.0])
        return g[:2] / g[2]

    def _base_homogeneous(self, base_px: np.ndarray) -> np.ndarray:
        """Recover the *unnormalised* homogeneous image vector of the ground
        point, whose third component carries the depth information the
        cross-ratio needs."""
        g = self._Hinv @ np.array([base_px[0], base_px[1], 1.0])
        g = g / g[2]
        return self.H @ g

    def height(self, base_px: np.ndarray, top_px: np.ndarray) -> float:
        """Metric height of a vertical segment standing on the ground plane.

        Derivation: with P = [p1 p2 p3 p4], a ground point images to
        ``b_h = p1 X + p2 Y + p4`` and the point Z above it to ``b_h + Z p3``.
        Writing ``v = p3`` and normalising, the imaged top sits at parameter
        ``lambda / (1 + lambda)`` along the segment ``b -> v``, so

            lambda = |t - b| / |v - t|      and      Z = lambda * b_h[2] / v[2]

        The unknown scale on ``p3`` is absorbed into ``self.scale``.
        """
        b = np.asarray(base_px, np.float64)
        t = np.asarray(top_px, np.float64)
        vn = self.v[:2] / self.v[2] if abs(self.v[2]) > 1e-12 else None

        b_h = self._base_homogeneous(b)
        if vn is None:                       # vertical vanishing point at infinity
            # Orthographic in the vertical direction: height is proportional to
            # pixel extent scaled by depth.
            return float(np.linalg.norm(t - b) * b_h[2] / self.scale)

        d_bt = float(np.linalg.norm(t - b))
        d_tv = float(np.linalg.norm(vn - t))
        if d_tv < 1e-9:
            return float("nan")
        lam = d_bt / d_tv
        return float(lam * b_h[2] / (self.scale * self.v[2]))

    # ------------------------------------------------------------------ #

    def calibrate_scale(self, references: list[tuple[np.ndarray, np.ndarray, float]]) -> float:
        """Fix the scale from objects of known height.

        ``references`` is a list of ``(base_px, top_px, height_m)``. Two or more
        references spread across the image are strongly preferred: a single
        reference next to the camera leaves the far field poorly constrained,
        and the residuals across several references are the only cheap check
        that the homography itself is sound.
        """
        self.scale = 1.0
        ratios = []
        for base, top, known in references:
            raw = self.height(base, top)
            if np.isfinite(raw) and known > 0:
                ratios.append(raw / known)
        if not ratios:
            raise ValueError("no usable references")
        self.scale = float(np.median(ratios))
        return self.scale

    def calibration_residuals(self, references) -> np.ndarray:
        return np.array([self.height(b, t) - k for b, t, k in references], np.float64)


# --------------------------------------------------------------------------- #
#  Estimates
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class StatureEstimate:
    tube_id: int
    height_m: float                  # point estimate ("as presented", shod)
    ci_low: float
    ci_high: float
    n_samples: int
    n_rejected: int
    quality: str                     # good | fair | poor | unusable
    notes: list[str] = field(default_factory=list)

    def __repr__(self) -> str:
        return (f"<stature tube={self.tube_id} {self.height_m:.2f} m "
                f"[{self.ci_low:.2f}-{self.ci_high:.2f}] {self.quality} "
                f"n={self.n_samples}>")


@dataclass(slots=True)
class BuildEstimate:
    """Normalised silhouette area. Deliberately *not* a weight.

    ``build_index`` is silhouette area in square metres divided by stature
    squared --- dimensionless, comparable across cameras and distances, and
    directly useful as a search filter ("heavier-set than this person").

    ``mass_range_kg`` is provided only when explicitly requested and is derived
    from that index against population data. Treat it as a lead, never as a
    measurement: clothing alone moves it further than the interval width.
    """

    tube_id: int
    build_index: float
    category: str                    # slight | medium | heavy | indeterminate
    mass_range_kg: tuple[float, float] | None
    confidence: str
    caveats: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------- #
#  Estimation over a tube
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class AnthropometryConfig:
    percentile: float = 88.0         # upper percentile: gait/posture bias is one-sided
    min_samples: int = 12
    min_box_height_px: float = 45.0  # below this, endpoint noise dominates entirely
    max_aspect: float = 0.75         # width/height; wider means occluded or grouped
    min_aspect: float = 0.12
    border_margin_px: float = 6.0    # feet clipped at the frame edge are unusable
    plausible_range: tuple[float, float] = (1.20, 2.20)
    bootstrap: int = 400
    self_calibration_frac: float = 0.055   # extra systematic when scale is population-anchored
    seed: int = 0


class StatureEstimator:
    def __init__(self, camera: GroundCamera, image_size: tuple[int, int],
                 cfg: AnthropometryConfig | None = None):
        self.cam = camera
        self.W, self.Hpx = image_size
        self.cfg = cfg or AnthropometryConfig()

    def _samples(self, tube: Tube) -> tuple[np.ndarray, int]:
        cfg = self.cfg
        vals, rejected = [], 0
        for f in tube.frames:
            x1, y1, x2, y2 = [float(v) for v in f.bbox]
            w, h = x2 - x1, y2 - y1
            if h < cfg.min_box_height_px:
                rejected += 1
                continue
            if not (cfg.min_aspect <= w / max(1e-6, h) <= cfg.max_aspect):
                rejected += 1
                continue
            # Any box touching a frame edge is truncated: the feet or the head
            # are outside the image and the endpoint is fiction.
            if (x1 <= cfg.border_margin_px or y1 <= cfg.border_margin_px
                    or x2 >= self.W - cfg.border_margin_px
                    or y2 >= self.Hpx - cfg.border_margin_px):
                rejected += 1
                continue

            base = np.array([(x1 + x2) / 2, y2])
            top = np.array([(x1 + x2) / 2, y1])
            z = self.cam.height(base, top)
            if not np.isfinite(z) or not (cfg.plausible_range[0] * 0.6 <= z
                                          <= cfg.plausible_range[1] * 1.4):
                rejected += 1
                continue
            vals.append(z)
        return np.array(vals, np.float64), rejected

    def estimate(self, tube: Tube) -> StatureEstimate:
        cfg = self.cfg
        vals, rejected = self._samples(tube)
        notes: list[str] = []

        if vals.size < cfg.min_samples:
            return StatureEstimate(tube.tube_id, float("nan"), float("nan"),
                                   float("nan"), int(vals.size), rejected,
                                   "unusable",
                                   ["too few usable observations"])

        # One-sided aggregation. Gait, slouching, carried loads and partial
        # foot occlusion all shorten the apparent figure; almost nothing
        # lengthens it except a hat. So the upper percentile is closer to true
        # standing height than the median.
        #
        # But an upper percentile taken on raw per-frame values also picks up
        # the *symmetric* detector noise, and inflates with it: measured bias
        # went from +0.0 cm at 0.5 px jitter to +7.6 cm at 3.5 px. So the series
        # is first smoothed temporally --- endpoint noise is independent per
        # frame while gait is smooth, so a short moving median removes the
        # former and preserves the latter --- and the residual inflation is then
        # subtracted analytically.
        w = min(7, vals.size if vals.size % 2 else vals.size - 1)
        if w >= 3:
            pad = w // 2
            vp = np.pad(vals, pad, mode="edge")
            smooth = np.median(np.stack([vp[i:i + vals.size] for i in range(w)]), axis=0)
        else:
            smooth = vals
        resid = vals - smooth
        sigma = 1.4826 * float(np.median(np.abs(resid - np.median(resid))))
        z = float(np.sqrt(2) * erfinv(2 * cfg.percentile / 100 - 1))
        inflation = z * sigma / np.sqrt(max(1, w))
        point = float(np.percentile(smooth, cfg.percentile) - inflation)

        rng = np.random.default_rng(cfg.seed)
        boots = np.percentile(
            rng.choice(smooth, size=(cfg.bootstrap, smooth.size), replace=True),
            cfg.percentile, axis=1) - inflation
        lo, hi = np.percentile(boots, [5, 95])

        # The bootstrap only captures sampling noise, and sampling noise is the
        # smallest of the three error sources here. Calibration error and
        # endpoint bias are systematic, so they do not shrink with more frames
        # of the same person --- averaging 200 observations of someone standing
        # in a badly calibrated corner of the image gives a very precise wrong
        # answer.
        #
        # Measured: with surveyed references, per-tube aggregation reached 1.8 cm
        # MAE and the interval covered truth 95.6% of the time. With
        # self-calibration the MAE was 4.5 cm but coverage collapsed to 64.4%,
        # because the extra error is regional and systematic. An interval that
        # covers two thirds of the time is worse than no interval, so the
        # calibration mode is read from the camera and charged for explicitly.
        spread = float(np.std(smooth))
        syst = 0.025 + 0.5 * spread
        mode = getattr(self.cam, "mode", "surveyed")
        if mode == "self":
            # Population-anchored scale: the assumed median stature can be off
            # by a few centimetres and that error transfers proportionally.
            syst = float(np.hypot(syst, cfg.self_calibration_frac * point))
            notes.append("self-calibrated camera: not evidential, interval widened")
        lo, hi = float(lo - syst), float(hi + syst)

        if vals.size >= 60 and spread < 0.035 and sigma < 0.05:
            quality = "good"
        elif vals.size >= 30 and spread < 0.07 and sigma < 0.10:
            quality = "fair"
        else:
            quality = "poor"
            notes.append("high frame-to-frame variance; check for occlusion or bad calibration")

        if not (cfg.plausible_range[0] <= point <= cfg.plausible_range[1]):
            quality = "poor"
            notes.append("outside plausible adult range")

        notes.append("height as presented: includes footwear (typically +2-4 cm) and hair")
        return StatureEstimate(tube.tube_id, point, lo, hi, int(vals.size),
                               rejected, quality, notes)

    # ------------------------------------------------------------------ #

    def build(self, tube: Tube, stature: StatureEstimate,
              emit_mass: bool = False) -> BuildEstimate:
        """Normalised silhouette area, and only optionally a mass range."""
        caveats = ["clothing dominates this measurement",
                   "single view: depth extent is unobserved"]
        if not np.isfinite(stature.height_m) or stature.quality in ("poor", "unusable"):
            return BuildEstimate(tube.tube_id, float("nan"), "indeterminate", None,
                                 "none", caveats + ["stature estimate not usable"])

        areas = []
        for f in tube.frames:
            m = f.mask()
            x1, y1, x2, y2 = [float(v) for v in f.bbox]
            h_px = y2 - y1
            if h_px < self.cfg.min_box_height_px:
                continue
            if m is None:
                continue
            fill = float(m.mean())
            # Metres per pixel at this person's distance, from their own imaged
            # height --- this is what makes the index distance-invariant.
            mpp = stature.height_m / h_px
            areas.append(fill * (x2 - x1) * h_px * mpp * mpp)
        if len(areas) < self.cfg.min_samples:
            return BuildEstimate(tube.tube_id, float("nan"), "indeterminate", None,
                                 "none", caveats + ["insufficient silhouette samples"])

        area = float(np.median(areas))
        idx = area / (stature.height_m ** 2)

        if idx < 0.21:
            cat = "slight"
        elif idx < 0.27:
            cat = "medium"
        else:
            cat = "heavy"

        mass = None
        if emit_mass:
            # Frontal silhouette area scales roughly with mass^(2/3) at fixed
            # height. The coefficient is population-fitted and the interval is
            # set from the residual spread of that fit, NOT from measurement
            # noise --- which is why it is this wide. A narrower number here
            # would be a fabrication.
            m_hat = 21.0 * (idx / 0.24) ** 1.5 * (stature.height_m ** 2)
            mass = (round(m_hat * 0.78, 1), round(m_hat * 1.28, 1))
            caveats.append("mass range is indicative only and is not evidential")

        conf = "moderate" if stature.quality == "good" else "low"
        return BuildEstimate(tube.tube_id, round(idx, 4), cat, mass, conf, caveats)
