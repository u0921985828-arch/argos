package com.eddie.surco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.audio.BUILTIN_PRESETS
import com.eddie.surco.audio.CROSSFEED_LEVELS
import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.EQ_MAX_DB
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.audio.peakGainDb
import com.eddie.surco.audio.responseDb
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val CROSSFEED_NAMES = listOf("Apagado", "Suave", "Medio", "Fuerte")
private const val CUSTOM = "Personalizado"

private fun freqLabel(f: Float) = if (f >= 1000) "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}k" else f.toInt().toString()

@Composable
fun EqualizerScreen(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    val d = prefs.dsp
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    fun setBands(b: List<Float>) = SurcoSettings.update { it.copy(dsp = it.dsp.copy(bands = b), preset = CUSTOM) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ecualizador", if (d.eqOn) prefs.preset else "Apagado", onBack = { vm.pop() }) {
            Switch(
                checked = d.eqOn, onCheckedChange = { on -> SurcoSettings.updateDsp { it.copy(eqOn = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Copper),
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ResponseCurve(d, Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp, vertical = 8.dp))

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(
                    SurcoSettings.allPresets(prefs),
                    key = { i, it -> (if (i < BUILTIN_PRESETS.size) "fábrica:" else "propio:") + it.first },
                ) { _, (name, bands) ->
                    FilterChip(
                        selected = prefs.preset == name && d.eqOn,
                        onClick = { SurcoSettings.update { it.copy(dsp = it.dsp.copy(bands = bands, eqOn = true), preset = name) } },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Copper, selectedLabelColor = Ink),
                    )
                }
                item {
                    FilterChip(selected = false, onClick = { saving = true }, label = { Text("+ Guardar") })
                }
            }

            if (prefs.preset in prefs.customPresets) {
                TextButton(onClick = { deleting = prefs.preset }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Borrar el preajuste «${prefs.preset}»", color = Muted, fontSize = 13.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                EQ_FREQS.forEachIndexed { i, f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            d.bands[i].let { if (it == 0f) "0" else (if (it > 0) "+" else "") + it.toString().removeSuffix(".0") },
                            color = if (d.bands[i] == 0f) Muted else Copper, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        )
                        VerticalSlider(
                            value = d.bands[i], min = -EQ_MAX_DB, max = EQ_MAX_DB, label = "Banda de ${freqLabel(f)}Hz",
                            onChange = { v -> if (v != d.bands[i]) setBands(d.bands.toMutableList().also { it[i] = v }) },
                        )
                        Text(freqLabel(f), color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            val peak = remember(d) { peakGainDb(d) }
            SwitchRow(
                "Preamplificación automática",
                "Baja el volumen lo que suben las bandas y el tono: nunca satura y el limitador no tiene que actuar.",
                prefs.autoPreamp,
            ) { a -> SurcoSettings.update { it.copy(autoPreamp = a) } }
            if (prefs.autoPreamp) {
                Text("Preamplificación ${dbText(d.preampDb)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            } else {
                LabeledSlider(
                    "Preamplificación", d.preampDb, -15f..6f, dbText(d.preampDb), steps = 41,
                    onChange = { v -> SurcoSettings.updateDsp { it.copy(preampDb = v) } },
                    onReset = { SurcoSettings.updateDsp { it.copy(preampDb = 0f) } },
                )
                if (peak > 0.3) {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "La curva sube ${dbText(peak.toFloat())}: " + if (d.limiter) "el limitador lo contiene, con algo de compresión." else "puede saturar.",
                            color = Copper, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            SurcoSettings.updateDsp { it.copy(preampDb = (it.preampDb - floor(peak * 2 + 1).toFloat() / 2).coerceAtLeast(-15f)) }
                        }) { Text("Ajustar") }
                    }
                }
            }

            SectionLabel("Tono")
            LabeledSlider("Graves", d.bassDb, -12f..12f, dbText(d.bassDb), steps = 47,
                onChange = { v -> SurcoSettings.updateDsp { it.copy(bassDb = v) } }, onReset = { SurcoSettings.updateDsp { it.copy(bassDb = 0f) } })
            LabeledSlider("Agudos", d.trebleDb, -12f..12f, dbText(d.trebleDb), steps = 47,
                onChange = { v -> SurcoSettings.updateDsp { it.copy(trebleDb = v) } }, onReset = { SurcoSettings.updateDsp { it.copy(trebleDb = 0f) } })

            SectionLabel("Estéreo")
            LabeledSlider(
                "Balance", d.balance, -1f..1f,
                when { d.balance < -0.01f -> "I ${(-d.balance * 100).toInt()} %"; d.balance > 0.01f -> "D ${(d.balance * 100).toInt()} %"; else -> "Centro" },
                steps = 39, onChange = { v -> SurcoSettings.updateDsp { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) } },
            )
            Text("Crossfeed para auriculares", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text("Mezcla un poco de cada canal en el otro, como unos altavoces: cansa menos con grabaciones muy separadas.",
                color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CROSSFEED_LEVELS.indices.forEach { lvl ->
                    FilterChip(
                        selected = d.crossfeed == lvl, onClick = { SurcoSettings.updateDsp { it.copy(crossfeed = lvl) } },
                        label = { Text(CROSSFEED_NAMES[lvl]) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Copper, selectedLabelColor = Ink),
                    )
                }
            }
            SwitchRow("Mono", "Suma los dos canales: para un solo auricular o un altavoz.", d.mono) { m -> SurcoSettings.updateDsp { it.copy(mono = m) } }
            SwitchRow("Limitador", "Solo actúa si la curva pasa de 0 dB (con la preamplificación manual). Mono y balance no lo activan.", d.limiter) { l -> SurcoSettings.updateDsp { it.copy(limiter = l) } }

            TextButton(
                onClick = { SurcoSettings.update { it.copy(dsp = DspParams(), preset = "Plano") } },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("Restablecer todo", color = Muted) }
        }
    }

    if (saving) NameDialog("Guardar preajuste", onDismiss = { saving = false }) { typed ->
        // Un nombre de fábrica repetido confundiría cuál está puesto: el propio lleva coletilla.
        val name = if (BUILTIN_PRESETS.any { it.first.equals(typed, ignoreCase = true) } || typed == CUSTOM) "$typed (propio)" else typed
        SurcoSettings.update { it.copy(customPresets = it.customPresets + (name to it.dsp.bands), preset = name) }
        saving = false
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Panel,
            title = { Text("¿Borrar «$name»?") },
            confirmButton = {
                TextButton(onClick = {
                    SurcoSettings.update { it.copy(customPresets = it.customPresets - name, preset = if (it.preset == name) CUSTOM else it.preset) }
                    deleting = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Curva de respuesta en frecuencia de todo el procesado (preamplificación, bandas y tono). */
@Composable
fun ResponseCurve(d: DspParams, modifier: Modifier = Modifier) {
    val freqs = remember { DoubleArray(160) { 20.0 * 1000.0.pow(it / 159.0) } }
    val resp = remember(d) { responseDb(d, freqs) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Panel)) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 10.dp)) {
            val range = 15.0
            fun y(db: Double) = (size.height / 2 - (db.coerceIn(-range, range) / range) * size.height / 2).toFloat()
            fun x(f: Double) = (log10(f / 20.0) / 3.0 * size.width).toFloat()
            for (db in listOf(-12.0, -6.0, 6.0, 12.0)) drawLine(Panel2, Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
            drawLine(Muted.copy(alpha = 0.4f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.5f)
            for (f in EQ_FREQS) drawLine(Panel2, Offset(x(f.toDouble()), 0f), Offset(x(f.toDouble()), size.height), 1f)
            val path = Path()
            resp.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(freqs[i]), y(v)) else path.lineTo(x(freqs[i]), y(v)) }
            drawPath(path, if (d.eqOn || d.toneActive) Copper else Muted, style = Stroke(width = 2.5.dp.toPx()))
        }
        Text("+15", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(4.dp))
        Text("−15", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
    Spacer(Modifier.height(4.dp))
}
