/**
 * ask.js — consola de consulta sobre el archivo.
 *
 * Se escribe en español corriente y responde en una línea. Parece un chat y
 * no lo es: no hay modelo de lenguaje detrás, hay una gramática.
 *
 * Esa decisión no es por falta de medios, es por el dominio. Un modelo
 * generativo sobre datos de vigilancia RELLENA los huecos, y aquí un hueco
 * relleno es una afirmación inventada sobre dónde estuvo alguien. Un
 * intérprete determinista tiene el defecto contrario, que es el bueno: cuando
 * no entiende, lo dice.
 *
 * Reglas de la respuesta, en el mismo espíritu que el resto del sistema:
 *   - números de objeto, nunca nombres ni descripciones de personas
 *   - cada cifra sale de una consulta real al archivo
 *   - lo que no se sabe se declara; no se estima para rellenar
 */

/* ------------------------------------------------------------------ */
/*  Vocabulario                                                        */
/* ------------------------------------------------------------------ */

const CLASES = [
  [/\b(personas?|gente|peatones?|viandantes?)\b/, "person", "personas"],
  [/\b(coches?|vehiculos?|furgonetas?|camiones?|autobuses?)\b/, "vehicle", "vehículos"],
  [/\b(bicis?|bicicletas?|motos?|patinetes?)\b/, "bike", "bicis y motos"],
];

const MIN = 60e3, HORA = 3600e3, DIA = 24 * HORA;

/**
 * Normaliza la pregunta antes de reconocerla: minúsculas y sin tildes.
 *
 * Hace falta por un motivo técnico y otro humano. El técnico: `\b` de
 * JavaScript se define sobre [A-Za-z0-9_], así que una vocal acentuada NO
 * cuenta como letra y `\b[úu]ltimos` no casa nunca con «últimos» --- un fallo
 * mudo, la frase simplemente no se entendía. El humano: en el móvil se
 * escriben las tildes a medias, y «cuantos vehiculos» tiene que valer igual.
 */
function norm(s) {
  return String(s).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}

/** Devuelve [desde, hasta] en milisegundos de reloj, o null si no hay pista. */
function ventana(q, now = Date.now()) {
  const n = norm(q);
  const d = new Date(now);
  const medianoche = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

  // "entre las 3 y las 4", "de 15:30 a 16:00"
  const rango = n.match(/\b(?:entre|de)\s+(?:las?\s+)?(\d{1,2})(?::(\d{2}))?\s+(?:y|a|hasta)\s+(?:las?\s+)?(\d{1,2})(?::(\d{2}))?/i);
  if (rango) {
    const h1 = +rango[1], m1 = +(rango[2] || 0), h2 = +rango[3], m2 = +(rango[4] || 0);
    if (h1 < 24 && h2 < 24) {
      let a = medianoche + h1 * HORA + m1 * MIN;
      let b = medianoche + h2 * HORA + m2 * MIN;
      // Un rango que empieza después de terminar cruza la medianoche.
      if (b <= a) b += DIA;
      // Si la ventana entera está en el futuro, se refiere a ayer.
      if (a > now) { a -= DIA; b -= DIA; }
      return [a, b, `entre las ${h1}:${String(m1).padStart(2, "0")} y las ${h2}:${String(m2).padStart(2, "0")}`];
    }
  }

  // "últimos 20 minutos", "última media hora", "últimas 3 horas"
  const ult = n.match(/\bultim[oa]s?\s+(\d+|media)\s*(min(?:uto)?s?|h(?:ora)?s?)\b/);
  if (ult) {
    const media = ult[1] === "media";
    const cuanto = media ? 0.5 : +ult[1];
    const esHora = /^h/.test(ult[2]);
    const span = cuanto * (esHora ? HORA : MIN);
    const unidad = esHora
      ? (cuanto === 1 || media ? "hora" : "horas")
      : (cuanto === 1 ? "minuto" : "minutos");
    const etiqueta = media
      ? `en la última media ${unidad}`
      : `en los últimos ${cuanto} ${unidad}`;
    return [now - span, now, etiqueta];
  }

  if (/\b(ahora|en este momento|ahora mismo)\b/.test(n)) return [now - 2 * MIN, now, "ahora"];
  if (/\b(ultima hora|esta hora)\b/.test(n)) return [now - HORA, now, "en la última hora"];
  if (/\bhoy\b/.test(n)) return [medianoche, now, "hoy"];
  if (/\bayer\b/.test(n)) return [medianoche - DIA, medianoche, "ayer"];
  if (/\b(esta noche|de noche|madrugada)\b/.test(n)) return [medianoche, medianoche + 7 * HORA, "esta madrugada"];
  if (/\b(esta semana|7 dias|ultima semana)\b/.test(n)) return [now - 7 * DIA, now, "esta semana"];
  if (/\b(siempre|todo|en total|historico)\b/.test(n)) return [null, null, "en toda la memoria"];
  return null;
}

function clase(q) {
  const n = norm(q);
  for (const [re, id, nombre] of CLASES) if (re.test(n)) return [id, nombre];
  return [null, "objetos"];
}

function horaDe(ms) {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/* ------------------------------------------------------------------ */
/*  Intérprete                                                         */
/* ------------------------------------------------------------------ */

const AYUDA = [
  "cuánta gente pasó en la última hora",
  "qué pasó entre las 3 y las 4",
  "algo raro hoy",
  "vehículos esta semana",
  "objeto 47",
  "lo más rápido de hoy",
];

class AskConsole {
  /**
   * @param {object} archive  Instancia de Archive (IndexedDB).
   * @param {object} opts.deck      OpsDeck, para preguntas sobre el presente.
   * @param {object} opts.behaviour BehaviourModel de la escena principal.
   */
  constructor(archive, {deck = null, behaviour = null} = {}) {
    this.archive = archive;
    this.deck = deck;
    this.behaviour = behaviour;
    this.log = [];
  }

  /** Procesa una pregunta. Devuelve {texto, datos} — siempre una línea. */
  async ask(raw) {
    const q = String(raw || "").trim();
    if (!q) return this._no("");
    this.log.push({rol: "tú", texto: q, t: Date.now()});
    let r;
    try {
      r = await this._route(q);
    } catch (err) {
      r = {texto: `No he podido consultar el archivo: ${err.message}`, datos: null};
    }
    this.log.push({rol: "argos", texto: r.texto, t: Date.now(), datos: r.datos});
    return r;
  }

  async _route(q) {
    const n = norm(q);
    // --- objeto concreto ---
    const obj = n.match(/\bobjeto\s+(\d+)\b/) || n.match(/^\s*#?(\d{1,6})\s*$/);
    if (obj) return this._objeto(+obj[1]);

    // --- estado del presente ---
    if (/\b(ahora mismo|que ves|que hay|estado)\b/.test(n) && !ventana(q)) {
      return this._ahora();
    }

    const v = ventana(q);
    const [cls, clsNombre] = clase(q);

    // --- anomalías ---
    if (/\b(raro|anomal|extran|inusual|avisos?|alertas?|llamativ)\w*/.test(n)) {
      return this._anomalias(v, cls, clsNombre);
    }

    // --- velocidad ---
    if (/\b(rapid|corriend|veloz|deprisa)\w*/.test(n)) {
      return this._rapido(v, cls, clsNombre);
    }

    // --- recuento ---
    if (/\b(cuant|numero de|total de|contar)\w*/.test(n)) {
      return this._contar(v, cls, clsNombre);
    }

    // --- qué pasó ---
    if (/\b(que paso|que hubo|resumen|como fue|actividad)\b/.test(n)) {
      return this._resumen(v, cls, clsNombre);
    }

    // Hay ventana y clase pero no verbo: se interpreta como recuento, que es
    // lo que casi siempre quiere decir "vehículos esta semana".
    if (v && cls) return this._contar(v, cls, clsNombre);
    if (v) return this._resumen(v, null, "objetos");

    return this._no(q);
  }

  /* ---------------- respuestas ---------------- */

  _no(q) {
    const sugerencia = AYUDA.slice(0, 3).map((s) => `«${s}»`).join(", ");
    return {
      texto: q
        ? `No entiendo esa pregunta. Puedo responder a cosas como ${sugerencia}.`
        : `Pregúntame algo. Por ejemplo ${sugerencia}.`,
      datos: {ayuda: AYUDA},
    };
  }

  _ahora() {
    if (!this.deck) return {texto: "No hay ningún puesto en marcha.", datos: null};
    const st = this.deck.state(Date.now());
    const total = st.camaras.reduce((a, c) => a + c.objetos, 0);
    const cola = st.colaTotal;
    return {
      texto: `${total} objeto(s) en seguimiento en ${st.vivas} cámara(s)` +
             (cola ? `, ${cola} aviso(s) en cola.` : ", nada en la cola."),
      datos: st,
    };
  }

  async _contar(v, cls, nombre) {
    if (!v) return {texto: `¿En qué periodo? Dime «hoy», «última hora» o «entre las 3 y las 4».`, datos: null};
    const [desde, hasta, etiqueta] = v;
    const rows = await this.archive.queryWall({cls, since: desde, until: hasta, limit: 5000});
    if (!rows.length) {
      // El castellano no deja construir esto por concatenación: «ningún
      // persona» y «ningún bicis» salen de recortar la -s del plural. Se
      // escriben a mano las cuatro formas.
      const NADA = {
        person: "Ninguna persona", vehicle: "Ningún vehículo",
        bike: "Ninguna bici ni moto", null: "Ningún objeto",
      };
      return {texto: `${NADA[cls] || NADA.null} ${etiqueta}.`, datos: {n: 0}};
    }
    // El archivo guarda un registro por tubo, así que contar filas es contar
    // objetos distintos, no apariciones. No hay que deduplicar.
    return {
      texto: `${rows.length} ${nombre} ${etiqueta}. El primero a las ${horaDe(rows[rows.length - 1].wall)}, el último a las ${horaDe(rows[0].wall)}.`,
      datos: {n: rows.length, ids: rows.slice(0, 20).map((r) => r.id)},
    };
  }

  async _resumen(v, cls, nombre) {
    if (!v) return {texto: "¿De cuándo? Dime «hoy», «última hora» o un rango de horas.", datos: null};
    const [desde, hasta, etiqueta] = v;
    const rows = await this.archive.queryWall({cls, since: desde, until: hasta, limit: 5000});
    const evs = await this.archive.eventsWall({since: desde, until: hasta, limit: 500});
    if (!rows.length && !evs.length) {
      return {texto: `Sin actividad registrada ${etiqueta}.`, datos: {n: 0}};
    }
    const porClase = {};
    for (const r of rows) porClase[r.cls] = (porClase[r.cls] || 0) + 1;
    const desglose = Object.entries(porClase)
      .sort((a, b) => b[1] - a[1])
      .map(([c, n]) => `${n} ${c === "person" ? "personas" : c === "vehicle" ? "vehículos" : c === "bike" ? "bicis" : c}`)
      .join(", ");
    return {
      texto: `${etiqueta.charAt(0).toUpperCase() + etiqueta.slice(1)}: ${desglose || "nada"}` +
             (evs.length ? `, y ${evs.length} evento(s).` : "."),
      datos: {n: rows.length, porClase, eventos: evs.length},
    };
  }

  async _anomalias(v, cls, nombre) {
    // Lo vivo primero: la cola del puesto es la respuesta correcta a "algo
    // raro" cuando hay un puesto en marcha.
    if (this.deck) {
      const cola = this.deck.queue.ranked(Date.now(), {includeAck: true});
      if (cola.length) {
        const top = cola[0];
        return {
          texto: `${cola.length} aviso(s). El más fuerte: objeto ${top.track} en ${top.cam}` +
                 (top.reasons?.length ? ` — ${top.reasons.slice(0, 2).join(", ")}.` : "."),
          datos: {cola: cola.slice(0, 10)},
        };
      }
    }
    const [desde, hasta, etiqueta] = v || [null, null, "en toda la memoria"];
    const evs = await this.archive.eventsWall({since: desde, until: hasta, limit: 300});
    if (!evs.length) return {texto: `Nada marcado como anómalo ${etiqueta}.`, datos: {n: 0}};
    const kinds = {};
    for (const e of evs) kinds[e.kind] = (kinds[e.kind] || 0) + 1;
    const d = Object.entries(kinds).sort((a, b) => b[1] - a[1])
      .map(([k, n]) => `${n}× ${k}`).join(", ");
    return {texto: `${evs.length} evento(s) ${etiqueta}: ${d}.`, datos: {n: evs.length, kinds}};
  }

  async _rapido(v, cls, nombre) {
    const [desde, hasta, etiqueta] = v || [null, null, "en toda la memoria"];
    const rows = await this.archive.queryWall({cls, since: desde, until: hasta, limit: 5000});
    const conV = rows.filter((r) => Number.isFinite(r.physics?.speed_m_s));
    if (!conV.length) {
      return {
        texto: `No tengo velocidades fiables ${etiqueta}. Hace falta que la escala métrica se haya ajustado; sin eso no doy números inventados.`,
        datos: {n: rows.length, conVelocidad: 0},
      };
    }
    conV.sort((a, b) => b.physics.speed_m_s - a.physics.speed_m_s);
    const top = conV[0];
    const kmh = (top.physics.speed_m_s * 3.6).toFixed(1);
    return {
      texto: `Objeto ${top.id} (${top.cls}), ${kmh} km/h a las ${horaDe(top.wall)}. De ${conV.length} con velocidad medida ${etiqueta}.`,
      datos: {id: top.id, kmh: +kmh, n: conV.length},
    };
  }

  async _objeto(id) {
    const rows = await this.archive.queryWall({limit: 20000});
    const r = rows.find((x) => x.id === id);
    if (!r) return {texto: `No tengo el objeto ${id} en el archivo.`, datos: null};
    const partes = [`Objeto ${id}: ${r.cls}`, `visto a las ${horaDe(r.wall)}`];
    if (Number.isFinite(r.physics?.speed_m_s)) {
      partes.push(`${(r.physics.speed_m_s * 3.6).toFixed(1)} km/h`);
    }
    if (Number.isFinite(r.stature?.m)) {
      // La estatura sale marcada SIEMPRE. Es una estimación anclada en una
      // mediana poblacional asumida: el error es sistemático y no se ve en
      // los datos, así que no puede presentarse como una medida.
      partes.push(`estatura estimada ${r.stature.m.toFixed(2)} m (no probatoria)`);
    }
    if (r.obs?.length) partes.push(`${r.obs.length} observaciones`);
    return {texto: partes.join(", ") + ".", datos: r};
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {AskConsole, ventana, clase, norm, AYUDA};
}
