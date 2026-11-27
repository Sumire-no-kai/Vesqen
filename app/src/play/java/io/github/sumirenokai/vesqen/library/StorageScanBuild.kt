package io.github.sumirenokai.vesqen.library

import android.content.Context

/**
 * #249: the Play build has no whole-storage scan; Play's policy does not allow "All files access" for
 * media. Rows an earlier GitHub install found stay hidden, and the music sources sheet says how to
 * bring them back.
 */
@Suppress("UNUSED_PARAMETER")
internal fun createStorageScan(context: Context): StorageScan? = null
