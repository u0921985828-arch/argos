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
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddie.surco.LibraryViewModel
import com.eddie.surco.R
import com.eddie.surco.Res
import com.eddie.surco.audio.BUILTIN_PRESETS
import com.eddie.surco.audio.CROSSFEED_LEVELS
import com.eddie.surco.audio.DspParams
import com.eddie.surco.audio.EQ_FREQS
import com.eddie.surco.audio.EQ_MAX_DB
import com.eddie.surco.audio.OutputRoute
import com.eddie.surco.audio.SurcoSettings
import com.eddie.surco.audio.peakGainDb
import com.eddie.surco.audio.presetLabel
import com.eddie.surco.audio.presetLabelRes
import com.eddie.surco.audio.responseDb
import com.eddie.surco.audio.routeName
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val CROSSFEED_NAMES = listOf(R.string.eq_off, R.string.eq_crossfeed_light, R.string.eq_crossfeed_medium, R.string.eq_crossfeed_strong)
private const val CUSTOM = "Personalizado"

/** Nombre visible de un preajuste: los de fábrica y «Personalizado» traducidos, los del usuario tal cual. */
@Composable
private fun presetText(name: String): String = presetLabelRes(name)?.let { stringResource(it) } ?: name

private fun freqLabel(f: Float) = if (f >= 1000) "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}k" else f.toInt().toString()

@Composable
fun EqualizerScreen(vm: LibraryViewModel) {
    val prefs by SurcoSettings.state.collectAsState()
    val d = prefs.dsp
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    fun setBands(b: List<Float>) = SurcoSettings.update { it.copy(dsp = it.dsp.copy(bands = b), preset = CUSTOM) }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.eq_title), if (d.eqOn) presetText(prefs.preset) else stringResource(R.string.eq_off), onBack = { vm.pop() }) {
            // Con nombre para TalkBack: el interruptor solo no dice qué enciende.
            val switchDesc = stringResource(R.string.eq_switch)
            Switch(
                checked = d.eqOn, onCheckedChange = { on -> SurcoSettings.updateDsp { it.copy(eqOn = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Copper),
                modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = switchDesc },
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Con un ecualizador por salida, para cuál es lo que se toca.
            val route by OutputRoute.state.collectAsState()
            val ctx = LocalContext.current
            route?.takeIf { prefs.perOutputEq }?.let { r ->
                Text(stringResource(R.string.eq_for, routeName(ctx, r)), color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            ResponseCurve(d, Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp, vertical = 8.dp))

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(
                    SurcoSettings.allPresets(prefs),
                    key = { i, it -> (if (i < BUILTIN_PRESETS.size) "fábrica:" else "propio:") + it.first },
                ) { _, (name, bands) ->
                    FilterChip(
                        selected = prefs.preset == name && d.eqOn,
                        onClick = { SurcoSettings.update { it.copy(dsp = it.dsp.copy(bands = bands, eqOn = true), preset = name) } },
                        label = { Text(presetText(name)) },
                        colors = chipColors(), modifier = Modifier.height(ChipHeight),
                    )
                }
                item {
                    FilterChip(selected = false, onClick = { saving = true }, label = { Text(stringResource(R.string.eq_save_chip)) }, colors = chipColors(), modifier = Modifier.height(ChipHeight))
                }
            }

            if (prefs.preset in prefs.customPresets) {
                TextButton(onClick = { deleting = prefs.preset }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.eq_delete_preset_button, prefs.preset), color = Muted, fontSize = 13.sp)
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
                            value = d.bands[i], min = -EQ_MAX_DB, max = EQ_MAX_DB, label = stringResource(R.string.eq_band, freqLabel(f)),
                            onChange = { v -> if (v != d.bands[i]) setBands(d.bands.toMutableList().also { it[i] = v }) },
                        )
                        Text(freqLabel(f), color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            val peak = remember(d) { peakGainDb(d) }
            SwitchRow(
                stringResource(R.string.eq_auto_preamp),
                stringResource(R.string.eq_auto_preamp_desc),
                prefs.autoPreamp,
            ) { a -> SurcoSettings.update { it.copy(autoPreamp = a) } }
            if (prefs.autoPreamp) {
                Text(stringResource(R.string.eq_preamp_value, dbText(d.preampDb)), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            } else {
                LabeledSlider(
                    stringResource(R.string.eq_preamp), d.preampDb, -15f..6f, dbText(d.preampDb), steps = 41,
                    onChange = { v -> SurcoSettings.updateDsp { it.copy(preampDb = v) } },
                    onReset = { SurcoSettings.updateDsp { it.copy(preampDb = 0f) } },
                )
                if (peak > 0.3) {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (d.limiter) R.string.eq_peak_limited else R.string.eq_peak_clips, dbText(peak.toFloat())),
                            color = Copper, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            SurcoSettings.updateDsp { it.copy(preampDb = (it.preampDb - floor(peak * 2 + 1).toFloat() / 2).coerceAtLeast(-15f)) }
                        }) { Text(stringResource(R.string.eq_adjust)) }
                    }
                }
            }

            SectionLabel(stringResource(R.string.eq_tone))
            LabeledSlider(stringResource(R.string.eq_bass), d.bassDb, -12f..12f, dbText(d.bassDb), steps = 47,
                onChange = { v -> SurcoSettings.updateDsp { it.copy(bassDb = v) } }, onReset = { SurcoSettings.updateDsp { it.copy(bassDb = 0f) } })
            LabeledSlider(stringResource(R.string.eq_treble), d.trebleDb, -12f..12f, dbText(d.trebleDb), steps = 47,
                onChange = { v -> SurcoSettings.updateDsp { it.copy(trebleDb = v) } }, onReset = { SurcoSettings.updateDsp { it.copy(trebleDb = 0f) } })

            SectionLabel(stringResource(R.string.eq_stereo))
            LabeledSlider(
                stringResource(R.string.eq_balance), d.balance, -1f..1f,
                when {
                    d.balance < -0.01f -> stringResource(R.string.eq_balance_left, (-d.balance * 100).toInt())
                    d.balance > 0.01f -> stringResource(R.string.eq_balance_right, (d.balance * 100).toInt())
                    else -> stringResource(R.string.eq_balance_center)
                },
                steps = 39, onChange = { v -> SurcoSettings.updateDsp { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) } },
            )
            Text(stringResource(R.string.eq_crossfeed), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(stringResource(R.string.eq_crossfeed_desc),
                color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
            // Desliza: con letra grande «Fuerte» se partía en dos líneas dentro del chip.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CROSSFEED_LEVELS.indices.forEach { lvl ->
                    FilterChip(
                        selected = d.crossfeed == lvl, onClick = { SurcoSettings.updateDsp { it.copy(crossfeed = lvl) } },
                        label = { Text(stringResource(CROSSFEED_NAMES[lvl]), maxLines = 1, softWrap = false) },
                        colors = chipColors(), modifier = Modifier.height(ChipHeight),
                    )
                }
            }
            SwitchRow(stringResource(R.string.eq_mono), stringResource(R.string.eq_mono_desc), d.mono) { m -> SurcoSettings.updateDsp { it.copy(mono = m) } }
            SwitchRow(stringResource(R.string.eq_limiter), stringResource(R.string.eq_limiter_desc), d.limiter) { l -> SurcoSettings.updateDsp { it.copy(limiter = l) } }

            TextButton(
                onClick = { SurcoSettings.update { it.copy(dsp = DspParams(), preset = "Plano") } },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(stringResource(R.string.eq_reset_all), color = Muted) }
        }
    }

    if (saving) NameDialog(stringResource(R.string.eq_save_preset), onDismiss = { saving = false }) { typed ->
        // Un nombre de fábrica repetido confundiría cuál está puesto: el propio lleva coletilla.
        // Se compara con el identificador guardado y con el nombre que se ve en el idioma del móvil.
        val taken = BUILTIN_PRESETS.any { it.first.equals(typed, ignoreCase = true) || presetLabel(it.first).equals(typed, ignoreCase = true) } ||
            typed == CUSTOM || typed.equals(presetLabel(CUSTOM), ignoreCase = true)
        val name = if (taken) Res.str(R.string.eq_own_suffix, typed) else typed
        SurcoSettings.update { it.copy(customPresets = it.customPresets + (name to it.dsp.bands), preset = name) }
        saving = false
        Notice.show(Res.str(R.string.eq_preset_saved, name))
    }
    deleting?.let { name ->
        ConfirmSheet(stringResource(R.string.eq_delete_title, name), stringResource(R.string.eq_delete_text), stringResource(R.string.eq_delete_confirm),
            onDismiss = { deleting = null }) {
            SurcoSettings.update { it.copy(customPresets = it.customPresets - name, preset = if (it.preset == name) CUSTOM else it.preset) }
            deleting = null
        }
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
