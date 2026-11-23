package com.rentalx.playa.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deleting the file behind a track on this device.
 *
 * Two ways, because Android has two. A plain path in shared storage is a
 * `File.delete` the app may do once it can read the file; anything the media
 * store owns needs the system's own confirmation on Android 11 and later, and
 * no permission the app can hold removes that prompt — so [requestSender] hands
 * the caller the sender to launch, and [delete] handles what needs no prompt.
 *
 * A file that is already gone counts as deleted: it was removed from outside
 * the app, and the answer the caller wants is "there is no file", not an error.
 */
object DeviceFileDelete {

    /** Null when no prompt is needed; a sender the caller launches and confirms otherwise. */
    fun requestSender(context: Context, uri: Uri): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (uri.scheme != "content") return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
        }.getOrNull()
    }

    /** Deletes without a prompt. True when there is no file left afterwards. */
    suspend fun delete(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> {
                val file = uri.path?.let(::File) ?: return@withContext false
                !file.exists() || file.delete()
            }
            else -> runCatching { context.contentResolver.delete(uri, null, null) >= 0 }
                .getOrDefault(false)
        }
    }
}
