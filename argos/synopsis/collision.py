"""Pairwise collision cost as a *function of relative shift*.

This module is the reason ARGOS can optimise thousands of tubes instead of
dozens.

Classic video-synopsis solvers evaluate the energy by rasterising the whole
synopsis for every candidate state: cost O(pairs x frames x pixels) per
evaluation, which caps you at a few hundred objects. The key observation is
that the collision cost between two tubes ``a`` and ``b`` depends *only* on
their relative shift ``d = shift_a - shift_b``, never on their absolute
positions on the synopsis timeline. So we precompute, once per pair, the entire
curve ``C_ab(d)`` and reduce every subsequent energy evaluation to an array
lookup.

Construction of that curve:

1.  Rasterise each tube frame into a **grid-aligned low-resolution silhouette**
    (``GridMask``). Grid alignment means two masks can be intersected by pure
    array slicing --- no resampling at query time.
2.  Prune pairs whose union bounding boxes are disjoint. In real camera scenes
    this kills the large majority of the O(n^2) pairs, because objects live on
    roads, doorways and walkways rather than uniformly over the frame.
3.  Build the overlap matrix ``O[i, j]`` = shared pixels between frame ``i`` of
    ``a`` and frame ``j`` of ``b``, with a bbox-intersection pre-filter so the
    expensive AND only runs on genuinely touching pairs.
4.  Collapse ``O`` along its diagonals: ``C_ab(d) = sum{ O[i, j] : i - j = -d }``.

Because silhouettes are used rather than rectangles, two people can pass within
centimetres on screen at zero cost as long as no pixel is actually shared ---
which is exactly the density advantage over bounding-box systems.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.signal import correlate2d
from scipy.spatial import cKDTree

from ..core.types import Tube, TubeFrame

# --------------------------------------------------------------------------- #
#  Grid-aligned silhouettes
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class GridMask:
    """A silhouette snapped to a global grid of cell size ``scale``."""

    gx: int              # grid-x origin
    gy: int              # grid-y origin
    arr: np.ndarray      # bool [gh, gw]

    @property
    def gw(self) -> int:
        return self.arr.shape[1]

    @property
    def gh(self) -> int:
        return self.arr.shape[0]

    @property
    def rect(self) -> tuple[int, int, int, int]:
        return self.gx, self.gy, self.gx + self.gw, self.gy + self.gh

    def pixels(self) -> int:
        return int(self.arr.sum())


def rasterize(tf: TubeFrame, scale: int) -> GridMask | None:
    """Project one tube frame onto the shared low-resolution grid.

    Falls back to a filled rectangle when no segmentation mask is available, so
    the whole pipeline degrades gracefully to a detection-only deployment.
    """
    x1, y1, x2, y2 = tf.bbox
    gx1, gy1 = int(np.floor(x1 / scale)), int(np.floor(y1 / scale))
    gx2, gy2 = int(np.ceil(x2 / scale)), int(np.ceil(y2 / scale))
    gw, gh = max(1, gx2 - gx1), max(1, gy2 - gy1)
    if gw <= 0 or gh <= 0:
        return None

    m = tf.mask()
    if m is None:
        return GridMask(gx1, gy1, np.ones((gh, gw), bool))

    # Area-average downsample of the crop onto the grid cells it covers.
    mh, mw = m.shape
    ys = np.clip(((np.arange(gh) + 0.5) * scale + gy1 * scale - y1) / max(1e-6, y2 - y1) * mh, 0, mh - 1)
    xs = np.clip(((np.arange(gw) + 0.5) * scale + gx1 * scale - x1) / max(1e-6, x2 - x1) * mw, 0, mw - 1)
    sub = m[ys.astype(np.int32)[:, None], xs.astype(np.int32)[None, :]]
    if not sub.any():
        return GridMask(gx1, gy1, np.ones((gh, gw), bool))
    return GridMask(gx1, gy1, sub)


@dataclass(slots=True)
class TubeVolume:
    """A tube pre-rasterised for collision queries."""

    tube_id: int
    length: int                       # frames spanned (dense, relative)
    masks: list[GridMask | None]      # index = relative frame
    rects: np.ndarray                 # int32 [L, 4] grid rects, for the fast prune
    union: np.ndarray                 # int32 [4] grid union rect
    centres: np.ndarray               # float32 [L, 2] grid centres
    radii: np.ndarray                 # float32 [L] half-diagonals
    valid: np.ndarray                 # bool [L]
    stamp: np.ndarray | None = None   # representative silhouette (grid res)
    stamp_area: float = 1.0           # grid-cell area of the stamp's rectangle
    weight: float = 1.0               # importance (drives drop decisions)

    @staticmethod
    def build(tube: Tube, scale: int) -> "TubeVolume":
        dense = tube.densify()
        start = tube.start
        L = tube.length
        masks: list[GridMask | None] = [None] * L
        rects = np.zeros((L, 4), np.int32)
        for idx, tf in dense.items():
            r = idx - start
            if not (0 <= r < L):
                continue
            gm = rasterize(tf, scale)
            masks[r] = gm
            if gm is not None:
                rects[r] = gm.rect
        valid = rects.any(axis=1)
        if valid.any():
            v = rects[valid]
            union = np.array([v[:, 0].min(), v[:, 1].min(), v[:, 2].max(), v[:, 3].max()], np.int32)
        else:
            union = np.zeros(4, np.int32)
        centres = np.stack([(rects[:, 0] + rects[:, 2]) / 2.0,
                            (rects[:, 1] + rects[:, 3]) / 2.0], 1).astype(np.float32)
        radii = (0.5 * np.hypot(rects[:, 2] - rects[:, 0],
                                rects[:, 3] - rects[:, 1])).astype(np.float32)

        # Representative silhouette: the frame of median silhouette area. Using
        # the median rather than the mean keeps the stamp robust to the partial
        # silhouettes that occur while an object enters or leaves the frame.
        idxs = [i for i in range(L) if masks[i] is not None]
        stamp = None
        stamp_area = 1.0
        if idxs:
            areas = np.array([masks[i].pixels() for i in idxs], np.float32)
            pick = idxs[int(np.argsort(areas)[len(areas) // 2])]
            gm = masks[pick]
            stamp = gm.arr
            stamp_area = float(max(1, gm.gw * gm.gh))
        return TubeVolume(tube.tube_id, L, masks, rects, union, centres, radii,
                          valid, stamp, stamp_area)


# --------------------------------------------------------------------------- #
#  Pairwise cost curves
# --------------------------------------------------------------------------- #


def _rects_disjoint(a: np.ndarray, b: np.ndarray) -> bool:
    return bool(a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])


def _mask_overlap(m1: GridMask, m2: GridMask) -> int:
    x1 = max(m1.gx, m2.gx)
    y1 = max(m1.gy, m2.gy)
    x2 = min(m1.gx + m1.gw, m2.gx + m2.gw)
    y2 = min(m1.gy + m1.gh, m2.gy + m2.gh)
    if x2 <= x1 or y2 <= y1:
        return 0
    a = m1.arr[y1 - m1.gy: y2 - m1.gy, x1 - m1.gx: x2 - m1.gx]
    b = m2.arr[y1 - m2.gy: y2 - m2.gy, x1 - m2.gx: x2 - m2.gx]
    return int(np.count_nonzero(a & b))


@dataclass(slots=True)
class PairCost:
    """``cost(d)`` for ``d = shift_a - shift_b``, stored compactly."""

    a: int
    b: int
    d_min: int
    curve: np.ndarray    # float32, curve[k] = cost at d = d_min + k

    def __call__(self, d: int) -> float:
        k = d - self.d_min
        if k < 0 or k >= self.curve.shape[0]:
            return 0.0
        return float(self.curve[k])

    @property
    def peak(self) -> float:
        return float(self.curve.max()) if self.curve.size else 0.0


def pair_cost(va: TubeVolume, vb: TubeVolume) -> PairCost | None:
    """Build the full collision curve for one pair, or ``None`` if never touching."""
    if _rects_disjoint(va.union, vb.union):
        return None

    ra, rb = va.rects, vb.rects
    ia = np.flatnonzero(va.valid)
    ib = np.flatnonzero(vb.valid)
    if ia.size == 0 or ib.size == 0:
        return None

    # Spatial prune. Two silhouettes can only touch if their centres are closer
    # than the sum of their half-diagonals, so a ball query on b's trajectory
    # gives us the candidate (i, j) set directly. This replaces the O(La x Lb)
    # broadcast that dominated build time --- objects are small relative to the
    # frame, so the candidate set is near-linear in practice.
    radius = float(va.radii[ia].max() + vb.radii[ib].max())
    coo = cKDTree(va.centres[ia]).sparse_distance_matrix(
        cKDTree(vb.centres[ib]), max_distance=radius, output_type="coo_matrix")
    if coo.nnz == 0:
        return None
    ii = ia[coo.row.astype(np.int64)]
    jj = ib[coo.col.astype(np.int64)]

    # Exact rectangle intersection filter on the survivors.
    keep = ((np.minimum(ra[ii, 2], rb[jj, 2]) > np.maximum(ra[ii, 0], rb[jj, 0])) &
            (np.minimum(ra[ii, 3], rb[jj, 3]) > np.maximum(ra[ii, 1], rb[jj, 1])))
    ii, jj = ii[keep], jj[keep]
    if ii.size == 0:
        return None

    # --- silhouette overlap via a displacement lookup table ---------------- #
    #
    # A per-(i, j) mask AND is exact but costs a Python-level numpy call for
    # every candidate, and the candidate set grows with trajectory length. So
    # instead we exploit the fact that an object's silhouette is near-rigid
    # along its own tube: overlap then depends almost entirely on the *relative
    # displacement* of the two silhouettes. A single 2-D cross-correlation of
    # the two representative stamps yields F(dy, dx) for every displacement at
    # once, after which all candidates collapse to one vectorised gather.
    #
    #   F(dy, dx) = sum_{y,x} A[y, x] * B[y + dy, x + dx]
    #             = correlate2d(B, A, "full")[dy + ha - 1, dx + wa - 1]
    #
    # A per-frame area ratio corrects for perspective scaling along the tube.
    A = va.stamp
    B = vb.stamp
    if A is None or B is None:
        return None
    ha, wa = A.shape
    F = correlate2d(B.astype(np.float32), A.astype(np.float32), mode="full")

    dy = ra[ii, 1] - rb[jj, 1]
    dx = ra[ii, 0] - rb[jj, 0]
    p = dy + ha - 1
    q = dx + wa - 1
    ok = (p >= 0) & (p < F.shape[0]) & (q >= 0) & (q < F.shape[1])
    ii, jj, p, q = ii[ok], jj[ok], p[ok], q[ok]
    if ii.size == 0:
        return None
    vals = F[p, q]

    # Perspective / size correction from the actual per-frame rectangles.
    fa = ((ra[ii, 2] - ra[ii, 0]) * (ra[ii, 3] - ra[ii, 1])).astype(np.float32) / max(1.0, va.stamp_area)
    fb = ((rb[jj, 2] - rb[jj, 0]) * (rb[jj, 3] - rb[jj, 1])).astype(np.float32) / max(1.0, vb.stamp_area)
    vals = vals * np.clip(np.sqrt(np.maximum(fa, 1e-6) * np.maximum(fb, 1e-6)), 0.25, 4.0)

    nz = vals > 0
    if not nz.any():
        return None
    ii, jj, vals = ii[nz], jj[nz], vals[nz]

    # Collapse along diagonals: a collision in the synopsis requires
    # i + shift_a == j + shift_b, i.e. shift_a - shift_b == j - i.
    d = jj - ii
    d_min, d_max = int(d.min()), int(d.max())
    curve = np.bincount(d - d_min, weights=vals, minlength=d_max - d_min + 1)
    return PairCost(va.tube_id, vb.tube_id, d_min, curve.astype(np.float32))


class CollisionModel:
    """All pair curves plus an adjacency list for incremental energy updates."""

    def __init__(self, tubes: list[Tube], scale: int = 8):
        self.scale = scale
        self.tubes = {t.tube_id: t for t in tubes}
        self.volumes: dict[int, TubeVolume] = {
            t.tube_id: TubeVolume.build(t, scale) for t in tubes
        }
        self.pairs: list[PairCost] = []
        self.adj: dict[int, list[int]] = {t.tube_id: [] for t in tubes}
        self._by_pair: dict[tuple[int, int], PairCost] = {}
        self.n_candidate_pairs = 0
        # Total silhouette mass: the denominator that turns a raw collision
        # score into a resolution-independent "fraction of object pixels that
        # are overlapped", which is what a viewer actually perceives as clutter.
        self.total_mass = float(sum(
            m.pixels() for v in self.volumes.values() for m in v.masks if m is not None
        )) or 1.0
        self._build()

    def _build(self) -> None:
        ids = sorted(self.volumes)
        for x in range(len(ids)):
            for y in range(x + 1, len(ids)):
                ia, ib = ids[x], ids[y]
                self.n_candidate_pairs += 1
                pc = pair_cost(self.volumes[ia], self.volumes[ib])
                if pc is None:
                    continue
                k = len(self.pairs)
                self.pairs.append(pc)
                self.adj[ia].append(k)
                self.adj[ib].append(k)
                self._by_pair[(ia, ib)] = pc

    @property
    def density(self) -> float:
        """Fraction of O(n^2) pairs that survived pruning."""
        return len(self.pairs) / max(1, self.n_candidate_pairs)

    def cost(self, k: int, shifts: dict[int, int]) -> float:
        pc = self.pairs[k]
        return pc(shifts[pc.a] - shifts[pc.b])

    def total(self, shifts: dict[int, int], active: set[int] | None = None) -> float:
        tot = 0.0
        for k, pc in enumerate(self.pairs):
            if active is not None and (pc.a not in active or pc.b not in active):
                continue
            tot += pc(shifts[pc.a] - shifts[pc.b])
        return tot

    def local(self, tube_id: int, shifts: dict[int, int], active: set[int] | None = None) -> float:
        """Sum of pair costs touching ``tube_id`` --- the incremental SA term."""
        tot = 0.0
        for k in self.adj[tube_id]:
            pc = self.pairs[k]
            other = pc.b if pc.a == tube_id else pc.a
            if active is not None and other not in active:
                continue
            tot += pc(shifts[pc.a] - shifts[pc.b])
        return tot
