"""Multi-object tracking: detections in, tubes out.

A ByteTrack-style two-stage association (high-confidence detections first, then
a second pass that rescues low-confidence boxes against surviving tracks) with a
constant-velocity Kalman filter, extended with an **appearance gate**.

Why the appearance gate matters here more than in a tracking benchmark: an ID
switch in a normal tracker costs a metric point. In a synopsis system it welds
two different people into one tube, so the optimiser places them as one object
and a search for either returns the other. Identity errors propagate into every
downstream answer, so association is deliberately conservative --- ARGOS prefers
to break a tube (recoverable later by re-ID stitching) over merging two.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from scipy.optimize import linear_sum_assignment

from ..core.types import Detection, Tube, TubeFrame


# --------------------------------------------------------------------------- #
#  Kalman filter (constant velocity on centre, aspect, height)
# --------------------------------------------------------------------------- #


class KalmanBox:
    def __init__(self, bbox: np.ndarray):
        self.x = np.zeros(8, np.float32)
        self.x[:4] = self._to_z(bbox)
        self.P = np.eye(8, dtype=np.float32) * 10.0
        self.P[4:, 4:] *= 100.0
        self.F = np.eye(8, dtype=np.float32)
        for i in range(4):
            self.F[i, i + 4] = 1.0
        self.H = np.zeros((4, 8), np.float32)
        self.H[:4, :4] = np.eye(4, dtype=np.float32)
        self.Q = np.eye(8, dtype=np.float32) * 0.01
        self.Q[4:, 4:] *= 0.1
        self.R = np.eye(4, dtype=np.float32) * 1.0

    @staticmethod
    def _to_z(b: np.ndarray) -> np.ndarray:
        w, h = max(1e-3, b[2] - b[0]), max(1e-3, b[3] - b[1])
        return np.array([b[0] + w / 2, b[1] + h / 2, w / h, h], np.float32)

    @staticmethod
    def _to_box(z: np.ndarray) -> np.ndarray:
        h = max(1e-3, z[3])
        w = max(1e-3, z[2] * h)
        return np.array([z[0] - w / 2, z[1] - h / 2, z[0] + w / 2, z[1] + h / 2], np.float32)

    def predict(self) -> np.ndarray:
        self.x = self.F @ self.x
        self.P = self.F @ self.P @ self.F.T + self.Q
        return self._to_box(self.x[:4])

    def update(self, bbox: np.ndarray) -> None:
        z = self._to_z(bbox)
        y = z - self.H @ self.x
        S = self.H @ self.P @ self.H.T + self.R
        K = self.P @ self.H.T @ np.linalg.inv(S)
        self.x = self.x + K @ y
        self.P = (np.eye(8, dtype=np.float32) - K @ self.H) @ self.P

    @property
    def box(self) -> np.ndarray:
        return self._to_box(self.x[:4])


# --------------------------------------------------------------------------- #
#  Association helpers
# --------------------------------------------------------------------------- #


def iou_matrix(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    if len(a) == 0 or len(b) == 0:
        return np.zeros((len(a), len(b)), np.float32)
    x1 = np.maximum(a[:, None, 0], b[None, :, 0])
    y1 = np.maximum(a[:, None, 1], b[None, :, 1])
    x2 = np.minimum(a[:, None, 2], b[None, :, 2])
    y2 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    aa = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1])
    bb = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    return (inter / np.maximum(1e-6, aa[:, None] + bb[None, :] - inter)).astype(np.float32)


@dataclass(slots=True)
class TrackerConfig:
    high_thresh: float = 0.55
    low_thresh: float = 0.15
    match_iou: float = 0.20
    second_iou: float = 0.45
    max_age: int = 30            # frames a track survives unmatched
    min_hits: int = 3
    appearance_weight: float = 0.35
    appearance_gate: float = 0.55   # cosine distance above which a match is vetoed
    appearance_min_hits: int = 5    # never gate on an immature embedding
    store_masks: bool = True
    # Un objeto que nunca se desplaza no es un objeto: es decorado. Un detector
    # real acierta al marcar una estatua como "person" --- lo es, formalmente ---
    # y aun así produce un falso positivo perpetuo que contamina recuentos,
    # sinopsis y búsquedas. El filtro es de desplazamiento, no de clase, porque
    # el problema tampoco es de clase: sirve igual para un contenedor, un cartel
    # o un coche aparcado.
    static_min_frames: int = 45      # antes de esto no hay evidencia suficiente
    static_disp_ratio: float = 0.65  # desplazamiento / diagonal del objeto


@dataclass
class Track:
    track_id: int
    class_id: int
    kf: KalmanBox
    frames: list[TubeFrame] = field(default_factory=list)
    age: int = 0
    hits: int = 0
    time_since_update: int = 0
    embeddings: list[np.ndarray] = field(default_factory=list)

    def smooth_embedding(self) -> np.ndarray | None:
        if not self.embeddings:
            return None
        e = np.mean(np.stack(self.embeddings[-30:]), axis=0)
        n = np.linalg.norm(e)
        return e / n if n > 0 else e


class MultiObjectTracker:
    def __init__(self, cfg: TrackerConfig | None = None, class_names: dict[int, str] | None = None):
        self.cfg = cfg or TrackerConfig()
        self.class_names = class_names or {}
        self.tracks: list[Track] = []
        self.finished: list[Tube] = []
        self._next_id = 1

    # ------------------------------------------------------------------ #

    def update(self, frame_idx: int, dets: list[Detection]) -> list[Track]:
        cfg = self.cfg
        for t in self.tracks:
            t.kf.predict()
            t.age += 1
            t.time_since_update += 1

        high = [d for d in dets if d.score >= cfg.high_thresh]
        low = [d for d in dets if cfg.low_thresh <= d.score < cfg.high_thresh]

        unmatched = list(range(len(self.tracks)))
        unmatched, used_high = self._associate(frame_idx, high, unmatched,
                                               cfg.match_iou, use_appearance=True)
        # Second pass: low-confidence boxes are usually occluded true positives,
        # so they may sustain a track but never create one.
        unmatched, _ = self._associate(frame_idx, low, unmatched,
                                       cfg.second_iou, use_appearance=False)

        for k, d in enumerate(high):
            if k not in used_high:
                self._spawn(frame_idx, d)

        alive = []
        for t in self.tracks:
            if t.time_since_update > cfg.max_age:
                self._retire(t)
            else:
                alive.append(t)
        self.tracks = alive
        return [t for t in self.tracks if t.hits >= cfg.min_hits]

    def _associate(self, frame_idx: int, dets: list[Detection], track_idx: list[int],
                   iou_thresh: float, use_appearance: bool) -> tuple[list[int], set[int]]:
        if not dets or not track_idx:
            return track_idx, set()
        tb = np.stack([self.tracks[i].kf.box for i in track_idx])
        db = np.stack([d.bbox for d in dets])
        cost = 1.0 - iou_matrix(tb, db)

        gate = np.zeros_like(cost, dtype=bool)
        if use_appearance and self.cfg.appearance_weight > 0:
            for r, ti in enumerate(track_idx):
                trk = self.tracks[ti]
                e = trk.smooth_embedding()
                # A track with two observations has a noisy mean embedding.
                # Vetoing on it costs far more identity than it saves, so the
                # gate only arms once the appearance model has settled.
                if e is None or len(trk.embeddings) < self.cfg.appearance_min_hits:
                    continue
                for c, d in enumerate(dets):
                    if d.embedding is None:
                        continue
                    de = d.embedding / max(1e-6, np.linalg.norm(d.embedding))
                    dist = float(1.0 - np.dot(e, de))
                    cost[r, c] = ((1 - self.cfg.appearance_weight) * cost[r, c]
                                  + self.cfg.appearance_weight * dist)
                    if dist > self.cfg.appearance_gate:
                        gate[r, c] = True

        cost[gate] = 1e6
        cost[cost > 1.0 - iou_thresh] = 1e6
        rows, cols = linear_sum_assignment(cost)

        still_unmatched = set(track_idx)
        used: set[int] = set()
        for r, c in zip(rows, cols):
            if cost[r, c] >= 1e6:
                continue
            ti = track_idx[r]
            self._hit(self.tracks[ti], frame_idx, dets[c])
            used.add(int(c))
            still_unmatched.discard(ti)
        return sorted(still_unmatched), used

    def _hit(self, t: Track, frame_idx: int, d: Detection) -> None:
        t.kf.update(d.bbox)
        t.hits += 1
        t.time_since_update = 0
        blob = shape = None
        if self.cfg.store_masks and d.mask is not None:
            blob, shape = TubeFrame.pack_mask(d.mask)
        t.frames.append(TubeFrame(frame_idx, d.bbox.astype(np.float32), d.score, blob, shape))
        if d.embedding is not None:
            t.embeddings.append(d.embedding.astype(np.float32))

    def _spawn(self, frame_idx: int, d: Detection) -> None:
        t = Track(self._next_id, d.class_id, KalmanBox(d.bbox))
        self._next_id += 1
        self._hit(t, frame_idx, d)
        t.hits = 1
        self.tracks.append(t)

    @staticmethod
    def _is_static(t: Track, min_frames: int, ratio: float) -> bool:
        """¿El objeto se movió menos que su propio tamaño?

        Se compara el desplazamiento neto contra la diagonal media de la caja,
        no contra un umbral en píxeles: así el criterio vale igual para un
        peatón de 30 px al fondo y un autobús de 300 en primer plano, sin
        recalibrar por cámara.
        """
        if len(t.frames) < min_frames:
            return False
        b = np.stack([f.bbox for f in t.frames])
        cx = (b[:, 0] + b[:, 2]) / 2
        cy = (b[:, 1] + b[:, 3]) / 2
        disp = float(np.hypot(cx[-1] - cx[0], cy[-1] - cy[0]))
        span = float(np.hypot(cx.max() - cx.min(), cy.max() - cy.min()))
        diag = float(np.median(np.hypot(b[:, 2] - b[:, 0], b[:, 3] - b[:, 1])))
        # Se usa el recorrido máximo, no solo el neto: algo que va y vuelve
        # (una puerta, una rama) se mueve pero tampoco es un objeto que pase.
        return max(disp, span) < ratio * max(1.0, diag)

    def _retire(self, t: Track) -> None:
        if t.hits < self.cfg.min_hits or not t.frames:
            return
        static = self._is_static(t, self.cfg.static_min_frames,
                                 self.cfg.static_disp_ratio)
        tube = Tube(
            tube_id=t.track_id,
            class_id=t.class_id,
            class_name=self.class_names.get(t.class_id, str(t.class_id)),
            frames=t.frames,
        )
        tube.embedding = t.smooth_embedding()
        # Se marca, no se descarta: para un recuento de aforo o una búsqueda
        # posterior el objeto existió, y borrarlo aquí sería perder información
        # que no se puede recuperar. Lo que se excluye es el sinopsis, donde un
        # objeto inmóvil no aporta nada porque ya está en la placa de fondo.
        tube.attributes["static"] = static
        self.finished.append(tube)

    def flush(self) -> list[Tube]:
        for t in self.tracks:
            self._retire(t)
        self.tracks = []
        out, self.finished = self.finished, []
        return out
