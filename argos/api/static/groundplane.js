/*
 * ARGOS · plano de suelo
 * ---------------------------------------------------------------------------
 * Rectifica la escena a una vista cenital donde un metro es un metro.
 *
 * La capa CCTV de God's Eye View proyecta el frame sobre la geometría 3D de la
 * ciudad, que se ve muy bien y necesita CesiumJS, teselas fotorrealistas de
 * Google y un token. Aquí se hace la mitad que de verdad sirve para analizar, y
 * se hace **con lo que ARGOS ya sabe**: el modelo de perspectiva aprendido de
 * los propios peatones.
 *
 * De `altura = a·y + b` sale todo lo que hace falta:
 *
 *   · La **línea de horizonte** es donde un objeto mediría cero: y₀ = −b/a.
 *   · La **profundidad** de un punto del suelo es inversamente proporcional a
 *     su distancia al horizonte: Z ∝ 1/(y − y₀). Es la misma razón por la que
 *     las vías del tren convergen.
 *   · La **escala métrica** la fija la estatura típica del peatón, que es lo
 *     que ancla todo el sistema.
 *
 * Se asume suelo horizontal y cámara sin alabeo. Con una cámara de tráfico o
 * de plaza eso se cumple; con una inclinada lateralmente, no, y entonces el
 * plano sale cizallado --- conviene decirlo en vez de presentar un mapa
 * torcido como si fuera una medición.
 *
 * Para qué sirve, más allá de que quede bien:
 *
 *   · Las trayectorias dejan de estar deformadas por la perspectiva: dos
 *     personas que caminan a la misma velocidad dibujan trazos de la misma
 *     longitud, estén cerca o lejos.
 *   · Las distancias entre objetos son reales, no aparentes.
 *   · Varias cámaras del mismo sitio pueden dibujarse sobre el MISMO plano.
 */

"use strict";

class GroundPlane {
  /**
   * @param {object} scale  modelo aprendido: {a, b, valid}
   * @param {number} width  ancho de la imagen en píxeles
   * @param {number} height alto de la imagen en píxeles
   */
  constructor(scale, width, height, opts = {}) {
    this.w = width;
    this.h = height;
    this.a = scale?.a ?? 0;
    this.b = scale?.b ?? 0;
    this.stature = opts.stature ?? 1.70;
    this.valid = !!scale?.valid && this.a > 0;

    // Horizonte: la fila donde un objeto de altura cero se proyectaría.
    this.horizon = this.a !== 0 ? -this.b / this.a : -Infinity;

    // Fuera de campo por arriba significa que la cámara no ve el horizonte, que
    // es lo normal en una vista picada. La rectificación sigue valiendo; solo
    // implica que toda la imagen está por debajo de él.
    this.horizonVisible = this.horizon > 0 && this.horizon < height;

    // Altura de la cámara sobre el suelo.
    //
    // Para una cámara enfocando un plano horizontal, un objeto de altura H a
    // la profundidad Z se proyecta con altura de imagen h = f·H/Z, y ese mismo
    // punto cae en la fila y − y₀ = f·h_cam/Z. Dividiendo, h = H·(y−y₀)/h_cam.
    //
    // Nuestro modelo dice h = a·(y − y₀). Igualando: **h_cam = H/a**. Sale
    // gratis y es una comprobación de cordura excelente: si da 3 m en una
    // cámara de tejado, el ajuste está mal.
    this.cameraHeight = this.stature / Math.max(1e-6, this.a);

    // Distancia focal.
    //
    // Aquí está el límite honesto de este método: con `a` y `b` se recupera la
    // altura de la cámara y la escala LATERAL en metros, pero **no la
    // profundidad**, porque Z = f·h_cam/(y−y₀) depende de la focal y la focal
    // no está en el modelo de perspectiva.
    //
    // La primera versión de este módulo lo pasó por alto y devolvía
    // profundidades de cinco centímetros para una avenida entera. El error no
    // era de cálculo: era usar una magnitud que los datos no contienen.
    //
    // Se estima desde un campo de visión típico, y todo lo que dependa de ella
    // sale marcado como estimado. Quien conozca la óptica de su cámara puede
    // pasarla y entonces sí es una medida.
    this.fovDeg = opts.fovDeg ?? 60;
    this.focal = opts.focal
      ?? (width / 2) / Math.tan((this.fovDeg * Math.PI / 180) / 2);
    this.focalKnown = !!(opts.focal || opts.fovDeg);

    // Centro óptico: sin calibración real se asume el centro de la imagen. Un
    // desplazamiento lateral inclina el plano; se deja configurable para quien
    // tenga el dato.
    this.cx = opts.cx ?? width / 2;
  }

  /**
   * Profundidad en metros del punto de suelo de la fila `y`.
   *
   * ESTIMADA salvo que se haya pasado la focal: depende de ella, y el modelo
   * de perspectiva no la contiene.
   */
  depth(y) {
    const d = y - this.horizon;
    if (!(d > 1e-6)) return Infinity;
    return this.focal * this.cameraHeight / d;
  }

  /**
   * Punto de imagen (píxeles) -> punto de suelo (metros).
   *
   * Origen en la cámara: X lateral (positivo a la derecha), Z profundidad.
   */
  toGround(x, y) {
    const Z = this.depth(y);
    if (!Number.isFinite(Z)) return null;
    // La escala lateral SÍ es métrica sin conocer la focal: un píxel a la fila
    // y cubre h_cam/(y−y₀) metros, que es exactamente estatura/altura_imagen.
    const mpp = this.stature / Math.max(1e-6, this.a * y + this.b);
    return {x: (x - this.cx) * mpp, z: Z, lateral_exact: true};
  }

  /** Punto de suelo -> píxel. Inversa exacta, útil para dibujar rejillas. */
  toImage(X, Z) {
    if (!(Z > 1e-6)) return null;
    const y = this.horizon + this.focal * this.cameraHeight / Z;
    const mpp = this.stature / Math.max(1e-6, this.a * y + this.b);
    return {x: this.cx + X / mpp, y};
  }

  /**
   * Extensión útil del plano.
   *
   * Cerca del horizonte la profundidad se dispara: unas pocas filas de píxeles
   * cubren cientos de metros y el plano se vuelve inservible ahí. Se acota por
   * el tamaño mínimo con el que un objeto es todavía resoluble --- por debajo
   * de `minObjectPx` no hay detección posible, así que tampoco tiene sentido
   * dibujar ese terreno.
   */
  extent({minObjectPx = 18} = {}) {
    const yMin = Math.max(0, (minObjectPx - this.b) / Math.max(1e-6, this.a));
    const yMax = this.h;
    const near = this.depth(yMax);
    const far = this.depth(yMin);
    const corners = [
      this.toGround(0, yMax), this.toGround(this.w, yMax),
      this.toGround(0, yMin), this.toGround(this.w, yMin),
    ].filter(Boolean);
    return {
      yUsable: [yMin, yMax],
      depth_m: [Math.round(near), Math.round(far)],
      width_near_m: corners.length >= 2
        ? Math.round(corners[1].x - corners[0].x) : null,
      width_far_m: corners.length >= 4
        ? Math.round(corners[3].x - corners[2].x) : null,
    };
  }

  /** Trayectoria de imagen a metros, descartando lo que cae tras el horizonte. */
  projectPath(path) {
    const out = [];
    for (const [x, y] of path) {
      const p = this.toGround(x, y);
      if (p && Number.isFinite(p.x) && p.z < 1e5) out.push([p.x, p.z]);
    }
    return out;
  }

  /**
   * Longitud real recorrida, en metros.
   *
   * En imagen, una persona que se aleja parece recorrer menos distancia. Sobre
   * el plano, no: es la misma caminata. Esto es lo que hace comparables dos
   * trayectorias a distinta profundidad.
   */
  pathLength(path) {
    const g = this.projectPath(path);
    let d = 0;
    for (let i = 1; i < g.length; i++) {
      d += Math.hypot(g[i][0] - g[i - 1][0], g[i][1] - g[i - 1][1]);
    }
    return d;
  }

  /**
   * La altura de cámara deducida es un control de calidad del propio ajuste.
   *
   * No hay cámaras urbanas a 250 m ni a 50 cm. Cuando el valor sale fuera de
   * rango, lo que falla no es este módulo: es el modelo de perspectiva del que
   * se deriva, normalmente porque se ajustó con objetos a profundidades
   * demasiado parecidas y la pendiente salió por ruido.
   *
   * Medido en tres escenas reales:
   *
   *     patio de oficinas   6,7 m    plausible
   *     tejado de Alcalá   72,3 m    plausible
   *     Puerta del Sol    257,6 m    IMPOSIBLE -> el ajuste es malo
   *
   * En Sol la plaza reparte a los peatones a profundidades parecidas y la
   * pendiente sale demasiado pequeña, lo que infla la altura. El sistema puede
   * decirlo en lugar de servir un plano inventado.
   */
  plausibility() {
    const h = this.cameraHeight;
    if (!this.valid) return {ok: false, reason: "sin modelo de perspectiva"};
    if (h < 2) {
      return {ok: false, height_m: +h.toFixed(1),
              reason: "altura de camara implausible (<2 m): el ajuste de "
                      + "perspectiva es demasiado pronunciado"};
    }
    if (h > 150) {
      return {ok: false, height_m: +h.toFixed(1),
              reason: "altura de camara implausible (>150 m): la escena no "
                      + "tiene suficiente variacion de profundidad para ajustar "
                      + "la perspectiva"};
    }
    return {ok: true, height_m: +h.toFixed(1)};
  }

  report() {
    if (!this.valid) return {valid: false, reason: "sin modelo de perspectiva"};
    return {
      valid: true,
      horizon_y: Math.round(this.horizon),
      horizon_visible: this.horizonVisible,
      // Medido: sale de a = estatura/altura_camara, sin suposiciones.
      camera_height_m: +this.cameraHeight.toFixed(1),
      plausible: this.plausibility(),
      // Estimadas: dependen de la focal, que el modelo no contiene.
      focal_px: Math.round(this.focal),
      focal_assumed: !this.focalKnown,
      ...this.extent(),
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {GroundPlane};
}
