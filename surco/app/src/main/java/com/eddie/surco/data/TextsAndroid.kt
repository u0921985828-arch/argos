package com.eddie.surco.data

import android.content.Context

/** Los textos de [Texts] desde los recursos (idioma elegido). Null: el de por defecto. */
fun androidText(ctx: Context, key: String): String? = null

/** Los plurales de [Texts] desde los recursos. Null: el de por defecto. */
fun androidPlural(ctx: Context, key: String, n: Int): String? = null
