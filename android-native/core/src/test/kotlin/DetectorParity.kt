package eus.argos.paridad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import eus.argos.core.YoloxDetector
import java.io.File
import java.nio.FloatBuffer

/**
 * Paridad del detector: las mismas fotos, el mismo búfer RGBA, por
 * `detector.js` (ONNX Runtime Web) y por `Detector.kt` (ONNX Runtime Java).
 *
 * Criterio C1: mismo número de objetos, misma clase agrupada, cajas a ±0,5 px
 * y puntuación a ±1e-3. No se exige igualdad de bits: los dos runtimes no
 * suman en el mismo orden y la última cifra de un float puede moverse.
 *
 * El caso reservado va aquí también: la misma foto con cada fila rellenada
 * hasta un múltiplo de 64 bytes, como entrega CameraX. Tiene que dar lo mismo.
 *
 * Dos modos: cuadro entero y teselas ×2 con la pasada completa.
 *
 * Generar la referencia (lo hace el CI, y se puede repetir en cualquier sitio):
 *   python3 scripts/prep_detector_ref.py DIR bus.jpg zidane.jpg dog.jpg
 *   node scripts/ref_detector.js DIR            (con yolox_nano.onnx en DIR)
 *   ./gradlew :core:paridadDetector            (DIR = android-native/build/det)
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "android-native/build/det" })
    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    val sess = env.createSession(File(dir, "yolox_nano.onnx").path, opts)
    val inName = sess.inputNames.first()
    val det = YoloxDetector(416) { tensor ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 416, 416)).use { t ->
            sess.run(mapOf(inName to t)).use { r ->
                val v = r[0] as OnnxTensor
                val fb = v.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }
    // meta.json: {"bus": [810, 1080], ...} --- leído a mano, sin dependencias.
    val meta = Regex("\"(\\w+)\": \\[(\\d+), (\\d+)\\]")
        .findAll(File(dir, "meta.json").readText())
        .map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.toList()
    val ref = File(dir, "ref_det_js.txt").readLines().filter { it.isNotBlank() }.map { it.split(" ") }

    var fallos = 0
    val tiempos = mutableListOf<Double>()
    for ((modo, zoom) in listOf("completo" to 0.0, "x2" to 2.0))
    for ((name, w, h) in meta) {
        val rgba = File(dir, "$name.rgba").readBytes()
        val mine = det.detect(rgba, w * 4, w, h, zoom)
        if (zoom == 0.0) tiempos.add(det.lastInferMs)
        val theirs = ref.filter { it[0] == modo && it[1] == name }.map { it.drop(1) }
        var ok = mine.size == theirs.size
        val peor = DoubleArray(2)
        if (ok) for (i in mine.indices) {
            val a = mine[i]; val b = theirs[i]
            val db = maxOf(Math.abs(a.x1 - b[4].toDouble()), Math.abs(a.y1 - b[5].toDouble()),
                           Math.abs(a.x2 - b[6].toDouble()), Math.abs(a.y2 - b[7].toDouble()))
            val ds = Math.abs(a.score - b[3].toDouble())
            peor[0] = maxOf(peor[0], db); peor[1] = maxOf(peor[1], ds)
            if (a.klass != b[1] || db > 0.5 || ds > 1e-3) ok = false
        }
        if (!ok) fallos++
        println("C1 $modo $name: KT ${mine.size} · JS ${theirs.size} · caja ±${"%.4f".format(peor[0])} px · " +
                "puntuación ±${"%.2e".format(peor[1])} -> ${if (ok) "IGUALES" else "DISTINTOS"}")
        if (!ok) {
            println("   KT: " + mine.joinToString { "${it.klass} ${"%.3f".format(it.score)}" })
            println("   JS: " + theirs.joinToString { "${it[1]} ${"%.3f".format(it[3].toDouble())}" })
        }

        // Caso reservado: filas rellenadas como las entrega CameraX.
        val stride = (w * 4 + 63) / 64 * 64 + 64
        val pad = ByteArray(stride * h) { 7 }
        for (y in 0 until h) System.arraycopy(rgba, y * w * 4, pad, y * stride, w * 4)
        val padded = det.detect(pad, stride, w, h, zoom)
        val same = padded.size == mine.size && padded.indices.all {
            padded[it].x1 == mine[it].x1 && padded[it].y2 == mine[it].y2 && padded[it].score == mine[it].score
        }
        if (!same) fallos++
        println("   reservado $modo $name (rowStride $stride en vez de ${w * 4}): " +
                if (same) "IDÉNTICO" else "DISTINTO (${padded.size} frente a ${mine.size})")
    }
    println("inferencia ORT Java, 1 hilo: " + tiempos.joinToString { "%.0f ms".format(it) })
    println(if (fallos == 0) "C1 detector: TODO IGUAL" else "C1 detector: $fallos FALLOS")
    if (fallos != 0) System.exit(1)
}
