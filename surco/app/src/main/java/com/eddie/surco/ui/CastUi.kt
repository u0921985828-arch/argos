package com.eddie.surco.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.R
import com.eddie.surco.data.RendererKind
import com.eddie.surco.player.CastSupport
import com.eddie.surco.player.Dlna
import com.eddie.surco.player.Output
import com.eddie.surco.player.Outputs

/**
 * Botón compacto «Enviar a» para la cabecera de «Sonando» (48 dp, con nombre para TalkBack). En
 * cobre mientras suena en otro aparato; el estado dice dónde.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val active by Outputs.active.collectAsState()
    val connecting by Outputs.connecting.collectAsState()
    val where = when (val a = active) {
        Output.Phone -> connecting?.let { stringResource(R.string.cast_connecting, it) } ?: stringResource(R.string.cast_on_phone)
        is Output.Renderer -> stringResource(R.string.cast_playing_on, a.device.name)
        is Output.Cast -> stringResource(R.string.cast_playing_on, a.name)
    }
    val remote = active != Output.Phone
    IconButton(onClick = { open = true }, modifier.semantics { stateDescription = where }) {
        Icon(
            if (remote) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            stringResource(R.string.cast_button),
            tint = if (remote || connecting != null) Copper else Paper,
        )
    }
    if (open) CastSheet(onDismiss = { open = false })
}

/**
 * Hoja «Enviar a»: este móvil, los Chromecast y los aparatos DLNA de la wifi. Mientras está abierta
 * se busca (SSDP cada 10 s y MediaRouter en activo); al cerrarla se deja de buscar.
 */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        Dlna.startSearching(ctx)
        CastSupport.startScan(ctx)
        onDispose { Dlna.stopSearching(); CastSupport.stopScan(ctx) }
    }
    val renderers by Dlna.devices.collectAsState()
    val casts by CastSupport.devices.collectAsState()
    val searching by Dlna.searching.collectAsState()
    val active by Outputs.active.collectAsState()
    val connecting by Outputs.connecting.collectAsState()

    SurcoSheet(stringResource(R.string.cast_title), onDismiss = onDismiss, subtitle = stringResource(R.string.cast_subtitle),
        dismiss = stringResource(R.string.cast_close)) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            DeviceRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.cast_this_phone), null, selected = active == Output.Phone && connecting == null) {
                Outputs.choose(Output.Phone); onDismiss()
            }
            casts.forEach { c ->
                val on = (active as? Output.Cast)?.name == c.name || connecting == c.name
                DeviceRow(Icons.Rounded.Cast, c.name, c.description.ifBlank { stringResource(R.string.cast_kind_chromecast) }, selected = on) {
                    Outputs.choose(Output.Cast(c.id, c.name)); onDismiss()
                }
            }
            renderers.forEach { d ->
                val icon = when (d.kind) {
                    RendererKind.TV -> Icons.Rounded.Tv
                    RendererKind.RECEIVER -> Icons.Rounded.Radio
                    RendererKind.SPEAKER -> Icons.Rounded.Speaker
                }
                val sub = listOf(d.manufacturer, d.model).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { stringResource(R.string.cast_kind_dlna) }
                DeviceRow(icon, d.name, sub, selected = (active as? Output.Renderer)?.device?.udn == d.udn) {
                    Outputs.choose(Output.Renderer(d)); onDismiss()
                }
            }
            if (searching) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Copper, strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.cast_searching), color = Muted, fontSize = 14.sp)
                }
            } else if (renderers.isEmpty() && casts.isEmpty()) {
                Text(stringResource(R.string.cast_none), color = Muted, fontSize = 13.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

/** Fila de un aparato: toda la fila se toca; TalkBack la lee como una opción elegida o no. */
@Composable
private fun DeviceRow(icon: ImageVector, name: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = if (selected) Copper else Paper.copy(alpha = 0.85f))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) Copper else Paper, fontSize = 15.sp, maxLines = 2)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Copper)
    }
}
