package com.eddie.surco

import com.eddie.surco.data.AnalysisEntry
import com.eddie.surco.data.BACKUP_VERSION
import com.eddie.surco.data.BackupContents
import com.eddie.surco.data.BackupJson
import com.eddie.surco.data.BackupRead
import com.eddie.surco.data.LibraryFile
import com.eddie.surco.data.analysisForBackup
import com.eddie.surco.data.analysisForRestore
import com.eddie.surco.data.analysisKey
import com.eddie.surco.data.backupFileName
import com.eddie.surco.data.buildBackup
import com.eddie.surco.data.idsByKey
import com.eddie.surco.data.parseBackupJson
import com.eddie.surco.data.readBackup
import com.eddie.surco.data.remapUserIds
import com.eddie.surco.data.restoreBackup
import com.eddie.surco.data.stableKeys
import com.eddie.surco.data.summary
import com.eddie.surco.data.writeBackupJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupTest {
    // usuario.json y ajustes.json tal como los escribe org.json (con «\/» y exponentes).
    private val userJson = """
        {"favorites":[101,102],"playlists":[{"id":1761500000000,"name":"Viaje \"largo\" \/ ñandú 🎵","tracks":[102,103,101]}],
         "plays":{"101":7,"103":2},"lastPlayed":{"101":1761500000123,"103":1761400000000},
         "queue":{"ids":[103,101],"index":1,"position":45000,"shuffle":true,"repeat":2},
         "keys":{"101":["p:\/sdcard\/music\/a.flac","m:uno|x|disco|200"],"102":["p:\/sdcard\/music\/b.mp3","m:dos|x|disco|180"],
                 "103":["p:\/sdcard\/music\/c.opus","m:tres|y|otro|240"]}}
    """.trimIndent()
    private val settingsJson = """
        {"eqOn":true,"bands":[1.5,-3,0,2.5E-1,0,0,0,0,0,-12],"preamp":-2.5,"preset":"Mío","custom":{"Mío":[1,2,3,4,5,6,7,8,9,10]},
         "replayGain":"ALBUM","speed":1.25,"accent":"Cobre","excluded":["\/sdcard\/Notas"],"analyze":true,"albumColumns":3}
    """.trimIndent()

    private fun obj(s: String) = parseBackupJson(s) as BackupJson.Obj

    private val analysis = listOf(
        AnalysisEntry("/sdcard/Music/a.flac", 1_700_000_000, listOf("p:/sdcard/music/a.flac", "m:uno|x|disco|200"), 128.5f, "8A"),
        AnalysisEntry("/sdcard/Music/c.opus", 1_700_000_500, listOf("p:/sdcard/music/c.opus"), null, null),
        AnalysisEntry("/sdcard/Music/d.mp3", 1_700_000_900, listOf("p:/sdcard/music/d.mp3"), 0.1f + 0.2f, null),
    )

    private fun contents() = BackupContents(1_761_523_200_000, obj(userJson), obj(settingsJson), analysis)

    @Test fun exportarEImportarDejaTodoIgual() {
        val c = contents()
        val r = readBackup(buildBackup(c))
        assertEquals(BackupRead.Ok(c), r)
        // Y otra vuelta: escribir lo leído da el mismo archivo, byte a byte.
        assertEquals(buildBackup(c), buildBackup((r as BackupRead.Ok).contents))
    }

    @Test fun losNumerosNoPasanPorDouble() {
        val big = Long.MAX_VALUE - 7
        val u = obj("""{"favorites":[$big],"lastPlayed":{"$big":$big}}""")
        val r = readBackup(buildBackup(contents().copy(user = u))) as BackupRead.Ok
        assertEquals(writeBackupJson(u), writeBackupJson(r.contents.user))
        assertEquals(0.1f + 0.2f, r.contents.analysis[2].bpm)
    }

    @Test fun textosRarosSobreviven() {
        val s = "comillas \" barra \\ / tab \t salto \n control \u0001 emoji 🎵 ñ"
        val j = BackupJson.Obj(mapOf("k" to BackupJson.Str(s)))
        assertEquals(j, parseBackupJson(writeBackupJson(j)))
    }

    @Test fun archivoRotoNoTocaNada() {
        val good = buildBackup(contents())
        val broken = listOf(
            "", "no es json", good.substring(0, good.length / 2), "$good basura", "[1,2,3]",
            good.replace("\"favorites\":[101,102]", "\"favorites\":[\"101\",102]"),
            good.replace("\"created\":1761523200000", "\"created\":\"ayer\""),
            good.replace("\"analysis\":[", "\"analysis\":{\"x\":[").replace("]}", "]}}"),
            good.replace("\"plays\":{\"101\":7", "\"plays\":{\"cien\":7"),
            good.replace("\"settings\":{", "\"settings\":[{").replace(",\"analysis\"", "],\"analysis\""),
            good.replace(",\"user\":", ",\"user\":{},\"user\":"),
            "[".repeat(100_000),
        )
        for (b in broken) {
            var applied = false
            val r = restoreBackup(b) { applied = true }
            assertTrue("debía rechazarse: ${b.take(60)}", r !is BackupRead.Ok)
            assertTrue(!applied)
        }
    }

    @Test fun otraVersionOtroFormatoNoTocaNada() {
        val good = buildBackup(contents())
        var applied = false
        val newer = restoreBackup(good.replace("\"version\":$BACKUP_VERSION", "\"version\":${BACKUP_VERSION + 1}")) { applied = true }
        assertEquals(BackupRead.TooNew(BACKUP_VERSION + 1L), newer)
        val zero = restoreBackup(good.replace("\"version\":$BACKUP_VERSION", "\"version\":0")) { applied = true }
        assertEquals(BackupRead.Broken, zero)
        val other = restoreBackup(good.replace("surco-backup", "otra-app")) { applied = true }
        assertEquals(BackupRead.NotSurco, other)
        assertEquals(BackupRead.NotSurco, restoreBackup(settingsJson) { applied = true })
        assertTrue(!applied)
        // Y la buena sí llega a aplicarse.
        restoreBackup(good) { applied = true }
        assertTrue(applied)
    }

    private val sameLibrary = listOf(
        LibraryFile(101, "/sdcard/Music/a.flac", 1_700_000_000, stableKeys("/sdcard/Music/a.flac", "Uno", "X", "Disco", 200_400)),
        LibraryFile(102, "/sdcard/Music/b.mp3", 1_700_000_100, stableKeys("/sdcard/Music/b.mp3", "Dos", "X", "Disco", 180_000)),
        LibraryFile(103, "/sdcard/Music/c.opus", 1_700_000_500, stableKeys("/sdcard/Music/c.opus", "Tres", "Y", "Otro", 240_000)),
    )

    @Test fun enElMismoMovilLosIdsNoCambian() {
        val u = obj(userJson)
        assertEquals(writeBackupJson(u), writeBackupJson(remapUserIds(u, idsByKey(sameLibrary))))
    }

    @Test fun enOtroMovilSeReconocePorClavesYNoChocaConOtraCancion() {
        // Otro móvil: «a» en otra carpeta (se reconoce por título+artista+álbum+duración), «b» en
        // la misma ruta con otro id, «c» no está. El id 101 aquí es OTRA canción.
        val other = listOf(
            LibraryFile(101, "/storage/x/otra.mp3", 5, stableKeys("/storage/x/otra.mp3", "Otra", "Z", "W", 100_000)),
            LibraryFile(7, "/storage/emulated/0/Music/a.flac", 9, stableKeys("/storage/emulated/0/Music/a.flac", "Uno", "X", "Disco", 200_900)),
            LibraryFile(8, "/sdcard/Music/b.mp3", 9, stableKeys("/sdcard/Music/b.mp3", "Dos", "X", "Disco", 180_000)),
        )
        val u = remapUserIds(obj(userJson), idsByKey(other))
        fun ids(j: BackupJson?) = (j as BackupJson.Arr).items.map { (it as BackupJson.Num).long }
        assertEquals(listOf(7L, 8L), ids(u["favorites"]))
        val p = (u["playlists"] as BackupJson.Arr).items[0] as BackupJson.Obj
        assertEquals(listOf(8L, -104L, 7L), ids(p["tracks"]))
        assertEquals(BackupJson.Str("Viaje \"largo\" / ñandú 🎵"), p["name"])
        assertEquals(setOf("7", "-104"), (u["plays"] as BackupJson.Obj).fields.keys)
        val q = u["queue"] as BackupJson.Obj
        assertEquals(listOf(-104L, 7L), ids(q["ids"]))
        assertEquals(BackupJson.Bool(true), q["shuffle"])
        // Las claves siguen con su canción: «c» (ahora -104) se reconocerá cuando aparezca.
        val keys = u["keys"] as BackupJson.Obj
        assertEquals(setOf("7", "8", "-104"), keys.fields.keys)
        assertEquals(BackupJson.Str("p:/sdcard/music/c.opus"), (keys["-104"] as BackupJson.Arr).items[0])
    }

    @Test fun dosIdsViejosDeLaMismaCancionSeJuntan() {
        val u = obj("""{"plays":{"1":3,"2":4},"lastPlayed":{"1":10,"2":20},"keys":{"1":["p:/a"],"2":["p:/a"]}}""")
        val r = remapUserIds(u, mapOf("p:/a" to 50L))
        assertEquals(BackupJson.Obj(mapOf("50" to BackupJson.Num("7"))), r["plays"])
        assertEquals(BackupJson.Obj(mapOf("50" to BackupJson.Num("20"))), r["lastPlayed"])
    }

    @Test fun loAnalizadoPasaDeUnMovilAOtro() {
        val stored = mapOf(
            analysisKey("/sdcard/Music/a.flac", 1_700_000_000) to (128.5f to "8A"),
            analysisKey("/sdcard/Music/c.opus", 1_700_000_500) to (null to null),
            analysisKey("/sdcard/Music/con|barra.mp3", 3) to (90f to null),
        )
        val entries = analysisForBackup(stored, sameLibrary)
        assertEquals(3, entries.size)
        assertEquals(sameLibrary[0].keys, entries.first { it.path.endsWith("a.flac") }.keys)
        assertEquals("/sdcard/Music/con|barra.mp3", entries.first { it.bpm == 90f }.path)
        // Mismo móvil: vuelve exactamente lo mismo.
        assertEquals(stored, analysisForRestore(entries, sameLibrary))
        // Otro móvil: «a» con otra ruta y otra fecha recibe su análisis con su clave nueva.
        val other = listOf(LibraryFile(7, "/storage/emulated/0/Music/a.flac", 9, stableKeys("/storage/emulated/0/Music/a.flac", "Uno", "X", "Disco", 200_900)))
        val r = analysisForRestore(analysisForBackup(stored, sameLibrary), other)
        assertEquals(128.5f to "8A", r[analysisKey("/storage/emulated/0/Music/a.flac", 9)])
        assertNull(r[analysisKey("/sdcard/Music/a.flac", 1_700_000_000)])
        // Lo que no está aquí se guarda con su clave de allí, por si llega igual.
        assertEquals(90f to null, r[analysisKey("/sdcard/Music/con|barra.mp3", 3)])
    }

    @Test fun nombreYResumen() {
        assertEquals("surco-2026-03-07.json", backupFileName(LocalDate.of(2026, 3, 7)))
        val s = contents().summary()
        assertEquals(2, s.favorites)
        assertEquals(1, s.playlists)
        assertEquals(2, s.analyzed)
    }
}
