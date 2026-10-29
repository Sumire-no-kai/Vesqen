package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** How the Artists page splits a shared artist tag (#168). */
class ArtistNamesTest {
    private val defaults = ArtistNames(ArtistSplitSettings())

    private fun ArtistNames.names(artist: String, albumArtist: String = ""): List<String> =
        of(AudioTrack(1, "content://track/1", "Song", artist, "Album", 1_000, albumArtist = albumArtist)).map(ArtistName::name)

    @Test
    fun `every default separator splits`() {
        assertEquals(listOf("塞壬唱片-MSR", "Adam Gubman", "Pete Calandra"), defaults.names("塞壬唱片-MSR/Adam Gubman/Pete Calandra"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori;Nari"))
        assertEquals(listOf("森", "成"), defaults.names("森；成"))
        assertEquals(listOf("周深", "王菲"), defaults.names("周深、王菲"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori feat. Nari"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori ft. Nari"))
    }

    @Test
    fun `feat and ft split only with a space on both sides, in any case`() {
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori FEAT. Nari"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori Ft. Nari"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori　feat.　Nari"))
        assertEquals(listOf("Mori feat.Nari"), defaults.names("Mori feat.Nari"))
        assertEquals(listOf("Morifeat. Nari"), defaults.names("Morifeat. Nari"))
        assertEquals(listOf("Swift.Nari"), defaults.names("Swift.Nari"))
        // A bracket is not a space: the tag stays whole rather than leave "Mori (" and "Nari)".
        assertEquals(listOf("Mori (feat. Nari)"), defaults.names("Mori (feat. Nari)"))
    }

    @Test
    fun `names that carry an ampersand, a comma or an x stay whole by default`() {
        listOf("Simon & Garfunkel", "Earth, Wind & Fire", "Lil Nas X", "Mori x Nari", "Mori × Nari", "Tyler, The Creator")
            .forEach { tag -> assertEquals(listOf(tag), defaults.names(tag)) }
    }

    @Test
    fun `kept names are not split, also inside a longer tag`() {
        assertEquals(listOf("AC/DC"), defaults.names("AC/DC"))
        assertEquals(listOf("ac/dc"), defaults.names("ac/dc"))
        assertEquals(listOf("AC/DC", "Metallica"), defaults.names("AC/DC/Metallica"))
        assertEquals(listOf("Metallica", "AC/DC"), defaults.names("Metallica / AC/DC"))

        val custom = ArtistNames(ArtistSplitSettings(keptNames = listOf("Au/Ra", "Smith/Kotzen/Band")))
        assertEquals(listOf("Smith/Kotzen/Band", "Au/Ra"), custom.names("Smith/Kotzen/Band/Au/Ra"))
        // Without the default list, AC/DC is two names.
        assertEquals(listOf("AC", "DC"), custom.names("AC/DC"))
    }

    @Test
    fun `split names are trimmed and a name repeated in one tag counts once`() {
        assertEquals(listOf("Mori", "Nari"), defaults.names("  Mori /  Nari  "))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori / Nari / mori / MORI"))
        assertEquals(listOf("Mori", "Nari"), defaults.names("Mori//Nari/"))
        assertEquals(listOf("ＡＢＣ", "Nari"), defaults.names("ＡＢＣ/Nari/abc"))
    }

    @Test
    fun `a tag of separators only or no tag still files the song somewhere`() {
        assertEquals(listOf("/"), defaults.names("/"))
        assertEquals(listOf(""), defaults.names(""))
        assertEquals(listOf("Mori", "Nari"), defaults.names("", albumArtist = "Mori/Nari"))
    }

    @Test
    fun `added separators split and letters still need spaces`() {
        val wide = ArtistNames(ArtistSplitSettings(separators = DefaultArtistSeparators + listOf("&", "x")))
        assertEquals(listOf("Simon", "Garfunkel"), wide.names("Simon & Garfunkel"))
        assertEquals(listOf("Mori", "Nari"), wide.names("Mori x Nari"))
        assertEquals(listOf("Mori", "Nari"), wide.names("Mori X Nari"))
        assertEquals(listOf("Lil Nas X"), wide.names("Lil Nas X"))
        assertEquals(listOf("Xenon"), wide.names("Xenon"))

        val none = ArtistNames(ArtistSplitSettings(separators = emptyList()))
        assertEquals(listOf("Mori/Nari"), none.names("Mori/Nari"))
    }

    @Test
    fun `switched off, the whole tag is the artist exactly as stored`() {
        val off = ArtistNames(ArtistSplitSettings(enabled = false))
        val artists = off.of(AudioTrack(1, "content://track/1", "Song", " Mori/Nari ", "Album", 1_000))

        assertEquals(listOf(" Mori/Nari "), artists.map(ArtistName::key))
        assertEquals(listOf(" Mori/Nari "), artists.map(ArtistName::name))
    }

    @Test
    fun `case, width and spacing make one key`() {
        assertEquals(artistNameKey("adam gubman"), artistNameKey("Adam Gubman"))
        assertEquals(artistNameKey("adam gubman"), artistNameKey("  ADAM   GUBMAN "))
        assertEquals(artistNameKey("adam gubman"), artistNameKey("Adam　Gubman"))
        assertEquals(artistNameKey("abc"), artistNameKey("ＡＢＣ"))
    }

    @Test
    fun `each tag is split once`() {
        val first = AudioTrack(1, "content://track/1", "One", "Mori/Nari", "Album", 1_000)
        val second = first.copy(id = 2, title = "Two")

        assertSame(defaults.of(first), defaults.of(second))
    }

    @Test
    fun `typed lists are parsed by spaces and by lines, each entry once`() {
        assertEquals(listOf("/", ";", "feat.", "&"), parseArtistSeparators(" / ;　feat.  FEAT.\n& "))
        assertEquals(emptyList<String>(), parseArtistSeparators("   "))
        assertEquals(listOf("AC/DC", "Au/Ra"), parseKeptArtistNames("AC/DC\n ac/dc \n\nAu/Ra\n"))
    }
}
