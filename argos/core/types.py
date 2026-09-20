"""ARGOS core data types.

The central abstraction is the **Tube**: a single moving object's entire
spatio-temporal footprint through a video. Everything downstream --- the
metadata index, the rule engine, the synopsis optimiser --- operates on tubes,
never on raw frames. This is what makes 8 hours of 4K collapse into a few
megabytes of searchable structure.
"""

from __future__ import annotations

import zlib
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Iterator, Sequence

import numpy as np

# --------------------------------------------------------------------------- #
#  Detections
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Detection:
    """A single-frame observation emitted by a detector backend."""

    frame_idx: int
    bbox: np.ndarray          # float32 [x1, y1, x2, y2] in pixels
    score: float
    class_id: int
    mask: np.ndarray | None = None      # bool HxW crop, aligned to bbox
    embedding: np.ndarray | None = None  # float32 appearance vector

    @property
    def cxcywh(self) -> np.ndarray:
        x1, y1, x2, y2 = self.bbox
        return np.array([(x1 + x2) / 2, (y1 + y2) / 2, x2 - x1, y2 - y1], np.float32)

    @property
    def area(self) -> float:
        return float(max(0.0, self.bbox[2] - self.bbox[0]) * max(0.0, self.bbox[3] - self.bbox[1]))


# --------------------------------------------------------------------------- #
#  Tubes
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class TubeFrame:
    """One time-slice of a tube.

    ``mask_blob`` is a zlib-compressed packed bitfield of the object's silhouette
    at the resolution of ``bbox``. Storing silhouettes rather than rectangles is
    what allows the optimiser to pack objects far more densely than a
    bbox-collision system: two pedestrians can share overlapping rectangles with
    zero pixel overlap.
    """

    frame_idx: int
    bbox: np.ndarray                 # float32 [x1, y1, x2, y2]
    score: float
    mask_blob: bytes | None = None
    mask_shape: tuple[int, int] | None = None

    def mask(self) -> np.ndarray | None:
        if self.mask_blob is None or self.mask_shape is None:
            return None
        h, w = self.mask_shape
        raw = zlib.decompress(self.mask_blob)
        bits = np.unpackbits(np.frombuffer(raw, np.uint8), count=h * w)
        return bits.reshape(h, w).astype(bool)

    @staticmethod
    def pack_mask(mask: np.ndarray) -> tuple[bytes, tuple[int, int]]:
        m = np.ascontiguousarray(mask.astype(bool))
        packed = np.packbits(m.ravel())
        return zlib.compress(packed.tobytes(), 6), (m.shape[0], m.shape[1])


@dataclass(slots=True)
class Tube:
    """A tracked object's full trajectory: the atom of ARGOS."""

    tube_id: int
    class_id: int
    class_name: str
    frames: list[TubeFrame] = field(default_factory=list)

    # --- derived / enrichment -------------------------------------------- #
    embedding: np.ndarray | None = None       # mean appearance vector (re-ID)
    clip_embedding: np.ndarray | None = None  # semantic vector for text search
    attributes: dict = field(default_factory=dict)  # colour, speed, direction...

    # --- source binding --------------------------------------------------- #
    camera_id: str = ""
    t0_wall: datetime | None = None  # wall-clock time of frames[0]
    fps: float = 25.0

    # ------------------------------------------------------------------ #
    @property
    def start(self) -> int:
        return self.frames[0].frame_idx

    @property
    def end(self) -> int:
        return self.frames[-1].frame_idx

    @property
    def length(self) -> int:
        """Duration in frames (inclusive)."""
        return self.end - self.start + 1

    @property
    def n_obs(self) -> int:
        return len(self.frames)

    def wall_time(self, frame_idx: int) -> datetime | None:
        if self.t0_wall is None:
            return None
        return self.t0_wall + timedelta(seconds=(frame_idx - self.start) / self.fps)

    def bboxes(self) -> np.ndarray:
        return np.stack([f.bbox for f in self.frames]).astype(np.float32)

    def union_bbox(self) -> np.ndarray:
        b = self.bboxes()
        return np.array([b[:, 0].min(), b[:, 1].min(), b[:, 2].max(), b[:, 3].max()], np.float32)

    def centroids(self) -> np.ndarray:
        b = self.bboxes()
        return np.stack([(b[:, 0] + b[:, 2]) / 2, (b[:, 1] + b[:, 3]) / 2], 1)

    def path_length(self) -> float:
        c = self.centroids()
        if len(c) < 2:
            return 0.0
        return float(np.linalg.norm(np.diff(c, axis=0), axis=1).sum())

    def mean_speed_px(self) -> float:
        return self.path_length() / max(1, self.length) * self.fps

    def densify(self) -> dict[int, TubeFrame]:
        """Index frames by absolute frame number, linearly filling short gaps."""
        by_idx = {f.frame_idx: f for f in self.frames}
        out: dict[int, TubeFrame] = {}
        idxs = sorted(by_idx)
        for a, b in zip(idxs, idxs[1:]):
            out[a] = by_idx[a]
            gap = b - a
            if 1 < gap <= 8:  # interpolate only across short occlusions
                fa, fb = by_idx[a], by_idx[b]
                for k in range(1, gap):
                    w = k / gap
                    out[a + k] = TubeFrame(
                        frame_idx=a + k,
                        bbox=(1 - w) * fa.bbox + w * fb.bbox,
                        score=min(fa.score, fb.score),
                        mask_blob=fa.mask_blob if w < 0.5 else fb.mask_blob,
                        mask_shape=fa.mask_shape if w < 0.5 else fb.mask_shape,
                    )
        out[idxs[-1]] = by_idx[idxs[-1]]
        return out

    def __iter__(self) -> Iterator[TubeFrame]:
        return iter(self.frames)

    def __repr__(self) -> str:
        return (
            f"Tube(id={self.tube_id}, {self.class_name}, "
            f"f[{self.start}:{self.end}] n={self.n_obs})"
        )


# --------------------------------------------------------------------------- #
#  Synopsis result
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Placement:
    """Where a tube ends up on the synopsis timeline."""

    tube_id: int
    shift: int          # synopsis_frame = original_frame - tube.start + shift
    dropped: bool = False

    def map_frame(self, tube: Tube, frame_idx: int) -> int:
        return frame_idx - tube.start + self.shift


@dataclass(slots=True)
class SynopsisPlan:
    placements: dict[int, Placement]
    duration: int                  # frames in the synopsis
    source_duration: int           # frames in the source
    energy: float = 0.0
    collision: float = 0.0
    chronology: float = 0.0
    dropped: int = 0

    @property
    def compression(self) -> float:
        return self.source_duration / max(1, self.duration)

    def summary(self) -> str:
        return (
            f"{self.source_duration} -> {self.duration} frames "
            f"({self.compression:.1f}x), E={self.energy:.1f} "
            f"[collision={self.collision:.1f} chrono={self.chronology:.1f}] "
            f"dropped={self.dropped}"
        )


def tubes_by_id(tubes: Sequence[Tube]) -> dict[int, Tube]:
    return {t.tube_id: t for t in tubes}
