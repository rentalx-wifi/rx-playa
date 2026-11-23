package com.rentalx.playa.data.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the picture the Local Music page is drawn with.
 *
 * Copied into the app's own storage rather than kept as the picker's URI: that
 * grant lasts until the process dies, and the section's cover has to survive a
 * restart. The same shape as [GuestProfileStore.importAvatar], for the same
 * reason — one picture, replaced atomically, never a half-written file that
 * Coil then fails to decode.
 *
 * Each import lands in a file of its own name: the URI is what the image loader
 * keys its cache by, and keeping the name constant would hand a replaced cover
 * the old bitmap. The previous file is deleted after the new one is in place.
 */
object LocalMusicCoverStore {

    private const val DIRECTORY = "local_music"
    private const val PREFIX = "cover_"

    suspend fun importCover(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val temporary = File(directory, "$PREFIX.new")
        context.contentResolver.openInputStream(source)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected image")
        if (temporary.length() == 0L) {
            temporary.delete()
            error("The selected image was empty")
        }
        val cover = File(directory, "$PREFIX${System.currentTimeMillis()}")
        if (!temporary.renameTo(cover)) {
            temporary.copyTo(cover, overwrite = true)
            temporary.delete()
        }
        directory.listFiles()
            ?.filter { it.name.startsWith(PREFIX) && it != cover }
            ?.forEach { runCatching { it.delete() } }
        Uri.fromFile(cover).toString()
    }

    fun clearCover(context: Context) {
        val directory = File(context.filesDir, DIRECTORY)
        directory.listFiles()
            ?.filter { it.name.startsWith(PREFIX) }
            ?.forEach { runCatching { it.delete() } }
    }
}
