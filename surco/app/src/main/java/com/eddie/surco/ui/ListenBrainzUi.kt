package com.eddie.surco.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.R
import com.eddie.surco.data.LB_SETTINGS
import com.eddie.surco.player.Scrobbler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes → Tus escuchas → ListenBrainz: interruptor (apagado por defecto), token pegado y
 * comprobado, y cuántas escuchas esperan a salir. El token se ve como puntos: es una credencial
 * y la pantalla puede acabar en una captura.
 */
@Composable
fun ListenBrainzSettings() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Scrobbler.init(ctx) } }
    val s by Scrobbler.state.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val noBrowser = stringResource(R.string.lb_no_browser)

    Column(Modifier.fillMaxWidth()) {
        SwitchRow(
            stringResource(R.string.lb_enable), stringResource(R.string.lb_enable_help), s.enabled,
        ) { Scrobbler.setEnabled(ctx, it) }

        OutlinedTextField(
            value = s.token,
            onValueChange = { Scrobbler.setToken(ctx, it); offline = false },
            label = { Text(stringResource(R.string.lb_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Copper, cursorColor = Copper, focusedLabelColor = Copper,
                unfocusedLabelColor = Muted, unfocusedBorderColor = Muted),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp),
        )

        // Enlace para sacar el token: toda la fila es tocable (48 dp) y dice qué hace.
        Text(
            stringResource(R.string.lb_get_token), color = Copper, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.lb_open_browser), role = Role.Button) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LB_SETTINGS))) }
                        .onFailure { Notice.show(noBrowser) }
                }
                .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 14.dp),
        )

        Button(
            onClick = {
                checking = true; offline = false
                scope.launch {
                    offline = Scrobbler.validate(ctx) == Scrobbler.Check.Offline
                    checking = false
                }
            },
            enabled = s.token.isNotEmpty() && !checking,
            colors = ButtonDefaults.buttonColors(containerColor = Copper, contentColor = Ink,
                disabledContainerColor = Panel2, disabledContentColor = Muted),
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.lb_check), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }

        // Resultado de la comprobación y estado: TalkBack lo lee al cambiar.
        val status = when {
            checking -> stringResource(R.string.lb_checking)
            offline -> stringResource(R.string.lb_offline)
            s.badToken -> stringResource(R.string.lb_bad_token)
            s.user != null -> stringResource(R.string.lb_connected, s.user.orEmpty())
            s.enabled && s.token.isEmpty() -> stringResource(R.string.lb_need_token)
            else -> null
        }
        val pending = if (s.pending > 0) plural(R.plurals.lb_pending, s.pending) else stringResource(R.string.lb_pending_none)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            if (status != null) Text(status, color = Paper, fontSize = 14.sp)
            Text(pending, color = Muted, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}
