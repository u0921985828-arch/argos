"""Self-calibration from pedestrians, validated against a known camera."""
import sys
sys.path.insert(0, "/home/claude/argos")
sys.path.insert(0, "/home/claude/argos/tests")
import numpy as np
from test_anthropometry import make_camera, project, W, Hpx
from argos.measure.calibration import (SelfCalibrationConfig, calibrate_from_pedestrians,
                                       estimate_vertical_vp)

def crowd(n=180, jitter=1.5, seed=1, median=1.70, spread=0.09):
    P = make_camera()
    rng = np.random.default_rng(seed)
    feet, heads, truth = [], [], []
    while len(feet) < n:
        X = rng.uniform(-6, 6); Y = rng.uniform(10, 32)
        s = float(np.clip(rng.normal(median, spread), 1.45, 2.05))
        b = project(P, X, Y, 0.0) + rng.normal(0, jitter, 2)
        t = project(P, X, Y, s) + rng.normal(0, jitter, 2)
        if not (0 < b[0] < W and 0 < b[1] < Hpx and 0 < t[0] < W and 0 < t[1] < Hpx):
            continue
        if abs(b[1] - t[1]) < 40:
            continue
        feet.append(b); heads.append(t); truth.append(s)
    return P, np.array(feet), np.array(heads), np.array(truth)

def report(label, cam, feet, heads, truth):
    est = np.array([cam.height(f, h) for f, h in zip(feet, heads)])
    ok = np.isfinite(est)
    err = est[ok] - truth[ok]
    print(f"{label:<40} MAE {np.abs(err).mean()*100:5.1f} cm  "
          f"p90 {np.percentile(np.abs(err),90)*100:5.1f} cm  "
          f"sesgo {err.mean()*100:+5.1f} cm  modo={cam.mode}")

if __name__ == "__main__":
    P, feet, heads, truth = crowd()
    v_true = P[:, 2] / P[2, 2]
    v_est = estimate_vertical_vp(feet, heads)
    print(f"punto de fuga vertical: real {v_true[:2].round(1)}  "
          f"estimado {v_est[:2].round(1)}  error {np.linalg.norm(v_true[:2]-v_est[:2]):.1f} px\n")

    cfg = SelfCalibrationConfig()
    cam = calibrate_from_pedestrians(feet, heads, cfg)
    print("provenance:", cam.provenance(), "\n")
    report("autocalibrado (mediana asumida 1.70)", cam, feet, heads, truth)

    refs = [(project(P, -3.0, 14.0, 0.0), project(P, -3.0, 14.0, 2.10), 2.10),
            (project(P, 3.5, 26.0, 0.0), project(P, 3.5, 26.0, 1.00), 1.00)]
    cam2 = calibrate_from_pedestrians(feet, heads, cfg, references=refs)
    report("con referencias medidas (evidencial)", cam2, feet, heads, truth)

    # La poblacion real no coincide con la asumida: el error entra entero.
    for real_median in (1.66, 1.74):
        P2, f2, h2, t2 = crowd(median=real_median, seed=5)
        c = calibrate_from_pedestrians(f2, h2, cfg)
        report(f"poblacion real {real_median} m, asumida 1.70", c, f2, h2, t2)

    # Menos muestras
    for n in (40, 80, 180):
        P3, f3, h3, t3 = crowd(n=n, seed=9)
        c = calibrate_from_pedestrians(f3, h3, cfg)
        report(f"n={n} peatones", c, f3, h3, t3)
