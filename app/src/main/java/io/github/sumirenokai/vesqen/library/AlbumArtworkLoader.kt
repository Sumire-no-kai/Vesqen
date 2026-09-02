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
 * Reads artwork only through a provider-owned content URI.
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
                    ?: loadEmbeddedArtwork(track, requestedSize)
            } else {
                loadEmbeddedArtwork(track, requestedSize)
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
        val albumArtStreamUri = legacyAlbumArtStreamUri(uri.toString())
        // A MediaStore `albums/{id}` URI is a metadata row, not an image stream. Opening it as a
        // stream crashes inside some old OEM providers before returning to the app. Directly
        // decode non-album URIs (including SAF cover images), but use the standard singular
        // `albumart/{id}` stream for MediaStore album rows.
        if (albumArtStreamUri == null) {
            decodeLegacyArtwork(targetPx) { contentResolver.openInputStream(uri) }?.let { return it }
        }
        val mediaVolume = albumArtStreamUri?.let(::legacyMediaVolume)
        val shouldAttemptAlbumProvider = mediaVolume == null || mediaVolume !in blockedLegacyAlbumVolumes
        if (shouldAttemptAlbumProvider) albumArtStreamUri?.let { streamUri ->
            decodeLegacyArtwork(targetPx) {
                try {
                    contentResolver.openInputStream(Uri.parse(streamUri))
                } catch (denied: SecurityException) {
                    mediaVolume?.let(blockedLegacyAlbumVolumes::add)
                    throw denied
                }
            }?.let { return it }
        }
        // Once a volume has explicitly denied its canonical album-art stream, avoid repeating
        // the same provider query and filesystem failure for every row. The first denied request
        // still gets one ALBUM_ART path attempt for ROMs that expose only that legacy column.
        if (!shouldAttemptAlbumProvider) return null
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
     * Final fallback on every Android version when MediaStore or an OEM provider withholds its
     * thumbnail. The container reader is stream-based and enforces a shared image-size boundary.
     */
    private fun loadEmbeddedArtwork(track: AudioTrack, targetPx: Int): Bitmap? = try {
        contentResolver.openAssetFileDescriptor(Uri.parse(track.contentUri), "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                extractBoundedEmbeddedArtwork(
                    input = input,
                    declaredLength = descriptor.length.takeIf { it > 0 } ?: track.fileSizeBytes.takeIf { it > 0 },
                )?.let { picture ->
                    decodeArtworkBytes(picture, 0, picture.size, targetPx)
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
        private val artworkCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        private val unavailableArtworkKeys = LruCache<String, Boolean>(256)
        private val inFlightLoads = ConcurrentHashMap<String, FutureTask<Bitmap?>>()
        private val blockedLegacyAlbumVolumes = ConcurrentHashMap.newKeySet<String>()
        private val cacheEpoch = AtomicLong()

        /** Clears only process memory; original artwork remains owned by MediaStore or the file. */
        fun clearMemoryCache() {
            cacheEpoch.incrementAndGet()
            artworkCache.evictAll()
            unavailableArtworkKeys.evictAll()
            blockedLegacyAlbumVolumes.clear()
            // Do not try to cancel platform MediaStore/metadata reads. Clearing the map lets a
            // subsequent generation start an independent request; the epoch prevents the old one
            // from storing an obsolete positive or negative result when it eventually completes.
            inFlightLoads.clear()
        }
    }
}

internal const val MAX_EMBEDDED_ART_BYTES = 8 * 1024 * 1024
private const val MAX_FLAC_METADATA_BYTES = 32 * 1024 * 1024
private const val MAX_FLAC_PICTURE_OVERHEAD_BYTES = 128 * 1024
private const val MAX_FLAC_MIME_BYTES = 1_024
private const val MAX_FLAC_DESCRIPTION_BYTES = 64 * 1024

/**
 * Extracts one bounded FLAC PICTURE block. The caller owns the returned byte array; malformed or
 * adversarial lengths fail closed without reading audio frames or allocating their declared size.
 */
internal fun extractBoundedFlacPicture(
    input: InputStream,
    maxPictureBytes: Int = MAX_EMBEDDED_ART_BYTES,
    maxMetadataBytes: Int = MAX_FLAC_METADATA_BYTES,
): ByteArray? {
    if (maxPictureBytes <= 0 || maxMetadataBytes <= 0) return null
    val magic = input.readExactly(4) ?: return null
    if (!magic.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))) {
        return null
    }

    var scannedBytes = 4L
    while (scannedBytes <= maxMetadataBytes.toLong()) {
        val header = input.readExactly(4) ?: return null
        scannedBytes += header.size
        val isLast = (header[0].toInt() and 0x80) != 0
        val blockType = header[0].toInt() and 0x7f
        val blockLength = ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        scannedBytes += blockLength.toLong()
        if (scannedBytes > maxMetadataBytes.toLong()) return null

        if (blockType == 6) {
            if (blockLength.toLong() > maxPictureBytes.toLong() + MAX_FLAC_PICTURE_OVERHEAD_BYTES) return null
            val block = input.readExactly(blockLength) ?: return null
            extractFlacPictureBlock(block, maxPictureBytes)?.let { return it }
        } else if (!input.skipExactly(blockLength)) {
            return null
        }
        if (isLast) return null
    }
    return null
}

internal fun extractFlacPictureBlock(block: ByteArray, maxPictureBytes: Int): ByteArray? {
    var cursor = 0
    fun readInt(): Int? {
        val value = decodeBigEndianInt(block, cursor) ?: return null
        cursor += 4
        return value
    }
    fun skipBounded(length: Int, maximum: Int): Boolean {
        if (length < 0 || length > maximum) return false
        val next = cursor.toLong() + length.toLong()
        if (next > block.size.toLong()) return false
        cursor = next.toInt()
        return true
    }

    readInt() ?: return null // picture type
    val mimeLength = readInt() ?: return null
    if (!skipBounded(mimeLength, MAX_FLAC_MIME_BYTES)) return null
    val descriptionLength = readInt() ?: return null
    if (!skipBounded(descriptionLength, MAX_FLAC_DESCRIPTION_BYTES)) return null
    repeat(4) { readInt() ?: return null } // width, height, depth, indexed colours
    val pictureLength = readInt() ?: return null
    if (pictureLength <= 0 || pictureLength > maxPictureBytes) return null
    val pictureEnd = cursor.toLong() + pictureLength.toLong()
    if (pictureEnd > block.size.toLong()) return null
    return block.copyOfRange(cursor, pictureEnd.toInt())
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

/**
 * Android 8/9 MediaProvider exposes album artwork streams from the singular `albumart`
 * collection, while album metadata is queried from the plural `albums` collection. Several OEM
 * providers, including older Huawei builds, reject `openInputStream()` on the metadata URI even
 * though the matching artwork stream exists. Keep the volume segment so adopted storage volumes
 * are not silently redirected to the primary external volume.
 */
internal fun legacyAlbumArtStreamUri(albumMetadataUri: String): String? {
    val prefix = "content://media/"
    if (!albumMetadataUri.startsWith(prefix)) return null
    val segments = albumMetadataUri.removePrefix(prefix).trim('/').split('/')
    if (segments.size != 4 || segments[1] != "audio" || segments[2] != "albums") return null
    val albumId = segments[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
    return "$prefix${segments[0]}/audio/albumart/$albumId"
}

private fun legacyMediaVolume(mediaUri: String): String? {
    val prefix = "content://media/"
    if (!mediaUri.startsWith(prefix)) return null
    return mediaUri.removePrefix(prefix).substringBefore('/').takeIf(String::isNotBlank)
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
