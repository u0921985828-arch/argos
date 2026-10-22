package eus.argos.paridad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import eus.argos.core.EfectoVoz
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
    if (args.isNotEmpty() && args[0] == "efecto") { efectoWav(File(args[1]), File(args[2]), EfectoVoz.Tipo.valueOf(args[3])); return }
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
    // El efecto: menos agudos, menos diferencia entre fuerte y flojo, silencio
    // entre palabras de verdad silencioso, y sin pasarse de escala.
    run {
        val sr = 22050
        val r = java.util.Random(5)
        // Una «voz» sintética a ráfagas de 500 ms (250 ms fuerte, 250 ms a un
        // cuarto, como dos sílabas) con siseo entre ellas.
        val a = FloatArray(sr * 2) { i ->
            val t = i / sr.toDouble(); val on = ((t * 2).toInt() % 2 == 0)
            val voz = if (on) (0.6 * Math.sin(2 * Math.PI * 140 * t) + 0.3 * Math.sin(2 * Math.PI * 5600 * t)) * (if ((t * 4).toInt() % 2 == 0) 1.0 else 0.25) else 0.0
            (voz + r.nextGaussian() * 0.01).toFloat()
        }
        val b = EfectoVoz.aplica(a, sr, EfectoVoz.Tipo.RADIO)
        fun agudos(x: FloatArray): Double { var e = 0.0; var p = 0f; for (v in x) { e += (v - p) * (v - p).toDouble(); p = v }; return e / x.sumOf { it * it.toDouble() }.coerceAtLeast(1e-12) }
        val silA = a.sliceArray(sr / 2 + 200 until sr - 200).map { Math.abs(it) }.average()
        val silB = b.sliceArray(sr / 2 + 2000 until sr - 200).map { Math.abs(it) }.average()
        pide("efecto: agudos %.3f → %.3f".format(agudos(a), agudos(b)), agudos(b) < agudos(a) * 0.5)
        // Rango dinámico al hablar: ventanas de 10 ms dentro de una ráfaga, la
        // más fuerte frente a la más floja (p90/p10).
        fun rango(x: FloatArray): Double {
            val v = (0 until sr / 2 - 220 step 220).map { k -> Math.sqrt((k until k + 220).sumOf { x[it] * x[it].toDouble() } / 220) }.sorted()
            return 20 * Math.log10(v[v.size * 9 / 10] / v[v.size / 10].coerceAtLeast(1e-9))
        }
        pide("efecto: rango dinámico al hablar %.1f dB → %.1f dB".format(rango(a), rango(b)), rango(b) < rango(a) - 5)
        pide("efecto: siseo entre palabras %.4f → %.5f".format(silA, silB), silB < silA * 0.1)
        pide("efecto: sin pasarse de escala", b.all { Math.abs(it) <= 1.0f })
        pide("efecto «limpia» no toca nada", EfectoVoz.aplica(a, sr, EfectoVoz.Tipo.LIMPIA) === a)
    }
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

private fun efectoWav(entra: File, sale: File, tipo: EfectoVoz.Tipo) {
    val b = ByteBuffer.wrap(entra.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val sr = b.getInt(24); val n = (b.capacity() - 44) / 2
    val a = FloatArray(n) { b.getShort(44 + it * 2) / 32768f }
    val pcm = VozPiper.pcm16(EfectoVoz.aplica(a, sr, tipo))
    val o = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    o.put("RIFF".toByteArray()); o.putInt(36 + pcm.size * 2); o.put("WAVEfmt ".toByteArray()); o.putInt(16)
    o.putShort(1); o.putShort(1); o.putInt(sr); o.putInt(sr * 2); o.putShort(2); o.putShort(16)
    o.put("data".toByteArray()); o.putInt(pcm.size * 2); pcm.forEach { o.putShort(it) }
    sale.writeBytes(o.array())
    println("efecto ${tipo.texto}: ${sale.name}")
}
