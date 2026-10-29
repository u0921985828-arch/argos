package com.eddie.surco.data

import java.time.LocalDate

/*
 * Copia de seguridad de Surco, la parte sin Android: un único JSON con versión, fecha y tres
 * partes (lo del usuario, los ajustes y lo analizado). Se lee y se valida ENTERO aquí antes de
 * tocar nada; org.json no está en la JVM de pruebas, así que el árbol JSON es propio y pequeño.
 */

const val BACKUP_FORMAT = "surco-backup"
/** Sube solo si cambia el sobre o el significado de una parte; esta versión no lee copias más nuevas. */
const val BACKUP_VERSION = 1
/** Más que esto no es una copia de Surco (10.000 canciones analizadas ocupan ~2 MB). */
const val BACKUP_MAX_BYTES = 64L * 1024 * 1024

/** «surco-AAAA-MM-DD.json»: LocalDate siempre escribe la fecha ISO con cifras occidentales. */
fun backupFileName(date: LocalDate): String = "surco-$date.json"

/** Clave de [AnalysisStore]: ruta y fecha de modificación (si el archivo cambia, se analiza otra vez). */
fun analysisKey(path: String, modified: Long) = "$path|$modified"

/** Árbol JSON mínimo. Los números se guardan como venían: un id de 64 bits no pasa por Double. */
sealed interface BackupJson {
    data class Obj(val fields: Map<String, BackupJson>) : BackupJson {
        operator fun get(key: String): BackupJson? = fields[key]
    }
    data class Arr(val items: List<BackupJson>) : BackupJson
    data class Str(val value: String) : BackupJson
    data class Num(val raw: String) : BackupJson {
        val long: Long? get() = raw.toLongOrNull()
    }
    data class Bool(val value: Boolean) : BackupJson
    data object Null : BackupJson
}

/** null si no es JSON (o sobra algo detrás, o anida demasiado). */
fun parseBackupJson(text: String): BackupJson? =
    runCatching { BackupJsonReader(text.removePrefix("\uFEFF")).document() }.getOrNull()

fun writeBackupJson(j: BackupJson): String = StringBuilder().also { writeJsonTo(j, it) }.toString()

private fun writeJsonTo(j: BackupJson, sb: StringBuilder) {
    when (j) {
        is BackupJson.Obj -> {
            sb.append('{')
            var first = true
            for ((k, v) in j.fields) {
                if (!first) sb.append(',')
                first = false
                quoteJson(k, sb); sb.append(':'); writeJsonTo(v, sb)
            }
            sb.append('}')
        }
        is BackupJson.Arr -> {
            sb.append('[')
            j.items.forEachIndexed { i, v -> if (i > 0) sb.append(','); writeJsonTo(v, sb) }
            sb.append(']')
        }
        is BackupJson.Str -> quoteJson(j.value, sb)
        is BackupJson.Num -> sb.append(j.raw)
        is BackupJson.Bool -> sb.append(j.value)
        BackupJson.Null -> sb.append("null")
    }
}

private fun quoteJson(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> sb.append(c)
    }
    sb.append('"')
}

/** Lector estricto: un archivo a medias o tocado a mano no pasa por bueno. */
private class BackupJsonReader(private val s: String) {
    private var i = 0

    fun document(): BackupJson {
        val v = value(0)
        ws()
        require(i == s.length)
        return v
    }

    private fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

    private fun next(): Char { require(i < s.length); return s[i++] }

    private fun value(depth: Int): BackupJson {
        // Sin límite, un archivo con miles de «[» desbordaría la pila.
        require(depth < 64)
        ws()
        require(i < s.length)
        return when (s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> BackupJson.Str(str())
            't' -> literal("true", BackupJson.Bool(true))
            'f' -> literal("false", BackupJson.Bool(false))
            'n' -> literal("null", BackupJson.Null)
            else -> num()
        }
    }

    private fun literal(word: String, v: BackupJson): BackupJson {
        require(s.startsWith(word, i))
        i += word.length
        return v
    }

    private fun obj(depth: Int): BackupJson.Obj {
        i++
        val m = LinkedHashMap<String, BackupJson>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return BackupJson.Obj(m) }
        while (true) {
            ws()
            require(i < s.length && s[i] == '"')
            val k = str()
            // Como org.json: una clave repetida es un archivo roto, no «gana la última».
            require(k !in m)
            ws()
            require(next() == ':')
            m[k] = value(depth + 1)
            ws()
            when (next()) {
                ',' -> continue
                '}' -> return BackupJson.Obj(m)
                else -> throw IllegalArgumentException()
            }
        }
    }

    private fun arr(depth: Int): BackupJson.Arr {
        i++
        val l = ArrayList<BackupJson>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return BackupJson.Arr(l) }
        while (true) {
            l += value(depth + 1)
            ws()
            when (next()) {
                ',' -> continue
                ']' -> return BackupJson.Arr(l)
                else -> throw IllegalArgumentException()
            }
        }
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> when (next()) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                    'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length)
                        val hex = s.substring(i, i + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException()
                }
                c < ' ' -> throw IllegalArgumentException()
                else -> sb.append(c)
            }
        }
    }

    private fun num(): BackupJson.Num {
        val start = i
        if (i < s.length && s[i] == '-') i++
        val intStart = i
        while (i < s.length && s[i].isAsciiDigit()) i++
        require(i > intStart)
        if (i < s.length && s[i] == '.') {
            i++
            val f = i
            while (i < s.length && s[i].isAsciiDigit()) i++
            require(i > f)
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val e = i
            while (i < s.length && s[i].isAsciiDigit()) i++
            require(i > e)
        }
        return BackupJson.Num(s.substring(start, i))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

// ---------- El contenido ----------

/**
 * Lo analizado de un archivo (lo de AnalysisStore): BPM, tonalidad, sonoridad en LUFS y si ya se
 * intentó medir la sonoridad ([loudnessDone]: si salió null, no se vuelve a intentar).
 */
data class Analyzed(val bpm: Float?, val key: String?, val lufs: Float? = null, val loudnessDone: Boolean = false) {
    val empty: Boolean get() = bpm == null && key == null && lufs == null
}

/** Lo analizado de un archivo, con sus claves estables para reconocerlo en otro móvil. */
data class AnalysisEntry(
    val path: String, val modified: Long, val keys: List<String>, val bpm: Float?, val key: String?,
    val lufs: Float? = null, val loudnessDone: Boolean = false,
)

/** Una canción de la biblioteca actual, lo justo para reconocerla. */
data class LibraryFile(val id: Long, val path: String, val modified: Long, val keys: List<String>)

/**
 * Lo que lleva una copia. [user] es el mismo JSON que usuario.json y [settings] el de ajustes.json:
 * los guardan sus propias serializaciones, así que un campo nuevo entra en la copia sin tocar esto.
 */
data class BackupContents(
    val created: Long,
    val user: BackupJson.Obj,
    val settings: BackupJson.Obj,
    val analysis: List<AnalysisEntry>,
)

sealed interface BackupRead {
    data class Ok(val contents: BackupContents) : BackupRead
    /** No es JSON, está a medias o una parte no tiene la forma que toca. */
    data object Broken : BackupRead
    /** JSON bueno, pero no es una copia de Surco. */
    data object NotSurco : BackupRead
    /** De un Surco más nuevo, con un formato que esta versión no sabe leer. */
    data class TooNew(val version: Long) : BackupRead
}

fun buildBackup(c: BackupContents): String = writeBackupJson(
    BackupJson.Obj(linkedMapOf(
        "format" to BackupJson.Str(BACKUP_FORMAT),
        "version" to BackupJson.Num(BACKUP_VERSION.toString()),
        "created" to BackupJson.Num(c.created.toString()),
        "user" to c.user,
        "settings" to c.settings,
        "analysis" to BackupJson.Arr(c.analysis.map { e ->
            BackupJson.Obj(LinkedHashMap<String, BackupJson>().apply {
                put("path", BackupJson.Str(e.path))
                put("modified", BackupJson.Num(e.modified.toString()))
                put("keys", BackupJson.Arr(e.keys.map { BackupJson.Str(it) }))
                // Float.toString es la escritura más corta que vuelve al mismo Float.
                e.bpm?.let { put("bpm", BackupJson.Num(it.toString())) }
                e.key?.let { put("key", BackupJson.Str(it)) }
                e.lufs?.let { put("lufs", BackupJson.Num(it.toString())) }
                if (e.loudnessDone) put("loudnessDone", BackupJson.Bool(true))
            })
        }),
    )),
)

/** Valida el archivo entero; solo [BackupRead.Ok] lleva algo que aplicar. */
fun readBackup(text: String): BackupRead {
    val root = parseBackupJson(text) as? BackupJson.Obj ?: return BackupRead.Broken
    if ((root["format"] as? BackupJson.Str)?.value != BACKUP_FORMAT) return BackupRead.NotSurco
    val version = (root["version"] as? BackupJson.Num)?.long ?: return BackupRead.Broken
    if (version > BACKUP_VERSION) return BackupRead.TooNew(version)
    if (version < 1) return BackupRead.Broken
    val created = (root["created"] as? BackupJson.Num)?.long ?: return BackupRead.Broken
    val user = root["user"] as? BackupJson.Obj ?: return BackupRead.Broken
    val settings = root["settings"] as? BackupJson.Obj ?: return BackupRead.Broken
    val analysis = (root["analysis"] as? BackupJson.Arr)?.items?.map { analysisEntry(it) ?: return BackupRead.Broken }
        ?: return BackupRead.Broken
    if (!validUser(user)) return BackupRead.Broken
    return BackupRead.Ok(BackupContents(created, user, settings, analysis))
}

/** Lee y, solo si todo está bien, aplica. Un archivo roto o de otra versión no llega a [apply]. */
fun restoreBackup(text: String, apply: (BackupContents) -> Unit): BackupRead =
    readBackup(text).also { if (it is BackupRead.Ok) apply(it.contents) }

private fun analysisEntry(j: BackupJson): AnalysisEntry? {
    val o = j as? BackupJson.Obj ?: return null
    val path = (o["path"] as? BackupJson.Str)?.value ?: return null
    val modified = (o["modified"] as? BackupJson.Num)?.long ?: return null
    val keys = strings(o["keys"]) ?: return null
    fun num(name: String): Result<Float?> = when (val b = o[name]) {
        null, BackupJson.Null -> Result.success(null)
        is BackupJson.Num -> b.raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(name))
        else -> Result.failure(IllegalArgumentException(name))
    }
    val bpm = num("bpm").getOrElse { return null }
    // Copias de antes de la 2.8 no traen sonoridad: se vuelve a medir.
    val lufs = num("lufs").getOrElse { return null }
    val done = when (val d = o["loudnessDone"]) {
        null, BackupJson.Null -> false
        is BackupJson.Bool -> d.value
        else -> return null
    }
    val key = when (val k = o["key"]) {
        null, BackupJson.Null -> null
        is BackupJson.Str -> k.value
        else -> return null
    }
    return AnalysisEntry(path, modified, keys, bpm, key, lufs, done)
}

private fun strings(j: BackupJson?): List<String>? =
    (j as? BackupJson.Arr)?.items?.map { (it as? BackupJson.Str)?.value ?: return null }

private fun ids(j: BackupJson?): List<Long>? =
    (j as? BackupJson.Arr)?.items?.map { (it as? BackupJson.Num)?.long ?: return null }

/** Un objeto con ids por clave ({"123": …}); null si alguna clave no es un id o un valor no vale. */
private fun idMap(j: BackupJson?, valid: (BackupJson) -> Boolean): Map<Long, BackupJson>? {
    val o = j as? BackupJson.Obj ?: return null
    return o.fields.entries.associate { (k, v) -> (k.toLongOrNull() ?: return null) to v.also { if (!valid(it)) return null } }
}

private fun isLong(j: BackupJson) = (j as? BackupJson.Num)?.long != null

/**
 * La parte del usuario tiene la forma de usuario.json (UserStore): lo que falta vale (es «vacío»),
 * pero lo que está tiene que tener su tipo, o se aplicaría a medias.
 */
fun validUser(u: BackupJson.Obj): Boolean {
    fun ok(key: String, check: (BackupJson) -> Boolean) = u[key]?.let(check) ?: true
    return ok("favorites") { ids(it) != null } &&
        ok("playlists") { pl ->
            (pl as? BackupJson.Arr)?.items?.all { p ->
                p is BackupJson.Obj && (p["id"]?.let(::isLong) ?: true) &&
                    (p["name"]?.let { it is BackupJson.Str } ?: true) && (p["tracks"]?.let { ids(it) != null } ?: true)
            } ?: false
        } &&
        ok("plays") { idMap(it, ::isLong) != null } &&
        ok("lastPlayed") { idMap(it, ::isLong) != null } &&
        ok("queue") { q -> q is BackupJson.Obj && (q["ids"]?.let { ids(it) != null } ?: true) } &&
        ok("keys") { idMap(it) { v -> strings(v) != null } != null }
}

// ---------- Otro móvil, otros ids ----------

/** Clave estable → id actual (la primera canción con esa clave). */
fun idsByKey(library: List<LibraryFile>): Map<String, Long> {
    val m = HashMap<String, Long>()
    for (f in library) for (k in f.keys) m.putIfAbsent(k, f.id)
    return m
}

/**
 * Los ids de MediaStore cambian de un móvil a otro y el id de una canción de allí puede ser el
 * de OTRA de aquí. Cada id con claves se cambia por el de la canción que las tiene; si aquí no
 * está (aún), por un id negativo que no choca con ninguno y que `UserStore.reconcile` cambia
 * por el bueno cuando la canción aparezca. Los ids sin claves se quedan como están.
 */
fun remapUserIds(user: BackupJson.Obj, idByKey: Map<String, Long>): BackupJson.Obj {
    val keysOf = idMap(user["keys"]) { true }.orEmpty().mapValues { strings(it.value).orEmpty() }
    val m = HashMap<Long, Long>()
    fun f(id: Long): Long = m.getOrPut(id) {
        val keys = keysOf[id].orEmpty()
        if (keys.isEmpty()) id else keys.firstNotNullOfOrNull { idByKey[it] } ?: if (id >= 0) -id - 1 else id
    }
    fun num(v: Long) = BackupJson.Num(v.toString())
    fun arr(j: BackupJson) = BackupJson.Arr(ids(j).orEmpty().map { num(f(it)) })
    /** Dos ids viejos pueden ser ya la misma canción: se juntan con [merge]. */
    fun byId(j: BackupJson?, merge: (Long, Long) -> Long): BackupJson? = j?.let { o ->
        val out = LinkedHashMap<Long, Long>()
        idMap(o, ::isLong).orEmpty().forEach { (id, v) ->
            val n = (v as? BackupJson.Num)?.long ?: 0L
            out[f(id)] = out[f(id)]?.let { merge(it, n) } ?: n
        }
        BackupJson.Obj(out.entries.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to num(v) })
    }

    val out = LinkedHashMap(user.fields)
    user["favorites"]?.let { a -> out["favorites"] = BackupJson.Arr(ids(a).orEmpty().map(::f).distinct().map(::num)) }
    (user["playlists"] as? BackupJson.Arr)?.let { pl ->
        out["playlists"] = BackupJson.Arr(pl.items.map { p ->
            val t = (p as? BackupJson.Obj)?.get("tracks")
            if (p is BackupJson.Obj && t != null) BackupJson.Obj(LinkedHashMap(p.fields).apply { put("tracks", arr(t)) }) else p
        })
    }
    byId(user["plays"]) { a, b -> a + b }?.let { out["plays"] = it }
    byId(user["lastPlayed"]) { a, b -> maxOf(a, b) }?.let { out["lastPlayed"] = it }
    (user["queue"] as? BackupJson.Obj)?.let { q ->
        out["queue"] = q["ids"]?.let { BackupJson.Obj(LinkedHashMap(q.fields).apply { put("ids", arr(it)) }) } ?: q
    }
    user["keys"]?.let {
        val keys = LinkedHashMap<String, BackupJson>()
        keysOf.forEach { (id, ks) -> keys.putIfAbsent(f(id).toString(), BackupJson.Arr(ks.map { k -> BackupJson.Str(k) })) }
        out["keys"] = BackupJson.Obj(keys)
    }
    return BackupJson.Obj(out)
}

/**
 * Lo analizado para la copia: [stored] es lo de AnalysisStore («ruta|fecha» → lo analizado).
 * Lleva las claves estables de la canción si está en la biblioteca y, si no, al menos la ruta.
 */
fun analysisForBackup(stored: Map<String, Analyzed>, library: List<LibraryFile>): List<AnalysisEntry> {
    val byKey = library.associateBy { analysisKey(it.path, it.modified) }
    return stored.mapNotNull { (k, v) ->
        val bar = k.lastIndexOf('|')
        if (bar < 0) return@mapNotNull null
        val path = k.substring(0, bar)
        val modified = k.substring(bar + 1).toLongOrNull() ?: return@mapNotNull null
        val keys = byKey[k]?.keys ?: stableKeys(path, "", "", "", 0)
        AnalysisEntry(path, modified, keys, v.bpm, v.key, v.lufs, v.loudnessDone)
    }.sortedWith(compareBy({ it.path }, { it.modified }))
}

/**
 * Al restaurar: cada análisis va a la canción de aquí que tenga sus claves (otra ruta u otra fecha
 * de modificación tras copiar los archivos); si no hay ninguna, con su clave de allí, por si es
 * el mismo móvil o la canción llega igual. Un «no se encontró nada» no tapa un resultado.
 */
fun analysisForRestore(entries: List<AnalysisEntry>, library: List<LibraryFile>): Map<String, Analyzed> {
    val idx = HashMap<String, LibraryFile>()
    for (f in library) for (k in f.keys) idx.putIfAbsent(k, f)
    val out = LinkedHashMap<String, Analyzed>()
    for (e in entries) {
        val f = e.keys.firstNotNullOfOrNull { idx[it] }
        val key = if (f != null) analysisKey(f.path, f.modified) else analysisKey(e.path, e.modified)
        val old = out[key]
        if (old == null || old.empty) out[key] = Analyzed(e.bpm, e.key, e.lufs, e.loudnessDone)
    }
    return out
}

/** Lo que se enseña antes de confirmar: qué trae la copia. */
data class BackupSummary(val favorites: Int, val playlists: Int, val analyzed: Int)

fun BackupContents.summary() = BackupSummary(
    favorites = (user["favorites"] as? BackupJson.Arr)?.items?.size ?: 0,
    playlists = (user["playlists"] as? BackupJson.Arr)?.items?.size ?: 0,
    analyzed = analysis.count { it.bpm != null || it.key != null },
)
