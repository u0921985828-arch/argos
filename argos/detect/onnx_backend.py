"""Detector and embedding backends.

Licence note, because it decides the architecture rather than being a footnote:
Ultralytics YOLO is AGPL-3.0, which for a product that ships or is offered as a
network service means either releasing the whole source or buying a commercial
licence. ARGOS therefore standardises on **ONNX Runtime** and defaults to
Apache-2.0 weights (RT-DETR, RTMDet) or YOLOX (Apache-2.0). Nothing in the
pipeline depends on a specific detector: the contract is a list of
:class:`Detection` per frame, so a site can swap in whatever it is licensed for.

Everything below is deliberately backend-agnostic and CPU-runnable so the rest
of the system can be tested without a GPU or model weights present.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..core.types import Detection

try:                                    # optional at import time
    import cv2
except ImportError:                     # pragma: no cover
    cv2 = None

try:
    import onnxruntime as ort
except ImportError:                     # pragma: no cover
    ort = None


COCO_KEEP = {0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus",
             7: "truck", 15: "cat", 16: "dog"}


class Detector:
    """Interface: frame in, detections out."""

    def __call__(self, frame_idx: int, frame: np.ndarray) -> list[Detection]:  # pragma: no cover
        raise NotImplementedError


@dataclass
class OnnxDetectorConfig:
    model_path: str = ""
    input_size: tuple[int, int] = (640, 640)
    # Umbral bajo a propósito. Medido sobre una vista aérea real (Alcalá,
    # vehículos de ~26 px):
    #
    #     conf 0.40 ->  30 vehículos
    #     conf 0.30 ->  41
    #     conf 0.20 ->  62
    #     conf 0.15 ->  79      lado mediano 24 px
    #     conf 0.05 -> 161      (aquí ya entra basura)
    #
    # Subirlo a 0.30 perdía la mitad de los coches, y no eran cajas espurias:
    # el tamaño mediano apenas cambia entre 0.15 y 0.40, así que lo que se cae
    # son vehículos pequeños y de bajo contraste, no ruido.
    #
    # La puntuación de un detector no sabe distinguir "coche lejano" de "mancha";
    # el modelo de perspectiva, el filtro de inmovilidad y el `min_hits` del
    # tracker sí. Filtrar por física aguas abajo es mejor que filtrar por
    # confianza aguas arriba, porque un objeto descartado en la detección ya no
    # se recupera.
    score_thresh: float = 0.15
    nms_iou: float = 0.65
    providers: tuple[str, ...] = ("CUDAExecutionProvider", "CPUExecutionProvider")
    classes: dict[int, str] | None = None
    batch: int = 8


class OnnxDetector(Detector):
    """ONNX Runtime detector.

    A note on the confidence threshold: it is set far lower than a benchmark
    would use. In a synopsis system a missed detection *fragments a tube*, and a
    fragmented tube is placed twice on the timeline and found twice in a search.
    Low-confidence boxes are cheap because the tracker's second association pass
    only lets them sustain an existing track, never create one --- so the false
    positives mostly die at the ``min_hits`` gate instead of becoming objects.
    """

    def __init__(self, cfg: OnnxDetectorConfig):
        if ort is None:
            raise RuntimeError("onnxruntime is required for OnnxDetector")
        self.cfg = cfg
        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sess = ort.InferenceSession(cfg.model_path, so, providers=list(cfg.providers))
        self.iname = self.sess.get_inputs()[0].name
        self.classes = cfg.classes or COCO_KEEP
        self._grids: dict = {}
        # El tamaño de entrada se lee del modelo, no se asume: yolox_nano y
        # yolox_tiny son 416, yolox_s es 640, y usar el equivocado produce cajas
        # desplazadas en vez de un error.
        shape = self.sess.get_inputs()[0].shape
        if isinstance(shape[2], int) and isinstance(shape[3], int):
            cfg.input_size = (shape[2], shape[3])

    # ------------------------------------------------------------------ #

    def _preprocess(self, frame: np.ndarray) -> tuple[np.ndarray, float]:
        """Letterbox al estilo YOLOX.

        Dos detalles que rompen el detector en silencio si se copian de otra
        familia de modelos: YOLOX espera **BGR crudo en 0-255**, sin dividir
        entre 255 y sin permutar canales, y rellena abajo/derecha con 114 en vez
        de centrar la imagen. Normalizar la entrada no da un error: da cero
        detecciones, que es mucho más caro de diagnosticar.
        """
        ih, iw = self.cfg.input_size
        h, w = frame.shape[:2]
        r = min(ih / h, iw / w)
        nh, nw = int(round(h * r)), int(round(w * r))
        canvas = np.full((ih, iw, 3), 114, np.uint8)
        canvas[:nh, :nw] = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_LINEAR)
        x = canvas.astype(np.float32).transpose(2, 0, 1)[None]
        return np.ascontiguousarray(x), r

    def _grid(self, ih: int, iw: int) -> tuple[np.ndarray, np.ndarray]:
        """Rejilla de anclas cacheada por tamaño de entrada.

        Los exports ONNX de YOLOX emiten **desplazamientos sin decodificar**
        respecto a la celda, no coordenadas de imagen. Sin reconstruir la
        rejilla, las cajas salen amontonadas en la esquina superior izquierda
        con tamaños de un píxel --- un fallo que parece de umbral y no lo es.
        """
        key = (ih, iw)
        if key in self._grids:
            return self._grids[key]
        grids, strides = [], []
        for stride in (8, 16, 32):
            gh, gw = ih // stride, iw // stride
            yv, xv = np.meshgrid(np.arange(gh), np.arange(gw), indexing="ij")
            grids.append(np.stack((xv, yv), 2).reshape(1, -1, 2).astype(np.float32))
            strides.append(np.full((1, gh * gw, 1), stride, np.float32))
        out = (np.concatenate(grids, 1), np.concatenate(strides, 1))
        self._grids[key] = out
        return out

    def __call__(self, frame_idx: int, frame: np.ndarray) -> list[Detection]:
        x, r = self._preprocess(frame)
        raw = self.sess.run(None, {self.iname: x})[0]
        boxes, scores, labels = self._decode(raw, r, frame.shape[1], frame.shape[0])
        if boxes.shape[0] == 0:
            return []
        keep = nms(boxes, scores, labels, self.cfg.nms_iou)
        dets = []
        for i in keep:
            cid = int(labels[i])
            if cid not in self.classes:
                continue
            dets.append(Detection(frame_idx, boxes[i].astype(np.float32),
                                  float(scores[i]), cid))
        return dets

    def _decode(self, raw: np.ndarray, r: float, w: int, h: int):
        ih, iw = self.cfg.input_size
        pred = raw[0].astype(np.float32)          # [N, 85]
        grid, stride = self._grid(ih, iw)
        cxcy = (pred[None, :, 0:2] + grid) * stride
        wh = np.exp(np.clip(pred[None, :, 2:4], -10, 10)) * stride
        cxcy, wh = cxcy[0], wh[0]

        obj = pred[:, 4]
        cls = pred[:, 5:]
        cid = cls.argmax(1)
        scores = obj * cls[np.arange(cls.shape[0]), cid]

        m = scores >= self.cfg.score_thresh
        if not m.any():
            return np.zeros((0, 4), np.float32), np.zeros(0, np.float32), np.zeros(0, np.int64)
        cxcy, wh, scores, cid = cxcy[m], wh[m], scores[m], cid[m]

        boxes = np.empty((cxcy.shape[0], 4), np.float32)
        boxes[:, 0] = cxcy[:, 0] - wh[:, 0] / 2
        boxes[:, 1] = cxcy[:, 1] - wh[:, 1] / 2
        boxes[:, 2] = cxcy[:, 0] + wh[:, 0] / 2
        boxes[:, 3] = cxcy[:, 1] + wh[:, 1] / 2
        boxes /= r                                 # deshacer el letterbox
        boxes[:, 0::2] = boxes[:, 0::2].clip(0, w)
        boxes[:, 1::2] = boxes[:, 1::2].clip(0, h)
        return boxes, scores, cid


class TiledDetector(Detector):
    """Inferencia por teselas solapadas para objetos pequeños.

    Una cámara fija de vigilancia suele ser gran angular o elevada, y ahí el
    letterbox es letal: meter 1920x864 en una entrada de 640 aplica un factor de
    0,33, así que un coche de 40 px pasa a 13 y cae por debajo de lo que
    cualquier detector resuelve. Medido sobre metraje real de una cámara a
    ~50 m de altura: 5 objetos a cuadro completo frente a los ~15 presentes.

    La solución estándar es trocear el cuadro en teselas que se procesan a
    escala nativa y fusionar después. El coste es una inferencia por tesela ---
    lineal en el número de teselas--- y a cambio los objetos llegan al modelo
    con el tamaño que el modelo espera.

    El solape no es opcional: sin él, todo objeto que cruce un borde de tesela se
    parte en dos detecciones parciales o desaparece. Se fija en fracción del
    lado de la tesela para que escale con la resolución.
    """

    def __init__(self, inner: "OnnxDetector", overlap: float = 0.35,
                 tile: int | None = None, min_tile_frac: float = 1.35,
                 nms_iou: float = 0.55, edge_margin: float = 3.0):
        self.inner = inner
        self.overlap = overlap
        self.tile = tile or inner.cfg.input_size[0]
        self.min_tile_frac = min_tile_frac
        self.nms_iou = nms_iou
        # Con solape suficiente, todo objeto cabe entero en alguna tesela, que
        # es lo que permite descartar los trozos sin perder el objeto.
        self.edge_margin = edge_margin
        self.classes = inner.classes
        self._plan: dict = {}

    def _tiles(self, w: int, h: int) -> list[tuple[int, int, int, int]]:
        key = (w, h)
        if key in self._plan:
            return self._plan[key]
        t = self.tile
        # Si el cuadro apenas supera el tamaño de tesela, trocear solo añade
        # coste y detecciones partidas: se procesa entero.
        if w < t * self.min_tile_frac and h < t * self.min_tile_frac:
            self._plan[key] = [(0, 0, w, h)]
            return self._plan[key]
        step = max(1, int(t * (1 - self.overlap)))
        xs = list(range(0, max(1, w - t + 1), step))
        ys = list(range(0, max(1, h - t + 1), step))
        if not xs or xs[-1] + t < w:
            xs.append(max(0, w - t))
        if not ys or ys[-1] + t < h:
            ys.append(max(0, h - t))
        out = [(x, y, min(w, x + t), min(h, y + t))
               for y in sorted(set(ys)) for x in sorted(set(xs))]
        self._plan[key] = out
        return out

    def __call__(self, frame_idx: int, frame: np.ndarray) -> list[Detection]:
        h, w = frame.shape[:2]
        found: list[Detection] = []
        for (x1, y1, x2, y2) in self._tiles(w, h):
            crop = frame[y1:y2, x1:x2]
            for d in self.inner(frame_idx, crop):
                b = d.bbox.copy()
                # Una caja que toca el borde de una tesela interior está
                # truncada por construcción: el modelo solo vio medio objeto.
                # La tesela vecina lo tiene entero, así que descartar el trozo
                # es gratis y evita que gane el NMS con una caja mala.
                #
                # Medido: el 71 % de los objetos perdidos frente a un modelo de
                # referencia estaban junto a un borde de tesela, contra el 40 %
                # de los detectados. No se perdían: se detectaban con la caja
                # cortada, y esa caja no casa con el objeto.
                if self._truncated(b, x1, y1, x2, y2, w, h):
                    continue
                b[0] += x1; b[2] += x1
                b[1] += y1; b[3] += y1
                found.append(Detection(frame_idx, b, d.score, d.class_id))
        if not found:
            return []
        # NMS global: los solapes producen la misma detección desde dos teselas,
        # y sin fusionarlas el tracker vería dos objetos donde hay uno.
        boxes = np.stack([d.bbox for d in found])
        scores = np.array([d.score for d in found], np.float32)
        labels = np.array([d.class_id for d in found], np.int64)
        return [found[i] for i in nms(boxes, scores, labels, self.nms_iou)]

    def _truncated(self, b, tx1, ty1, tx2, ty2, w, h) -> bool:
        """¿La caja toca un borde de tesela que no es borde del cuadro?

        Un margen negativo desactiva la comprobación. Un valor grande la haría
        descartar *todo*, que es lo contrario de desactivarla --- conviene que la
        vía de escape sea explícita y no un número que parezca laxo.
        """
        m = self.edge_margin
        if m < 0:
            return False
        tw, th = tx2 - tx1, ty2 - ty1
        if b[0] <= m and tx1 > 0:
            return True
        if b[1] <= m and ty1 > 0:
            return True
        if b[2] >= tw - m and tx2 < w:
            return True
        if b[3] >= th - m and ty2 < h:
            return True
        return False

    @property
    def n_tiles(self) -> int:
        return len(next(iter(self._plan.values()), []))


def nms(boxes: np.ndarray, scores: np.ndarray, labels: np.ndarray, iou: float) -> list[int]:
    """Class-aware NMS."""
    keep: list[int] = []
    for c in np.unique(labels):
        idx = np.flatnonzero(labels == c)
        idx = idx[np.argsort(-scores[idx])]
        while idx.size:
            i = idx[0]
            keep.append(int(i))
            if idx.size == 1:
                break
            b, rest = boxes[i], boxes[idx[1:]]
            x1 = np.maximum(b[0], rest[:, 0]); y1 = np.maximum(b[1], rest[:, 1])
            x2 = np.minimum(b[2], rest[:, 2]); y2 = np.minimum(b[3], rest[:, 3])
            inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
            a = (b[2] - b[0]) * (b[3] - b[1])
            ar = (rest[:, 2] - rest[:, 0]) * (rest[:, 3] - rest[:, 1])
            o = inter / np.maximum(1e-6, a + ar - inter)
            idx = idx[1:][o < iou]
    return keep


# --------------------------------------------------------------------------- #
#  Enrichment
# --------------------------------------------------------------------------- #


class OnnxEmbedder:
    """Appearance embedding (OSNet / CLIP image tower) over object crops."""

    def __init__(self, model_path: str, size: tuple[int, int] = (128, 256),
                 providers=("CUDAExecutionProvider", "CPUExecutionProvider")):
        if ort is None:
            raise RuntimeError("onnxruntime is required")
        self.sess = ort.InferenceSession(model_path, providers=list(providers))
        self.iname = self.sess.get_inputs()[0].name
        self.size = size

    def __call__(self, crops: list[np.ndarray]) -> np.ndarray:
        if not crops:
            return np.zeros((0, 512), np.float32)
        w, h = self.size
        batch = np.stack([cv2.resize(c, (w, h)) for c in crops])
        x = (batch[:, :, :, ::-1].astype(np.float32) / 255.0 - 0.45) / 0.225
        x = np.ascontiguousarray(x.transpose(0, 3, 1, 2))
        e = self.sess.run(None, {self.iname: x})[0]
        return e / np.maximum(1e-9, np.linalg.norm(e, axis=1, keepdims=True))


def dominant_colour(crop: np.ndarray, mask: np.ndarray | None = None) -> tuple[str, np.ndarray]:
    """Perceptual colour label from the silhouette interior.

    Two things matter and are usually got wrong. First, the background inside
    the bounding box has to be excluded or every object on tarmac reads grey ---
    hence the mask. Second, the comparison happens in CIELab, because nearest
    neighbour in RGB does not match what an operator means by "red".
    """
    if cv2 is None or crop.size == 0:
        return "unknown", np.zeros(3, np.float32)
    px = crop.reshape(-1, 3)
    if mask is not None and mask.size:
        m = cv2.resize(mask.astype(np.uint8), (crop.shape[1], crop.shape[0]),
                       interpolation=cv2.INTER_NEAREST).astype(bool)
        # Erode so silhouette edges (half background) do not pollute the average.
        m = cv2.erode(m.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
        if m.sum() > 20:
            px = crop[m]
    lab = cv2.cvtColor(px.reshape(-1, 1, 3), cv2.COLOR_BGR2LAB).reshape(-1, 3).astype(np.float32)
    lab[:, 0] *= 100 / 255.0
    lab[:, 1:] -= 128
    med = np.median(lab, axis=0)

    refs = {
        "black": (12, 0, 0), "white": (95, 0, 0), "grey": (55, 0, 0),
        "silver": (75, 0, 0), "red": (48, 68, 50), "orange": (65, 40, 62),
        "yellow": (88, -5, 82), "green": (52, -55, 42), "blue": (42, 18, -62),
        "purple": (40, 55, -45), "brown": (36, 22, 30),
    }
    best, bd = "unknown", 1e9
    for name, ref in refs.items():
        d = float(np.linalg.norm(med - np.array(ref, np.float32)))
        if d < bd:
            best, bd = name, d
    return best, med
