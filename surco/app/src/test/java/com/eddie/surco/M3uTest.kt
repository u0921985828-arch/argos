package com.eddie.surco

import com.eddie.surco.data.M3uEntry
import com.eddie.surco.data.M3uSong
import com.eddie.surco.data.decodeM3u
import com.eddie.surco.data.dirFromDocumentId
import com.eddie.surco.data.m3uFileName
import com.eddie.surco.data.matchM3u
import com.eddie.surco.data.parseM3u
import com.eddie.surco.data.playlistNameFromFile
import com.eddie.surco.data.writeM3u8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    private val m = "/storage/emulated/0/Music"
    private val lib = listOf(
        M3uSong(1, "$m/Mecano/Entre el cielo y el suelo/01 Hijo de la Luna.flac", "Hijo de la Luna", "Mecano", 258_000),
        M3uSong(2, "$m/Rosalía/Motomami/03 Candy.mp3", "Candy", "Rosalía", 193_400),
        M3uSong(3, "$m/Varios/CD1/01 Intro.mp3", "Intro", "Varios", 60_000),
        M3uSong(4, "$m/Otros/CD1/01 Intro.mp3", "Intro", "Otros", 90_000),
        M3uSong(5, "$m/Héroes del Silencio/Senderos de traición/02 Maldito duende.mp3", "Maldito duende", "Héroes del Silencio", 263_000),
        M3uSong(6, "/storage/1A2B-3C4D/Discos/Canción triste.ogg", "Canción triste", "", 0),
    )

    private fun ids(text: String, base: String? = null) = matchM3u(parseM3u(text), lib, base)

    @Test fun leeExtinfComentariosYHuecos() {
        val e = parseM3u("\uFEFF#EXTM3U\r\n\r\n# un comentario\r\n#EXTINF:258,Mecano - Hijo de la Luna\r\nuno.flac\r\n  \r\ndos.mp3\n#EXTINF:-1 tvg-name=\"a,b\",Radio\nhttp://radio/x\n")
        assertEquals(
            listOf(M3uEntry("uno.flac", 258, "Mecano - Hijo de la Luna"), M3uEntry("dos.mp3"), M3uEntry("http://radio/x", -1, "Radio")),
            e,
        )
    }

    @Test fun duracionConDecimales() {
        assertEquals(193, parseM3u("#EXTINF:193.4,Rosalía - Candy\na.mp3").single().durationSec)
    }

    @Test fun porRutaAbsoluta() {
        val r = ids("$m/Rosalía/Motomami/03 Candy.mp3\n$m/Varios/CD1/01 Intro.mp3\n$m/Otros/CD1/01 Intro.mp3")
        assertEquals(listOf(2L, 3L, 4L), r.ids)
        assertEquals(0, r.missing)
    }

    @Test fun rutaDeWindowsPorSufijo() {
        val r = ids(
            "#EXTM3U\r\nC:\\Users\\Eddie\\Music\\Rosalía\\Motomami\\03 Candy.mp3\r\n" +
                "D:\\Musica\\Otros\\CD1\\01 Intro.mp3\r\n\\\\nas\\musica\\Varios\\CD1\\01 Intro.mp3\r\n",
        )
        assertEquals(listOf(2L, 4L, 3L), r.ids)
    }

    @Test fun relativaAlaCarpetaDeLaLista() {
        // La lista está en Music/Listas: «..\» sube a Music.
        val r = ids("..\\Mecano\\Entre el cielo y el suelo\\01 Hijo de la Luna.flac\n../Otros/CD1/01 Intro.mp3", "$m/Listas")
        assertEquals(listOf(1L, 4L), r.ids)
    }

    @Test fun relativaSinCarpetaCasaPorSufijo() {
        assertEquals(listOf(4L, 3L), ids("Otros/CD1/01 Intro.mp3\n./Varios/CD1/01 Intro.mp3").ids)
    }

    @Test fun urlFileConEscapes() {
        val r = ids(
            "file:///storage/emulated/0/Music/H%C3%A9roes%20del%20Silencio/Senderos%20de%20traici%C3%B3n/02%20Maldito%20duende.mp3\n" +
                "file:///C:/M%C3%BAsica/Rosal%C3%ADa/Motomami/03%20Candy.mp3\n" +
                "file://localhost/storage/1A2B-3C4D/Discos/Canci%C3%B3n%20triste.ogg",
        )
        assertEquals(listOf(5L, 2L, 6L), r.ids)
    }

    @Test fun nombreDeArchivoSinMayusculas() {
        assertEquals(listOf(1L), ids("E:\\otra\\cosa\\01 HIJO DE LA LUNA.FLAC").ids)
    }

    @Test fun empateDeNombreLoDecideElExtinf() {
        // Dos «01 Intro.mp3» y ninguna carpeta en común: el #EXTINF elige, y si no hay, la duración.
        assertEquals(listOf(4L), ids("#EXTINF:90,Otros - Intro\nX:\\a\\01 Intro.mp3").ids)
        assertEquals(listOf(4L), ids("#EXTINF:89,\nX:\\a\\01 Intro.mp3").ids)
        assertEquals(listOf(3L), ids("X:\\a\\01 Intro.mp3").ids)
    }

    @Test fun ultimoRecursoArtistaTituloSinTildes() {
        val r = ids(
            "#EXTINF:263,HEROES DEL SILENCIO - Maldito Duende\nZ:\\no\\esta\\pista2.wma\n" +
                "#EXTINF:193,rosalia - candy\nhttps://stream/x.mp3\n" +
                "#EXTINF:0,Cancion Triste\nperdida.mp3\n" +
                "#EXTINF:100,Nadie - Nada\nnada.mp3\n" +
                "Mecano - Hijo de la luna.mp3\n",
        )
        assertEquals(listOf(5L, 2L, 6L, 1L), r.ids)
        assertEquals(1, r.missing)
    }

    @Test fun tildesDescompuestasDeMac() {
        // «ó» como «o» + U+0301, como las escribe macOS.
        val nfd = "/Users/yo/Music/Canci\u006F\u0301n triste.ogg"
        assertEquals(listOf(6L), ids(nfd).ids)
    }

    @Test fun latin1DeWindows() {
        val bytes = "#EXTINF:193,Rosalía - Candy\r\nC:\\Música\\Rosalía\\Motomami\\03 Candy.mp3\r\n".toByteArray(charset("windows-1252"))
        val text = decodeM3u(bytes)
        assertEquals("C:\\Música\\Rosalía\\Motomami\\03 Candy.mp3", parseM3u(text).single().path)
        assertEquals(listOf(2L), matchM3u(parseM3u(text), lib).ids)
    }

    @Test fun utf8ConBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\nCanción triste.ogg\n".toByteArray()
        val text = decodeM3u(bytes)
        assertEquals("#EXTM3U\nCanción triste.ogg\n", text)
        assertEquals(listOf(6L), matchM3u(parseM3u(text), lib).ids)
    }

    @Test fun escribeM3u8() {
        val out = writeM3u8(listOf(lib[1], lib[5], M3uSong(9, "/x/a.mp3", "Dos\nlíneas", "Yo", 1_499)))
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:193,Rosalía - Candy\n$m/Rosalía/Motomami/03 Candy.mp3\n" +
                "#EXTINF:-1,Canción triste\n/storage/1A2B-3C4D/Discos/Canción triste.ogg\n" +
                "#EXTINF:1,Yo - Dos líneas\n/x/a.mp3\n",
            out,
        )
    }

    @Test fun idaYVuelta() {
        val text = decodeM3u(writeM3u8(lib.reversed()).toByteArray(Charsets.UTF_8))
        val r = matchM3u(parseM3u(text), lib)
        assertEquals(lib.reversed().map { it.id }, r.ids)
        assertEquals(0, r.missing)
    }

    @Test fun vaciaOSoloComentarios() {
        assertEquals(0, ids("").ids.size)
        assertEquals(0, ids("#EXTM3U\n# nada\n").missing)
    }

    @Test fun nombres() {
        assertEquals("Mi lista", playlistNameFromFile("Mi lista.m3u8"))
        assertEquals("Fiesta", playlistNameFromFile("C:\\listas\\Fiesta.M3U"))
        assertEquals("notas.txt", playlistNameFromFile("notas.txt"))
        assertEquals("AC_DC_ lo mejor.m3u8", m3uFileName("AC/DC: lo mejor"))
        assertEquals("playlist.m3u8", m3uFileName("  "))
    }

    @Test fun carpetaDelDocumento() {
        assertEquals("/storage/emulated/0/Music/Listas", dirFromDocumentId("primary:Music/Listas/x.m3u"))
        assertEquals("/storage/1A2B-3C4D", dirFromDocumentId("1A2B-3C4D:x.m3u8"))
        assertEquals("/storage/emulated/0/Download", dirFromDocumentId("raw:/storage/emulated/0/Download/x.m3u"))
        assertNull(dirFromDocumentId("msf:42"))
        assertEquals("/storage/emulated/0", dirFromDocumentId("primary:x.m3u"))
    }
}
