package eus.argos.core

/**
 * Estabilizado de la imagen en pantalla, con el movimiento de cámara que ya
 * estima el núcleo (`Engine.camX/camY`, en píxeles de fuente).
 *
 * Se desplaza la vista lo justo para quitar el TEMBLOR y se deja pasar el
 * movimiento intencionado (un paneo): el desplazamiento es la diferencia entre
 * dos medias del camino de la cámara: una rápida, adelantada lo que tarda en
 * llegar, y una lenta (~0,5 s).
 * La rápida, en vez del valor crudo, por dos motivos: el estimador da pasos de
 * píxel entero, que a escala de pantalla saltan, y el análisis llega con unos
 * 50 ms de retraso respecto a la vista previa, así que corregir un temblor de
 * 8 Hz con ese retraso lo empeoraría. Lo lento (balanceo de la mano, 1-3 Hz)
 * es lo que se ve y lo que esto quita.
 *
 * El desplazamiento se limita a `maxFrac` del ancho; la vista se amplía un poco
 * para que no se vean los bordes al moverla.
 */
class Estabilizador(private val rapida: Double = 0.8, private val lenta: Double = 0.08,
                    private val maxFrac: Double = 0.05, private val adelanto: Double = 1.0) {
    private var fx = Double.NaN; private var fy = 0.0
    private var vx = 0.0; private var vy = 0.0
    private var lx = 0.0; private var ly = 0.0
    var dx = 0.0; private set
    var dy = 0.0; private set

    /** Un frame: posición acumulada de la cámara y ancho de la imagen. Devuelve el desplazamiento a aplicar. */
    fun paso(camX: Double, camY: Double, ancho: Int): Pair<Double, Double> {
        if (fx.isNaN()) { fx = camX; fy = camY; lx = camX; ly = camY; vx = 0.0; vy = 0.0 }
        val px = fx; val py = fy
        fx += (camX - fx) * rapida; fy += (camY - fy) * rapida
        vx += ((fx - px) - vx) * 0.5; vy += ((fy - py) - vy) * 0.5
        // Se adelanta un frame, lo que tarda en llegar el análisis. Sin esto la
        // corrección llega tarde y el balanceo apenas baja (medido: −12 %;
        // con el adelanto, −60 %; adelantar más empeora).
        val ax = fx + vx * adelanto; val ay = fy + vy * adelanto
        lx += (ax - lx) * lenta; ly += (ay - ly) * lenta
        val lim = maxFrac * ancho
        dx = ax - lx; dy = ay - ly
        // Si se pasa del límite, la media lenta se arrastra: un paneo largo no
        // deja la vista pegada al borde.
        if (Math.abs(dx) > lim) { lx = ax - Math.signum(dx) * lim; dx = Math.signum(dx) * lim }
        if (Math.abs(dy) > lim) { ly = ay - Math.signum(dy) * lim; dy = Math.signum(dy) * lim }
        return dx to dy
    }

    /** Encuadre nuevo (la cámara se reinició): se empieza de cero sin salto. */
    fun reinicia() { fx = Double.NaN; dx = 0.0; dy = 0.0 }

    /** Cuánto ampliar la vista para que el desplazamiento máximo no enseñe bordes. */
    val zoom: Double get() = 1.0 + 2 * maxFrac + 0.01
}
