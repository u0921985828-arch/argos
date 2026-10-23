package eus.argos.paridad

import eus.argos.core.Orden
import eus.argos.core.Ordenes

/** Lo que se le pide a ARGOS en voz alta, como lo dice la gente. */
fun main() {
    var fallos = 0
    fun es(frase: String, esperado: Orden?) {
        val o = Ordenes.lee(frase)
        val ok = o == esperado
        println((if (ok) "ok    " else "FALLA ") + "«$frase» → $o"); if (!ok) fallos++
    }
    es("Pon una cámara de Los Ángeles", Orden.Publica("los angeles"))
    es("quiero ver Londres", Orden.Publica("londres"))
    es("búscame cámaras en Minnesota", Orden.Publica("minnesota"))
    es("ponme la cámara con menos retraso", Orden.Publica(null))
    es("otra cámara", Orden.OtraCamara)
    es("pon la cámara del móvil", Orden.Movil)
    es("conecta mi cámara IP", Orden.CamaraIp)
    es("gira a la izquierda", Orden.Mueve(-0.5, 0.0, 0.0, 800))
    es("mueve la cámara un poco a la derecha", Orden.Mueve(0.5, 0.0, 0.0, 350))
    es("apunta mucho más arriba", Orden.Mueve(0.0, 0.5, 0.0, 1800))
    es("baja la cámara", Orden.Mueve(0.0, -0.5, 0.0, 800))
    es("acércate", Orden.Mueve(0.0, 0.0, 0.5, 800))
    es("aleja un poco", Orden.Mueve(0.0, 0.0, -0.5, 350))
    es("vuelve a la posición inicial", Orden.ACasa)
    es("activa el estabilizado", Orden.Estabilizar(true))
    es("quita la estabilización", Orden.Estabilizar(false))
    es("enciende la radio", Orden.Radio(true))
    es("apaga el bluetooth", Orden.Radio(false))
    es("zoom automático", Orden.Zoom("auto"))
    es("borra todas las zonas", Orden.BorraZonas)
    es("dibuja una zona", Orden.Abre("zonas"))
    es("ábreme la memoria", Orden.Abre("memoria"))
    es("abre los ajustes", Orden.Abre("ajustes"))
    es("cierra", Orden.Cierra)
    es("¡Cállate!", Orden.Calla)
    // Preguntas: no son órdenes, siguen a Ask.
    es("cuántas personas pasaron hoy", null)
    es("qué ves", null)
    es("hay algún dron", null)
    es("qué puedes hacer", null)
    es("a qué velocidad iba el coche", null)
    println(if (fallos == 0) "ORDENES: CUMPLE" else "ORDENES: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
