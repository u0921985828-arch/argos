"""The synopsis solver.

Given a set of tubes and a target duration ``T``, assign each tube a start
offset on the synopsis timeline so that objects are packed as densely as
possible without visually colliding, while preserving the causal order of
objects that actually interacted.

Energy
------
    E(s) = sum_{(a,b)} C_ab(s_a - s_b)            <- pixel collisions
         + lambda_c * sum_{(a,b)} chrono(a, b)     <- causal order violations
         + lambda_a * sum_a |s_a - anchor_a|        <- global time-order anchor

Solver
------
Three stages, each cheap because :mod:`collision` reduced pair evaluation to an
array lookup:

1.  **Greedy seeding.** Longest tubes first (they are the most constrained),
    each placed at the exact argmin of its cost profile against everything
    already placed. The profile is accumulated with vectorised slice-adds, so
    scoring *all* candidate shifts for one tube costs O(sum of neighbour curve
    lengths), not O(T x neighbours).
2.  **Simulated annealing** with incremental deltas --- moving one tube only
    touches its adjacency list.
3.  **Best-shift polish**, a deterministic sweep re-placing each tube at its
    global argmin until nothing improves.

The optimal-jump move in stage 2 is what lets the annealer escape the deep
local minima that plague pure random-walk implementations at high object
density.

Design choice worth flagging: ``allow_drop`` defaults to ``False``. Commercial
synopsis products silently discard objects they cannot fit, which is
unacceptable when the output is used as evidence. ARGOS instead lengthens the
synopsis until everything fits under the collision budget, and reports the
achieved compression honestly.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

import numpy as np

from ..core.types import Placement, SynopsisPlan, Tube
from .collision import CollisionModel


@dataclass(slots=True)
class SolverConfig:
    scale: int = 8                  # collision grid: pixels per cell
    lambda_chrono: float = 40.0     # weight on causal-order preservation
    lambda_anchor: float = 0.6      # pull toward the original relative position
    iterations: int = 20000
    t_start: float = 1.0            # SA temperature schedule (relative to E0)
    t_end: float = 0.002
    p_jump: float = 0.35            # probability of an optimal-jump move
    polish_rounds: int = 6
    seed: int = 0
    allow_drop: bool = False
    max_overlap_ratio: float = 0.012  # fraction of silhouette mass allowed to overlap


class SynopsisSolver:
    def __init__(self, tubes: list[Tube], cfg: SolverConfig | None = None):
        self.cfg = cfg or SolverConfig()
        self.tubes = {t.tube_id: t for t in tubes}
        self.ids = sorted(self.tubes)
        self.model = CollisionModel(tubes, scale=self.cfg.scale)
        self.rng = random.Random(self.cfg.seed)
        # Original relative offsets, for the chronology term.
        self._d0 = {
            (pc.a, pc.b): self.tubes[pc.a].start - self.tubes[pc.b].start
            for pc in self.model.pairs
        }
        self.source_duration = (
            max(t.end for t in tubes) - min(t.start for t in tubes) + 1 if tubes else 0
        )
        self._t_min = min((t.start for t in tubes), default=0)
        self._anchor: dict[int, int] = {}

    # ------------------------------------------------------------------ #
    #  Energy terms
    # ------------------------------------------------------------------ #

    def _chrono_pair(self, pc, d: int) -> float:
        """Penalty for inverting the original order of two interacting tubes."""
        d0 = self._d0[(pc.a, pc.b)]
        if d0 == 0:
            return self.cfg.lambda_chrono * abs(d)
        if d0 > 0:
            return self.cfg.lambda_chrono * max(0, -d)
        return self.cfg.lambda_chrono * max(0, d)

    def _anchor_cost(self, tid: int, s: int) -> float:
        return self.cfg.lambda_anchor * abs(s - self._anchor.get(tid, s))

    def energy(self, shifts: dict[int, int]) -> tuple[float, float, float]:
        coll = chrono = 0.0
        for pc in self.model.pairs:
            d = shifts[pc.a] - shifts[pc.b]
            coll += pc(d)
            chrono += self._chrono_pair(pc, d)
        anchor = sum(self._anchor_cost(i, shifts[i]) for i in self.ids)
        return coll + chrono + anchor, coll, chrono

    def _profile(self, tid: int, shifts: dict[int, int], smax: int,
                 placed: set[int]) -> np.ndarray:
        """Cost of every candidate shift for ``tid``, vectorised.

        For a neighbour ``b`` fixed at ``s_b``, the pair cost as a function of
        ``s_a`` is just the stored curve translated by ``s_b`` --- so we add it
        with a single array slice instead of looping over shifts.
        """
        prof = np.zeros(smax + 1, np.float32)
        lam = self.cfg.lambda_chrono
        # Unary anchor: without it the solver is free to pile every object at
        # shift 0 whenever slack is plentiful, producing a front-loaded synopsis
        # with a dead tail. The anchor is weak enough never to outrank a real
        # collision, but it fixes ties in favour of the original time order.
        if self.cfg.lambda_anchor > 0:
            anc = self._anchor.get(tid)
            if anc is not None:
                ramp = self._ramp(smax)
                cut = min(max(anc, 0), smax + 1)
                if cut > 0:
                    prof[:cut] += self.cfg.lambda_anchor * (anc - ramp[:cut])
                if cut < smax + 1:
                    prof[cut:] += self.cfg.lambda_anchor * (ramp[cut:smax + 1] - anc)
        for k in self.model.adj[tid]:
            pc = self.model.pairs[k]
            other = pc.b if pc.a == tid else pc.a
            if other not in placed:
                continue
            so = shifts[other]
            sign = 1 if pc.a == tid else -1
            # d = sign * (s_a - s_o)  ->  s_a = so + sign * d
            n = pc.curve.shape[0]
            if sign == 1:
                lo = so + pc.d_min
                seg = pc.curve
            else:
                lo = so - (pc.d_min + n - 1)
                seg = pc.curve[::-1]
            a0 = max(0, lo)
            a1 = min(smax + 1, lo + n)
            if a1 > a0:
                prof[a0:a1] += seg[a0 - lo: a1 - lo]

            # Chronology, as a one-sided ramp written with plain slice adds ---
            # allocating an arange per neighbour dominated the annealing loop.
            d0 = self._d0[(pc.a, pc.b)]
            ramp = self._ramp(smax)
            if pc.a != tid:
                d0 = -d0        # express everything in terms of d = s_a - s_o
            lo_hi = so if d0 >= 0 else None
            if d0 == 0:
                cut = min(max(so, 0), smax + 1)
                if cut > 0:
                    prof[:cut] += lam * (so - ramp[:cut])
                if cut < smax + 1:
                    prof[cut:] += lam * (ramp[cut:smax + 1] - so)
            elif d0 > 0:
                cut = min(max(lo_hi, 0), smax + 1)
                if cut > 0:
                    prof[:cut] += lam * (so - ramp[:cut])
            else:
                start = min(max(so + 1, 0), smax + 1)
                if start < smax + 1:
                    prof[start:] += lam * (ramp[start:smax + 1] - so)
        return prof

    def _ramp(self, smax: int) -> np.ndarray:
        r = getattr(self, "_ramp_cache", None)
        if r is None or r.shape[0] < smax + 1:
            r = np.arange(smax + 1, dtype=np.float32)
            self._ramp_cache = r
        return r

    # ------------------------------------------------------------------ #
    #  Solve
    # ------------------------------------------------------------------ #

    def solve(self, duration: int) -> SynopsisPlan:
        cfg = self.cfg
        tubes = self.tubes
        T = max(duration, max((t.length for t in tubes.values()), default=1))
        smax = {i: max(0, T - tubes[i].length) for i in self.ids}
        span = max(1, self.source_duration)
        self._anchor = {
            i: int(round((tubes[i].start - self._t_min) / span * smax[i]))
            for i in self.ids
        }

        # --- stage 1: greedy seeding, most constrained first ------------- #
        order = sorted(self.ids, key=lambda i: -tubes[i].length)
        shifts: dict[int, int] = {i: 0 for i in self.ids}
        placed: set[int] = set()
        for tid in order:
            prof = self._profile(tid, shifts, smax[tid], placed)
            best = int(np.argmin(prof))
            # Break ties toward the original chronological position.
            lo = float(prof[best])
            ties = np.flatnonzero(prof <= lo + 1e-6)
            if ties.size > 1:
                target = int(round((tubes[tid].start / max(1, self.source_duration)) * smax[tid]))
                best = int(ties[np.argmin(np.abs(ties - target))])
            shifts[tid] = best
            placed.add(tid)

        E, coll, chrono = self.energy(shifts)

        # --- stage 2: annealing ------------------------------------------ #
        if len(self.ids) > 1 and cfg.iterations > 0:
            scale = max(1.0, E)
            t0, t1 = cfg.t_start * scale, cfg.t_end * scale
            for it in range(cfg.iterations):
                temp = t0 * (t1 / t0) ** (it / cfg.iterations)
                tid = self.rng.choice(self.ids)
                if smax[tid] == 0:
                    continue
                cur = shifts[tid]

                if self.rng.random() < cfg.p_jump:
                    others = placed - {tid}
                    prof = self._profile(tid, shifts, smax[tid], others)
                    cand = int(np.argmin(prof))
                else:
                    span = max(2, int(smax[tid] * (0.02 + 0.30 * temp / max(t0, 1e-9))))
                    cand = cur + self.rng.randint(-span, span)
                    cand = max(0, min(smax[tid], cand))
                if cand == cur:
                    continue

                before = self._local_energy(tid, shifts)
                shifts[tid] = cand
                after = self._local_energy(tid, shifts)
                delta = after - before
                if delta <= 0 or self.rng.random() < math.exp(-delta / max(temp, 1e-9)):
                    E += delta
                else:
                    shifts[tid] = cur

        # --- stage 3: deterministic polish -------------------------------- #
        for _ in range(cfg.polish_rounds):
            improved = False
            for tid in sorted(self.ids, key=lambda i: -tubes[i].length):
                if smax[tid] == 0:
                    continue
                others = placed - {tid}
                prof = self._profile(tid, shifts, smax[tid], others)
                best = int(np.argmin(prof))
                if best != shifts[tid] and prof[best] < prof[shifts[tid]] - 1e-6:
                    shifts[tid] = best
                    improved = True
            if not improved:
                break

        E, coll, chrono = self.energy(shifts)
        placements = {i: Placement(i, shifts[i]) for i in self.ids}
        return SynopsisPlan(
            placements=placements,
            duration=T,
            source_duration=self.source_duration,
            energy=E,
            collision=coll,
            chronology=chrono,
            dropped=0,
        )

    def _local_energy(self, tid: int, shifts: dict[int, int]) -> float:
        tot = self._anchor_cost(tid, shifts[tid])
        for k in self.model.adj[tid]:
            pc = self.model.pairs[k]
            d = shifts[pc.a] - shifts[pc.b]
            tot += pc(d) + self._chrono_pair(pc, d)
        return tot

    # ------------------------------------------------------------------ #
    #  Automatic duration
    # ------------------------------------------------------------------ #

    def auto(self, min_duration: int | None = None, max_duration: int | None = None,
             tolerance: float = 0.04) -> SynopsisPlan:
        """Bisect for the shortest synopsis meeting the collision budget.

        The budget is the fraction of total silhouette mass allowed to be
        occluded by another object. That is resolution-independent and maps
        directly onto perceived clutter --- unlike a hard object-count cap,
        which yields either an empty or an unreadable synopsis depending on how
        big the objects happen to be in that particular camera view.
        """
        tubes = list(self.tubes.values())
        if not tubes:
            return SynopsisPlan({}, 0, 0)

        lo = min_duration or max(t.length for t in tubes)
        hi = max_duration or self.source_duration
        if hi <= lo:
            return self.solve(lo)

        # Search with a cheaper solver, then re-solve the winner at full effort.
        import dataclasses
        full_cfg = self.cfg
        self.cfg = dataclasses.replace(full_cfg, iterations=max(2000, full_cfg.iterations // 8),
                                       polish_rounds=2)
        best = self.solve(hi)
        it = 0
        while hi - lo > max(4, int(tolerance * hi)) and it < 12:
            mid = (lo + hi) // 2
            plan = self.solve(mid)
            if plan.collision / self.model.total_mass <= self.cfg.max_overlap_ratio:
                best, hi = plan, mid
            else:
                lo = mid
            it += 1
        self.cfg = full_cfg
        return self.solve(best.duration)


def plan_synopsis(tubes: list[Tube], duration: int | None = None,
                  cfg: SolverConfig | None = None) -> SynopsisPlan:
    solver = SynopsisSolver(tubes, cfg)
    return solver.solve(duration) if duration else solver.auto()
