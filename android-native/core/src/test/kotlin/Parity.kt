import eus.argos.core.DetBox
import eus.argos.core.Engine
import java.io.File

/**
 * Prueba de paridad: la misma secuencia por el JS y por el puerto Kotlin.
 *
 * El criterio es **cero divergencias**, no «parecido». Dos implementaciones del
 * mismo algoritmo que se separan poco a poco son la peor forma de deuda: nadie
 * se entera hasta que las cajas de una y otra ya no coinciden y no hay forma de
 * saber cuál tenía razón.
 *
 * Los frames se generan aquí con el mismo generador que el volcado JS --- mismo
 * LCG, mismos rectángulos, mismas cuentas en coma flotante --- para que la
 * única diferencia posible sea el código bajo prueba.
 */

const val W = 320
const val H = 180
const val SRCW = 1280
const val SRCH = 720
const val N = 8

fun bgPlate(): ByteArray {
    val d = ByteArray(W * H * 4)
    var s = 12345
    for (i in 0 until W * H) {
        s = s * 1664525 + 1013904223          // Int de 32 bits: mismo desbordamiento que `imul`
        val v = 60 + ((s ushr 24) % 40)
        d[i * 4] = v.toByte()
        d[i * 4 + 1] = v.toByte()
        d[i * 4 + 2] = (v + 5).toByte()
        d[i * 4 + 3] = 255.toByte()
    }
    return d
}

class Obj(var x: Double, var y: Double, val vx: Double, var vy: Double, val w: Int, val h: Int)

fun main(args: Array<String>) {
    val plate = bgPlate()
    val objs = (0 until N).map { k ->
        Obj(((k * 37) % W).toDouble(), (40 + (k * 13) % 90).toDouble(),
            0.4 + (k % 3) * 0.17, if (k % 2 != 0) 0.07 else -0.07, 7, 16)
    }
    fun frame(): ByteArray {
        val d = plate.copyOf()
        for (o in objs) {
            o.x = (o.x + o.vx) % W
            o.y += o.vy
            if (o.y < 30 || o.y > 150) o.vy = -o.vy
            val x0 = Math.floor(o.x).toInt()
            val y0 = Math.floor(o.y).toInt()
            var yy = y0
            while (yy < y0 + o.h && yy < H) {
                var xx = x0
                while (xx < x0 + o.w && xx < W) {
                    val p = (yy * W + xx) * 4
                    d[p] = 216.toByte(); d[p + 1] = 210.toByte()
                    d[p + 2] = 196.toByte(); d[p + 3] = 255.toByte()
                    xx++
                }
                yy++
            }
        }
        return d
    }

    val RX = 40; val RY = 20
    val SX = SRCW.toDouble() / W; val SY = SRCH.toDouble() / H
    val times = mutableListOf<Double>()
    fun pasada(conDet: Boolean): List<String> {
        for (k in 0 until N) {
            objs[k].x = ((k * 37) % W).toDouble(); objs[k].y = (40 + (k * 13) % 90).toDouble()
            objs[k].vy = if (k % 2 != 0) 0.07 else -0.07
        }
        val e = Engine()
        val out = mutableListOf<String>()
        for (f in 0 until 260) {
            val data = frame()
            val det = if (!conDet) null else objs.map { o ->
                val fx = Math.floor(o.x); val fy = Math.floor(o.y)
                DetBox(RX + fx * SX, RY + fy * SY, RX + (fx + o.w) * SX, RY + (fy + o.h) * SY,
                       "person", 0.9)
            }
            val r = e.analyze(data, W, H, SRCW, SRCH, det, if (conDet) RX else 0, if (conDet) RY else 0)
            if (!conDet) times.add(r.ms)
            out.add(r.boxes.joinToString(";") {
                "${it.id},${it.klass}," + (if (conDet) "${it.klassSrc}," else "") +
                    "${fmt(it.x1)},${fmt(it.y1)},${fmt(it.x2)},${fmt(it.y2)}"
            })
        }
        return out
    }
    val mine = pasada(false)
    val mineDet = pasada(true)

    // El volcado de referencia llega por argumento; por defecto, donde lo deja
    // `dump_js.js`. Estaba fijado a una ruta absoluta de otra maquina.
    val refFile = File(if (args.isNotEmpty()) args[0] else "android-native/build/ref_js.txt")
    if (!refFile.exists()) {
        println("falta el volcado de referencia en ${refFile.path}")
        println("generalo antes con: node scripts/dump_js.js")
        System.exit(1)
    }
    val ref = refFile.readLines()
    var dif = 0
    var primero = -1
    for (i in mine.indices) {
        val r = if (i < ref.size) ref[i] else ""
        if (mine[i] != r) { dif++; if (primero < 0) primero = i }
    }
    println("C1 paridad: ${mine.size} frames · ${dif} divergencias " +
        if (dif == 0) "-> IDENTICOS" else "-> DIVERGEN (primero en frame $primero)\n  JS : ${ref.getOrNull(primero)}\n  KT : ${mine.getOrNull(primero)}")

    val refDet = File(refFile.path.replace(Regex("\\.txt$"), "_det.txt")).readLines()
    var difD = 0; var primD = -1
    for (i in mineDet.indices) {
        val r = if (i < refDet.size) refDet[i] else ""
        if (mineDet[i] != r) { difD++; if (primD < 0) primD = i }
    }
    val conCajas = mineDet.count { it.isNotEmpty() }
    println("C1 paridad con detector: ${mineDet.size} frames ($conCajas con cajas) · ${difD} divergencias " +
        if (difD == 0) "-> IDENTICOS" else "-> DIVERGEN (primero en frame $primD)\n  JS : ${refDet.getOrNull(primD)}\n  KT : ${mineDet.getOrNull(primD)}")

    // Una paridad que imprime «DIVERGEN» y sale con 0 pasa cualquier CI.
    if (dif != 0 || difD != 0) { println("PARIDAD ROTA"); System.exit(1) }

    // Se descarta el calentamiento: el modelo de fondo no analiza nada hasta
    // que se asienta, y medir esos frames rebaja la media sin motivo.
    val calientes = times.drop(60).sorted()
    println("C2 velocidad: analyze() mediana ${"%.3f".format(calientes[calientes.size / 2])} ms " +
        "(JS medido: 2.58 ms)")
}

fun fmt(v: Double): String = "%.4f".format(java.util.Locale.ROOT, v)
