package com.eddie.surco

import com.eddie.surco.data.BrowseIds
import com.eddie.surco.data.BrowseList
import com.eddie.surco.data.BrowseSong
import com.eddie.surco.data.BrowseTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTest {
    private fun s(id: Long, title: String, artist: String, album: String, track: Int, genre: String = "Rock", bpm: Int? = null) = BrowseSong(
        id, title, artist, album, album.lowercase().ifBlank { null }, listOf(artist.lowercase() to artist), listOf(genre.lowercase() to genre),
        bpm?.let { "${it / 10 * 10} – ${it / 10 * 10 + 9} BPM" to (it / 10 * 10).toLong() }, 1, track,
    )

    private val songs = listOf(
        s(1, "Uno", "Banda A", "Disco Á", 2, bpm = 124),
        s(2, "Dos", "Banda A", "Disco Á", 1, bpm = 128),
        s(3, "Tres", "Banda B", "Otro", 1, "Jazz"),
        s(4, "Cuatro", "Banda B", "", 0, "Jazz"),
    )
    private val tree = BrowseTree(songs, listOf(BrowseList("s:FAVORITES", "Favoritas", listOf(3, 99)), BrowseList("s:NEVER", "Sin escuchar", emptyList())),
        listOf(BrowseList("p:7", "Para el coche", listOf(4, 1))))

    @Test fun cuatroPestanas() {
        assertEquals(listOf("inicio", "listas", "albumes", "artistas"), tree.children(BrowseIds.ROOT)!!.map { it.id })
        assertTrue(tree.children(BrowseIds.ROOT)!!.all { it.browsable && !it.playable })
    }

    @Test fun paraTiYListas() {
        val home = tree.children(BrowseIds.HOME)!!
        assertEquals(listOf("mezclar", "s:FAVORITES", "generos", "ritmo"), home.map { it.id }) // las listas vacías no salen
        assertTrue(home.first().playable)
        assertEquals("1 canción", home[1].subtitle) // la 99 ya no está en el móvil
        assertEquals(listOf("s:FAVORITES", "p:7"), tree.children(BrowseIds.LISTS)!!.map { it.id })
        assertEquals(listOf("120 – 129 BPM"), tree.children(BrowseIds.TEMPO)!!.map { it.title })
    }

    @Test fun albumesEnOrdenDeDisco() {
        val albums = tree.children(BrowseIds.ALBUMS)!!
        assertEquals(listOf("Disco Á", "Otro"), albums.map { it.title })
        assertTrue(albums.first().grid.not() && BrowseIds.ALBUMS.let { tree.children(BrowseIds.ROOT)!!.first { n -> n.id == it }.grid })
        val tracks = tree.children(albums.first().id)!!
        assertEquals(listOf("Dos", "Uno"), tracks.map { it.title }) // por número de pista
        assertTrue(tracks.all { it.playable })
    }

    @Test fun tocarUnaCancionSuenaSuCarpetaDesdeElla() {
        val album = tree.children(BrowseIds.ALBUMS)!!.first().id
        val uno = tree.children(album)!!.first { it.title == "Uno" }
        assertEquals(Triple(listOf(2L, 1L), 1, false), tree.playContext(uno.id))
        assertEquals(Triple(listOf(4L, 1L), 1, false), tree.playContext(BrowseIds.song("p:7", 1)))
        val todo = tree.playContext(BrowseIds.SHUFFLE_ALL)!!
        assertEquals(4, todo.first.size); assertTrue(todo.third)
        // Una canción suelta (por su id de siempre) suena sola; una carpeta que ya no existe, también.
        assertEquals(Triple(listOf(3L), 0, false), tree.playContext("3"))
        assertEquals(Triple(listOf(3L), 0, false), tree.playContext(BrowseIds.song("al:no existe", 3)))
        assertNull(tree.playContext("999"))
        assertEquals(Pair("al:disco á", 1L), BrowseIds.parseSong("al:disco á|t:1"))
    }

    @Test fun buscarPorVoz() {
        assertEquals(listOf(1L), tree.search("uno").map { it.id })
        // «Banda A» → sus canciones, en orden de disco.
        assertEquals(listOf(2L, 1L), tree.search("banda a").map { it.id })
        assertEquals(listOf(2L, 1L), tree.search("DISCO A").map { it.id }) // sin tildes ni mayúsculas
        assertTrue(tree.search("   ").isEmpty())
        assertTrue(tree.search("nada que ver").isEmpty())
    }

    @Test fun nodosSueltos() {
        assertEquals("Surco", tree.item(BrowseIds.ROOT)!!.title)
        assertEquals("Uno", tree.item(BrowseIds.song("al:disco á", 1))!!.title)
        assertEquals("Para el coche", tree.item("p:7")!!.title)
        assertNull(tree.item("al:no existe"))
        assertNull(tree.children("al:no existe"))
    }
}
