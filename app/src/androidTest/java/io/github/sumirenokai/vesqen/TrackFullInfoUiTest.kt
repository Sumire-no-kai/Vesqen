package io.github.sumirenokai.vesqen

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.TrackFullInfo
import io.github.sumirenokai.vesqen.library.TrackInfoReader
import io.github.sumirenokai.vesqen.library.TrackTags
import io.github.sumirenokai.vesqen.ui.components.TrackDetailsSheet
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Full information in track details (#240): collapsed, read when opened, rows only with values. */
class TrackFullInfoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val track = AudioTrack(
        id = 21,
        contentUri = "content://media/external/audio/media/21",
        title = "Evening",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000,
        fileName = "Evening.flac",
        fileSizeBytes = 25_000_000,
        mimeType = "audio/flac",
    )
    private var reads = 0

    private fun show(info: TrackFullInfo) {
        compose.setContent {
            VesqenTheme {
                TrackDetailsSheet(
                    track = track,
                    onDismiss = {},
                    onPlay = {},
                    loadFullInfo = {
                        reads++
                        info
                    },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithTag("vesqen.track-details.full-info").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertRow(label: Int, value: String) {
        compose.onNode(hasText(context.getString(label))).assertExists()
        compose.onNode(hasText(value)).assertExists()
    }

    @Test
    fun itStaysCollapsedAndUnreadUntilOpened() {
        show(TrackFullInfo(TrackTags(composer = "Bach")))

        val collapsed = context.getString(R.string.now_notes_collapsed)
        compose.onNodeWithTag("vesqen.track-details.full-info")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
        compose.onAllNodesWithText("Bach").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, reads) }

        open()
        compose.onNodeWithTag("vesqen.track-details.full-info")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.now_notes_expanded)))
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test
    fun everyValueShowsUnderItsGroup() {
        show(
            TrackFullInfo(
                TrackTags(
                    container = "FLAC",
                    composer = "Bach",
                    lyricist = "Picander",
                    encoder = "libFLAC 1.4.3",
                    isrc = "USRC17607839",
                    trackTotal = 12,
                    discTotal = 2,
                    replayGainTrackGain = "-7.12 dB",
                    replayGainTrackPeak = "0.98",
                    replayGainAlbumGain = "-6.00 dB",
                    coverMimeType = "image/jpeg",
                    coverIsFront = true,
                ),
                channelLayout = "L R",
                lossless = true,
            ),
        )
        open()

        assertRow(R.string.detail_container, "FLAC")
        assertRow(R.string.detail_compression, context.getString(R.string.compression_lossless))
        // 25 000 000 bytes over 200 s.
        assertRow(R.string.detail_overall_bitrate, context.getString(R.string.bitrate_value, 1000))
        assertRow(R.string.detail_channel_layout, "L R")
        assertRow(R.string.detail_encoder, "libFLAC 1.4.3")
        val peak = context.getString(R.string.replay_gain_peak_value, "0.98")
        val separator = context.getString(R.string.chain_segment_list_separator)
        assertRow(R.string.detail_replay_gain_track, "-7.12 dB$separator$peak")
        assertRow(R.string.detail_replay_gain_album, "-6.00 dB")
        compose.onNodeWithText(context.getString(R.string.replay_gain_not_applied)).assertExists()
        assertRow(R.string.detail_cover, context.getString(R.string.cover_front_value, "JPEG"))
        assertRow(R.string.detail_composer, "Bach")
        assertRow(R.string.detail_lyricist, "Picander")
        assertRow(R.string.detail_track_total, "12")
        assertRow(R.string.detail_disc_total, "2")
        assertRow(R.string.detail_isrc, "USRC17607839")
    }

    @Test
    fun rowsWithoutAValueStayHidden() {
        show(TrackFullInfo())
        open()

        listOf(
            R.string.detail_container,
            R.string.detail_compression,
            R.string.detail_channel_layout,
            R.string.detail_encoder,
            R.string.detail_replay_gain_track,
            R.string.replay_gain_not_applied,
            R.string.detail_cover,
            R.string.full_info_tags,
            R.string.detail_composer,
        ).forEach { compose.onAllNodesWithText(context.getString(it)).assertCountEquals(0) }
        // Worked out from the catalog, so this still shows.
        compose.onNode(hasText(context.getString(R.string.detail_overall_bitrate))).assertExists()
    }

    @Test
    fun aPeakAloneStillSaysItIsNotApplied() {
        show(TrackFullInfo(tags = TrackTags(replayGainAlbumPeak = "0.98")))
        open()

        compose.onNodeWithText(context.getString(R.string.replay_gain_not_applied)).assertExists()
    }

    @Test
    fun aLossyCodecSaysSo() {
        show(TrackFullInfo(lossless = false))
        open()

        assertRow(R.string.detail_compression, context.getString(R.string.compression_lossy))
    }

    @Test
    fun compressionComesFromTheCodecInTheFileAndNotItsType() = runBlocking {
        val reader = TrackInfoReader(context)
        fun wav(formatTag: Int, bitsPerSample: Int): TrackFullInfo = runBlocking {
            val file = File(context.cacheDir, "full-info-${System.nanoTime()}.wav")
            try {
                val blockAlign = bitsPerSample / 8
                val data = ByteArray(8_000 * blockAlign)
                val fmt = le16(formatTag) + le16(1) + le(8_000) + le(8_000 * blockAlign) + le16(blockAlign) + le16(bitsPerSample)
                file.writeBytes(
                    "RIFF".toByteArray() + le(4 + 8 + fmt.size + 8 + data.size) + "WAVE".toByteArray() +
                        "fmt ".toByteArray() + le(fmt.size) + fmt + "data".toByteArray() + le(data.size) + data,
                )
                reader.read(
                    track.copy(
                        contentUri = Uri.fromFile(file).toString(),
                        mimeType = "audio/wav",
                        fileName = "fixture.wav",
                        fileSizeBytes = file.length(),
                    ),
                )
            } finally {
                file.delete()
            }
        }

        assertEquals("PCM", true, wav(formatTag = 1, bitsPerSample = 16).lossless)
        assertEquals("A-law, in the same file type", false, wav(formatTag = 6, bitsPerSample = 8).lossless)
    }

    @Test
    fun theReaderReadsARealFileAndSurvivesAMissingOne() = runBlocking {
        val file = File(context.cacheDir, "full-info-${System.nanoTime()}.flac")
        try {
            val comment = "COMPOSER=Bach".toByteArray()
            val vendor = "libFLAC".toByteArray()
            val body = le(vendor.size) + vendor + le(1) + le(comment.size) + comment
            file.writeBytes(
                "fLaC".toByteArray() + byteArrayOf(0x84.toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body,
            )
            val reader = TrackInfoReader(context)
            val tags = reader.read(track.copy(contentUri = Uri.fromFile(file).toString(), fileSizeBytes = file.length())).tags
            assertEquals("FLAC", tags.container)
            assertEquals("Bach", tags.composer)
            assertEquals("libFLAC", tags.encoder)

            val missing = reader.read(track.copy(contentUri = Uri.fromFile(File(file.path + ".gone")).toString()))
            assertEquals(TrackFullInfo(), missing)
        } finally {
            file.delete()
        }
    }

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
