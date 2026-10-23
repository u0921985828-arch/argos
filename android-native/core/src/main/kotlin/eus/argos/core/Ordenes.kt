package eus.argos.core

/**
 * Lo que se le PIDE a ARGOS, no lo que se le pregunta: cambiar de cámara,
 * buscar una cámara pública de un sitio, mover la cámara IP, encender o
 * apagar cosas, abrir paneles. Todo en el teléfono, sin red: una gramática
 * en español tolerante (sin tildes, con sinónimos, en cualquier orden
 * razonable). Lo que no es una orden sigue a `Ask`, que responde preguntas.
 */
sealed class Orden {
    object Movil : Orden()
    object CamaraIp : Orden()
    /** Una cámara pública; `lugar` null = la de menos retraso. */
    data class Publica(val lugar: String?) : Orden()
    object OtraCamara : Orden()
    /** Velocidades -1..1 durante `ms`. */
    data class Mueve(val x: Double, val y: Double, val zoom: Double, val ms: Long) : Orden()
    object ACasa : Orden()
    data class Estabilizar(val si: Boolean) : Orden()
    data class Radio(val si: Boolean) : Orden()
    /** "auto", "entero" o "teselas". */
    data class Zoom(val modo: String) : Orden()
    data class Abre(val que: String) : Orden()
    object BorraZonas : Orden()
    object Cierra : Orden()
    object Calla : Orden()
}

object Ordenes {
    private fun n(s: String) = Ask.norm(s).replace(Regex("[¿?¡!.,;:]"), " ").replace(Regex("\\s+"), " ").trim()

    private val PIDE = "(pon|ponme|pongas|abre|abreme|cambia|cambiame|muestra|muestrame|ensena|ensename|quiero ver|conecta|conectate|ve a|vete a|llevame a|busca|buscame|encuentra|mira|vamos a|dame|cambiar|poner|ver)"
    private val ACTIVA = "(activa|activar|enciende|encender|pon|poner|conecta|arranca|habilita)"
    private val APAGA = "(desactiva|desactivar|apaga|apagar|quita|quitar|para|parar|deshabilita|sin)"

    /** null si no es una orden. */
    fun lee(texto: String): Orden? {
        val t = n(texto)
        if (t.isEmpty()) return null
        fun hay(re: String) = Regex("(^|\\b)($re)(\\b|$)").containsMatchIn(t)

        // Callar va primero: es lo que se dice con prisa.
        if (Regex("^(calla|callate|silencio|basta|para de hablar|deja de hablar|stop)( ya| por favor)?$").matches(t)) return Orden.Calla

        // Mover la cámara.
        val cuanto = when { hay("un poco|poquito|un pelin|algo") -> 350L; hay("mucho|del todo|bastante|hasta el final") -> 1800L; else -> 800L }
        val verboMover = hay("gira|girar|gire|mueve|mover|mueva|apunta|apuntar|rota|rotar|orienta|mira|enfoca|desplaza|vete|ve|lleva")
        val haciaCamara = hay("camara") || verboMover
        if (haciaCamara) {
            when {
                hay("izquierda") && (verboMover || hay("camara")) -> return Orden.Mueve(-0.5, 0.0, 0.0, cuanto)
                hay("derecha") && (verboMover || hay("camara")) -> return Orden.Mueve(0.5, 0.0, 0.0, cuanto)
                hay("arriba|hacia el cielo") && (verboMover || hay("camara")) -> return Orden.Mueve(0.0, 0.5, 0.0, cuanto)
                hay("abajo|hacia el suelo") && (verboMover || hay("camara")) -> return Orden.Mueve(0.0, -0.5, 0.0, cuanto)
            }
        }
        if (hay("sube la camara|levanta la camara")) return Orden.Mueve(0.0, 0.5, 0.0, cuanto)
        if (hay("baja la camara")) return Orden.Mueve(0.0, -0.5, 0.0, cuanto)
        if (hay("acerca|acercate|amplia|ampliar|mas zoom|zoom in|haz zoom")) return Orden.Mueve(0.0, 0.0, 0.5, cuanto)
        if (hay("aleja|alejate|menos zoom|zoom out|quita zoom")) return Orden.Mueve(0.0, 0.0, -0.5, cuanto)
        if (hay("posicion inicial|vuelve al inicio|vuelve a casa|centra la camara|vuelve a la posicion")) return Orden.ACasa

        // Ajustes.
        if (hay("estabiliza|estabilizado|estabilizador|estabilizacion|estabilizar")) {
            if (hay(APAGA) || hay("no estabilices")) return Orden.Estabilizar(false)
            if (hay(ACTIVA) || hay("estabiliza")) return Orden.Estabilizar(true)
        }
        if (hay("radio|bluetooth|rastreadores|drones|vigilancia de radio")) {
            if (hay(APAGA)) return Orden.Radio(false)
            if (hay(ACTIVA) || hay("escucha|vigila")) return Orden.Radio(true)
        }
        if (hay("zoom automatico|zoom auto|modo automatico")) return Orden.Zoom("auto")
        if (hay("cuadro entero|imagen entera|sin teselas")) return Orden.Zoom("entero")
        if (hay("teselas|zoom por trozos|zoom x2|zoom por 2")) return Orden.Zoom("teselas")
        if (hay("(borra|borrar|quita|quitar|elimina) (las |todas las )?zonas")) return Orden.BorraZonas

        // Paneles.
        if (hay("(abre|abreme|ensename|muestra|muestrame|ve a|vete a|llevame a|quiero ver)( la| los| el)? (memoria|historial|resumen|recorridos)")) return Orden.Abre("memoria")
        if (hay("(abre|abreme|ensename|muestra|muestrame|ve a|vete a|llevame a)( los| la| el)? (ajustes|configuracion|opciones)")) return Orden.Abre("ajustes")
        if (hay("(dibuja|dibujar|marca|marcar|crea|crear|hacer|haz|anade|pon)( una| la| las)? zonas?") || hay("(abre|ensename|ve a)( las)? zonas")) return Orden.Abre("zonas")
        if (hay("(abre|ensename|muestra)( la lista de| las)? (fuentes|camaras publicas|lista de camaras)")) return Orden.Abre("fuentes")
        if (Regex("^(cierra|cierralo|cerrar|cierra esto|cierra el chat|cierra los ajustes|vuelve|atras|salir)$").matches(t)) return Orden.Cierra

        // Fuentes.
        if (hay("$PIDE( la| mi)? camara del (movil|telefono|telefono movil)|camara del movil|camara trasera|usa el movil")) return Orden.Movil
        if (hay("$PIDE( la| mi)? (camara ip|camara de casa|mi camara|la tapo|camara propia)") || t == "camara ip") return Orden.CamaraIp
        if (hay("(otra|siguiente|la proxima|cambia de) camara|otra distinta|la siguiente")) return Orden.OtraCamara
        if (hay("camara (mas rapida|con menos retraso|en directo|mas en directo)|la mas rapida|menos retraso")) return Orden.Publica(null)
        // «pon una cámara de Los Ángeles», «quiero ver Londres», «busca cámaras en Minnesota»
        Regex("$PIDE( me)?( una| alguna| la| las)?( camaras?)?( publicas?)?( de trafico)? (de|en|del|desde) (.+)$").find(t)?.let { m ->
            val lugar = m.groupValues.last().removePrefix("la ").removePrefix("el ").trim()
            if (lugar.isNotEmpty() && !lugar.startsWith("movil") && !lugar.startsWith("telefono") && !lugar.startsWith("casa")) return Orden.Publica(lugar)
        }
        if (hay("$PIDE( una)? camara publica|una camara de trafico|camaras publicas")) return Orden.Publica(null)
        // «quiero ver Londres», «llévame a San Diego»: un sitio a secas.
        Regex("^(quiero ver|ensename|muestrame|llevame a|vamos a|vete a|ve a) (.+)$").find(t)?.let { m ->
            val lugar = m.groupValues[2].removePrefix("la ").removePrefix("el ").trim()
            if (!Regex("\\b(camara|movil|telefono|que|cuant|como|donde|quien|algo|nada|todo|esto|eso)").containsMatchIn(lugar)) return Orden.Publica(lugar)
        }
        return null
    }

    /** Ejemplos para quien pregunta qué puede hacer. */
    val EJEMPLOS = listOf("pon una cámara de Los Ángeles", "otra cámara", "pon mi cámara IP", "gira a la izquierda un poco",
        "acerca", "vuelve a la posición inicial", "activa el estabilizado", "dibuja una zona", "abre la memoria", "calla")
}
