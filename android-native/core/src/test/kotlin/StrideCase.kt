package eus.argos.paridad

import eus.argos.core.Engine
import eus.argos.core.downscaleRgba

/**
 * Caso reservado: el MISMO vídeo de cámara entregado con filas sin relleno y
 * con filas rellenadas como lo hace CameraX (`rowStride` > anchura × 4, con
 * basura en el relleno). Las pistas tienen que ser idénticas frame a frame.
 *
 * Un `rowStride` mal usado no da error: cizalla la imagen, el modelo de fondo
 * ve otra escena y las cajas se desplazan en diagonal.
 */
fun main() {
    val sw = 640; val sh = 480; val aw = 320; val ah = 240
    val tight = sw * 4
    val padStride = tight + 96
    val objs = (0 until 6).map { k -> doubleArrayOf(((k * 97) % sw).toDouble(), (60 + (k * 53) % 300).toDouble(), 1.2 + k * 0.3) }
    var seed = 777
    val plate = ByteArray(sw * sh * 4).also {
        for (i in 0 until sw * sh) { seed = seed * 1103515245 + 12345; val v = 70 + ((seed ushr 25) % 30)
            it[i * 4] = v.toByte(); it[i * 4 + 1] = (v + 3).toByte(); it[i * 4 + 2] = v.toByte(); it[i * 4 + 3] = -1 }
    }
    val eA = Engine(); val eB = Engine()
    val smallA = ByteArray(aw * ah * 4); val smallB = ByteArray(aw * ah * 4)
    var dif = 0; var conCajas = 0
    for (f in 0 until 220) {
        val a = plate.copyOf()
        for (o in objs) {
            o[0] = (o[0] + o[2]) % sw
            val x0 = o[0].toInt(); val y0 = o[1].toInt()
            for (y in y0 until minOf(sh, y0 + 40)) for (x in x0 until minOf(sw, x0 + 16)) {
                val p = (y * sw + x) * 4; a[p] = 220.toByte(); a[p + 1] = 205.toByte(); a[p + 2] = 190.toByte()
            }
        }
        val b = ByteArray(padStride * sh) { (it * 31).toByte() }   // relleno con basura
        for (y in 0 until sh) System.arraycopy(a, y * tight, b, y * padStride, tight)
        downscaleRgba(a, tight, sw, sh, smallA, aw, ah)
        downscaleRgba(b, padStride, sw, sh, smallB, aw, ah)
        val ra = eA.analyze(smallA, aw, ah, sw, sh)
        val rb = eB.analyze(smallB, aw, ah, sw, sh)
        val sa = ra.boxes.joinToString(";") { "${it.id},${it.x1},${it.y1},${it.x2},${it.y2}" }
        val sb = rb.boxes.joinToString(";") { "${it.id},${it.x1},${it.y1},${it.x2},${it.y2}" }
        if (sa != sb) dif++
        if (ra.boxes.isNotEmpty()) conCajas++
    }
    println("Caso reservado rowStride $padStride frente a $tight: 220 frames ($conCajas con pistas) · " +
            "$dif divergencias -> ${if (dif == 0 && conCajas > 0) "IDÉNTICO" else "FALLA"}")
    if (dif != 0 || conCajas == 0) System.exit(1)
}
