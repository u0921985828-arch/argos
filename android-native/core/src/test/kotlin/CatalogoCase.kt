package eus.argos.paridad

import eus.argos.core.Catalogo
import java.io.File

/**
 * El catálogo de cámaras públicas: se leen los formatos de Caltrans y de TfL
 * tal como los describe `cameras.js`, solo cámaras con vídeo y en servicio.
 * Con `vivo <dir>` se leen además los ficheros que el CI baja de los propios
 * organismos: si una respuesta llega y no sale ninguna cámara, el formato ha
 * cambiado y hay que saberlo antes que el usuario.
 */
fun main(args: Array<String>) {
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val caltrans = """{"data":[
      {"cctv":{"index":"1","inService":"true","location":{"district":"4","locationName":"I-80 : Bay Bridge","latitude":"37.79","longitude":"-122.39"},
        "imageData":{"streamingVideoURL":"https://wzmedia.dot.ca.gov/D4/E80_at_Bay_Bridge.stream/playlist.m3u8","static":{"currentImageURL":"https://cwwp2.dot.ca.gov/x.jpg"}}}},
      {"cctv":{"index":"2","inService":"true","location":{"district":"4","locationName":"Solo foto","latitude":"37.7","longitude":"-122.4"},
        "imageData":{"streamingVideoURL":"Not Reported","static":{"currentImageURL":"https://cwwp2.dot.ca.gov/y.jpg"}}}},
      {"cctv":{"index":"3","inService":"false","location":{"district":"4","locationName":"Fuera de servicio á","latitude":"37.6","longitude":"-122.3"},
        "imageData":{"streamingVideoURL":"https://wzmedia.dot.ca.gov/D4/z.m3u8"}}}
    ]}"""
    val c = Catalogo.caltrans(caltrans)
    pide("Caltrans: solo la cámara con vídeo y en servicio (${c.size})", c.size == 1 && c[0].nombre == "I-80 : Bay Bridge" && c[0].directo)
    pide("Caltrans: posición leída", Math.abs(c[0].lat - 37.79) < 1e-9 && Math.abs(c[0].lon + 122.39) < 1e-9)
    val tfl = """[{"id":"JamCams_00001.01251","commonName":"Romford Rd/Tennyson Rd","lat":51.5444,"lon":0.0198,
        "additionalProperties":[{"key":"available","value":"true"},{"key":"videoUrl","value":"https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.01251.mp4"}]},
      {"id":"JamCams_2","commonName":"Apagada","lat":51.5,"lon":0.0,
        "additionalProperties":[{"key":"available","value":"false"},{"key":"videoUrl","value":"https://x/2.mp4"}]}]"""
    val t = Catalogo.tfl(tfl)
    pide("TfL: solo la disponible (${t.size}), en clips", t.size == 1 && t[0].video.endsWith(".mp4") && !t[0].directo)
    pide("JSON roto no revienta", Catalogo.caltrans("{\"data\":[{\"cctv\":").isEmpty() && Catalogo.tfl("nada").isEmpty())
    if (args.getOrNull(0) == "vivo") {
        val dir = File(args[1])
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val n = if (f.name.startsWith("tfl")) Catalogo.tfl(f.readText()).size else Catalogo.caltrans(f.readText()).size
            pide("en vivo ${f.name}: $n cámaras con vídeo", n > 0)
        }
    }
    println(if (fallos == 0) "CATÁLOGO: CUMPLE" else "CATÁLOGO: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
