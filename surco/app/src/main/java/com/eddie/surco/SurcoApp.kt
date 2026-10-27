package com.eddie.surco

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.eddie.surco.data.Texts
import com.eddie.surco.data.androidPlural
import com.eddie.surco.data.androidText

/**
 * La aplicación: antes que cualquier pantalla, servicio, widget o receptor, conecta los textos al
 * idioma elegido (el del móvil, o el de Ajustes → Idioma en Android 13+).
 */
class SurcoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Res.app = this
        Texts.lookup = { androidText(this, it) }
        Texts.plural = { k, n -> androidPlural(this, k, n) }
    }
}

/**
 * Textos de los recursos fuera de Compose (avisos, servicio, widget). Se leen en el momento: si se
 * cambia de idioma, el siguiente ya sale en el nuevo. En Compose, mejor stringResource.
 */
object Res {
    lateinit var app: Context

    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
        if (args.isEmpty()) app.resources.getQuantityString(id, n, n) else app.resources.getQuantityString(id, n, *args)
}
