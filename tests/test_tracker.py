"""Tracker smoke test against synthetic ground truth."""
import sys
sys.path.insert(0, "/home/claude/argos")
import numpy as np
from argos.core.types import Detection
from argos.detect.synthetic import SceneConfig, SyntheticScene
from argos.track.tracker import MultiObjectTracker, TrackerConfig


def run(miss_rate=0.08, noise=1.6, appearance=True, emb_noise=0.10):
    sc = SyntheticScene(SceneConfig(n_objects=25, duration=1500, max_dwell_s=10, seed=4))
    rng = np.random.default_rng(0)
    # Per-identity unit vectors. A previous version of this fixture used
    # full(8, tube_id), which normalises to the *same* unit vector for every
    # identity -- the gate then vetoed correct matches on pure noise. Appearance
    # fixtures must be checked for degeneracy before they are trusted.
    ident = {t.tube_id: rng.normal(0, 1, 32).astype(np.float32) for t in sc.tubes}
    ident = {k: v / np.linalg.norm(v) for k, v in ident.items()}
    per_frame = {}
    for t in sc.tubes:
        for f in t.frames:
            if rng.random() < miss_rate:
                continue
            d = Detection(f.frame_idx, f.bbox + rng.normal(0, noise, 4).astype(np.float32),
                          float(np.clip(rng.normal(0.75, 0.15), 0.05, 0.99)), t.class_id,
                          mask=f.mask())
            if appearance:
                e = ident[t.tube_id] + rng.normal(0, emb_noise, 32).astype(np.float32)
                d.embedding = e / np.linalg.norm(e)
            per_frame.setdefault(f.frame_idx, []).append(d)

    trk = MultiObjectTracker(TrackerConfig(), {0: "person", 2: "car"})
    for i in range(1500):
        trk.update(i, per_frame.get(i, []))
    tubes = trk.flush()
    return sc, tubes


if __name__ == "__main__":
    print("--- clean conditions ---")
    for app in (False, True):
        sc, tubes = run(appearance=app)
        gt = len(sc.tubes)
        obs_gt = sum(t.n_obs for t in sc.tubes)
        obs = sum(t.n_obs for t in tubes)
        print(f"appearance_gate={app}: gt {gt} tubes / {obs_gt} obs -> "
              f"{len(tubes)} tubes / {obs} obs, "
              f"fragmentation {len(tubes)/gt:.2f}x, recall {obs/obs_gt:.1%}")

    print("--- degraded: 25% miss rate, heavy box noise, noisier re-ID ---")
    for app in (False, True):
        sc, tubes = run(miss_rate=0.25, noise=4.0, appearance=app, emb_noise=0.20)
        gt = len(sc.tubes)
        obs_gt = sum(t.n_obs for t in sc.tubes)
        obs = sum(t.n_obs for t in tubes)
        print(f"appearance_gate={app}: {len(tubes)} tubes, "
              f"fragmentation {len(tubes)/gt:.2f}x, recall {obs/obs_gt:.1%}")


# --------------------------------------------------------------------------- #
#  Aserciones de regresión
# --------------------------------------------------------------------------- #
#
# Lo de arriba imprime métricas; imprimir no es probar. Sin una aserción, una
# regresión que dejara el recall en la mitad seguiría "pasando" --- solo saldría
# un número peor en un log que nadie lee.
#
# Los umbrales son las cifras medidas menos un margen. No son objetivos: son
# alarmas. Si alguna salta, algo cambió de comportamiento y hay que mirarlo.


def _metricas(**kw):
    sc, tubes = run(**kw)
    gt = len(sc.tubes)
    obs_gt = sum(t.n_obs for t in sc.tubes)
    obs = sum(t.n_obs for t in tubes)
    return {"tubes": len(tubes), "gt": gt,
            "fragmentation": len(tubes) / max(1, gt),
            "recall": obs / max(1, obs_gt)}


def test_recall_limpio_no_se_degrada():
    m = _metricas(miss_rate=0.0, noise=0.4, appearance=True)
    assert m["recall"] >= 0.85, f"recall {m['recall']:.2f} por debajo del suelo"
    assert m["fragmentation"] <= 1.30, (
        f"fragmentación {m['fragmentation']:.2f}: el tracker está partiendo "
        "trayectorias que antes mantenía enteras")


def test_degradado_sigue_siendo_utilizable():
    """Con 25 % de detecciones perdidas y ruido fuerte el sistema aguanta."""
    m = _metricas(miss_rate=0.25, noise=4.0, appearance=True, emb_noise=0.20)
    assert m["recall"] >= 0.55, f"recall {m['recall']:.2f}"
    assert m["fragmentation"] <= 2.2, f"fragmentación {m['fragmentation']:.2f}"


def test_la_puerta_de_apariencia_no_fusiona_identidades():
    """Fragmentar es recuperable; fusionar dos personas en un tubo, no.

    Por eso la puerta se activa solo tras varias observaciones y prefiere
    partir antes que unir. Esta prueba fija esa preferencia: con apariencia
    activada nunca deben salir MUCHOS menos tubos, porque eso significaría que
    está uniendo identidades distintas.
    """
    con = _metricas(miss_rate=0.08, noise=1.6, appearance=True)
    sin = _metricas(miss_rate=0.08, noise=1.6, appearance=False)
    assert con["tubes"] >= sin["tubes"] * 0.8, (
        f"con apariencia {con['tubes']} tubos frente a {sin['tubes']} sin ella: "
        "señal de que está fusionando identidades distintas")
