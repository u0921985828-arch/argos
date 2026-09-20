"""Análisis de proporciones: descriptores de forma y coherencia con la escena.

Dos preguntas distintas, y las dos se responden desde la silueta:

**¿Qué forma tiene esto?** La proporción de la caja es el descriptor más pobre
posible: un coche visto de frente desde arriba es casi cuadrado, igual que un
grupo de tres peatones juntos. La silueta permite medir *solidez*, *extensión* y
*elongación*, que separan lo que la proporción confunde --- un vehículo es
convexo y llena su caja; un peatón es delgado y sólido; un grupo es irregular y
deja huecos.

**¿Es coherente con dónde está?** En un plano de suelo, el tamaño de un objeto
en la imagen es función de su posición vertical: cuanto más abajo, más cerca,
más grande. Esa relación es aprendible de la propia escena sin calibrar nada.
Un objeto que la viola no está en el suelo --- es una antena, un reflejo, un
cartel, una estatua en una cornisa --- y ese es un discriminador de falsos
positivos que ningún umbral de confianza proporciona, porque el detector está
*acertando*: la estatua sí parece una persona.

El modelo de perspectiva se ajusta con regresión robusta sobre las medianas de
los tubos, no sobre observaciones sueltas. Un tubo aporta un punto; si no,
un solo objeto largo domina el ajuste y la escena queda descrita por él.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

try:
    import cv2
except ImportError:                                  # pragma: no cover
    cv2 = None

from ..core.types import Tube


# --------------------------------------------------------------------------- #
#  Descriptores de forma
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Shape:
    """Descriptores invariantes a escala de una silueta."""

    aspect: float        # alto / ancho de la caja
    extent: float        # área de silueta / área de caja
    solidity: float      # área de silueta / área de su envolvente convexa
    elongation: float    # razón de ejes principales (momentos de segundo orden)
    orientation: float   # grados del eje mayor respecto a la horizontal
    n_parts: int         # componentes tras erosión: separa grupos de individuos

    def as_dict(self) -> dict:
        return {k: (round(getattr(self, k), 4) if isinstance(getattr(self, k), float)
                    else getattr(self, k)) for k in self.__slots__}


def describe(mask: np.ndarray) -> Shape | None:
    """Descriptores de una máscara booleana."""
    if cv2 is None or mask is None or mask.size == 0:
        return None
    m = mask.astype(np.uint8)
    area = float(m.sum())
    h, w = m.shape
    if area < 12 or w < 3 or h < 3:
        return None

    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea)
    hull = cv2.convexHull(c)
    hull_area = float(cv2.contourArea(hull)) or area

    # Momentos centrales de segundo orden -> ejes principales. Es más estable
    # que ajustar una elipse cuando la silueta es pequeña o tiene el borde
    # dentado, que es el caso normal a esta escala.
    mu = cv2.moments(m, binaryImage=True)
    if mu["m00"] <= 0:
        return None
    a = mu["mu20"] / mu["m00"]
    b = mu["mu11"] / mu["m00"]
    d = mu["mu02"] / mu["m00"]
    common = np.sqrt(max(0.0, (a - d) ** 2 + 4 * b * b))
    l1, l2 = (a + d + common) / 2, (a + d - common) / 2
    elong = float(np.sqrt(max(l1, 1e-9) / max(l2, 1e-9)))
    theta = float(np.degrees(0.5 * np.arctan2(2 * b, a - d)))

    # Erosión proporcional al tamaño: dos peatones pegados comparten borde pero
    # se separan al erosionar, mientras un vehículo sigue siendo una pieza.
    k = max(1, int(round(min(w, h) * 0.14)))
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k * 2 + 1,) * 2)
    eroded = cv2.erode(m, kern)
    n_parts, _, stats, _ = cv2.connectedComponentsWithStats(eroded, 8)
    parts = int(sum(1 for i in range(1, n_parts)
                    if stats[i, cv2.CC_STAT_AREA] > 0.06 * area))

    return Shape(aspect=h / max(1.0, w),
                 extent=area / (w * h),
                 solidity=area / hull_area,
                 elongation=elong,
                 orientation=theta,
                 n_parts=max(1, parts))


def tube_shape(tube: Tube, samples: int = 9) -> Shape | None:
    """Forma representativa de un tubo, por mediana sobre varias observaciones.

    Una sola silueta puede estar recortada por una oclusión o partida por una
    sombra. La mediana sobre el tubo es lo que hace utilizable el descriptor.
    """
    frames = [f for f in tube.frames if f.mask_blob]
    if not frames:
        return None
    idx = np.unique(np.linspace(0, len(frames) - 1, samples).astype(int))
    shapes = [s for s in (describe(frames[i].mask()) for i in idx) if s]
    if not shapes:
        return None
    med = lambda key: float(np.median([getattr(s, key) for s in shapes]))
    return Shape(aspect=med("aspect"), extent=med("extent"),
                 solidity=med("solidity"), elongation=med("elongation"),
                 orientation=med("orientation"),
                 n_parts=int(np.median([s.n_parts for s in shapes])))


# --------------------------------------------------------------------------- #
#  Coherencia con la perspectiva
# --------------------------------------------------------------------------- #


@dataclass
class PerspectiveModel:
    """Altura esperada en píxeles en función de la fila de contacto con el suelo.

    Se ajusta ``altura = a * y + b`` porque bajo proyección de un plano sobre
    otro esa relación es lineal, no cuadrática ni exponencial. Ajustar algo más
    flexible parece más preciso y en realidad absorbe los mismos valores atípicos
    que se quieren detectar.
    """

    a: float = 0.0
    b: float = 0.0
    sigma: float = 0.0
    n: int = 0
    valid: bool = False
    # Factor por clase. Un autobús mide el doble que un turismo a la misma
    # distancia, así que un modelo único de altura-frente-a-fila marca como
    # anómalo todo autobús real: medido, salían a z entre +4,6 y +6,3 mientras
    # la estatua salía a +30. Separables, pero solo con un umbral afinado a
    # mano, que es justo lo que hay que evitar.
    #
    # Un factor multiplicativo por clase, estimado de los propios datos, cuesta
    # un parámetro por clase y elimina la confusión: la geometría de la escena
    # es común, la estatura típica del objeto no.
    class_scale: dict = field(default_factory=dict)

    def expected(self, base_y: float, class_name: str = "") -> float:
        return (self.a * base_y + self.b) * self.class_scale.get(class_name, 1.0)

    def residual_z(self, base_y: float, height_px: float,
                   class_name: str = "") -> float:
        if not self.valid or self.sigma <= 0:
            return 0.0
        k = self.class_scale.get(class_name, 1.0)
        # La dispersión escala con el tamaño esperado: comparar un autobús
        # contra la sigma de los turismos lo penalizaría por ser grande.
        return (height_px - self.expected(base_y, class_name)) / (self.sigma * k)


def fit_perspective(tubes: list[Tube], iterations: int = 5,
                    min_tubes: int = 8,
                    min_class_samples: int = 5) -> PerspectiveModel:
    """Ajuste robusto por mínimos cuadrados reponderados.

    Un ajuste ordinario se lo comen los propios atípicos: la estatua, el cartel
    y el reflejo tiran de la recta hasta que dejan de parecer anómalos. Se
    reajusta descartando iterativamente lo que queda lejos.
    """
    pts, kept = [], []
    for t in tubes:
        b = t.bboxes()
        if len(b) < 4:
            continue
        # Mediana sobre el tubo: un punto por objeto, no por observación.
        pts.append((float(np.median(b[:, 3])),
                    float(np.median(b[:, 3] - b[:, 1]))))
        kept.append(t)
    tubes = kept
    if len(pts) < min_tubes:
        return PerspectiveModel()

    y = np.array([p[0] for p in pts], np.float64)
    hgt = np.array([p[1] for p in pts], np.float64)
    keep = np.ones(len(y), bool)

    a = b0 = 0.0
    for _ in range(iterations):
        if keep.sum() < 4:
            break
        a, b0 = np.polyfit(y[keep], hgt[keep], 1)
        resid = hgt - (a * y + b0)
        s = 1.4826 * np.median(np.abs(resid[keep] - np.median(resid[keep])))
        if s <= 1e-6:
            break
        new = np.abs(resid) < 2.5 * s
        if new.sum() == keep.sum() and (new == keep).all():
            break
        keep = new

    resid = hgt[keep] - (a * y[keep] + b0)
    sigma = float(np.std(resid)) if keep.sum() > 2 else 0.0
    model = PerspectiveModel(a=float(a), b=float(b0), sigma=max(sigma, 1e-3),
                             n=int(keep.sum()),
                             valid=bool(keep.sum() >= min_tubes and sigma > 0))

    # Factor por clase: mediana de la razón observada/esperada, y solo cuando
    # hay muestras suficientes. Con dos ejemplares el factor lo fija el ruido.
    by_class: dict[str, list[float]] = {}
    for t, (yy, hh) in zip(tubes, pts):
        base = model.a * yy + model.b
        if base > 1.0:
            by_class.setdefault(t.class_name, []).append(hh / base)
    for name, ratios in by_class.items():
        if len(ratios) >= min_class_samples:
            model.class_scale[name] = float(np.median(ratios))
    return model


# --------------------------------------------------------------------------- #
#  Veredicto por tubo
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class Verdict:
    tube_id: int
    shape: Shape | None
    perspective_z: float
    flags: list = field(default_factory=list)
    suggested_class: str = ""
    confidence: str = "media"

    def as_dict(self) -> dict:
        return {"tube": self.tube_id,
                "shape": self.shape.as_dict() if self.shape else None,
                "perspective_z": round(self.perspective_z, 2),
                "flags": self.flags,
                "suggested_class": self.suggested_class,
                "confidence": self.confidence}


def classify_shape(s: Shape) -> tuple[str, str]:
    """Clase sugerida a partir de la forma, no de la proporción.

    Los umbrales son deliberadamente conservadores: cuando la forma no separa,
    se devuelve ``indeterminado`` en lugar de inventar una clase. Una etiqueta
    equivocada se propaga a búsquedas y recuentos; una ausente solo cuesta una
    consulta más amplia.
    """
    if s.n_parts >= 2 and s.solidity < 0.86:
        return "grupo", "baja"
    if s.aspect >= 1.7 and s.elongation >= 1.8 and s.extent >= 0.34:
        return "persona", "media"
    if s.aspect <= 0.85 and s.solidity >= 0.88 and s.extent >= 0.55:
        return "vehiculo", "media"
    if s.solidity >= 0.9 and s.extent >= 0.7:
        return "vehiculo", "baja"
    return "indeterminado", "baja"


def analyse(tubes: list[Tube], model: PerspectiveModel | None = None,
            z_flag: float = 3.0) -> list[Verdict]:
    model = model or fit_perspective(tubes)
    out = []
    for t in tubes:
        sh = tube_shape(t)
        b = t.bboxes()
        base_y = float(np.median(b[:, 3]))
        h_px = float(np.median(b[:, 3] - b[:, 1]))
        z = model.residual_z(base_y, h_px, t.class_name)

        flags = []
        if model.valid and abs(z) > z_flag:
            # No está sobre el plano de suelo que describe el resto de la escena.
            flags.append("fuera_de_perspectiva")
        if sh is None:
            flags.append("sin_silueta")
        elif sh.extent > 0.97:
            # La máscara es la caja entera: normalmente significa que la placa
            # de fondo no ha convergido ahí, no que el objeto sea rectangular.
            flags.append("silueta_no_fiable")
        if t.attributes.get("static"):
            flags.append("inmovil")

        cls, conf = classify_shape(sh) if sh else ("indeterminado", "baja")
        # Dos señales independientes que coinciden: inmóvil y fuera del plano de
        # suelo. Eso ya no es un objeto dudoso, es decorado.
        if "inmovil" in flags and "fuera_de_perspectiva" in flags:
            flags.append("decorado_probable")
            conf = "alta"
        out.append(Verdict(t.tube_id, sh, z, flags, cls, conf))
    return out
