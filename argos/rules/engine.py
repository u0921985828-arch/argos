"""Rule evaluation over tubes.

Rules run on completed trajectories rather than on individual frames. This is
not a performance detail --- it changes what the rules can express and how often
they are wrong:

*   A frame-level "person in zone" trigger fires on a single noisy detection.
    A tube-level one can require sustained presence, a minimum path length and a
    plausible entry edge, which removes most of the false alarms that make
    operators disable analytics entirely.
*   Direction, dwell and speed are properties of a trajectory. Computing them
    per frame means differentiating noise.
*   Re-evaluating a changed rule over a month of archive is a query over stored
    tubes, taking seconds. Frame-level systems have to re-process the video.

Speeds and distances are reported in metres when the camera carries a
homography, and in pixels otherwise. The unit is always stated: a speed figure
without a stated calibration basis has no evidential value.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, time
from typing import Callable, Iterable, Sequence

import numpy as np

from ..core.types import Tube


# --------------------------------------------------------------------------- #
#  Geometry
# --------------------------------------------------------------------------- #


def point_in_poly(pts: np.ndarray, poly: np.ndarray) -> np.ndarray:
    """Vectorised even-odd test for many points against one polygon."""
    x, y = pts[:, 0], pts[:, 1]
    inside = np.zeros(len(pts), bool)
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        cond = ((yi > y) != (yj > y)) & (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi)
        inside ^= cond
        j = i
    return inside


def segment_side(p: np.ndarray, a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return np.sign((b[0] - a[0]) * (p[:, 1] - a[1]) - (b[1] - a[1]) * (p[:, 0] - a[0]))


def apply_homography(pts: np.ndarray, H: np.ndarray) -> np.ndarray:
    ones = np.ones((len(pts), 1), np.float32)
    q = np.hstack([pts, ones]) @ H.T
    return q[:, :2] / np.maximum(1e-9, q[:, 2:3])


# --------------------------------------------------------------------------- #
#  Rules
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Event:
    rule: str
    tube_id: int
    camera_id: str
    ts: datetime | None
    frame_idx: int
    severity: int = 1
    payload: dict = field(default_factory=dict)

    def __repr__(self) -> str:
        when = self.ts.strftime("%H:%M:%S") if self.ts else f"f{self.frame_idx}"
        return f"<{self.rule} tube={self.tube_id} @{when} {self.payload}>"


@dataclass(slots=True)
class Schedule:
    """Active window. Most 'false positives' in the field are simply the right
    detection at an hour nobody cared about."""

    days: tuple[int, ...] = (0, 1, 2, 3, 4, 5, 6)   # Monday = 0
    start: time = time(0, 0)
    end: time = time(23, 59, 59)

    def active(self, ts: datetime | None) -> bool:
        if ts is None:
            return True
        if ts.weekday() not in self.days:
            return False
        t = ts.time()
        if self.start <= self.end:
            return self.start <= t <= self.end
        return t >= self.start or t <= self.end     # window crossing midnight


class Rule:
    name = "rule"

    def __init__(self, name: str, classes: Sequence[str] | None = None,
                 schedule: Schedule | None = None, min_score: float = 0.0):
        self.name = name
        self.classes = set(classes) if classes else None
        self.schedule = schedule or Schedule()
        self.min_score = min_score

    def applicable(self, tube: Tube) -> bool:
        if self.classes and tube.class_name not in self.classes:
            return False
        if self.schedule and not self.schedule.active(tube.wall_time(tube.start)):
            return False
        return True

    def evaluate(self, tube: Tube) -> list[Event]:  # pragma: no cover
        raise NotImplementedError


class IntrusionRule(Rule):
    """Sustained presence inside a polygon. Requires a dwell, not a frame."""

    def __init__(self, name: str, polygon: np.ndarray, min_dwell_s: float = 1.5,
                 anchor: str = "base", **kw):
        super().__init__(name, **kw)
        self.polygon = np.asarray(polygon, np.float32)
        self.min_dwell_s = min_dwell_s
        self.anchor = anchor

    def _anchor_points(self, tube: Tube) -> np.ndarray:
        b = tube.bboxes()
        if self.anchor == "base":
            # Feet, not centroid: a person standing outside a doorway has their
            # centroid inside it whenever the box is tall.
            return np.stack([(b[:, 0] + b[:, 2]) / 2, b[:, 3]], 1)
        return tube.centroids()

    def evaluate(self, tube: Tube) -> list[Event]:
        if not self.applicable(tube):
            return []
        pts = self._anchor_points(tube)
        inside = point_in_poly(pts, self.polygon)
        if not inside.any():
            return []
        need = max(1, int(self.min_dwell_s * tube.fps))
        events, run, start = [], 0, 0
        for k, v in enumerate(inside):
            if v:
                if run == 0:
                    start = k
                run += 1
            else:
                if run >= need:
                    events.append(self._event(tube, start, run))
                run = 0
        if run >= need:
            events.append(self._event(tube, start, run))
        return events

    def _event(self, tube: Tube, start: int, run: int) -> Event:
        fi = tube.frames[start].frame_idx
        return Event(self.name, tube.tube_id, tube.camera_id, tube.wall_time(fi), fi,
                     payload={"dwell_s": round(run / tube.fps, 2),
                              "class": tube.class_name})


class LineCrossingRule(Rule):
    """Directional tripwire. Direction is mandatory --- an undirected line
    doubles the alarm rate for no extra information."""

    def __init__(self, name: str, a, b, direction: str = "any", **kw):
        super().__init__(name, **kw)
        self.a = np.asarray(a, np.float32)
        self.b = np.asarray(b, np.float32)
        self.direction = direction     # 'any' | 'positive' | 'negative'

    def evaluate(self, tube: Tube) -> list[Event]:
        if not self.applicable(tube):
            return []
        pts = tube.centroids()
        side = segment_side(pts, self.a, self.b)
        events = []
        for k in range(1, len(side)):
            if side[k - 1] == 0 or side[k] == 0 or side[k] == side[k - 1]:
                continue
            # Only count the crossing if it happens within the segment's extent.
            seg = self.b - self.a
            t = float(np.dot(pts[k] - self.a, seg) / max(1e-9, np.dot(seg, seg)))
            if not (0.0 <= t <= 1.0):
                continue
            sign = "positive" if side[k] > 0 else "negative"
            if self.direction != "any" and sign != self.direction:
                continue
            fi = tube.frames[k].frame_idx
            events.append(Event(self.name, tube.tube_id, tube.camera_id,
                                tube.wall_time(fi), fi,
                                payload={"direction": sign, "class": tube.class_name}))
        return events


class LoiteringRule(Rule):
    """Present for a long time while covering little ground.

    Uses displacement-over-path-length rather than raw dwell: someone waiting
    for a bus and someone pacing the same doorway for ten minutes both dwell,
    but only the second has a low straightness ratio.
    """

    def __init__(self, name: str, min_dwell_s: float = 60.0,
                 max_straightness: float = 0.25, polygon=None, **kw):
        super().__init__(name, **kw)
        self.min_dwell_s = min_dwell_s
        self.max_straightness = max_straightness
        self.polygon = np.asarray(polygon, np.float32) if polygon is not None else None

    def evaluate(self, tube: Tube) -> list[Event]:
        if not self.applicable(tube):
            return []
        dwell = tube.length / tube.fps
        if dwell < self.min_dwell_s:
            return []
        c = tube.centroids()
        if self.polygon is not None and not point_in_poly(c, self.polygon).mean() > 0.6:
            return []
        path = tube.path_length()
        disp = float(np.linalg.norm(c[-1] - c[0]))
        straightness = disp / max(1e-6, path)
        if straightness > self.max_straightness:
            return []
        fi = tube.frames[0].frame_idx
        return [Event(self.name, tube.tube_id, tube.camera_id, tube.wall_time(fi), fi,
                      severity=2,
                      payload={"dwell_s": round(dwell, 1),
                               "straightness": round(straightness, 3)})]


class SpeedRule(Rule):
    """Speed threshold. Reports metres per second only when calibrated."""

    def __init__(self, name: str, threshold: float, homography=None,
                 units: str = "px/s", **kw):
        super().__init__(name, **kw)
        self.threshold = threshold
        self.H = np.asarray(homography, np.float32) if homography is not None else None
        self.units = "m/s" if self.H is not None else units

    def evaluate(self, tube: Tube) -> list[Event]:
        if not self.applicable(tube) or tube.n_obs < 5:
            return []
        c = tube.centroids()
        if self.H is not None:
            c = apply_homography(c, self.H)
        idx = np.array([f.frame_idx for f in tube.frames], np.float32)
        dt = np.diff(idx) / tube.fps
        v = np.linalg.norm(np.diff(c, axis=0), axis=1) / np.maximum(1e-6, dt)
        # Median filter: a single jittered box must not produce a speeding ticket.
        k = min(9, len(v) if len(v) % 2 else len(v) - 1)
        if k >= 3:
            pad = k // 2
            vp = np.pad(v, pad, mode="edge")
            v = np.median(np.stack([vp[i:i + len(v)] for i in range(k)]), axis=0)
        peak = float(v.max())
        if peak < self.threshold:
            return []
        j = int(np.argmax(v))
        fi = tube.frames[j].frame_idx
        return [Event(self.name, tube.tube_id, tube.camera_id, tube.wall_time(fi), fi,
                      severity=2,
                      payload={"speed": round(peak, 2), "units": self.units,
                               "calibrated": self.H is not None})]


# --------------------------------------------------------------------------- #


class RuleEngine:
    def __init__(self, rules: Iterable[Rule]):
        self.rules = list(rules)

    def run(self, tubes: Sequence[Tube]) -> list[Event]:
        out: list[Event] = []
        for t in tubes:
            for r in self.rules:
                out.extend(r.evaluate(t))
        out.sort(key=lambda e: (e.frame_idx, e.rule))
        return out

    def counts(self, tubes: Sequence[Tube]) -> dict[str, int]:
        c: dict[str, int] = {}
        for e in self.run(tubes):
            c[e.rule] = c.get(e.rule, 0) + 1
        return c
