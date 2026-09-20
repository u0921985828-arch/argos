"""Validate stature estimation against known ground truth.

A synthetic pinhole camera looks down at a ground plane. People of known height
walk across it. We project them exactly, then degrade the observation the way a
real detector would -- bbox jitter, gait bobbing, footwear, occasional foot
occlusion -- and measure how far the estimate lands from truth.
"""
import sys
sys.path.insert(0, "/home/claude/argos")
import numpy as np
from argos.core.types import Tube, TubeFrame
from argos.measure.anthropometry import (AnthropometryConfig, GroundCamera,
                                         StatureEstimator)

W, Hpx = 1280, 720


def make_camera(cam_h=6.0, tilt_deg=28.0, f=900.0):
    """Full pinhole P, from which we derive H (ground) and v (vertical vp)."""
    t = np.deg2rad(tilt_deg)
    # World: X right, Y forward on ground, Z up. Camera looks down by `tilt`.
    R = np.array([[1, 0, 0],
                  [0, -np.sin(t), -np.cos(t)],
                  [0,  np.cos(t), -np.sin(t)]], np.float64)
    C = np.array([0.0, 0.0, cam_h])
    K = np.array([[f, 0, W / 2], [0, f, Hpx / 2], [0, 0, 1]], np.float64)
    P = K @ np.hstack([R, (-R @ C).reshape(3, 1)])
    return P


def project(P, X, Y, Z):
    p = P @ np.array([X, Y, Z, 1.0])
    return p[:2] / p[2]


def build_scene(n=60, seed=0, jitter=1.5, foot_occl=0.10, hat_rate=0.0):
    rng = np.random.default_rng(seed)
    P = make_camera()
    H = P[:, [0, 1, 3]]          # ground plane Z=0
    v = P[:, 2]                  # vertical vanishing point (correct scale)

    # Calibrate scale from two reference objects of known height, as a field
    # technician would: a 2.10 m doorway and a 1.00 m bollard.
    refs = []
    for (X, Y, hgt) in [(-3.0, 14.0, 2.10), (3.5, 26.0, 1.00)]:
        refs.append((project(P, X, Y, 0.0), project(P, X, Y, hgt), hgt))

    cam = GroundCamera(H, v)
    cam.calibrate_scale(refs)

    tubes, truth = [], {}
    for i in range(n):
        stature = float(np.clip(rng.normal(1.72, 0.09), 1.45, 2.05))
        shoes = float(rng.uniform(0.02, 0.04))
        hat = float(rng.uniform(0.05, 0.18)) if rng.random() < hat_rate else 0.0
        presented = stature + shoes + hat
        y0 = float(rng.uniform(11.0, 30.0))
        x0 = float(rng.uniform(-5.0, 5.0))
        vx, vy = rng.uniform(-0.9, 0.9), rng.uniform(-0.5, 0.5)
        frames = []
        for k in range(70):
            X, Y = x0 + vx * k / 12.5, y0 + vy * k / 12.5
            if Y < 8.0:
                break
            # Gait: stature oscillates ~3 cm, always at or below standing height.
            bob = -0.015 * (1 - np.cos(0.9 * k))
            top = project(P, X, Y, presented + bob)
            base = project(P, X, Y, 0.0)
            if rng.random() < foot_occl:
                base = base + np.array([0.0, -abs(rng.normal(0, 6.0))])
            n1 = rng.normal(0, jitter, 2)
            n2 = rng.normal(0, jitter, 2)
            top, base = top + n1, base + n2
            half_w = abs(base[1] - top[1]) * 0.16
            bbox = np.array([base[0] - half_w, top[1], base[0] + half_w, base[1]], np.float32)
            if bbox[3] <= bbox[1]:
                continue
            frames.append(TubeFrame(k, bbox, 0.9))
        if len(frames) >= 20:
            t = Tube(i, 0, "person", frames, fps=12.5)
            tubes.append(t)
            truth[i] = (stature, presented)
    return cam, tubes, truth


def run(label, **kw):
    cam, tubes, truth = build_scene(**kw)
    est = StatureEstimator(cam, (W, Hpx))
    errs, errs_bare, covered, quals = [], [], 0, {}
    for t in tubes:
        e = est.estimate(t)
        quals[e.quality] = quals.get(e.quality, 0) + 1
        if not np.isfinite(e.height_m):
            continue
        bare, presented = truth[t.tube_id]
        errs.append(e.height_m - presented)
        errs_bare.append(e.height_m - bare)
        if e.ci_low <= presented <= e.ci_high:
            covered += 1
    a = np.abs(errs)
    print(f"{label:<34} n={len(errs):3d}  MAE {a.mean()*100:5.1f} cm   "
          f"p90 {np.percentile(a,90)*100:5.1f} cm   bias {np.mean(errs)*100:+5.1f} cm   "
          f"CI cubre {covered/max(1,len(errs)):5.1%}   {quals}")
    return np.mean(errs_bare)


if __name__ == "__main__":
    print("error frente a la altura PRESENTADA (con calzado):\n")
    bias = run("ideal (jitter 0.5 px)", jitter=0.5, foot_occl=0.0)
    run("realista (jitter 1.5 px, 10% pies)", jitter=1.5, foot_occl=0.10)
    run("degradado (jitter 3.5 px, 25% pies)", jitter=3.5, foot_occl=0.25)
    run("con gorros (30%)", jitter=1.5, foot_occl=0.10, hat_rate=0.30)
    print(f"\nsesgo sistematico vs estatura DESCALZA (caso ideal): {bias*100:+.1f} cm")
