package com.eddie.surco.ui

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.Screen
import com.eddie.surco.data.TagKey
import com.eddie.surco.data.TagWriter
import com.eddie.surco.data.Track
import com.eddie.surco.data.changedValues
import com.eddie.surco.data.invalidKeys
import com.eddie.surco.data.isWritableFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Canciones que se están guardando: salir de la pantalla y volver no permite guardar dos veces. */
private object Saving { var ids by mutableStateOf(emptySet<Long>()) }

/**
 * Editar las etiquetas de una canción y escribirlas en su archivo. Los campos salen de lo que dice
 * el ARCHIVO, no de la biblioteca: allí el BPM o la tonalidad pueden venir del análisis y, al
 * guardar otra cosa, se escribirían como si fueran etiqueta. Solo se escribe lo cambiado.
 */
@Composable
fun TagEditorScreen(vm: LibraryViewModel, id: Long, byId: Map<Long, Track>) {
    val track = byId[id]
    if (track == null) { LaunchedEffect(Unit) { vm.pop() }; return }
    val ctx = LocalContext.current
    val app = ctx.applicationContext

    var before by remember(id) { mutableStateOf<Map<TagKey, String>?>(null) }
    var unreadable by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val r = withContext(Dispatchers.IO) { TagWriter.load(track) }
        if (r == null) unreadable = true else before = r
    }
    // Lo escrito, en el orden de TagKey; sobrevive a girar la pantalla.
    // (Tipos de solo lectura en el estado; por dentro, ArrayList y HashMap, que caben en el estado guardado.)
    var edits by rememberSaveable(id) { mutableStateOf<List<String>?>(null) }
    val base = before
    val values: List<String>? = edits ?: base?.let { b -> TagKey.entries.map { b[it].orEmpty() } }
    val after = values?.let { v -> TagKey.entries.associateWith { v[it.ordinal] } }
    val changes = if (base != null && after != null) changedValues(base, after) else emptyMap()
    val invalid = if (base != null && after != null) invalidKeys(base, after) else emptySet()
    val saving = id in Saving.ids
    val editable = !unreadable

    // Lo que se iba a guardar mientras el sistema pide permiso (sobrevive a girar la pantalla).
    var pending by rememberSaveable(id) { mutableStateOf<Map<String, String>?>(null) }
    var consentFor by remember { mutableStateOf<IntentSender?>(null) }

    // En el ámbito del ViewModel: salir de la pantalla a mitad no deja el archivo a medio escribir
    // ni se pierde el aviso.
    fun save(ch: Map<TagKey, String>, afterConsent: Boolean) {
        if (id in Saving.ids) return
        Saving.ids += id
        vm.viewModelScope.launch {
            try {
                // Si es la que suena, el reproductor suelta el archivo mientras se escribe y vuelve
                // a cargarlo en el mismo punto (si no, leería un archivo a medio cambiar).
                val resume = vm.player.releaseIfCurrent(id)
                val result = try { withContext(Dispatchers.IO) { TagWriter.write(app, track, ch) } } finally { resume?.invoke() }
                when (val r = result) {
                    is TagWriter.Result.Done -> {
                        vm.reread(track.copy(size = r.size))
                        Notice.show(Res.str(R.string.tags_saved))
                        if (vm.stack.lastOrNull() == Screen.TagEditor(id)) vm.pop()
                    }
                    is TagWriter.Result.NeedsConsent ->
                        // Ya dijo «Permitir» y aun así no deja: no se le vuelve a preguntar en bucle.
                        if (afterConsent) Notice.show(Res.str(R.string.tags_no_access), long = true)
                        else { pending = ch.toSaved(); consentFor = r.sender }
                    is TagWriter.Result.Failed -> Notice.show(Res.str(failText(r.reason)), long = true)
                }
            } finally {
                Saving.ids -= id
            }
        }
    }

    fun resume(granted: Boolean) {
        val ch = pending?.toChanges()
        pending = null
        if (granted && ch != null) save(ch, afterConsent = true) else Notice.show(Res.str(R.string.tags_denied))
    }
    // Android 10+: el diálogo del sistema («Permitir»). Android 8–9: el permiso de almacenamiento.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        resume(it.resultCode == Activity.RESULT_OK)
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resume(it) }
    LaunchedEffect(consentFor) {
        val s = consentFor ?: return@LaunchedEffect
        consentFor = null
        consent.launch(IntentSenderRequest.Builder(s).build())
    }

    fun onSave() {
        if (changes.isEmpty() || invalid.isNotEmpty() || saving) return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = changes.toSaved()
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else save(changes, afterConsent = false)
    }

    // Con la app de borde a borde el teclado tapa la pantalla: se sube lo que haga falta, menos lo
    // que ya ocupan debajo el minirreproductor y la barra de pestañas.
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    var below by remember { mutableIntStateOf(0) }
    val lift = with(density) { (ime - below).coerceAtLeast(0).toDp() }

    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { c -> below = (c.findRootCoordinates().size.height - c.boundsInRoot().bottom).roundToInt().coerceAtLeast(0) }
            .padding(bottom = lift),
    ) {
        TopBar(stringResource(R.string.tags_title), track.title, onBack = { vm.pop() })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(track, 72.dp, modifier = Modifier.size(72.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (track.format.isNotBlank()) Text(track.format, color = Teal, fontSize = 12.sp, fontFeatureSettings = "tnum")
                    Text(track.path, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            when {
                unreadable -> Text(
                    if (isWritableFormat(track.path)) stringResource(R.string.tags_unreadable)
                    else stringResource(R.string.tags_unsupported, track.format.ifBlank { track.path.substringAfterLast('.').uppercase() }),
                    color = Paper, fontSize = 15.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                values == null -> Text(stringResource(R.string.tags_loading), color = Muted, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                else -> TagKey.entries.forEach { k ->
                    TagInput(k, values[k.ordinal], k in invalid, readOnly = saving) { v ->
                        edits = ArrayList(values).apply { this[k.ordinal] = v }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { vm.pop() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper),
            ) { Text(stringResource(if (editable) R.string.app_cancel else R.string.tags_back), fontSize = 15.sp) }
            if (editable) {
                Button(
                    onClick = { onSave() }, enabled = changes.isNotEmpty() && invalid.isEmpty() && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Copper, contentColor = Ink,
                        disabledContainerColor = Panel2, disabledContentColor = Muted),
                ) {
                    Text(stringResource(if (saving) R.string.tags_saving else R.string.app_save), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Un campo con su nombre siempre a la vista (también para TalkBack); números con su teclado. */
@Composable
private fun TagInput(k: TagKey, value: String, invalid: Boolean, readOnly: Boolean, onChange: (String) -> Unit) {
    val long = k.kind == TagKey.Kind.LONG
    OutlinedTextField(
        value = value, onValueChange = onChange, readOnly = readOnly,
        label = { Text(stringResource(labelOf(k))) },
        singleLine = !long, minLines = if (long) 2 else 1, maxLines = if (long) 6 else 1,
        isError = invalid,
        supportingText = when {
            invalid -> { { Text(stringResource(errorOf(k))) } }
            k.multi -> { { Text(stringResource(R.string.tags_multi_hint)) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = if (k.numeric) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            keyboardType = when (k.kind) {
                TagKey.Kind.YEAR, TagKey.Kind.COUNT -> KeyboardType.Number
                TagKey.Kind.BPM -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
            // El comentario admite saltos de línea; en los demás, «Siguiente» pasa al de abajo.
            imeAction = if (long) ImeAction.Default else ImeAction.Next,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Copper, cursorColor = Copper, focusedLabelColor = Copper,
            unfocusedBorderColor = Muted.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@StringRes
private fun labelOf(k: TagKey): Int = when (k) {
    TagKey.TITLE -> R.string.tags_field_title
    TagKey.ARTIST -> R.string.tags_field_artist
    TagKey.ALBUM -> R.string.tags_field_album
    TagKey.ALBUM_ARTIST -> R.string.tags_field_album_artist
    TagKey.GENRE -> R.string.tags_field_genre
    TagKey.YEAR -> R.string.tags_field_year
    TagKey.TRACK -> R.string.tags_field_track
    TagKey.DISC -> R.string.tags_field_disc
    TagKey.COMPOSER -> R.string.tags_field_composer
    TagKey.BPM -> R.string.tags_field_bpm
    TagKey.KEY -> R.string.tags_field_key
    TagKey.MOOD -> R.string.tags_field_mood
    TagKey.COMMENT -> R.string.tags_field_comment
}

@StringRes
private fun errorOf(k: TagKey): Int = when (k.kind) {
    TagKey.Kind.YEAR -> R.string.tags_error_year
    TagKey.Kind.BPM -> R.string.tags_error_bpm
    else -> R.string.tags_error_count
}

@StringRes
private fun failText(r: TagWriter.Reason): Int = when (r) {
    TagWriter.Reason.NO_ACCESS -> R.string.tags_no_access
    TagWriter.Reason.NO_SPACE -> R.string.tags_no_space
    TagWriter.Reason.UNSUPPORTED, TagWriter.Reason.WRITE -> R.string.tags_failed
}

// Los cambios pendientes se guardan por nombre de campo (un HashMap cabe en el estado guardado).
private fun Map<TagKey, String>.toSaved(): Map<String, String> = HashMap(mapKeys { it.key.name })

private fun Map<String, String>.toChanges(): Map<TagKey, String> =
    TagKey.entries.filter { it.name in this }.associateWith { getValue(it.name) }
