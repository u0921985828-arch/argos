package eus.argos.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Radio pasiva: lo que el teléfono OYE por Bluetooth LE y por GNSS, sin emitir
 * nada. Es un módulo que se ACTIVA A MANO y viene apagado.
 *
 * Inspirado en Fieldwatch (Off Grid Pete LLC, licencia MIT; ver NOTICE). La
 * decodificación de Remote ID sigue la disposición de ASTM F3411 / OpenDroneID
 * igual que allí.
 *
 * Privacidad, por construcción y no por promesa:
 *  - La dirección de un aparato no se guarda ni se muestra. Entra, se convierte
 *    en un resumen con una sal aleatoria que vive solo en memoria durante la
 *    sesión, y se descarta. Al cerrar, no queda manera de volver a ella.
 *  - Nada de esto se escribe en disco ni se cruza con las pistas de la cámara.
 *    Un rastreador y una persona del vídeo NO se asocian: eso sería seguir a
 *    alguien por su aparato.
 *  - De un dron se lee lo que emite para la seguridad aérea (número de serie,
 *    posición, altura, velocidad). La posición y el identificador del PILOTO
 *    no se decodifican.
 */

/** Un anuncio BLE leído: fabricante → datos, servicio de 16 bits → datos. */
class Anuncio(val fabricante: Map<Int, ByteArray>, val servicio: Map<Int, ByteArray>,
              val uuids16: Set<Int>, val nombre: String?)

object BleAd {
    /** Estructuras AD de un anuncio crudo (longitud, tipo, datos). Tolera basura. */
    fun parse(b: ByteArray?): Anuncio {
        val fab = LinkedHashMap<Int, ByteArray>(); val srv = LinkedHashMap<Int, ByteArray>()
        val uuids = LinkedHashSet<Int>(); var nombre: String? = null
        if (b == null) return Anuncio(fab, srv, uuids, null)
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0 || i + len >= b.size) break
            val tipo = b[i + 1].toInt() and 0xFF
            val d = b.copyOfRange(i + 2, i + 1 + len)
            when (tipo) {
                0x02, 0x03 -> { var k = 0; while (k + 1 < d.size) { uuids.add(u16(d, k)); k += 2 } }
                0x08, 0x09 -> if (d.isNotEmpty()) nombre = String(d, Charsets.UTF_8).trim('\u0000', ' ').take(32)
                0x16 -> if (d.size >= 2) srv[u16(d, 0)] = d.copyOfRange(2, d.size)
                0xFF -> if (d.size >= 2) fab[u16(d, 0)] = d.copyOfRange(2, d.size)
            }
            i += len + 1
        }
        return Anuncio(fab, srv, uuids, nombre)
    }

    internal fun u16(d: ByteArray, at: Int) = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
}

/** Qué es lo que se oye. Solo las firmas que están documentadas y no dan lugar a duda. */
enum class Clase(val texto: String) {
    FIND_MY_SEPARADO("rastreador Find My lejos de su dueño"),
    FIND_MY("accesorio Find My junto a su dueño"),
    TILE("rastreador Tile"),
    SMARTTAG("rastreador Galaxy SmartTag"),
    GOOGLE("rastreador de la red Encontrar de Google"),
    DULT("rastreador (estándar contra el rastreo no deseado)"),
    DRON("dron con Remote ID"),
    OTRO("aparato Bluetooth");

    val rastreador get() = this != DRON && this != OTRO && this != FIND_MY
}

object Clasificador {
    const val APPLE = 0x004C
    const val ODID = 0xFFFA

    fun clase(a: Anuncio): Clase {
        a.servicio[ODID]?.let { if (it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x0D) return Clase.DRON }
        a.fabricante[APPLE]?.let { d ->
            // Find My sin conexión: tipo 0x12. Con 25 bytes de clave es que el
            // accesorio está SEPARADO de su dueño, que es el caso que importa
            // a quien teme que le hayan metido uno.
            if (d.size >= 2 && (d[0].toInt() and 0xFF) == 0x12)
                return if ((d[1].toInt() and 0xFF) >= 0x19) Clase.FIND_MY_SEPARADO else Clase.FIND_MY
        }
        if (0xFEED in a.servicio || 0xFEED in a.uuids16 || 0xFEEC in a.uuids16) return Clase.TILE
        if (0xFD5A in a.servicio) return Clase.SMARTTAG
        a.servicio[0xFEAA]?.let { d -> if (d.isNotEmpty() && ((d[0].toInt() and 0xFF) == 0x40 || (d[0].toInt() and 0xFF) == 0x41)) return Clase.GOOGLE }
        if (0xFCB2 in a.servicio || 0xFCB2 in a.uuids16) return Clase.DULT
        return Clase.OTRO
    }
}

/**
 * Lo que un dron dice de sí mismo por Remote ID. NaN = no lo ha dicho.
 * `alturaM` es la altura sobre el punto de despegue o sobre el suelo (según
 * `sobreSuelo`), que es la que entiende una persona; la geodésica, sobre el
 * elipsoide, va aparte: en Madrid son 650 m de diferencia.
 */
class Dron(val serie: String?, val tipo: String?, val lat: Double, val lon: Double,
           val alturaM: Double, val velocidadMs: Double, val rumbo: Double,
           val sobreSuelo: Boolean = false, val altGeodesicaM: Double = Double.NaN)

object RemoteId {
    private const val MSG = 25

    /** Datos del servicio 0xFFFA: código 0x0D, contador y uno o varios mensajes de 25 bytes. */
    fun decodifica(d: ByteArray): Dron? {
        if (d.size < 2 + MSG || (d[0].toInt() and 0xFF) != 0x0D) return null
        var serie: String? = null; var tipo: String? = null
        var lat = Double.NaN; var lon = Double.NaN; var alt = Double.NaN; var vel = Double.NaN; var rumbo = Double.NaN
        var geo = Double.NaN; var sobreSuelo = false
        for (m in mensajes(d, 2)) {
            when ((m[0].toInt() and 0xFF) shr 4) {
                0 -> {   // Basic ID
                    val idTipo = (m[1].toInt() and 0xFF) shr 4
                    val uaTipo = m[1].toInt() and 0x0F
                    val id = String(m, 2, 20, Charsets.US_ASCII).trim('\u0000', ' ')
                    // 1 = número de serie del aparato (CTA-2063). 2 = registro de
                    // la autoridad, 3-4 = de sesión: identifican a un operador o
                    // un vuelo, no al aparato, y no se muestran.
                    if (idTipo == 1 && id.isNotEmpty()) serie = id
                    tipo = tipoUa(uaTipo) ?: tipo
                }
                1 -> {   // Location (ASTM F3411): 1 flags, 2 rumbo, 3 velocidad,
                         // 4 vertical, 5-8 lat, 9-12 lon, 13-14 barométrica,
                         // 15-16 geodésica, 17-18 ALTURA (despegue o suelo).
                    val flags = m[1].toInt() and 0xFF
                    // 361 (desconocido) llega como 181 con el bit E/O: se descarta.
                    val dir = (m[2].toInt() and 0xFF) + if ((flags shr 1) and 1 == 1) 180 else 0
                    if (dir < 360) rumbo = dir.toDouble()
                    val v = m[3].toInt() and 0xFF
                    if (v != 255) vel = if (flags and 1 == 0) v * 0.25 else v * 0.75 + 255 * 0.25
                    lat = i32(m, 5) * 1e-7; lon = i32(m, 9) * 1e-7
                    val g = u16(m, 15)
                    if (g != 0) geo = g * 0.5 - 1000.0
                    val h = u16(m, 17)
                    if (h != 0) { alt = h * 0.5 - 1000.0; sobreSuelo = (flags shr 2) and 1 == 1 }
                    if (lat == 0.0 && lon == 0.0) { lat = Double.NaN; lon = Double.NaN }
                }
                // 3 = Self ID (texto libre del piloto), 4 = System (POSICIÓN DEL
                // PILOTO), 5 = Operator ID: no se leen.
            }
        }
        if (serie == null && lat.isNaN() && alt.isNaN() && geo.isNaN()) return null
        return Dron(serie, tipo, lat, lon, alt, vel, rumbo, sobreSuelo, geo)
    }

    /** Un mensaje suelto o un paquete (tipo 0xF) con varios. */
    private fun mensajes(b: ByteArray, start: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (start >= b.size) return out
        if (((b[start].toInt() and 0xFF) shr 4) == 0xF && start + 3 <= b.size) {
            val paso = maxOf(MSG, b[start + 1].toInt() and 0xFF)
            val n = b[start + 2].toInt() and 0xFF
            var i = start + 3
            repeat(n) { if (i + MSG <= b.size) { out.add(b.copyOfRange(i, i + MSG)); i += paso } }
            return out
        }
        var i = start
        while (i + MSG <= b.size) { out.add(b.copyOfRange(i, i + MSG)); i += MSG }
        return out
    }

    private fun tipoUa(t: Int) = when (t) {
        1 -> "avión"; 2 -> "multirrotor"; 3 -> "girocóptero"; 4 -> "despegue vertical"; 5 -> "ornitóptero"
        6 -> "planeador"; 7 -> "cometa"; 8 -> "globo"; 9 -> "dirigible"; 10 -> "paracaídas"; 11 -> "cohete"
        else -> null
    }

    private fun i32(m: ByteArray, at: Int) = (m[at].toInt() and 0xFF) or ((m[at + 1].toInt() and 0xFF) shl 8) or
        ((m[at + 2].toInt() and 0xFF) shl 16) or ((m[at + 3].toInt() and 0xFF) shl 24)
    private fun u16(m: ByteArray, at: Int) = (m[at].toInt() and 0xFF) or ((m[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * El campo de radio de la sesión: qué hay cerca y desde cuándo.
 *
 * Un rastreador que sigue ahí diez minutos seguidos es un aviso: en una cámara
 * fija quiere decir que alguien dejó uno a su alcance. Se cuenta por resumen
 * de dirección, así que un aparato que cambia de dirección cuenta como otro
 * (Find My separado la cambia una vez al día; la red de Google, cada ~17 min).
 */
class CampoRadio(private val avisoS: Double = 600.0, private val huecoS: Double = 120.0,
                 private val vivoS: Double = 60.0, private val recienteS: Double = 600.0, sal: ByteArray? = null) {
    private val sal = sal ?: ByteArray(16).also { SecureRandom().nextBytes(it) }

    class Visto(val clase: Clase, val desde: Double, var ultimo: Double, var rssi: Double, var avisado: Boolean = false,
                var dron: Dron? = null)
    class Aviso(val t: Double, val texto: String)
    /** `avisos` son solo los recientes (`recienteS`); los viejos siguen en `avisos` del campo. */
    class Estado(val aparatos: Int, val porClase: Map<Clase, Int>, val drones: List<Dron>,
                 val rastreadorMasLargoS: Double, val avisos: List<Aviso>)

    private val vistos = HashMap<String, Visto>()
    val avisos = ArrayDeque<Aviso>()
    private var olvidado = false

    /** Resumen de la dirección con la sal de la sesión. La dirección no sale de aquí. */
    fun clave(direccion: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sal); md.update(direccion.uppercase().toByteArray())
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun oye(direccion: String, rssi: Int, a: Anuncio, tSec: Double) {
        // Un resultado que llega tarde, con la radio ya apagada, no entra: la
        // sal ya no existe y el resumen sería deshacible.
        if (olvidado) return
        val c = Clasificador.clase(a)
        val k = clave(direccion)
        var v = vistos[k]
        // Un hueco largo es otra visita: el reloj empieza de nuevo.
        if (v == null || tSec - v.ultimo > huecoS || v.clase != c) { v = Visto(c, tSec, tSec, rssi.toDouble()); vistos[k] = v }
        v.ultimo = tSec
        v.rssi += (rssi - v.rssi) * 0.3
        if (c == Clase.DRON) a.servicio[Clasificador.ODID]?.let { d -> RemoteId.decodifica(d)?.let { v.dron = mezcla(v.dron, it) } }
        if (c.rastreador && !v.avisado && tSec - v.desde >= avisoS) {
            v.avisado = true
            avisa(tSec, "${c.texto} cerca desde hace ${((tSec - v.desde) / 60).toInt()} min")
        }
        if (c == Clase.DRON && tSec == v.desde) avisa(tSec, "dron con Remote ID" + (v.dron?.serie?.let { " ($it)" } ?: ""))
    }

    /** Un dron manda Basic ID y Location en anuncios distintos: se juntan. */
    private fun mezcla(a: Dron?, b: Dron): Dron = if (a == null) b else Dron(
        b.serie ?: a.serie, b.tipo ?: a.tipo, if (b.lat.isNaN()) a.lat else b.lat, if (b.lon.isNaN()) a.lon else b.lon,
        if (b.alturaM.isNaN()) a.alturaM else b.alturaM, if (b.velocidadMs.isNaN()) a.velocidadMs else b.velocidadMs,
        if (b.rumbo.isNaN()) a.rumbo else b.rumbo,
        if (b.alturaM.isNaN()) a.sobreSuelo else b.sobreSuelo, if (b.altGeodesicaM.isNaN()) a.altGeodesicaM else b.altGeodesicaM)

    private fun avisa(t: Double, texto: String) {
        avisos.addFirst(Aviso(t, texto)); if (avisos.size > 30) avisos.removeLast()
    }

    fun estado(tSec: Double): Estado {
        // Lo que lleva un rato sin oírse se olvida del todo: ni siquiera el
        // resumen se queda.
        vistos.values.removeIf { tSec - it.ultimo > huecoS }
        val vivos = vistos.values.filter { tSec - it.ultimo <= vivoS }
        val por = vivos.groupingBy { it.clase }.eachCount()
        val largo = vivos.filter { it.clase.rastreador }.maxOfOrNull { tSec - it.desde } ?: 0.0
        return Estado(vivos.size, por, vivos.mapNotNull { it.dron }, largo, avisos.filter { tSec - it.t <= recienteS })
    }

    /** Al apagar el módulo: todo fuera, sal incluida. */
    fun olvida() { olvidado = true; vistos.clear(); avisos.clear(); sal.fill(0) }
}

/**
 * Interferencia o suplantación de GNSS, desde la intensidad de los satélites.
 *
 * Con la cámara fija el teléfono no se mueve, así que lo normal del cielo se
 * aprende bien. Se avisa de un CAMBIO, nunca de lo que ya había al empezar:
 *  - interferencia: la intensidad típica de los mejores satélites cae 10 dB o
 *    más y se pierde la mitad de los satélites fuertes, durante 5 s seguidos.
 *    Una nube o la lluvia bajan 1-3 dB; un satélite tapado es uno.
 *  - suplantación: seis o más satélites con intensidades casi IGUALES (un
 *    emisor en tierra los manda todos con la misma potencia; los de verdad
 *    llegan a alturas distintas y varían varios dB), durante 10 s.
 * La suplantación se mira en términos absolutos y solo después de aprender:
 * si ya estaba al encender, se avisa al terminar el aprendizaje.
 * Es una pista, no una prueba, y no dice dónde está el emisor.
 */
class VigiaGnss(private val aprendeS: Double = 60.0) {
    class Sat(val id: Int, val cn0: Double)
    enum class Estado { APRENDIENDO, NORMAL, INTERFERENCIA, SUPLANTACION }

    var estado = Estado.APRENDIENDO; private set
    var base = Double.NaN; private set
    var baseFuertes = Double.NaN; private set
    /** Avisos, el más reciente al final; como mucho 20. */
    val avisos = ArrayList<String>()
    var ultimoAvisoT = Double.NaN; private set
    private var t0 = Double.NaN
    private var malDesde = Double.NaN
    private var igualDesde = Double.NaN

    fun epoca(sats: List<Sat>, tSec: Double): Estado {
        if (t0.isNaN()) t0 = tSec
        val cn = sats.map { it.cn0 }.filter { it > 0 }.sortedDescending()
        val top = cn.take(6)
        val tipica = if (top.isEmpty()) 0.0 else top[top.size / 2]
        val fuertes = cn.count { it >= 25 }

        if (tSec - t0 < aprendeS || base.isNaN()) {
            if (top.size >= 4) {
                base = if (base.isNaN()) tipica else base + (tipica - base) * 0.05
                baseFuertes = if (baseFuertes.isNaN()) fuertes.toDouble() else baseFuertes + (fuertes - baseFuertes) * 0.05
            }
            if (tSec - t0 < aprendeS) { estado = Estado.APRENDIENDO; return estado }
            if (base.isNaN()) return estado
        }

        val caida = base - tipica
        // Hace falta un cielo bueno aprendido: con menos de 4 satélites fuertes
        // de base (interior), perderlos no distingue nada.
        val mal = baseFuertes >= 4 && caida >= 10.0 && fuertes <= baseFuertes * 0.5
        malDesde = if (mal) (if (malDesde.isNaN()) tSec else malDesde) else Double.NaN

        // Uniformidad: desviación típica de los 6 mejores, con cielo lleno.
        val iguales = top.size >= 6 && run {
            val m = top.average(); Math.sqrt(top.sumOf { (it - m) * (it - m) } / top.size) < 1.0
        }
        igualDesde = if (iguales) (if (igualDesde.isNaN()) tSec else igualDesde) else Double.NaN

        val antes = estado
        estado = when {
            !malDesde.isNaN() && tSec - malDesde >= 5 -> Estado.INTERFERENCIA
            !igualDesde.isNaN() && tSec - igualDesde >= 10 -> Estado.SUPLANTACION
            else -> Estado.NORMAL
        }
        if (estado != antes) when (estado) {
            Estado.INTERFERENCIA -> avisa("posible interferencia GNSS: los satélites bajan %.0f dB".format(java.util.Locale("es"), caida), tSec)
            Estado.SUPLANTACION -> avisa("posible suplantación GNSS: satélites con intensidades casi idénticas", tSec)
            else -> {}
        }
        // Lo normal se sigue aprendiendo despacio, pero nunca durante un aviso.
        if (estado == Estado.NORMAL && top.size >= 4) {
            base += (tipica - base) * 0.002
            baseFuertes += (fuertes - baseFuertes) * 0.002
        }
        return estado
    }

    private fun avisa(t: String, tSec: Double) {
        avisos.add(t); ultimoAvisoT = tSec
        while (avisos.size > 20) avisos.removeAt(0)
    }
}
