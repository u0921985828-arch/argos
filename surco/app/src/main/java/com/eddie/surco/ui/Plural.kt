package com.eddie.surco.ui

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Plural de los recursos en Compose (pluralStringResource aún es experimental en Compose 1.7).
 * Sin [args], el número va como único argumento («%d canciones»). Al cambiar de idioma la
 * actividad se recrea, así que se vuelve a leer.
 */
@Composable
@ReadOnlyComposable
fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String {
    val r = LocalContext.current.resources
    return if (args.isEmpty()) r.getQuantityString(id, n, n) else r.getQuantityString(id, n, *args)
}
