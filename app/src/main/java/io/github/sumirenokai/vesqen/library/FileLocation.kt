package io.github.sumirenokai.vesqen.library

import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/**
 * #359: where a file lies on a storage volume, the same whichever source found it, so that one file
 * is one catalog row: "primary:music/album/01.flac", the volume and then the path from its root.
 *
 * Android's shared storage looks names up without regard to case (the emulated storage, and the
 * FAT and exFAT of memory cards), so two spellings of one path name one file and the key is lower
 * case. A source that cannot place its file on a volume, such as a cloud provider's folder, gives
 * no key and is never merged with another.
 */
internal fun fileLocationKey(volume: String, path: String): String? {
    val root = volume.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty) ?: return null
    val relative = path.trim('/').takeIf(String::isNotEmpty) ?: return null
    return "$root:${relative.lowercase(Locale.ROOT)}"
}

/**
 * A MediaStore row from Android 10 on. MediaStore calls the phone's own storage "external_primary"
 * and a memory card by its lower-case file system UUID; "external" is no volume but all of them.
 */
internal fun mediaStoreLocationKey(volumeName: String, relativePath: String, displayName: String): String? {
    val volume = when (volumeName) {
        MEDIA_STORE_PRIMARY_VOLUME -> PRIMARY_VOLUME
        MEDIA_STORE_ALL_VOLUMES -> return null
        else -> volumeName
    }
    if (displayName.isBlank()) return null
    return fileLocationKey(volume, "${relativePath.trim('/')}/$displayName")
}

/** A MediaStore row on Android 9, whose `DATA` column holds the file's path. */
internal fun legacyDataLocationKey(dataPath: String): String? {
    val path = dataPath.replace('\\', '/')
    EMULATED_PATH.matchEntire(path)?.let { return fileLocationKey(PRIMARY_VOLUME, it.groupValues[1]) }
    LEGACY_SDCARD_PATH.matchEntire(path)?.let { return fileLocationKey(PRIMARY_VOLUME, it.groupValues[1]) }
    VOLUME_PATH.matchEntire(path)?.let { match ->
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        return fileLocationKey(volume, match.groupValues[2])
    }
    return null
}

/**
 * A document of Android's own storage provider, whose IDs are the volume and the path: "primary:Music/a.flac",
 * or "1A2B-3C4D:Music/a.flac" on a memory card. Its "home" root is a view of the Documents folder and is
 * left without a key, like every other provider.
 */
internal fun documentLocationKey(authority: String?, documentId: String): String? {
    if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    if (volume.isEmpty() || volume == HOME_ROOT) return null
    return fileLocationKey(volume, documentId.substringAfter(':'))
}

/**
 * The key every file inside an added folder starts with: "primary:music/" for the tree of
 * "primary:Music", "primary:" for a whole volume. Null for a folder of another provider.
 */
internal fun documentTreeLocationPrefix(treeUri: Uri): String? {
    if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val treeId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val volume = treeId.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.ROOT)
    if (volume.isEmpty() || volume == HOME_ROOT) return null
    val path = treeId.substringAfter(':').trim('/').lowercase(Locale.ROOT)
    return if (path.isEmpty()) "$volume:" else "$volume:$path/"
}

internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME = "primary"
private const val HOME_ROOT = "home"
private const val MEDIA_STORE_PRIMARY_VOLUME = "external_primary"
private const val MEDIA_STORE_ALL_VOLUMES = "external"
private val EMULATED_PATH = Regex("""/storage/emulated/\d+/(.+)""")
private val LEGACY_SDCARD_PATH = Regex("""/sdcard/(.+)""")
private val VOLUME_PATH = Regex("""/storage/([^/]+)/(.+)""")
