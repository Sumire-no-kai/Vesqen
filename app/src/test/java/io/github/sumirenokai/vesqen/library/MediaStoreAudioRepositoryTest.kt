package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreAudioRepositoryTest {
    @Test
    fun `legacy folder fallback keeps only the immediate parent label`() {
        assertEquals(
            "Album",
            legacyMediaFolderName("/storage/emulated/0/Music/Artist/Album/song.flac"),
        )
        assertEquals("Music", legacyMediaFolderName("/storage/emulated/0/Music/song.mp3"))
        assertEquals("Album", legacyMediaFolderName("C:\\Music\\Album\\song.wav"))
        assertEquals("", legacyMediaFolderName("song.opus"))
    }
}
