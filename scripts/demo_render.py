"""End-to-end demo: synthetic scene -> tubes -> plan -> rendered synopsis video."""

from __future__ import annotations

import sys
import time

import cv2
import numpy as np

sys.path.insert(0, "/home/claude/argos")

from argos.detect.synthetic import SceneConfig, SyntheticScene            # noqa: E402
from argos.synopsis.optimizer import SolverConfig, SynopsisSolver         # noqa: E402
from argos.synopsis.renderer import RenderConfig, SynopsisRenderer        # noqa: E402
from argos.tubes.patchstore import build_patch_store                      # noqa: E402

OUT = "/mnt/user-data/outputs"


def main() -> None:
    scene = SyntheticScene(SceneConfig(n_objects=70, duration=9000, max_dwell_s=12.0,
                                       width=960, height=540, seed=5))
    tubes = scene.tubes
    print(f"{len(tubes)} tubes over {scene.cfg.duration} frames "
          f"({scene.cfg.duration / 25:.0f}s)")

    cfg = SolverConfig(scale=6, iterations=30000, lambda_chrono=25.0,
                       max_overlap_ratio=0.010, seed=1)
    solver = SynopsisSolver(tubes, cfg)
    t = time.perf_counter()
    plan = solver.auto()
    print(f"plan: {plan.summary()}  ({time.perf_counter() - t:.1f}s)")

    # Source frames actually needed by the plan (pixels are only fetched for
    # frames that contribute an object --- in production this is a seek list
    # against the recorder, not a full decode).
    t = time.perf_counter()
    src = build_patch_store(tubes, scene.frame)
    print(f"patch store: {len(src)} crops, {src.nbytes / 1e6:.1f} MB "
          f"({time.perf_counter() - t:.1f}s) --- source video no longer needed")

    plate = np.median(np.stack([scene.frame(i) for i in
                                np.linspace(0, scene.cfg.duration - 1, 40).astype(int)]),
                      axis=0).astype(np.uint8)

    r = SynopsisRenderer(tubes, plan, RenderConfig(feather=2, draw_labels=True))
    occ = r.occupancy_profile()
    print(f"occupancy: mean {occ.mean():.1f}, p95 {np.percentile(occ, 95):.0f}, "
          f"max {occ.max()}, empty frames {(occ == 0).sum()}")

    path = f"{OUT}/argos_synopsis_demo.mp4"
    t = time.perf_counter()
    r.render(src, plate, path, fps=25.0)
    print(f"rendered {plan.duration} frames -> {path} ({time.perf_counter() - t:.1f}s)")

    # Contact sheet: source frames on top, synopsis frames below, same count.
    src_idx = np.linspace(0, scene.cfg.duration - 1, 4).astype(int)
    syn_idx = np.linspace(0, plan.duration - 1, 4).astype(int)
    rows = []
    for label, idxs, getter in (
        ("SOURCE", src_idx, lambda i: scene.frame(i)),
        ("SYNOPSIS", syn_idx, lambda i: r.compose(i, plate, src)),
    ):
        tiles = []
        for i in idxs:
            im = cv2.resize(getter(int(i)), (420, 236))
            tag = f"{label} f{int(i)}"
            cv2.rectangle(im, (0, 0), (150, 18), (20, 20, 20), -1)
            cv2.putText(im, tag, (4, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.42,
                        (235, 235, 235), 1, cv2.LINE_AA)
            tiles.append(im)
        rows.append(np.hstack(tiles))
    sheet = np.vstack([rows[0], np.full((6, rows[0].shape[1], 3), 30, np.uint8), rows[1]])
    cv2.imwrite(f"{OUT}/argos_contact_sheet.png", sheet)
    print(f"contact sheet -> {OUT}/argos_contact_sheet.png")

    # Occupancy plot as a simple rendered strip.
    h, w = 160, 900
    plot = np.full((h, w, 3), 24, np.uint8)
    xs = np.linspace(0, w - 1, len(occ)).astype(int)
    ys = (h - 12 - (occ / max(1, occ.max()) * (h - 30))).astype(int)
    for a, b in zip(range(len(xs) - 1), range(1, len(xs))):
        cv2.line(plot, (xs[a], ys[a]), (xs[b], ys[b]), (120, 220, 140), 1, cv2.LINE_AA)
    cv2.putText(plot, f"objects per synopsis frame (max {occ.max()})", (10, 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 200, 200), 1, cv2.LINE_AA)
    cv2.imwrite(f"{OUT}/argos_occupancy.png", plot)
    print(f"occupancy plot -> {OUT}/argos_occupancy.png")


if __name__ == "__main__":
    main()
