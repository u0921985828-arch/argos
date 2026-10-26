package com.eddie.surco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deslizador vertical para las bandas del ecualizador. El relleno sale del centro (0 dB);
 * doble toque vuelve a 0.
 */
@Composable
fun VerticalSlider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
    min: Float = -12f, max: Float = 12f, step: Float = 0.5f, height: Dp = 180.dp, label: String = "",
) {
    val change by rememberUpdatedState(onChange)
    fun fromY(y: Float, h: Int): Float {
        val v = max - (y / h.coerceAtLeast(1)) * (max - min)
        return ((v / step).roundToInt() * step).coerceIn(min, max)
    }
    Canvas(
        // Con TalkBack: anuncia banda y valor, y se ajusta con los gestos de deslizar.
        modifier.width(38.dp).height(height)
            .semantics {
                contentDescription = label
                stateDescription = dbText(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, ((max - min) / step).toInt() - 1)
                setProgress { v -> change(((v / step).roundToInt() * step).coerceIn(min, max)); true }
            }
            .pointerInput(min, max) {
                detectVerticalDragGestures(
                    onDragStart = { change(fromY(it.y, size.height)) },
                ) { ev, _ -> ev.consume(); change(fromY(ev.position.y, size.height)) }
            }
            .pointerInput(min, max) {
                detectTapGestures(onDoubleTap = { change(0f) }, onTap = { change(fromY(it.y, size.height)) })
            },
    ) {
        val cx = size.width / 2
        val trackW = 4.dp.toPx()
        fun yOf(v: Float) = (max - v) / (max - min) * size.height
        drawRoundRect(Panel2, Offset(cx - trackW / 2, 0f), Size(trackW, size.height), CornerRadius(trackW))
        val y0 = yOf(0f.coerceIn(min, max)); val yv = yOf(value)
        drawRoundRect(Copper, Offset(cx - trackW / 2, minOf(y0, yv)), Size(trackW, abs(yv - y0)), CornerRadius(trackW))
        drawLine(Muted.copy(alpha = 0.5f), Offset(cx - 8.dp.toPx(), y0), Offset(cx + 8.dp.toPx(), y0), 1.dp.toPx())
        drawCircle(Paper, 9.dp.toPx(), Offset(cx, yv))
        drawCircle(Copper, 6.dp.toPx(), Offset(cx, yv))
    }
}

/**
 * Deslizador de Surco: pulgar redondo y pista continua. El de Material 1.3 pinta una barra con
 * hueco, un punto de parada al final y una marca por cada paso: en «Sonando» se veían tres piezas
 * sueltas y en el ecualizador pistas punteadas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurcoSlider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onFinished: (() -> Unit)? = null, fill: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    // Sin relleno en los que tienen el cero en medio (tono, balance): a 0 dB la pista medio cobre parecía un valor.
    val colors = SliderDefaults.colors(thumbColor = Copper, activeTrackColor = if (fill) Copper else Panel2, inactiveTrackColor = Panel2)
    Slider(
        value = value, onValueChange = onChange, modifier = modifier, valueRange = range, steps = steps,
        onValueChangeFinished = onFinished, interactionSource = source, colors = colors,
        thumb = { SliderDefaults.Thumb(interactionSource = source, colors = colors, thumbSize = DpSize(20.dp, 20.dp)) },
        track = { SliderDefaults.Track(sliderState = it, colors = colors, drawStopIndicator = null, drawTick = { _, _ -> }, thumbTrackGapSize = 0.dp) },
    )
}

/** Fila de ajuste con título, valor a la derecha y deslizador debajo. */
@Composable
fun LabeledSlider(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
    onChange: (Float) -> Unit, steps: Int = 0, onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            // Volver a 0 con un icono y solo cuando hace falta: un «0» junto al valor se leía como dos cifras.
            if (onReset != null && value != 0f) IconButton(onClick = onReset) { Icon(Icons.Default.Refresh, "Poner a 0", tint = Muted) }
            Text(valueText, color = Copper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        SurcoSlider(value = value, onChange = onChange, range = range, steps = steps, fill = range.start != -range.endInclusive)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Toda la fila cambia el interruptor (no solo el interruptor, que es un blanco pequeño).
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Copper),
        )
    }
}

/** Rojo de lo que borra: con Ink encima pasa de 4,5:1. */
val Danger = Color(0xFFE5675C)

/**
 * Hoja de Surco: lo que en otras apps es un diálogo en mitad de la pantalla (temporizador,
 * velocidad, nombres, confirmar un borrado) sube desde abajo, con el fondo de la app, se cierra
 * deslizando o tocando fuera y deja los botones grandes al alcance del pulgar. Con el teclado
 * abierto, la hoja sube por encima de él.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurcoSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    confirm: String? = null,
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
    onConfirm: () -> Unit = {},
    dismiss: String? = "Cancelar",
    onDismissButton: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Ink,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 20.dp)) {
            Text(title, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            content()
            if (confirm != null || dismiss != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dismiss != null) {
                        OutlinedButton(onClick = onDismissButton, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper)) { Text(dismiss, fontSize = 15.sp) }
                    }
                    if (confirm != null) {
                        Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (danger) Danger else Copper, contentColor = Ink,
                                disabledContainerColor = Panel2, disabledContentColor = Muted)) {
                            Text(confirm, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Hoja para escribir un nombre (lista nueva, preajuste…): el teclado sale solo y «Hecho» guarda. */
@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Guardar", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val ok = text.text.isNotBlank()
    SurcoSheet(title, onDismiss, confirm = confirm, confirmEnabled = ok, onConfirm = { if (ok) onOk(text.text.trim()) }) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("Nombre", color = Muted) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ok) onOk(text.text.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Copper, cursorColor = Copper),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
        )
        // Dentro de la hoja (su propia ventana): fuera podría correr antes de que el campo exista.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** Hoja para confirmar algo que borra: el botón dice lo que hace y va en rojo. */
@Composable
fun ConfirmSheet(title: String, text: String, confirm: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    SurcoSheet(title, onDismiss, subtitle = text, confirm = confirm, danger = true, onConfirm = onOk)
}

/** Fila pulsable de una opción con texto secundario. */
@Composable
fun OptionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    // Fila a 20 dp del borde, como los demás títulos de Ajustes (el TextButton empezaba a 14 dp).
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 15.sp)
        }
        // La flecha dice que abre algo: sin ella se confundía con los textos fijos de Ajustes.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}

fun dbText(v: Float): String = (if (v > 0) "+" else "") + "%.1f dB".format(v).replace('.', ',')
