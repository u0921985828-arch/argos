package eus.argos.paridad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import eus.argos.core.YoloxDetector
import java.io.File
import java.nio.FloatBuffer

/** Detector con ORT Java sobre el modelo de DIR. Compartido por los bancos. */
fun detectorDe(dir: File): YoloxDetector {
    val env = OrtEnvironment.getEnvironment()
    val sess = env.createSession(File(dir, "yolox_nano.onnx").path,
                                 OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    val inName = sess.inputNames.first()
    return YoloxDetector(416) { tensor ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 416, 416)).use { t ->
            sess.run(mapOf(inName to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }
}

fun metaDe(dir: File) = Regex("\"(\\w+)\": \\[(\\d+), (\\d+)\\]")
    .findAll(File(dir, "meta.json").readText())
    .map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.toList()
