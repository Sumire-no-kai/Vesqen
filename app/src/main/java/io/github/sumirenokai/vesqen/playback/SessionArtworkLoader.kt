package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.AudioTrack
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors

// Session-only presentation metadata. Never included in Audio Proof or diagnostic exports.
internal const val PLAYBACK_ARTWORK_SOURCE_EXTRA = "io.github.sumirenokai.vesqen.playback.artwork_source"

/** The MediaStore album URI is a metadata row, not a directly decodable image stream. */
@UnstableApi
internal class SessionArtworkLoader(context: Context) : BitmapLoader, Closeable {
    private val artwork = AlbumArtworkLoader(context)
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val decoder = DataSourceBitmapLoader.Builder(context)
        .setExecutorService(executor)
        .setMaximumOutputDimension(256)
        .build()

    override fun supportsMimeType(mimeType: String): Boolean = decoder.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        if (data.size <= 2 * 1024 * 1024) decoder.decodeBitmap(data)
        else Futures.immediateFailedFuture(IOException("Session artwork exceeds the encoded size limit"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load(uri.toString(), uri.toString(), 0)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val source = metadata.extras?.getString(PLAYBACK_ARTWORK_SOURCE_EXTRA)
        if (source != null) return load(source, metadata.artworkUri?.toString(),
            metadata.extras?.getLong(PLAYBACK_ARTWORK_REVISION_EXTRA) ?: 0)
        return metadata.artworkData?.let(::decodeBitmap) ?: metadata.artworkUri?.let(::loadBitmap)
    }

    private fun load(source: String, cover: String?, revision: Long): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            artwork.load(AudioTrack(id = 0, contentUri = source, title = "", artist = "", album = "",
                durationMs = 0, albumArtworkUri = cover, artworkRevision = revision), 256)
                ?: throw IOException("Session artwork is unavailable")
        }

    override fun close() { executor.shutdownNow() }
}
