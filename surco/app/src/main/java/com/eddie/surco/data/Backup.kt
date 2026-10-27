package com.eddie.surco.data

import android.content.Context
import android.net.Uri
import com.eddie.surco.audio.SurcoSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate

/**
 * Copia de seguridad en un archivo, para cambiar de móvil sin perder nada: favoritas, listas,
 * escuchas y cola (usuario.json), ajustes y preajustes del ecualizador (ajustes.json) y lo
 * analizado de BPM y tonalidad. Lo de leer y validar está en PureBackup.kt; aquí, los archivos.
 * Todo fuera del hilo principal.
 */
object Backup {
    sealed interface Outcome {
        data object Saved : Outcome
        data object Restored : Outcome
        /** No se pudo leer o escribir el archivo (proveedor caído, sin permiso, sin espacio). */
        data object IoError : Outcome
        /** Lo que dice [BackupRead]: roto, de otra app o de una versión más nueva. Nada se ha tocado. */
        data class Rejected(val why: BackupRead) : Outcome
    }

    fun fileName(): String = backupFileName(LocalDate.now())

    private fun library(tracks: List<Track>) = tracks.map { LibraryFile(it.id, it.path, it.dateModified, it.stableKeys()) }

    /** Escribe la copia en [uri] (de CreateDocument). */
    fun save(ctx: Context, uri: Uri, tracks: List<Track>): Outcome {
        // Claves estables al día de todo lo que se usa: sin ellas, en el otro móvil no se reconoce.
        UserStore.reconcile(tracks)
        val user = parseBackupJson(UserStore.exportJson()) as? BackupJson.Obj ?: return Outcome.IoError
        val settings = parseBackupJson(SurcoSettings.exportJson()) as? BackupJson.Obj ?: return Outcome.IoError
        val analysis = analysisForBackup(AnalysisStore.exportAll(ctx), library(tracks))
        val bytes = buildBackup(BackupContents(System.currentTimeMillis(), user, settings, analysis)).toByteArray(Charsets.UTF_8)
        return runCatching {
            // «wt» vacía lo que hubiera; hay proveedores que solo aceptan «w».
            val out = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: ctx.contentResolver.openOutputStream(uri, "w")
            checkNotNull(out).use { it.write(bytes) }
            Outcome.Saved
        }.getOrDefault(Outcome.IoError)
    }

    /** Lee y valida la copia de [uri] (de OpenDocument) sin tocar nada: la copia, o por qué no vale. */
    fun open(ctx: Context, uri: Uri): Pair<BackupContents?, Outcome?> {
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        }.getOrNull() ?: return null to Outcome.IoError
        return when (val r = readBackup(text)) {
            is BackupRead.Ok -> r.contents to null
            else -> null to Outcome.Rejected(r)
        }
    }

    /** Más de [BACKUP_MAX_BYTES] no es una copia: se corta antes de llenar la memoria (y sale «rota»). */
    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > BACKUP_MAX_BYTES) return ""
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Sustituye lo de ahora por la copia. Primero se pasa todo a los tipos de Surco (si algo
     * falla aquí, no se ha tocado nada); después se aplica y se guarda en el acto. Los ids de
     * canción se traducen por claves estables a los de este móvil y, al final, la reconciliación
     * de siempre con la biblioteca actual.
     */
    fun restore(ctx: Context, c: BackupContents, tracks: List<Track>): Outcome {
        val lib = library(tracks)
        val parsed = runCatching {
            Triple(
                UserStore.parseJson(writeBackupJson(remapUserIds(c.user, idsByKey(lib)))),
                SurcoSettings.parseJson(writeBackupJson(c.settings)),
                analysisForRestore(c.analysis, lib),
            )
        }.getOrNull() ?: return Outcome.Rejected(BackupRead.Broken)
        val (user, prefs, analysis) = parsed
        SurcoSettings.restore(prefs)
        AnalysisStore.replaceAll(ctx, analysis)
        UserStore.update { user }
        UserStore.reconcile(tracks)
        UserStore.flush()
        return Outcome.Restored
    }
}
