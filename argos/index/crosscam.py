"""Cross-camera stitching.

Linking an object seen on camera 3 to one seen on camera 7 is the feature that
turns a synopsis tool into an investigation tool --- and it is also the feature
most likely to produce a confident, plausible, wrong answer that ends up in a
case file. So the design here is deliberately defensive:

*   **Topology gating first, appearance second.** A candidate link is only
    scored if the transition is physically possible: cameras must be adjacent in
    the site graph, and the time gap must fall inside the plausible walking or
    driving interval between them. Appearance similarity alone, over a large
    archive, will always find a good-looking match --- that is a property of
    high-dimensional nearest-neighbour search, not evidence.
*   **Calibrated confidence, not raw cosine.** Similarity is converted into a
    likelihood ratio against a background distribution sampled from tubes that
    are *known* not to match (same camera, overlapping time). A cosine of 0.82
    means nothing until you know what 0.82 looks like among strangers.
*   **Nothing is auto-confirmed.** The output is a ranked hypothesis list with
    its evidence attached. Writing an identity into the index requires an
    operator, a justification string and an audit entry (see ``identity`` in
    schema.sql). The system proposes; a person decides and is recorded as having
    decided.

This is not caution for its own sake. Under the EU AI Act, remote biometric
identification is heavily restricted, and the distinction that matters is
whether the system *identifies a person* or merely *associates appearances*.
Keeping the assertion step human, logged and reversible is what keeps a
deployment on the right side of that line --- and separately, it is what makes
the output survive cross-examination.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta

import numpy as np

from ..core.types import Tube


@dataclass(slots=True)
class Transition:
    """A physically plausible camera-to-camera movement."""

    src: str
    dst: str
    min_s: float
    max_s: float
    prior: float = 1.0      # relative frequency of this transition


@dataclass
class SiteTopology:
    transitions: list[Transition] = field(default_factory=list)

    def allowed(self, src: str, dst: str, gap_s: float) -> Transition | None:
        for t in self.transitions:
            if t.src == src and t.dst == dst and t.min_s <= gap_s <= t.max_s:
                return t
        return None

    @staticmethod
    def from_pairs(pairs) -> "SiteTopology":
        return SiteTopology([Transition(*p) for p in pairs])


@dataclass(slots=True)
class LinkHypothesis:
    tube_a: int
    tube_b: int
    camera_a: str
    camera_b: str
    gap_s: float
    similarity: float
    log_lr: float
    evidence: dict

    @property
    def confidence(self) -> str:
        """Deliberately coarse. A percentage on this number would imply a
        calibration the underlying model does not have."""
        if self.log_lr >= 4.0:
            return "strong"
        if self.log_lr >= 2.0:
            return "moderate"
        if self.log_lr >= 0.7:
            return "weak"
        return "insufficient"


class CrossCameraStitcher:
    def __init__(self, topology: SiteTopology, min_log_lr: float = 0.7,
                 max_candidates: int = 25, colour_agree_llr: float = 0.9,
                 colour_disagree_llr: float = 2.5):
        self.topo = topology
        self.min_log_lr = min_log_lr
        self.max_candidates = max_candidates
        self.colour_agree_llr = colour_agree_llr
        self.colour_disagree_llr = colour_disagree_llr
        self._bg_mean = 0.30       # background similarity among non-matches
        self._bg_std = 0.11

    # ------------------------------------------------------------------ #

    def fit_background(self, tubes: list[Tube], n: int = 5000,
                       seed: int = 0) -> tuple[float, float]:
        """Estimate the impostor distribution from pairs that cannot match.

        Tubes on the same camera whose lifetimes overlap are, barring a tracking
        failure, different objects. That gives thousands of labelled negatives
        per site for free --- and it means the confidence scale is calibrated to
        *this* installation's cameras, lighting and crowd, not to a benchmark.
        """
        rng = np.random.default_rng(seed)
        with_emb = [t for t in tubes if t.embedding is not None]
        by_cam: dict[str, list[Tube]] = {}
        for t in with_emb:
            by_cam.setdefault(t.camera_id, []).append(t)

        sims: list[float] = []
        for cam, group in by_cam.items():
            if len(group) < 2:
                continue
            for _ in range(min(n // max(1, len(by_cam)), 4000)):
                a, b = rng.choice(len(group), 2, replace=False)
                ta, tb = group[a], group[b]
                if ta.end < tb.start or tb.end < ta.start:
                    continue        # not simultaneous: could be the same object
                sims.append(float(np.dot(ta.embedding, tb.embedding)))
        if len(sims) >= 50:
            s = np.asarray(sims, np.float32)
            self._bg_mean = float(s.mean())
            self._bg_std = float(max(0.02, s.std()))
        return self._bg_mean, self._bg_std

    def _log_lr(self, sim: float) -> float:
        """How many standard deviations above the impostor distribution."""
        z = (sim - self._bg_mean) / self._bg_std
        return float(max(0.0, z))

    # ------------------------------------------------------------------ #

    def propose(self, tubes: list[Tube]) -> list[LinkHypothesis]:
        usable = [t for t in tubes
                  if t.embedding is not None and t.t0_wall is not None and t.camera_id]
        usable.sort(key=lambda t: t.t0_wall)
        out: list[LinkHypothesis] = []

        for i, a in enumerate(usable):
            a_end = a.wall_time(a.end)
            if a_end is None:
                continue
            n_checked = 0
            for b in usable[i + 1:]:
                if b.camera_id == a.camera_id:
                    continue
                b_start = b.wall_time(b.start)
                if b_start is None:
                    continue
                gap = (b_start - a_end).total_seconds()
                if gap < 0:
                    continue
                trans = self.topo.allowed(a.camera_id, b.camera_id, gap)
                if trans is None:
                    # Beyond the slowest plausible transition: everything later
                    # is too, so stop scanning this source tube.
                    if gap > self._max_gap(a.camera_id):
                        break
                    continue
                if a.class_name != b.class_name:
                    continue

                sim = float(np.dot(a.embedding, b.embedding))
                llr = self._log_lr(sim) + float(np.log(max(trans.prior, 1e-3)))
                # Independent evidence channels are added, not averaged: a
                # colour disagreement is real counter-evidence and should be
                # able to sink an otherwise attractive appearance match.
                ca, cb = a.attributes.get("colour"), b.attributes.get("colour")
                if ca and cb:
                    llr += self.colour_agree_llr if ca == cb else -self.colour_disagree_llr
                if llr < self.min_log_lr:
                    continue
                out.append(LinkHypothesis(
                    a.tube_id, b.tube_id, a.camera_id, b.camera_id,
                    round(gap, 1), round(sim, 4), round(llr, 2),
                    evidence={
                        "class": a.class_name,
                        "transition": f"{a.camera_id}->{b.camera_id}",
                        "plausible_window_s": [trans.min_s, trans.max_s],
                        "impostor_mean": round(self._bg_mean, 3),
                        "impostor_std": round(self._bg_std, 3),
                        "colour_a": a.attributes.get("colour"),
                        "colour_b": b.attributes.get("colour"),
                        "colour_agrees": (a.attributes.get("colour")
                                          == b.attributes.get("colour")),
                    },
                ))
                n_checked += 1
                if n_checked >= self.max_candidates:
                    break
        out.sort(key=lambda h: -h.log_lr)
        return out

    def _max_gap(self, camera_id: str) -> float:
        gaps = [t.max_s for t in self.topo.transitions if t.src == camera_id]
        return max(gaps) if gaps else 0.0
