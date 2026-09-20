"""Benchmark the synopsis solver against baselines, with independent verification.

The collision figures reported here are *not* taken from the solver's own energy
function --- they are re-measured by rasterising every placed object into a
fresh occupancy buffer. A solver that lies to itself gets caught.
"""

from __future__ import annotations

import dataclasses
import sys
import time

import numpy as np

sys.path.insert(0, "/home/claude/argos")

from argos.core.types import Placement, SynopsisPlan, Tube          # noqa: E402
from argos.detect.synthetic import SceneConfig, SyntheticScene      # noqa: E402
from argos.synopsis.optimizer import SolverConfig, SynopsisSolver   # noqa: E402


# --------------------------------------------------------------------------- #
#  Independent verification
# --------------------------------------------------------------------------- #

def verify(tubes: list[Tube], plan: SynopsisPlan, W: int, H: int, scale: int = 4) -> dict:
    """Re-rasterise the plan and measure what a viewer would actually see."""
    gw, gh = W // scale + 2, H // scale + 2
    by_id = {t.tube_id: t for t in tubes}
    dense = {t.tube_id: t.densify() for t in tubes}

    sched: dict[int, list[tuple[int, int]]] = {}
    for tid, pl in plan.placements.items():
        if pl.dropped:
            continue
        for src in dense[tid]:
            o = pl.map_frame(by_id[tid], src)
            if 0 <= o < plan.duration:
                sched.setdefault(o, []).append((tid, src))

    overlap_px = 0
    obj_px = 0
    occ = []
    worst = 0
    for f in range(plan.duration):
        items = sched.get(f, [])
        occ.append(len(items))
        if len(items) < 1:
            continue
        acc = np.zeros((gh, gw), np.uint16)
        for tid, src in items:
            tf = dense[tid][src]
            x1, y1, x2, y2 = tf.bbox / scale
            gx1, gy1 = int(max(0, np.floor(x1))), int(max(0, np.floor(y1)))
            gx2, gy2 = int(min(gw, np.ceil(x2))), int(min(gh, np.ceil(y2)))
            if gx2 <= gx1 or gy2 <= gy1:
                continue
            m = tf.mask()
            if m is None:
                sub = np.ones((gy2 - gy1, gx2 - gx1), bool)
            else:
                ys = np.linspace(0, m.shape[0] - 1, gy2 - gy1).astype(int)
                xs = np.linspace(0, m.shape[1] - 1, gx2 - gx1).astype(int)
                sub = m[ys[:, None], xs[None, :]]
            acc[gy1:gy2, gx1:gx2] += sub.astype(np.uint16)
        obj_px += int(acc.sum())
        ov = int(np.clip(acc.astype(np.int32) - 1, 0, None).sum())
        overlap_px += ov
        worst = max(worst, len(items))

    occ = np.array(occ)
    return {
        "duration": plan.duration,
        "compression": plan.compression,
        "overlap_ratio": overlap_px / max(1, obj_px),
        "overlap_px_per_frame": overlap_px / max(1, plan.duration),
        "mean_objects": float(occ.mean()),
        "p95_objects": float(np.percentile(occ, 95)),
        "max_objects": int(worst),
        "empty_frames": int((occ == 0).sum()),
    }


# --------------------------------------------------------------------------- #
#  Baselines
# --------------------------------------------------------------------------- #

def baseline_stack(tubes: list[Tube], duration: int) -> SynopsisPlan:
    """Naive: spread tubes uniformly over the target duration, keeping order."""
    src = max(t.end for t in tubes) - min(t.start for t in tubes) + 1
    order = sorted(tubes, key=lambda t: t.start)
    pl = {}
    for i, t in enumerate(order):
        smax = max(0, duration - t.length)
        pl[t.tube_id] = Placement(t.tube_id, int(i / max(1, len(order) - 1) * smax) if smax else 0)
    return SynopsisPlan(pl, duration, src)


def baseline_greedy(tubes: list[Tube], duration: int, cfg: SolverConfig) -> SynopsisPlan:
    g = dataclasses.replace(cfg, iterations=0, polish_rounds=0)
    return SynopsisSolver(tubes, g).solve(duration)


# --------------------------------------------------------------------------- #

def main() -> None:
    scene = SyntheticScene(SceneConfig(n_objects=120, duration=27000, max_dwell_s=14.0))
    tubes = scene.tubes
    W, H = scene.cfg.width, scene.cfg.height
    src_dur = max(t.end for t in tubes) - min(t.start for t in tubes) + 1
    total_obs = sum(t.n_obs for t in tubes)
    print(f"scene: {len(tubes)} tubes, {total_obs} observations, source {src_dur} frames "
          f"({src_dur / scene.cfg.fps / 60:.1f} min), source concurrency "
          f"{total_obs / src_dur:.2f} obj/frame")

    cfg = SolverConfig(scale=6, iterations=40000, lambda_chrono=25.0, seed=3)
    t = time.perf_counter()
    solver = SynopsisSolver(tubes, cfg)
    build_t = time.perf_counter() - t
    m = solver.model
    print(f"collision model: {len(m.pairs)}/{m.n_candidate_pairs} pairs kept "
          f"({m.density:.1%} of O(n^2)) in {build_t:.2f}s\n")

    print(f"{'target':>7}{'x':>6}  {'method':<16}{'overlap%':>10}{'mean obj':>10}"
          f"{'p95':>7}{'empty':>7}{'solve s':>9}")
    results = {}
    for target in (600, 900, 1400, 2200, 3600):
        for name, fn in [
            ("uniform-spread", lambda t=target: baseline_stack(tubes, t)),
            ("greedy-seed", lambda t=target: baseline_greedy(tubes, t, cfg)),
            ("argos-full", lambda t=target: solver.solve(t)),
        ]:
            t0 = time.perf_counter()
            plan = fn()
            dt = time.perf_counter() - t0
            v = verify(tubes, plan, W, H)
            results[(target, name)] = v
            print(f"{target:>7}{v['compression']:>6.1f}  {name:<16}"
                  f"{v['overlap_ratio'] * 100:>10.2f}{v['mean_objects']:>10.1f}"
                  f"{v['p95_objects']:>7.0f}{v['empty_frames']:>7}{dt:>9.2f}")
        b = results[(target, "uniform-spread")]["overlap_ratio"]
        a = results[(target, "argos-full")]["overlap_ratio"]
        print(f"{'':>13}  -> overlap reduction {(1 - a / max(b, 1e-9)) * 100:.1f}%\n")

    t0 = time.perf_counter()
    auto = solver.auto()
    dt = time.perf_counter() - t0
    v = verify(tubes, auto, W, H)
    print(f"auto duration under clutter budget: {auto.duration} frames "
          f"({auto.compression:.1f}x, {auto.duration / scene.cfg.fps:.0f}s), "
          f"overlap {v['overlap_ratio'] * 100:.2f}%, mean {v['mean_objects']:.1f} obj/frame, "
          f"search {dt:.1f}s")


if __name__ == "__main__":
    main()
