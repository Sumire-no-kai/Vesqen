package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.IOException
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
            loadMediaStoreThumbnail(track.contentUri, requestedSize)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (contentUri.isBlank()) return null
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

    companion object {
        private const val MIN_ARTWORK_SIZE_PX = 32
        private const val MAX_ARTWORK_SIZE_PX = 512
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

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
