/*
 * ARGOS · registro de cámaras públicas
 * ---------------------------------------------------------------------------
 * Fuentes de cámara fija y pública, con posición publicada y sin clave.
 *
 * La idea viene de la capa CCTV de God's Eye View (MIT, bilawalsidhu). Lo que
 * se toma prestado es el **catálogo**: qué organismos publican cámaras y por
 * qué endpoint. Lo que hace ARGOS con ellas es lo contrario de lo que hace
 * aquel proyecto --- allí las cámaras se proyectan sobre un globo 3D, aquí se
 * analizan sus píxeles.
 *
 * Por qué importa: ARGOS necesita **cámara fija**, y hasta ahora la única vía
 * cómoda era capturar pantalla sobre un navegador. Con esto se apunta directo a
 * la fuente, con dos ventajas que no son menores:
 *
 *   · Resolución nativa en lugar de lo que quepa en pantalla. Medido en esta
 *     misma herramienta, reducir a la mitad una plaza hizo caer la detección de
 *     personas de 65 a 8: el presupuesto de píxeles por objeto lo decide todo.
 *   · La posición y la orientación vienen publicadas, así que se sabe de
 *     antemano si el encuadre sirve --- una cámara de tráfico a 6 m ve peatones
 *     de 40 px; una de autopista a 12 m, de 8 px.
 *
 * Nota sobre el uso: son cámaras de organismos públicos de tráfico, pensadas
 * para consulta ciudadana. Se leen a la cadencia que indica cada organismo y
 * nada se reenvía a ningún sitio. El análisis es local, como todo en ARGOS.
 */

"use strict";

/**
 * Proveedores.
 *
 * `kind` decide cómo se consume: `hls` es vídeo continuo y es lo que interesa
 * para análisis; `jpeg` es una instantánea que se refresca cada pocos
 * segundos --- vale para ocupación, no para seguimiento, porque entre dos
 * imágenes un peatón ya ha cruzado.
 */
const PROVIDERS = {
  caltrans: {
    name: "Caltrans (California)",
    kind: "hls",
    // Doce distritos; cada uno publica su propio catálogo.
    districts: ["3", "4", "5", "6", "7", "8", "10", "11", "12"],
    url: (d) => `https://cwwp2.dot.ca.gov/data/d${d}/cctv/cctvStatusD${String(d).padStart(2, "0")}.json`,
    parse: (json) => (json.data || json || []).map((row) => {
      const c = row.cctv || row;
      const loc = c.location || {};
      const img = c.imageData || {};
      return {
        id: `caltrans:${loc.district || ""}:${c.index || loc.locationName}`,
        name: loc.locationName || "sin nombre",
        lat: Number(loc.latitude), lon: Number(loc.longitude),
        stream: img.streamingVideoURL || null,
        snapshot: img.static?.currentImageURL || null,
        route: loc.route || null,
        direction: loc.direction || null,
        provider: "caltrans",
      };
    }).filter((c) => c.stream || c.snapshot),
  },

  tfl: {
    name: "Transport for London",
    kind: "mp4",
    url: () => "https://api.tfl.gov.uk/Place/Type/JamCam",
    parse: (json) => (json || []).map((p) => {
      const props = Object.fromEntries(
        (p.additionalProperties || []).map((q) => [q.key, q.value]));
      return {
        id: `tfl:${p.id}`,
        name: p.commonName,
        lat: Number(p.lat), lon: Number(p.lon),
        // TfL publica clips de unos segundos que se renuevan, no HLS.
        stream: props.videoUrl || null,
        snapshot: props.imageUrl || null,
        view: props.view || null,
        available: props.available !== "false",
        provider: "tfl",
      };
    }).filter((c) => c.available && (c.stream || c.snapshot)),
  },
};

/* ==========================================================================
   Catálogo
   ========================================================================== */

class CameraRegistry {
  constructor(opts = {}) {
    // Los organismos no envían CORS, así que desde el navegador hace falta un
    // intermediario. Se deja configurable en lugar de cablearlo: quien corra
    // ARGOS con su servidor local ya lo tiene; quien lo abra como fichero
    // suelto necesita uno o se queda sin esta función, y es mejor decirlo que
    // fallar en silencio.
    this.proxy = opts.proxy || null;
    this.cameras = [];
    this.errors = [];
  }

  _url(target) {
    return this.proxy ? this.proxy + encodeURIComponent(target) : target;
  }

  async load(providers = ["tfl", "caltrans"], {districts = ["4"]} = {}) {
    this.cameras = [];
    this.errors = [];
    for (const key of providers) {
      const p = PROVIDERS[key];
      if (!p) continue;
      const targets = p.districts
        ? districts.filter((d) => p.districts.includes(String(d))).map((d) => p.url(d))
        : [p.url()];
      for (const t of targets) {
        try {
          const res = await fetch(this._url(t));
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          this.cameras.push(...p.parse(await res.json()));
        } catch (err) {
          this.errors.push(`${key}: ${err.message}`);
        }
      }
    }
    return this.cameras;
  }

  /** Cámaras cerca de un punto, ordenadas por distancia. */
  near(lat, lon, km = 25, limit = 40) {
    const R = 6371;
    const rad = (d) => d * Math.PI / 180;
    return this.cameras
      .filter((c) => Number.isFinite(c.lat) && Number.isFinite(c.lon))
      .map((c) => {
        const dLat = rad(c.lat - lat), dLon = rad(c.lon - lon);
        const a = Math.sin(dLat / 2) ** 2
          + Math.cos(rad(lat)) * Math.cos(rad(c.lat)) * Math.sin(dLon / 2) ** 2;
        return {...c, km: 2 * R * Math.asin(Math.sqrt(a))};
      })
      .filter((c) => c.km <= km)
      .sort((a, b) => a.km - b.km)
      .slice(0, limit);
  }

  search(text, limit = 40) {
    const q = text.toLowerCase().trim();
    if (!q) return this.cameras.slice(0, limit);
    return this.cameras
      .filter((c) => c.name.toLowerCase().includes(q)
                  || (c.route || "").toLowerCase().includes(q))
      .slice(0, limit);
  }

  /**
   * Prioriza las que probablemente sirvan para detectar personas.
   *
   * No es más que heurística sobre el nombre --- los organismos no publican la
   * altura ni el campo de visión --- pero acierta lo suficiente para ahorrar
   * abrir veinte cámaras de autopista antes de dar con una de calle. Una
   * cámara de intersección mira de cerca; una de autopista, lejos y de lado.
   */
  rankForPeople(list = this.cameras) {
    const buena = /\b(st|street|ave|avenue|rd|road|blvd|plaza|square|bridge|junction|jct|intersection|calle|centre|center|high st)\b/i;
    const mala = /\b(i-\d|sr-\d|us-\d|hwy|freeway|motorway|m\d+|a\d{2,}|tunnel|ramp)\b/i;
    return list
      .map((c) => {
        let score = 0;
        if (buena.test(c.name)) score += 2;
        if (mala.test(c.name)) score -= 2;
        if (c.stream) score += 1;        // vídeo continuo vale más que foto
        return {...c, peopleScore: score};
      })
      .sort((a, b) => b.peopleScore - a.peopleScore);
  }

  stats() {
    const porProveedor = {};
    for (const c of this.cameras) {
      porProveedor[c.provider] = (porProveedor[c.provider] || 0) + 1;
    }
    return {
      total: this.cameras.length,
      con_video: this.cameras.filter((c) => c.stream).length,
      solo_imagen: this.cameras.filter((c) => !c.stream && c.snapshot).length,
      por_proveedor: porProveedor,
      errores: this.errors,
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {CameraRegistry, PROVIDERS};
}
