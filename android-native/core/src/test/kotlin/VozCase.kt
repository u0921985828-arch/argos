package eus.argos.paridad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import eus.argos.core.Fonetica
import eus.argos.core.Normaliza
import eus.argos.core.VozPiper
import eus.argos.core.frase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * La voz: fonética contra espeak-ng y, si hay un modelo, audio de verdad.
 *
 * `voz-espeak.tsv` tiene palabras y frases de las respuestas de ARGOS con lo
 * que espeak-ng 1.51 («es») devuelve para ellas; se generó una vez con
 * espeak-ng instalado, para que el CI no lo necesite. Se exige:
 *  - palabras sueltas idénticas a espeak en ≥ 95 %;
 *  - palabras dentro de frase idénticas en ≥ 85 % (espeak acentúa a veces
 *    las átonas según lo que viene detrás);
 *  - números, horas y unidades dichos bien.
 * Con `voz <modelo.onnx> <salida.wav> <texto>` sintetiza y escribe un WAV.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "voz") {
        // El texto de un fichero si empieza por @: la JVM no siempre lee bien las tildes de la línea de órdenes.
        val t = args.drop(3).joinToString(" ")
        sintetiza(File(args[1]), File(args[2]), if (t.startsWith("@")) File(t.drop(1)).readText() else t); return
    }
    var fallos = 0
    fun pide(n: String, ok: Boolean) { println((if (ok) "ok    " else "FALLA ") + n); if (!ok) fallos++ }
    val tsv = File(args.getOrElse(0) { "src/test/resources/voz-espeak.tsv" }).readLines().map { it.split("\t") }
    val palabras = tsv.filter { it[0] == "p" }; val frases = tsv.filter { it[0] == "f" }
    val okP = palabras.count { Fonetica.palabra(it[1]) == it[2] }
    pide("palabras sueltas como espeak: $okP/${palabras.size}", okP >= palabras.size * 0.95)
    var tot = 0; var ok = 0
    for (f in frases) {
        val ref = f[2].split(" ").filter { it.isNotBlank() }
        val mio = Fonetica.frase(f[1]).replace(Regex("[,.:!?]"), " ").split(" ").filter { it.isNotBlank() }
        // Alineadas por la subsecuencia común más larga: una palabra de más no
        // descuadra el resto.
        val dp = Array(ref.size + 1) { IntArray(mio.size + 1) }
        for (x in 1..ref.size) for (y in 1..mio.size)
            dp[x][y] = if (ref[x - 1] == mio[y - 1]) dp[x - 1][y - 1] + 1 else maxOf(dp[x - 1][y], dp[x][y - 1])
        tot += maxOf(ref.size, mio.size)
        ok += dp[ref.size][mio.size]
    }
    pide("palabras en frase como espeak: $ok/$tot", ok >= tot * 0.85)
    for ((t, e) in listOf(
        "1 persona" to "una persona", "21 personas" to "veintiuna personas", "1 vehículo" to "un vehículo",
        "a las 18:07" to "a las dieciocho y siete", "a las 9:00" to "a las nueve en punto",
        "28,8 km/h" to "veintiocho coma ocho kilómetros por hora", "120 m" to "ciento veinte metros",
        "1.250 eventos" to "mil doscientos cincuenta eventos", "GPS" to "ge pe ese", "100" to "cien",
        "2.000.000" to "dos millones")) pide("«$t» → «${Normaliza.texto(t)}»", Normaliza.texto(t) == e)
    println(if (fallos == 0) "VOZ: CUMPLE" else "VOZ: FALLA ($fallos)")
    if (fallos > 0) System.exit(1)
}

private fun sintetiza(modelo: File, wav: File, texto: String) {
    val env = OrtEnvironment.getEnvironment()
    val ses = env.createSession(modelo.absolutePath, OrtSession.SessionOptions())
    val voz = VozPiper(File(modelo.path + ".json").readText()) { ids, esc ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { a ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)).use { b ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(esc), longArrayOf(3)).use { c ->
            ses.run(mapOf("input" to a, "input_lengths" to b, "scales" to c)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) }
            } } } }
    }
    val t0 = System.nanoTime()
    val pcm = VozPiper.pcm16(voz.habla(texto))
    val ms = (System.nanoTime() - t0) / 1_000_000
    val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size * 2); bb.put("WAVEfmt ".toByteArray()); bb.putInt(16)
    bb.putShort(1); bb.putShort(1); bb.putInt(voz.muestreo); bb.putInt(voz.muestreo * 2); bb.putShort(2); bb.putShort(16)
    bb.put("data".toByteArray()); bb.putInt(pcm.size * 2); pcm.forEach { bb.putShort(it) }
    wav.writeBytes(bb.array())
    println("voz: %.2f s de audio en %d ms · %s".format(pcm.size / voz.muestreo.toDouble(), ms, Fonetica.frase(texto)))
}
