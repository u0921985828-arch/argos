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
    // El acabado cálido: limpio, con cuerpo y sin sibilancias; NO de radio. Con una «voz» sintética de
    // ráfagas (dos sílabas, una fuerte y otra a un cuarto), un retumbe de
    // 40 Hz debajo y siseo entre ráfagas, se exige:
    //  - el siseo entre palabras baja más de 20 dB;
    //  - el retumbe de 40 Hz baja más de 6 dB;
    //  - los graves de la voz (150 Hz) suben frente a la presencia (3,2 kHz);
    //  - las sibilancias (7,5 kHz) bajan frente a la presencia más de 3 dB;
    //  - el rango dinámico al hablar baja;
    //  - nada pasa de escala, y «natural» no toca la señal.
    run {
        val sr = 22050
        val r = java.util.Random(5)
        fun tono(t: Double, f: Double) = Math.sin(2 * Math.PI * f * t)
        val a = FloatArray(sr * 2) { i ->
            val t = i / sr.toDouble(); val on = ((t * 2).toInt() % 2 == 0)
            val g = if ((t * 4).toInt() % 2 == 0) 1.0 else 0.25
            val voz = if (on) g * (0.4 * tono(t, 280.0) + 0.25 * tono(t, 3200.0) + 0.12 * tono(t, 7500.0) + 0.2 * tono(t, 150.0)) else 0.0
            (voz + 0.15 * tono(t, 40.0) * (if (on) 1.0 else 0.0) + r.nextGaussian() * 0.01).toFloat()
        }
        val b = EfectoVoz.aplica(a, sr, EfectoVoz.Tipo.ESTUDIO)
        // Amplitud de una frecuencia en el tramo [i0, i1) (correlación con seno y coseno).
        fun amp(x: FloatArray, f: Double, i0: Int, i1: Int): Double {
            var s = 0.0; var c = 0.0
            for (k in i0 until i1) { val w = 2 * Math.PI * f * k / sr; s += x[k] * Math.sin(w); c += x[k] * Math.cos(w) }
            return 2 * Math.hypot(s, c) / (i1 - i0)
        }
        fun db(v: Double) = 20 * Math.log10(v.coerceAtLeast(1e-9))
        val i0 = 1000; val i1 = sr / 4 - 200            // dentro de la sílaba fuerte
        fun rel(x: FloatArray, f: Double) = db(amp(x, f, i0, i1)) - db(amp(x, 1000.0, i0, i1).coerceAtLeast(amp(x, 150.0, i0, i1)))
        val silA = a.sliceArray(sr / 2 + 2000 until sr - 200).map { Math.abs(it) }.average()
        val silB = b.sliceArray(sr / 2 + 2000 until sr - 200).map { Math.abs(it) }.average()
        pide("acabado: siseo entre palabras %.0f dB".format(db(silB / silA)), db(silB / silA) < -20)
        val ret = (db(amp(b, 40.0, i0, i1)) - db(amp(b, 150.0, i0, i1))) - (db(amp(a, 40.0, i0, i1)) - db(amp(a, 150.0, i0, i1)))
        pide("acabado: retumbe de 40 Hz %.1f dB".format(ret), ret < -6)
        val graves = (db(amp(b, 150.0, i0, i1)) - db(amp(b, 3200.0, i0, i1))) - (db(amp(a, 150.0, i0, i1)) - db(amp(a, 3200.0, i0, i1)))
        pide("acabado: graves frente a presencia %+.1f dB".format(graves), graves > 1)
        val sib = (db(amp(b, 7500.0, i0, i1)) - db(amp(b, 3200.0, i0, i1))) - (db(amp(a, 7500.0, i0, i1)) - db(amp(a, 3200.0, i0, i1)))
        pide("acabado: sibilancias frente a presencia %+.1f dB".format(sib), sib < -3)
        fun rango(x: FloatArray): Double {
            fun rms(i0: Int, i1: Int) = Math.sqrt((i0 until i1).sumOf { x[it] * x[it].toDouble() } / (i1 - i0))
            val f = rms(sr * 6 / 100, sr / 4); val d = rms(sr / 4 + sr * 6 / 100, sr / 2)
            return 20 * Math.log10(f / d.coerceAtLeast(1e-9))
        }
        pide("acabado: rango dinámico al hablar %.1f → %.1f dB".format(rango(a), rango(b)), rango(b) < rango(a) - 3)
        pide("acabado: sin pasarse de escala", b.all { Math.abs(it) <= 1.0f })
        pide("acabado «natural» no toca nada", EfectoVoz.aplica(a, sr, EfectoVoz.Tipo.NATURAL) === a)
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
