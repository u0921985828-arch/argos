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
      {"cctv":{"index":"1","inService":"true","location":{"district":"4","locationName":"I-80 : Bay Bridge","nearbyPlace":"San Francisco","county":"San Francisco","latitude":"37.79","longitude":"-122.39"},
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
    pide("Caltrans: ciudad leída, sin repetir (${c[0].zona})", c[0].zona == "San Francisco")
    val todas = c + t
    fun n(q: String) = Catalogo.busca(todas, q).size
    pide("búsqueda por ciudad, que no está en el nombre", n("san francisco") == 1)
    pide("búsqueda sin tildes ni mayúsculas, palabras en cualquier orden", n("BRIDGE bay") == 1 && n("rómford") == 1)
    pide("búsqueda por país, en español o en inglés", n("Londres") == 1 && n("london") == 1 && n("Estados Unidos") == 1)
    pide("búsqueda vacía da todas; sin coincidencia, ninguna", n("  ") == 2 && n("madrid") == 0)
    // Retraso: listas como las de Caltrans (Wowza) y clips como los de TfL.
    val R = eus.argos.core.Retraso
    val maestra = R.leeHls("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x240\nchunklist_w42.m3u8\n")
    pide("HLS: la maestra apunta a su variante", maestra?.variante == "chunklist_w42.m3u8")
    val trozos = R.leeHls("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:9\n#EXTINF:4.0,\nm_9.ts\n#EXTINF:4.0,\nm_10.ts\n")!!
    pide("HLS: segmentos y duración objetivo", trozos.variante == null && trozos.segmentos == 2 && trozos.objetivoS == 4.0)
    pide("HLS sin hora: red + un segmento + tres de colchón", R.directo(trozos, 0, 0.5) == 0.5 + 4 + 12)
    val conHora = R.leeHls("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-PROGRAM-DATE-TIME:2026-10-22T10:00:00.000Z\n#EXTINF:2.0,\na.ts\n")!!
    val t0 = java.time.Instant.parse("2026-10-22T10:00:02Z").toEpochMilli()
    pide("HLS con hora: lo que va por detrás el último segmento", Math.abs(R.directo(conHora, t0 + 5000, 0.2)!! - (0.2 + 5 + 6)) < 1e-9)
    pide("HLS vacía = cámara caída; texto que no es HLS, nada",
        R.directo(R.leeHls("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")!!, 0, 0.1) == null && R.leeHls("<html>404</html>") == null)
    pide("clip: su edad; de hace más de una hora, caído; sin hora, NaN",
        R.clip(1_000, 61_000, 0.3) == 60.3 && R.clip(1_000, 1_000 + 3_700_000, 0.1) == null && R.clip(0, 5, 0.1)!!.isNaN())
    val a = c[0]; val b = t[0]
    val x = b.copy(id = "tfl:x", nombre = "Otra"); val y = b.copy(id = "tfl:y", nombre = "Sin medir")
    val orden = R.ordena(listOf(y, x, b, a), mapOf(a.id to 30.0, b.id to 12.0, x.id to Double.NaN), setOf("tfl:nada"))
    pide("orden: de menos a más retraso, luego sin dato, luego sin medir", orden.map { it.id } == listOf(b.id, a.id, x.id, y.id))
    pide("orden: las caídas no salen", R.ordena(listOf(a, b), emptyMap(), setOf(a.id)) == listOf(b))
    val muchas = (1..30).map { a.copy(id = "caltrans:4:$it") } + (1..30).map { a.copy(id = "caltrans:7:$it") } + (1..5).map { b.copy(id = "tfl:$it") }
    val m = R.muestra(muchas, 9, 1)
    pide("muestra: de cada sitio un poco", m.size == 9 && m.count { it.id.startsWith("tfl") } == 3 && m.count { it.id.startsWith("caltrans:7") } == 3)
    pide("JSON roto no revienta", Catalogo.caltrans("{\"data\":[{\"cctv\":").isEmpty() && Catalogo.tfl("nada").isEmpty())
    if (args.getOrNull(0) == "vivo") {
        val dir = File(args[1])
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val l = if (f.name.startsWith("tfl")) Catalogo.tfl(f.readText()) else Catalogo.caltrans(f.readText())
            pide("en vivo ${f.name}: ${l.size} cámaras con vídeo", l.isNotEmpty())
            // Se busca por ciudad: si el organismo deja de darla, la búsqueda vuelve a fallar.
            if (!f.name.startsWith("tfl")) {
                val conZona = l.count { it.zona.isNotEmpty() }
                pide("en vivo ${f.name}: $conZona con ciudad o condado (p. ej. «${l.firstOrNull { it.zona.isNotEmpty() }?.zona}»)", conZona * 2 >= l.size)
            }
        }
    }
    println(if (fallos == 0) "CATÁLOGO: CUMPLE" else "CATÁLOGO: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}
