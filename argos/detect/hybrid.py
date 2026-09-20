"""Detector híbrido: cajas de red neuronal, siluetas de sustracción de fondo.

Ninguno de los dos detectores que hay en el sistema gana por sí solo. Medido
sobre 30 s de una cámara fija a ~50 m de altura:

    MOG2              53 tubos, clases por proporción, pierde lo que se para,
                      pero da siluetas gratis y cuesta 25 ms
    YOLOX teselado    87 tubos, clases reales, ve lo parado,
                      pero no da máscaras y cuesta 1.300 ms

Este módulo los combina, y la combinación arregla las tres carencias a la vez:

*   **Siluetas.** El detector da la caja; el fondo da la forma dentro de ella.
    Recupera la capa de siluetas —que es a la vez el control de privacidad y la
    ventaja de densidad del optimizador— sin pagar un modelo de segmentación.
*   **Velocidad.** La máscara de movimiento no solo sirve para recortar: sirve
    para *decidir dónde mirar*. En una vista fija, la mayor parte del cuadro es
    edificio y cielo, y teselar uniformemente gasta el 70-80 % del cómputo en
    zonas donde nunca pasa nada. Solo se infieren las teselas con movimiento.
*   **Objetos parados.** El barrido completo periódico los recupera, y la placa
    lenta permite seguir extrayendo su silueta mucho después de que la
    sustracción rápida los haya absorbido.

Las dos escalas de fondo son deliberadas y hacen cosas distintas:

    rápida (MOG2, alpha alto)   ¿dónde está pasando algo *ahora*? -> atención
    lenta (media exponencial)   ¿cómo es la escena vacía? -> siluetas

Con una sola no se puede tener ambas: una que se adapta rápido absorbe al objeto
parado en segundos y deja de recortarlo; una que se adapta despacio marca como
movimiento cualquier cambio de luz durante minutos.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from ..core.types import Detection
from .onnx_backend import COCO_KEEP, Detector, nms


@dataclass
class HybridConfig:
    # --- atención ---------------------------------------------------- #
    motion_width: int = 480          # ancho del modelo rápido
    motion_var: float = 26.0
    tile_motion_frac: float = 0.0004  # fracción de tesela en movimiento para mirarla
    full_sweep_every: int = 10       # barrido completo cada N frames
    hint_margin: float = 0.35        # margen alrededor de un objeto ya seguido
    dilate_tiles: bool = True        # incluir teselas vecinas a una con movimiento

    # --- siluetas ------------------------------------------------------ #
    plate_alpha: float = 0.0012      # placa lenta: minutos, no segundos
    sil_threshold: float = 14.0      # diferencia mínima con la placa
    plate_guard: float = 0.12        # margen de protección alrededor de una caja
    # Refinar el borde adelgaza la máscara: los umbrales de relleno calibrados
    # para una máscara tosca rechazaban siluetas correctas. Medido: el
    # rendimiento cayó del 68 % al 31 % solo por no reajustarlos.
    sil_min_fill: float = 0.05       # por debajo, la máscara no es fiable
    sil_max_fill: float = 0.995      # por encima, la máscara es la caja: inútil
    sil_target_px: int = 96          # lado deseado tras ampliar
    sil_max_up: int = 4              # tope de ampliación
    sil_morph_frac: float = 0.03     # kernel proporcional, no fijo
    sil_morph_frac_base: float = 0.08  # limpieza de la máscara base
    sil_keep_frac: float = 0.45      # área mínima a conservar tras refinar
    # Punto de operación elegido por barrido, no a ojo. El codo está en `k`
    # --- el suelo de ruido--- no en el ratio de señal:
    #
    #     k=1.2  siluetas 32,6 %   IoU min 0,64   centroide p90 0,219
    #     k=1.5  siluetas 31,5 %   IoU min 0,64   centroide p90 0,144
    #     k=2.2  siluetas 28,7 %   IoU min 0,93   centroide p90 0,068
    #
    # Tres puntos de rendimiento compran que ninguna silueta superviviente esté
    # descolocada. Es el intercambio correcto: una máscara mal puesta contamina
    # forma, proporciones y clase, mientras que una ausente solo es un objeto
    # sin contorno.
    sil_min_snr: float = 1.5         # señal/ruido mínima dentro de la máscara
    sil_snr_k: float = 2.2           # desviaciones sobre el suelo de ruido
    sil_guide_frac: float = 0.09     # radio del filtro guiado
    sil_guide_eps: float = 90.0

    # --- fusión --------------------------------------------------------- #
    nms_iou: float = 0.55
    detect_every: int = 1            # inferir cada N frames (el resto, predicho)


class HybridDetector(Detector):
    """Nota sobre cobertura: el plan de teselas cubre el **100 %** del cuadro
    (verificado: 39 % con una tesela, 48 % con dos, 13 % con tres o más). Las
    regiones sin detecciones son tejados, cielo y fachadas, no zonas ciegas."""

    def __init__(self, inner, cfg: HybridConfig | None = None,
                 tile: int | None = None, overlap: float = 0.35,
                 edge_margin: float = 3.0):
        self.inner = inner
        self.cfg = cfg or HybridConfig()
        self.tile = tile or inner.cfg.input_size[0]
        self.overlap = overlap
        self.classes = getattr(inner, "classes", COCO_KEEP)

        self.sub = cv2.createBackgroundSubtractorMOG2(
            history=500, varThreshold=self.cfg.motion_var, detectShadows=False)
        self.plate: np.ndarray | None = None      # float32, escena vacía
        self.n = 0
        self._plan: dict = {}
        self._hints: list = []
        self.stats = {"tiles_total": 0, "tiles_run": 0, "sweeps": 0,
                      "masks": 0, "mask_fallback": 0}

    def set_hints(self, boxes) -> None:
        """Cajas de los objetos vivos del tracker, para la próxima llamada.

        Se pasa por método y no por argumento obligatorio para que el detector
        siga cumpliendo la interfaz `Detector` y pueda usarse sin tracker.
        """
        self._hints = [tuple(float(v) for v in b) for b in boxes]

    # ------------------------------------------------------------------ #
    #  Planificación de teselas
    # ------------------------------------------------------------------ #

    def _tiles(self, w: int, h: int) -> list[tuple[int, int, int, int]]:
        key = (w, h)
        if key in self._plan:
            return self._plan[key]
        t = self.tile
        if w <= t and h <= t:
            self._plan[key] = [(0, 0, w, h)]
            return self._plan[key]
        step = max(1, int(t * (1 - self.overlap)))
        xs = list(range(0, max(1, w - t + 1), step))
        ys = list(range(0, max(1, h - t + 1), step))
        if xs[-1] + t < w:
            xs.append(max(0, w - t))
        if ys[-1] + t < h:
            ys.append(max(0, h - t))
        self._plan[key] = [(x, y, min(w, x + t), min(h, y + t))
                           for y in sorted(set(ys)) for x in sorted(set(xs))]
        return self._plan[key]

    def _motion(self, frame: np.ndarray) -> np.ndarray:
        w = self.cfg.motion_width
        h = max(2, int(round(frame.shape[0] * w / frame.shape[1])))
        small = cv2.resize(frame, (w, h), interpolation=cv2.INTER_AREA)
        m = (self.sub.apply(small) > 200).astype(np.uint8)
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
        return cv2.dilate(cv2.morphologyEx(m, cv2.MORPH_OPEN, k), k, iterations=2)

    def _active_tiles(self, tiles, motion, w, h, hints=None):
        """Teselas donde mirar.

        Dos fuentes de atención, y hacen falta las dos. El movimiento encuentra
        lo que entra en escena; las **pistas del tracker** mantienen lo que ya
        está siendo seguido aunque se haya detenido. Solo con movimiento, un
        coche parado en un semáforo desaparece del detector y su tubo se rompe:
        medido, la atención por movimiento sola recuperaba el 60 % de los
        objetos del teselado uniforme.
        """
        mh, mw = motion.shape
        kx, ky = mw / w, mh / h
        idx = []
        for i, (x1, y1, x2, y2) in enumerate(tiles):
            sub = motion[int(y1 * ky):max(int(y1 * ky) + 1, int(y2 * ky)),
                         int(x1 * kx):max(int(x1 * kx) + 1, int(x2 * kx))]
            if sub.size and sub.mean() >= self.cfg.tile_motion_frac:
                idx.append(i)
                continue
            if hints is not None and self._tile_has_hint(tiles[i], hints):
                idx.append(i)
        if self.cfg.dilate_tiles and idx:
            # Un objeto que entra por el borde de una tesela activa aún no ha
            # generado movimiento en la vecina, pero puede estar mayormente
            # dentro de ella. Incluir los vecinos cuesta poco y evita perder el
            # objeto justo en la transición.
            grow = set()
            for i in idx:
                grow.update((i - 1, i, i + 1))
            idx = sorted(j for j in grow if 0 <= j < len(tiles))
        return [tiles[i] for i in idx]

    def _tile_has_hint(self, tile, hints) -> bool:
        tx1, ty1, tx2, ty2 = tile
        m = self.cfg.hint_margin
        for (hx1, hy1, hx2, hy2) in hints:
            gw, gh = (hx2 - hx1) * m, (hy2 - hy1) * m
            if not (hx2 + gw <= tx1 or hx1 - gw >= tx2
                    or hy2 + gh <= ty1 or hy1 - gh >= ty2):
                return True
        return False

    # ------------------------------------------------------------------ #
    #  Placa lenta y siluetas
    # ------------------------------------------------------------------ #

    def _update_plate(self, frame: np.ndarray, motion: np.ndarray) -> None:
        """Actualiza la escena vacía, protegiéndola de los objetos.

        Congelar la placa solo donde hay *movimiento* parece razonable y está
        exactamente al revés para lo que hace falta: un coche detenido en un
        semáforo deja de generar movimiento, la placa se actualiza sobre él, y
        el coche queda impreso en el fondo. A partir de ahí su diferencia con la
        placa es cero y deja de tener silueta.

        Medido antes de corregirlo: las cajas grandes —las de los vehículos
        detenidos, más cercanos— daban un relleno mediano de 0,00 mientras las
        pequeñas daban 0,84.

        La corrección aprovecha lo que un detector sabe y una sustracción de
        fondo no: **dónde hay un objeto**, se mueva o no. Las cajas del frame
        anterior protegen la placa, y así un vehículo puede estar parado
        indefinidamente sin ser absorbido.
        """
        f = frame.astype(np.float32)
        if self.plate is None:
            self.plate = f.copy()
            return
        a = 0.15 if self.n < 30 else self.cfg.plate_alpha

        protect = cv2.resize(motion, (frame.shape[1], frame.shape[0]),
                             interpolation=cv2.INTER_NEAREST).astype(bool)
        if self._hints:
            g = self.cfg.plate_guard
            for (x1, y1, x2, y2) in self._hints:
                mx, my = (x2 - x1) * g, (y2 - y1) * g
                a1 = max(0, int(x1 - mx)); b1 = max(0, int(y1 - my))
                a2 = min(frame.shape[1], int(x2 + mx))
                b2 = min(frame.shape[0], int(y2 + my))
                if a2 > a1 and b2 > b1:
                    protect[b1:b2, a1:a2] = True

        upd = (1 - a) * self.plate + a * f
        self.plate = np.where(protect[:, :, None], self.plate, upd).astype(np.float32)

    def _silhouette(self, frame: np.ndarray, box: np.ndarray) -> np.ndarray | None:
        """Máscara del objeto dentro de su caja, refinada contra los bordes.

        Un umbral duro sobre la diferencia con la placa da una máscara con el
        borde en la rejilla de píxeles, y a estos tamaños --- la caja mediana en
        una vista aérea mide 27x26 px --- eso se ve como un bloque. Peor aún: la
        morfología de limpieza usaba un kernel fijo de 7x7, que sobre 27 px de
        ancho es el 26 % del objeto. No suavizaba el contorno, lo borraba.

        Tres cambios, en este orden:

        1.  **Supermuestreo.** El recorte se amplía antes de segmentar, así el
            borde puede caer entre píxeles del original. Es la única forma de
            obtener precisión subpíxel sin resolver un problema de matting.
        2.  **Alfa suave con dos umbrales** en vez de uno duro. La diferencia
            con el fondo no es binaria y tratarla como tal descarta justo la
            información de borde.
        3.  **Filtro guiado por la imagen.** Suaviza el alfa respetando los
            bordes reales del recorte, de modo que el contorno se ajusta al
            objeto en lugar de a la forma que dejó el umbral. Es lo que
            convierte un bloque en una silueta.

        La morfología final usa kernel **proporcional al objeto**, no fijo.
        """
        if self.plate is None:
            return None
        cfg = self.cfg
        x1, y1, x2, y2 = [int(round(v)) for v in box]
        x1, y1 = max(0, x1), max(0, y1)
        x2 = min(frame.shape[1], x2)
        y2 = min(frame.shape[0], y2)
        bw, bh = x2 - x1, y2 - y1
        if bw < 4 or bh < 4:
            return None

        # Factor de ampliación: más agresivo cuanto más pequeño el objeto, con
        # tope para no disparar el coste en primeros planos.
        up = int(np.clip(round(cfg.sil_target_px / max(bw, bh)), 1, cfg.sil_max_up))
        sw, sh = bw * up, bh * up

        crop = frame[y1:y2, x1:x2]
        ref = self.plate[y1:y2, x1:x2]
        if up > 1:
            crop_u = cv2.resize(crop, (sw, sh), interpolation=cv2.INTER_CUBIC)
            ref_u = cv2.resize(ref, (sw, sh), interpolation=cv2.INTER_CUBIC)
        else:
            crop_u, ref_u = crop, ref.astype(np.float32)

        # --- 1. máscara base, a resolución nativa --------------------- #
        #
        # Quién existe lo decide el umbral; el refinado solo mueve el borde.
        # Al revés no funciona: en una caja fina casi todo es borde, el filtro
        # guiado arrastra el alfa hacia la media local y el objeto desaparece
        # entero. Medido con el refinado decidiendo: el rendimiento cayó del
        # 68 % al 38 %.
        d0 = np.abs(crop.astype(np.float32) - ref).max(axis=2)
        base = (d0 > cfg.sil_threshold).astype(np.uint8)
        kb = max(1, int(round(min(bw, bh) * cfg.sil_morph_frac_base)))
        kern_b = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kb * 2 + 1,) * 2)
        base = cv2.morphologyEx(base, cv2.MORPH_CLOSE, kern_b)
        base = cv2.morphologyEx(base, cv2.MORPH_OPEN, kern_b)
        if base.sum() < 4:
            self._reject = "vacia"
            self.stats["mask_fallback"] += 1
            return None

        # --- 2. refinado del borde, a resolución ampliada -------------- #
        if up > 1:
            alpha = cv2.resize(base.astype(np.float32), (sw, sh),
                               interpolation=cv2.INTER_LINEAR)
        else:
            alpha = base.astype(np.float32)

        guide = crop_u if crop_u.dtype == np.uint8 else crop_u.astype(np.uint8)
        r = max(2, int(round(min(sw, sh) * cfg.sil_guide_frac)))
        try:
            refined = cv2.ximgproc.guidedFilter(guide, alpha, r, cfg.sil_guide_eps)
        except Exception:
            refined = cv2.bilateralFilter(alpha, max(3, r | 1), 0.15, r)

        m = (refined > 0.5).astype(np.uint8)
        base_up = (cv2.resize(base, (sw, sh), interpolation=cv2.INTER_NEAREST)
                   if up > 1 else base)
        # Red de seguridad: si el refinado se ha comido el objeto, se conserva
        # la base suavizada. Un contorno algo más tosco es infinitamente mejor
        # que ningún objeto.
        if m.sum() < cfg.sil_keep_frac * max(1, base_up.sum()):
            m = cv2.GaussianBlur(base_up.astype(np.float32) * 255, (0, 0),
                                 max(0.8, min(sw, sh) * 0.02))
            m = (m > 120).astype(np.uint8)
            self.stats["mask_refine_fallback"] = self.stats.get(
                "mask_refine_fallback", 0) + 1

        k = max(1, int(round(min(sw, sh) * cfg.sil_morph_frac)))
        kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k * 2 + 1,) * 2)
        m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, kern)

        n, lab, stats, _ = cv2.connectedComponentsWithStats(m, 8)
        self._reject = None
        if n > 2:
            # La caja de un detector suele rozar objetos vecinos, y una máscara
            # con dos manchas produce un contorno que salta entre ellas.
            big = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
            m = (lab == big).astype(np.uint8)
        elif n <= 1:
            self._reject = "vacia"
            self.stats["mask_fallback"] += 1
            return None

        # --- 3. compuerta de señal ------------------------------------ #
        #
        # Un umbral sobre la diferencia produce *algo* siempre, incluso cuando
        # la diferencia es ruido: la morfología agrupa píxeles dispersos en
        # manchas que después pasan cualquier comprobación de relleno. Es lo que
        # ocurre con el decorado --- estatuas, fachadas --- que forma parte de la
        # placa y por tanto no difiere de ella.
        #
        # Medido sobre objetos reales frente a decorado detectado:
        #
        #     vehículos    SNR 1,7-2,8   IoU base/refinado 0,96-0,97   0,3 px
        #     decorado     SNR 1,3-1,4   IoU 0,40-0,68                 15-32 px
        #
        # La separación es limpia y el desplazamiento no era un fallo aparte:
        # era el síntoma de refinar una base que no contenía objeto.
        m_small = (cv2.resize(m, (bw, bh), interpolation=cv2.INTER_NEAREST).astype(bool)
                   if up > 1 else m.astype(bool))
        inside = d0[m_small]
        outside = d0[~m_small]
        if inside.size < 4 or outside.size < 4:
            self._reject = "sin_contraste"
            self.stats["mask_fallback"] += 1
            return None
        # Suelo de ruido robusto, estimado fuera de la propia máscara.
        med_out = float(np.median(outside))
        mad_out = float(np.median(np.abs(outside - med_out)))
        floor = med_out + cfg.sil_snr_k * 1.4826 * mad_out
        signal = float(np.median(inside))
        if signal < max(cfg.sil_threshold, cfg.sil_min_snr * max(1.0, med_out), floor):
            self._reject = "sin_senal"
            self.stats["mask_no_signal"] = self.stats.get("mask_no_signal", 0) + 1
            self.stats["mask_fallback"] += 1
            return None

        fill = float(m.mean())
        if fill < cfg.sil_min_fill:
            self._reject = "poco_relleno"
            self.stats["mask_fallback"] += 1
            return None
        if fill > cfg.sil_max_fill:
            self._reject = "caja_entera"
            self.stats["mask_fallback"] += 1
            return None
        self.stats["masks"] += 1
        # Se devuelve a resolución ampliada: la máscara se guarda relativa a su
        # caja, así que conservar el supermuestreo cuesta unos bytes tras la
        # compresión y da un contorno mucho más limpio al vectorizar.
        return m.astype(bool)

    # ------------------------------------------------------------------ #

    def __call__(self, frame_idx: int, frame: np.ndarray,
                 hints: list | None = None) -> list[Detection]:
        cfg = self.cfg
        h, w = frame.shape[:2]
        motion = self._motion(frame)
        self._update_plate(frame, motion)
        self.n += 1

        tiles = self._tiles(w, h)
        sweep = (self.n % cfg.full_sweep_every == 0) or self.n <= 2
        run = tiles if sweep else self._active_tiles(tiles, motion, w, h,
                                                     hints if hints else self._hints)
        if sweep:
            self.stats["sweeps"] += 1
        self.stats["tiles_total"] += len(tiles)
        self.stats["tiles_run"] += len(run)

        found: list[Detection] = []
        for (x1, y1, x2, y2) in run:
            tw, th = x2 - x1, y2 - y1
            for d in self.inner(frame_idx, frame[y1:y2, x1:x2]):
                b = d.bbox.copy()
                m = self.edge_margin
                # Trozo truncado en una tesela interior: la vecina lo tiene entero.
                if ((b[0] <= m and x1 > 0) or (b[1] <= m and y1 > 0)
                        or (b[2] >= tw - m and x2 < w) or (b[3] >= th - m and y2 < h)):
                    continue
                b[0] += x1; b[2] += x1
                b[1] += y1; b[3] += y1
                found.append(Detection(frame_idx, b, d.score, d.class_id))
        if not found:
            return []

        boxes = np.stack([d.bbox for d in found])
        scores = np.array([d.score for d in found], np.float32)
        labels = np.array([d.class_id for d in found], np.int64)
        keep = [found[i] for i in nms(boxes, scores, labels, cfg.nms_iou)]

        for d in keep:
            d.mask = self._silhouette(frame, d.bbox)
        # Las cajas de este frame sirven de pista para el siguiente aunque nadie
        # llame a set_hints: mantiene la atención sobre lo ya visto.
        self._hints = [tuple(float(v) for v in d.bbox) for d in keep]
        return keep

    # ------------------------------------------------------------------ #

    def report(self) -> dict:
        t = self.stats
        saved = 1 - t["tiles_run"] / max(1, t["tiles_total"])
        total_masks = t["masks"] + t["mask_fallback"]
        return {
            "teselas_evitadas": round(saved * 100, 1),
            "teselas_por_frame": round(t["tiles_run"] / max(1, self.n), 1),
            "barridos": t["sweeps"],
            "siluetas_ok": round(100 * t["masks"] / max(1, total_masks), 1),
            "rechazo_sin_senal": t.get("mask_no_signal", 0),
        }
