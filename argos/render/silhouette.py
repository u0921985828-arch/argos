"""Silhouette extraction as clean vector art.

The segmentation masks the pipeline already produces are, visually, terrible:
jagged, aliased, full of single-pixel spurs from the network's stride. Dropping
them straight onto a canvas looks like a screenshot of a debugger. This module
turns them into something you would actually put on a wall or in a report.

The path from mask to artwork:

1.  **Contour extraction** --- outermost boundary only. Interior holes are
    discarded on purpose: a hole between an arm and a torso reads as a printing
    defect at small sizes, and carries no information a viewer uses.
2.  **Morphological cleanup** before tracing, to remove the stride artefacts.
    Closing then opening, with a kernel sized relative to the object rather than
    fixed, because a 40 px pedestrian and a 400 px lorry need different amounts.
3.  **Resample to constant arc length.** This is the step that most
    implementations skip and it is why their curves look lumpy: contour points
    from a raster are unevenly spaced, so any smoothing applied to them weights
    dense regions more heavily and flattens exactly the corners you want to keep.
4.  **Chaikin corner-cutting**, then **Catmull-Rom to cubic Bezier**. Chaikin
    kills the staircase; Catmull-Rom gives a path that is smooth at every joint,
    so the outline holds up when scaled to poster size.

### Why this is more than decoration

A silhouette is **anonymous by construction**. It carries posture, gait, class,
size and position --- everything an operator needs to understand what is
happening in a scene --- and none of the facial or clothing detail that makes an
image personal data in the first place.

That makes it a serious privacy control, not a visual style: a control room can
run on the silhouette layer by default, and revealing the underlying pixels
becomes a separate, logged, authorised action. Most of the time nobody needs the
pixels. Under data-minimisation (Art. 5(1)(c) GDPR) that is not merely allowed,
it is the argument you want to be able to make.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

try:
    import cv2
except ImportError:                                  # pragma: no cover
    cv2 = None

from ..core.types import Tube


# --------------------------------------------------------------------------- #
#  Geometry
# --------------------------------------------------------------------------- #


def clean_mask(mask: np.ndarray, strength: float = 0.035) -> np.ndarray:
    """Morphological cleanup with a kernel scaled to the object."""
    m = mask.astype(np.uint8)
    k = max(2, int(round(strength * math.sqrt(m.shape[0] * m.shape[1]))))
    if k % 2 == 0:
        k += 1
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, kern)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, kern)
    return m


def outer_contour(mask: np.ndarray, min_area_frac: float = 0.04) -> np.ndarray | None:
    """Largest external contour, in mask pixel coordinates."""
    m = clean_mask(mask)
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    if cv2.contourArea(c) < min_area_frac * m.shape[0] * m.shape[1]:
        return None
    return c.reshape(-1, 2).astype(np.float64)


def resample_closed(pts: np.ndarray, n: int) -> np.ndarray:
    """Uniform arc-length resampling of a closed polyline.

    Raster contours are unevenly spaced --- diagonal runs pack points more
    tightly than axis-aligned ones. Smoothing such a sequence directly weights
    the dense stretches and rounds off the corners that carry the shape.
    """
    p = np.vstack([pts, pts[:1]])
    seg = np.linalg.norm(np.diff(p, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    total = s[-1]
    if total <= 1e-9:
        return pts
    targets = np.linspace(0, total, n, endpoint=False)
    x = np.interp(targets, s, p[:, 0])
    y = np.interp(targets, s, p[:, 1])
    return np.stack([x, y], 1)


def chaikin(pts: np.ndarray, iterations: int = 2, ratio: float = 0.25) -> np.ndarray:
    """Corner cutting on a closed polygon."""
    p = pts
    for _ in range(iterations):
        a = p
        b = np.roll(p, -1, axis=0)
        q = a + ratio * (b - a)
        r = a + (1 - ratio) * (b - a)
        p = np.empty((len(p) * 2, 2), np.float64)
        p[0::2] = q
        p[1::2] = r
    return p


def catmull_rom_path(pts: np.ndarray, tension: float = 1.0, decimals: int = 2) -> str:
    """Closed Catmull-Rom spline as an SVG cubic path."""
    n = len(pts)
    if n < 3:
        return ""
    d = [f"M {pts[0,0]:.{decimals}f} {pts[0,1]:.{decimals}f}"]
    for i in range(n):
        p0 = pts[(i - 1) % n]
        p1 = pts[i]
        p2 = pts[(i + 1) % n]
        p3 = pts[(i + 2) % n]
        c1 = p1 + (p2 - p0) / 6.0 * tension
        c2 = p2 - (p3 - p1) / 6.0 * tension
        d.append(f"C {c1[0]:.{decimals}f} {c1[1]:.{decimals}f} "
                 f"{c2[0]:.{decimals}f} {c2[1]:.{decimals}f} "
                 f"{p2[0]:.{decimals}f} {p2[1]:.{decimals}f}")
    d.append("Z")
    return " ".join(d)


# --------------------------------------------------------------------------- #
#  Style
# --------------------------------------------------------------------------- #


@dataclass
class SilhouetteStyle:
    """Deliberate defaults.

    ``stroke_px`` is specified at a reference object height and scaled from
    there: a constant stroke makes distant objects into blobs and near ones into
    hairlines. Weight should read as constant *relative to the figure*, which is
    what the eye actually judges.
    """

    palette: dict = field(default_factory=lambda: {
        "person": "#E8552D",
        "car": "#2E6F9E",
        "truck": "#1F4E6B",
        "bus": "#1F4E6B",
        "bicycle": "#C99A2E",
        "motorcycle": "#C99A2E",
        "dog": "#6B8F4E",
        "cat": "#6B8F4E",
        "_default": "#6E6E6E",
    })
    background: str = "#F4F1EA"
    fill_opacity: float = 0.90
    stroke: str = "#1A1A1A"
    stroke_px: float = 1.6
    reference_height_px: float = 120.0
    outline_only: bool = False
    resample_points: int = 128     # working resolution for smoothing
    bezier_points: int = 44        # final control points; each becomes one cubic
    chaikin_iters: int = 2
    tension: float = 1.0

    def colour(self, class_name: str) -> str:
        return self.palette.get(class_name, self.palette["_default"])

    def stroke_for(self, obj_height_px: float) -> float:
        return round(self.stroke_px * max(0.45, min(2.4, obj_height_px / self.reference_height_px)), 2)


# --------------------------------------------------------------------------- #
#  Rendering
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Figure:
    """One traced silhouette placed in image coordinates."""

    path: str
    class_name: str
    base_y: float
    height_px: float
    opacity: float = 1.0
    label: str | None = None
    points: np.ndarray | None = None


def trace(tube: Tube, frame_idx: int, style: SilhouetteStyle) -> Figure | None:
    """Trace one observation into a placed, smoothed vector figure."""
    tf = next((f for f in tube.frames if f.frame_idx == frame_idx), None)
    if tf is None:
        return None
    m = tf.mask()
    if m is None:
        return None
    c = outer_contour(m)
    if c is None or len(c) < 8:
        return None

    x1, y1, x2, y2 = [float(v) for v in tf.bbox]
    w, h = max(1e-6, x2 - x1), max(1e-6, y2 - y1)
    # Contour lives in mask coordinates; map it onto the object's box in image
    # space so silhouettes land exactly where the object was.
    c[:, 0] = x1 + c[:, 0] / m.shape[1] * w
    c[:, 1] = y1 + c[:, 1] / m.shape[0] * h

    # Order matters. Smoothing must run at a high point count to suppress the
    # raster staircase, but each surviving point becomes a cubic segment in the
    # output, so the curve is decimated again afterwards. Emitting the smoothing
    # resolution directly produced 17 kB of path data per figure -- a 110-figure
    # plate came to 1.9 MB, for a picture the eye cannot tell from a 44-point
    # version.
    c = resample_closed(c, style.resample_points)
    c = chaikin(c, style.chaikin_iters)
    c = resample_closed(c, style.bezier_points)
    return Figure(catmull_rom_path(c, style.tension), tube.class_name, y2, h, points=c)


def _svg_open(w: int, h: int, style: SilhouetteStyle, title: str) -> list[str]:
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">',
        f'<title>{title}</title>',
        f'<rect width="{w}" height="{h}" fill="{style.background}"/>',
    ]


def _emit(fig: Figure, style: SilhouetteStyle) -> str:
    col = style.colour(fig.class_name)
    sw = style.stroke_for(fig.height_px)
    fill = "none" if style.outline_only else col
    op = style.fill_opacity * fig.opacity
    return (f'<path d="{fig.path}" fill="{fill}" fill-opacity="{op:.3f}" '
            f'stroke="{style.stroke}" stroke-opacity="{fig.opacity:.3f}" '
            f'stroke-width="{sw}" stroke-linejoin="round"/>')


def render_figures(figures: list[Figure], size: tuple[int, int],
                   style: SilhouetteStyle | None = None,
                   title: str = "ARGOS silhouettes") -> str:
    style = style or SilhouetteStyle()
    W, H = size
    out = _svg_open(W, H, style, title)
    # Back to front by ground contact: a figure standing lower in the frame is
    # nearer the camera and must occlude, not be occluded.
    for f in sorted(figures, key=lambda f: f.base_y):
        out.append(_emit(f, style))
    out.append("</svg>")
    return "\n".join(out)


def rasterize_figures(figures: list[Figure], size: tuple[int, int],
                      style: SilhouetteStyle | None = None) -> np.ndarray:
    """PNG preview of the same figures, for people without an SVG viewer."""
    style = style or SilhouetteStyle()
    W, H = size

    def bgr(c: str):
        c = c.lstrip("#")
        return tuple(int(c[i:i + 2], 16) for i in (4, 2, 0))

    img = np.full((H, W, 3), bgr(style.background), np.uint8)
    for f in sorted(figures, key=lambda f: f.base_y):
        if f.points is None:
            continue
        pts = f.points.astype(np.int32)
        ov = img.copy()
        if not style.outline_only:
            cv2.fillPoly(ov, [pts], bgr(style.colour(f.class_name)))
        cv2.polylines(ov, [pts], True, bgr(style.stroke),
                      max(1, int(round(style.stroke_for(f.height_px)))), cv2.LINE_AA)
        a = float(np.clip(style.fill_opacity * f.opacity, 0, 1))
        img = cv2.addWeighted(ov, a, img, 1 - a, 0)
    return img


def figures_for_plate(tubes: list[Tube], style: SilhouetteStyle,
                      per_tube: int = 1, max_figures: int = 400) -> list[Figure]:
    figs: list[Figure] = []
    for t in tubes:
        idxs = [f.frame_idx for f in t.frames]
        if not idxs:
            continue
        for i in np.unique(np.linspace(0, len(idxs) - 1, per_tube).astype(int)):
            fig = trace(t, idxs[int(i)], style)
            if fig is not None:
                figs.append(fig)
        if len(figs) >= max_figures:
            break
    return figs


def figures_for_strip(tube: Tube, style: SilhouetteStyle, n: int = 9,
                      fade: tuple[float, float] = (0.25, 1.0)) -> list[Figure]:
    idxs = [f.frame_idx for f in tube.frames]
    if len(idxs) < 2:
        return []
    picks = np.unique(np.linspace(0, len(idxs) - 1, n).astype(int))
    figs = []
    for k, i in enumerate(picks):
        fig = trace(tube, idxs[int(i)], style)
        if fig is None:
            continue
        fig.opacity = fade[0] + (fade[1] - fade[0]) * (k / max(1, len(picks) - 1))
        figs.append(fig)
    return figs


def trajectory_strip(tube: Tube, size: tuple[int, int], n: int = 9,
                     style: SilhouetteStyle | None = None,
                     fade: tuple[float, float] = (0.25, 1.0)) -> str:
    """One object's whole passage as a stroboscopic sequence.

    This is the single most useful forensic image the system can produce: an
    entire trajectory --- direction, speed (from the spacing), posture changes,
    where the object paused --- readable in one glance, with no video playback
    and no identifiable imagery.
    """
    style = style or SilhouetteStyle()
    # Older positions recede: the eye reads the opacity ramp as time direction
    # without needing an arrow or a legend.
    figs = figures_for_strip(tube, style, n, fade)
    return render_figures(figs, size, style,
                          title=f"tube {tube.tube_id} ({tube.class_name})")


def activity_plate(tubes: list[Tube], size: tuple[int, int],
                   per_tube: int = 1, style: SilhouetteStyle | None = None,
                   max_figures: int = 400) -> str:
    """Every object that passed, as one anonymous composite.

    An hour of a street on a single sheet. Useful as an operational overview and
    as the default control-room layer, since it contains no personal imagery.
    """
    style = style or SilhouetteStyle()
    figs = figures_for_plate(tubes, style, per_tube, max_figures)
    return render_figures(figs, size, style, title=f"activity plate ({len(figs)} figures)")
