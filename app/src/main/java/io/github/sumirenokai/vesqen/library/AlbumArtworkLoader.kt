package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.IOException
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads artwork only through a MediaStore provider-owned content URI.
 *
 * The cache is process-local and bounded: user artwork is never copied to disk. Artwork is
 * optional presentation data, so a missing provider row or permission change always falls back
 * to the neutral UI placeholder instead of affecting playback. M1 deliberately does not call
 * MediaMetadataRetriever.embeddedPicture: that API allocates the full unbounded APIC byte array
 * before application code can inspect or cap it.
 */
class AlbumArtworkLoader(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    fun load(track: AudioTrack, targetPx: Int): Bitmap? {
        val requestedSize = targetPx.coerceIn(MIN_ARTWORK_SIZE_PX, MAX_ARTWORK_SIZE_PX)
        track.albumArtworkUri?.takeIf(String::isNotBlank)?.let { artworkUri ->
            val albumKey = AlbumArtworkCacheKey.albumThumbnail(
                artworkUri = artworkUri,
                targetPx = requestedSize,
                revision = track.artworkRevision,
            )
            loadCachedArtwork(albumKey) {
                loadMediaStoreThumbnail(artworkUri, requestedSize)
            }?.let { return it }
        }

        // Some MediaStore implementations can thumbnail the media item even when their album
        // collection does not serve a row thumbnail. Keep this fallback per track rather than
        // sharing it by album: embedded art can legitimately vary between media items.
        return loadCachedArtwork(
            AlbumArtworkCacheKey.mediaThumbnail(track, requestedSize),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                loadMediaStoreThumbnail(track.contentUri, requestedSize)
            } else {
                loadLegacyEmbeddedId3Artwork(Uri.parse(track.contentUri), requestedSize)
            }
        }
    }

    /**
     * Coalesces requests for the same artwork key. A long list should not decode the same album
     * once per visible row, and a missing or rejected source should not be retried for every
     * recomposition during the same MediaStore scan.
     */
    private fun loadCachedArtwork(key: String, loader: () -> Bitmap?): Bitmap? {
        val requestEpoch = cacheEpoch.get()
        artworkCache.get(key)?.let { return it }
        if (unavailableArtworkKeys.get(key) != null) return null

        val task = FutureTask {
            loader().also { artwork ->
                // A rescan or permission revoke can happen while the provider/decode request is
                // running. That older result may still serve its original caller, but it must not
                // repopulate the new cache generation after the UI has discarded its source.
                if (cacheEpoch.get() == requestEpoch) {
                    if (artwork == null) {
                        unavailableArtworkKeys.put(key, true)
                    } else {
                        artworkCache.put(key, artwork)
                    }
                }
            }
        }
        val activeTask = inFlightLoads.putIfAbsent(key, task) ?: task
        val ownsTask = activeTask === task
        if (ownsTask) task.run()

        return try {
            activeTask.get()
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            if (ownsTask) inFlightLoads.remove(key, task)
        }
    }

    private fun loadMediaStoreThumbnail(contentUri: String, targetPx: Int): Bitmap? {
        if (contentUri.isBlank()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return loadLegacyProviderArtwork(Uri.parse(contentUri), targetPx)
        }
        return try {
            contentResolver.loadThumbnail(Uri.parse(contentUri), Size(targetPx, targetPx), null)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Android 8/9 has no ContentResolver.loadThumbnail. Decode only the provider-owned album-art
     * stream, first reading its bounds and then choosing a power-of-two sample that caps the
     * decoded longest edge. This preserves the M1 memory boundary without allocating an
     * unbounded embedded APIC byte array.
     */
    private fun loadLegacyProviderArtwork(uri: Uri, targetPx: Int): Bitmap? {
        decodeLegacyArtwork(targetPx) { contentResolver.openInputStream(uri) }?.let { return it }
        val providerArtworkPath = resolveLegacyAlbumArtworkPath(uri) ?: return null
        return decodeLegacyArtwork(targetPx) { FileInputStream(providerArtworkPath) }
    }

    private fun decodeLegacyArtwork(
        targetPx: Int,
        openStream: () -> InputStream?,
    ): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateLegacyArtworkSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetPx = targetPx,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        openStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }?.let { decoded ->
            val longestEdge = maxOf(decoded.width, decoded.height)
            if (longestEdge <= targetPx) {
                decoded
            } else {
                val scale = targetPx.toFloat() / longestEdge
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { scaled ->
                    if (scaled !== decoded) decoded.recycle()
                }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacyAlbumArtworkPath(albumUri: Uri): String? = try {
        contentResolver.query(
            albumUri,
            arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val pathIndex = cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART)
            if (pathIndex < 0) null else cursor.getString(pathIndex)?.takeIf(String::isNotBlank)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Final Android 8/9 fallback for MP3 files whose ROM keeps artwork only inside ID3 metadata.
     * The parser reads frame headers as a stream and refuses oversized tags or APIC frames before
     * allocating their payload, unlike MediaMetadataRetriever.embeddedPicture.
     */
    private fun loadLegacyEmbeddedId3Artwork(mediaUri: Uri, targetPx: Int): Bitmap? = try {
        contentResolver.openAssetFileDescriptor(mediaUri, "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                val header = input.readExactly(ID3_HEADER_BYTES) ?: return null
                if (!header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))) {
                    return null
                }
                val majorVersion = header[3].toInt() and 0xff
                if (majorVersion !in 3..4 || (header[5].toInt() and ID3_UNSYNCHRONISATION_FLAG) != 0) {
                    return null
                }
                val tagSize = decodeSynchsafeInt(header, 6) ?: return null
                if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null

                var remaining = tagSize
                while (remaining >= ID3_FRAME_HEADER_BYTES) {
                    val frameHeader = input.readExactly(ID3_FRAME_HEADER_BYTES) ?: return null
                    remaining -= ID3_FRAME_HEADER_BYTES
                    if (frameHeader.take(4).all { it == 0.toByte() }) return null

                    val frameId = String(frameHeader, 0, 4, Charsets.US_ASCII)
                    val frameSize = when (majorVersion) {
                        4 -> decodeSynchsafeInt(frameHeader, 4)
                        else -> decodeBigEndianInt(frameHeader, 4)
                    } ?: return null
                    if (frameSize <= 0 || frameSize > remaining) return null

                    val hasUnsupportedFlags = frameHeader[8] != 0.toByte() || frameHeader[9] != 0.toByte()
                    if (frameId == "APIC" && !hasUnsupportedFlags) {
                        if (frameSize > MAX_EMBEDDED_ART_FRAME_BYTES) return null
                        val frame = input.readExactly(frameSize) ?: return null
                        val imageOffset = findId3ApicImageOffset(frame) ?: return null
                        val imageSize = frame.size - imageOffset
                        if (imageSize <= 0 || imageSize > MAX_EMBEDDED_ART_BYTES) return null
                        return decodeArtworkBytes(frame, imageOffset, imageSize, targetPx)
                    }
                    if (!input.skipExactly(frameSize)) return null
                    remaining -= frameSize
                }
                null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodeArtworkBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        targetPx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateLegacyArtworkSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, offset, length, options) ?: return null
        val longestEdge = maxOf(decoded.width, decoded.height)
        if (longestEdge <= targetPx) return decoded
        val scale = targetPx.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled ->
            if (scaled !== decoded) decoded.recycle()
        }
    }

    companion object {
        private const val MIN_ARTWORK_SIZE_PX = 32
        private const val MAX_ARTWORK_SIZE_PX = 512
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        private const val ID3_HEADER_BYTES = 10
        private const val ID3_FRAME_HEADER_BYTES = 10
        private const val ID3_UNSYNCHRONISATION_FLAG = 0x80
        private const val MAX_ID3_TAG_BYTES = 16 * 1024 * 1024
        private const val MAX_EMBEDDED_ART_FRAME_BYTES = 8 * 1024 * 1024 + 4096
        private const val MAX_EMBEDDED_ART_BYTES = 8 * 1024 * 1024

        private val artworkCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        private val unavailableArtworkKeys = LruCache<String, Boolean>(256)
        private val inFlightLoads = ConcurrentHashMap<String, FutureTask<Bitmap?>>()
        private val cacheEpoch = AtomicLong()

        /** Clears only process memory; original artwork remains owned by MediaStore or the file. */
        fun clearMemoryCache() {
            cacheEpoch.incrementAndGet()
            artworkCache.evictAll()
            unavailableArtworkKeys.evictAll()
            // Do not try to cancel platform MediaStore/metadata reads. Clearing the map lets a
            // subsequent generation start an independent request; the epoch prevents the old one
            // from storing an obsolete positive or negative result when it eventually completes.
            inFlightLoads.clear()
        }
    }
}

private fun InputStream.readExactly(size: Int): ByteArray? {
    if (size < 0) return null
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) return null
        if (read == 0) continue
        offset += read
    }
    return bytes
}

private fun InputStream.skipExactly(size: Int): Boolean {
    var remaining = size.toLong()
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() >= 0) {
            remaining--
        } else {
            return false
        }
    }
    return true
}

internal fun decodeSynchsafeInt(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    var result = 0
    repeat(4) { index ->
        val value = bytes[offset + index].toInt() and 0xff
        if ((value and 0x80) != 0) return null
        result = (result shl 7) or value
    }
    return result
}

internal fun decodeBigEndianInt(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    var result = 0L
    repeat(4) { index ->
        result = (result shl 8) or (bytes[offset + index].toLong() and 0xff)
    }
    return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

internal fun findId3ApicImageOffset(frame: ByteArray): Int? {
    if (frame.size < 4) return null
    val textEncoding = frame[0].toInt() and 0xff
    var cursor = 1
    while (cursor < frame.size && frame[cursor] != 0.toByte()) cursor++
    if (cursor >= frame.size) return null
    cursor++ // MIME terminator
    if (cursor >= frame.size) return null
    cursor++ // picture type

    if (textEncoding == 1 || textEncoding == 2) {
        while (cursor + 1 < frame.size && !(frame[cursor] == 0.toByte() && frame[cursor + 1] == 0.toByte())) {
            cursor++
        }
        if (cursor + 1 >= frame.size) return null
        cursor += 2
    } else {
        while (cursor < frame.size && frame[cursor] != 0.toByte()) cursor++
        if (cursor >= frame.size) return null
        cursor++
    }
    return cursor.takeIf { it < frame.size }
}

internal fun calculateLegacyArtworkSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val decodedEdgeLimit = targetPx.toLong() * 2L
    val longestEdge = maxOf(width, height).toLong()
    var sampleSize = 1
    while (longestEdge / sampleSize > decodedEdgeLimit && sampleSize <= Int.MAX_VALUE / 2) {
        sampleSize *= 2
    }
    return sampleSize
}

internal object AlbumArtworkCacheKey {
    fun albumThumbnail(artworkUri: String, targetPx: Int, revision: Long): String = listOf(
        "album",
        artworkUri,
        targetPx,
        revision,
    ).joinToString(separator = "|")

    fun mediaThumbnail(track: AudioTrack, targetPx: Int): String = listOf(
        "media",
        track.contentUri,
        track.dateModifiedSeconds,
        targetPx,
        track.artworkRevision,
    ).joinToString(separator = "|")
}
