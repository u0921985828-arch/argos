package com.eddie.surco.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewModelScope
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.data.Backup
import com.eddie.surco.data.BackupContents
import com.eddie.surco.data.BackupRead
import com.eddie.surco.data.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Ajustes → Biblioteca → Copia de seguridad: todo lo de Surco en un archivo, para cambiar de móvil.
 * Restaurar lee y valida la copia entera antes de preguntar, y no toca nada hasta confirmar.
 */
@Composable
fun BackupSettings(vm: LibraryViewModel) {
    // La de la aplicación: lo que se escribe o se aplica sigue aunque se cierre la pantalla.
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    /** La copia ya leída y validada, a la espera de confirmar. */
    var pending by remember { mutableStateOf<BackupContents?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            // En el ámbito del ViewModel: salir de Ajustes no deja el archivo a medias.
            vm.viewModelScope.launch {
                val r = withContext(Dispatchers.IO) { Backup.save(ctx, uri, vm.tracks.value) }
                busy = false
                Notice.show(message(r))
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val (contents, error) = withContext(Dispatchers.IO) { Backup.open(ctx, uri) }
                busy = false
                if (contents != null) pending = contents
                else if (error != null) Notice.show(message(error), long = true)
            }
        }
    }

    SectionLabel(stringResource(R.string.backup_section))
    OptionRow(
        stringResource(R.string.backup_save),
        stringResource(if (busy) R.string.backup_working else R.string.backup_save_help),
    ) {
        // Sin selector de documentos (algunos móviles sin «Archivos») no hay dónde guardar.
        if (!busy) runCatching { create.launch(Backup.fileName()) }.onFailure { Notice.show(Res.str(R.string.backup_no_picker)) }
    }
    OptionRow(
        stringResource(R.string.backup_restore),
        stringResource(if (busy) R.string.backup_working else R.string.backup_restore_help),
    ) {
        // Algunos gestores de archivos dan los .json como texto o como binario.
        if (!busy) runCatching { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            .onFailure { Notice.show(Res.str(R.string.backup_no_picker)) }
    }

    pending?.let { c ->
        val date = remember(c) { DateFormat.getDateInstance(DateFormat.LONG, appLocale()).format(Date(c.created)) }
        val s = remember(c) { c.summary() }
        ConfirmSheet(
            title = stringResource(R.string.backup_confirm_title),
            text = stringResource(R.string.backup_confirm_text, date, s.favorites, s.playlists, s.analyzed),
            confirm = stringResource(R.string.backup_confirm_ok),
            onDismiss = { pending = null },
        ) {
            pending = null
            busy = true
            vm.viewModelScope.launch {
                val r = withContext(Dispatchers.IO) { Backup.restore(ctx, c, vm.tracks.value) }
                busy = false
                // Aplica a la biblioteca lo analizado que trae la copia (y sigue con lo que falte).
                if (r == Backup.Outcome.Restored) vm.analyzeLibrary()
                Notice.show(message(r))
            }
        }
    }
}

private fun message(r: Backup.Outcome): String = when (r) {
    Backup.Outcome.Saved -> Res.str(R.string.backup_saved)
    Backup.Outcome.Restored -> Res.str(R.string.backup_restored)
    Backup.Outcome.IoError -> Res.str(R.string.backup_io_error)
    is Backup.Outcome.Rejected -> when (val why = r.why) {
        BackupRead.NotSurco -> Res.str(R.string.backup_not_surco)
        is BackupRead.TooNew -> Res.str(R.string.backup_too_new, why.version)
        else -> Res.str(R.string.backup_broken)
    }
}
