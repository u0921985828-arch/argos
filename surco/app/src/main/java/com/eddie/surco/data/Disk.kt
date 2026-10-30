package com.eddie.surco.data

import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File

/**
 * Lectura y escritura de los JSON de Surco sin poder dejarlos a medias: AtomicFile escribe aparte,
 * hace fsync y solo entonces sustituye (si algo falla, queda la copia buena). Todas las escrituras
 * van por un único hilo: dos guardados a la vez no se mezclan.
 */
object Disk {
    @OptIn(ExperimentalCoroutinesApi::class)
    val writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** null si no existe. */
    fun read(f: File): String? = runCatching { AtomicFile(f).readFully().toString(Charsets.UTF_8) }.getOrNull()

    fun write(f: File, text: String) {
        val af = AtomicFile(f)
        val out = runCatching { af.startWrite() }.getOrNull() ?: return
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (_: Throwable) {
            af.failWrite(out)
        }
    }

    /** Un archivo que no se puede leer se aparta (no se borra) para no guardar encima de él. */
    fun quarantine(f: File) {
        runCatching { f.renameTo(File(f.parentFile, f.name + ".roto-" + System.currentTimeMillis())) }
    }
}
