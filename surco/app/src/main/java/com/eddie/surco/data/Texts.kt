package com.eddie.surco.data

/**
 * Textos de la parte pura (nombres de etiquetas, carpetas del coche, plurales…). Sin Android: la
 * app pone [lookup] y [plural] para leerlos de los recursos, en el idioma elegido; las pruebas en
 * la JVM se quedan con los de por defecto, en español.
 */
object Texts {
    /** Texto de [key] en el idioma de la app, o null para usar el de por defecto. */
    @Volatile var lookup: (key: String) -> String? = { null }

    /** Plural de [key] para [n] en el idioma de la app, o null para usar el de por defecto. */
    @Volatile var plural: (key: String, n: Int) -> String? = { _, _ -> null }

    fun t(key: String, default: String): String = lookup(key) ?: default

    /** [one] y [other] llevan «%d» donde va el número. */
    fun n(key: String, n: Int, one: String, other: String): String =
        plural(key, n) ?: (if (n == 1) one else other).replace("%d", n.toString())
}
