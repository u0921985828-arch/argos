package com.eddie.surco.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Avisos breves de Surco: una franja con el estilo de la app encima del minirreproductor, en vez
 * del Toast gris del sistema, y con una acción cuando la hay («Deshacer», «Ver»). Uno a la vez:
 * el nuevo sustituye al anterior. Con la app fuera de la pantalla (lo avisa el servicio), Toast.
 */
object Notice {
    class Item(val id: Long, val text: String, val action: String?, val onAction: (() -> Unit)?)

    private val _current = MutableStateFlow<Item?>(null)
    val current = _current.asStateFlow()
    private var next = 0L

    /** La app está a la vista (entre onStart y onStop): si no, nadie vería la franja. */
    @Volatile var visible = false

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _current.value = Item(++next, text, action?.takeIf { onAction != null }, onAction)
    }

    /** Desde el servicio: franja si la app se ve, Toast si no. Llamar en el hilo principal. */
    fun fromService(ctx: Context, text: String, long: Boolean = false) {
        if (visible) show(text) else Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    internal fun dismiss(item: Item) { _current.compareAndSet(item, null) }
}

/**
 * La franja del aviso, abajo y a [inset] del borde; con [top], arriba (en «Sonando», donde abajo
 * están los mandos y no se pueden tapar).
 */
@Composable
fun BoxScope.NoticeHost(inset: Dp = 0.dp, top: Boolean = false) {
    val item by Notice.current.collectAsState()
    // El último que hubo, para que la franja salga con su texto mientras se va (no es estado: no repinta).
    val last = remember { arrayOfNulls<Notice.Item>(1) }
    item?.let { last[0] = it }
    LaunchedEffect(item) {
        val cur = item ?: return@LaunchedEffect
        // Con acción hay que tener tiempo de leerla y llegar al botón.
        delay(if (cur.action != null) 6000 else 3500)
        Notice.dismiss(cur)
    }
    AnimatedVisibility(
        visible = item != null,
        modifier = Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(top = if (top) inset + 8.dp else 0.dp, bottom = if (top) 0.dp else inset + 8.dp).padding(horizontal = 12.dp),
        enter = slideInVertically { if (top) -it / 2 else it / 2 } + fadeIn(), exit = slideOutVertically { if (top) -it / 2 else it / 2 } + fadeOut(),
    ) {
        val n = item ?: last[0] ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel2).heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(n.text, color = Paper, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (n.action != null && n.onAction != null) {
                TextButton(onClick = { Notice.dismiss(n); n.onAction.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(n.action, color = Copper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            } else Box(Modifier.padding(end = 12.dp))
        }
    }
}
