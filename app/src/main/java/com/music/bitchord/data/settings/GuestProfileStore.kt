package com.music.bitchord.data.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Owns the local avatar file so a temporary photo-picker grant is never persisted as profile state. */
object GuestProfileStore {
    suspend fun importAvatar(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "guest_profile").apply { mkdirs() }
        val temporary = File(directory, "avatar.new")
        context.contentResolver.openInputStream(source)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected image")
        if (temporary.length() == 0L) {
            temporary.delete()
            error("The selected image was empty")
        }
        val avatar = File(directory, "avatar")
        if (avatar.exists()) avatar.delete()
        if (!temporary.renameTo(avatar)) {
            temporary.copyTo(avatar, overwrite = true)
            temporary.delete()
        }
        Uri.fromFile(avatar).toString()
    }

    fun clearAvatar(context: Context) {
        runCatching { File(context.filesDir, "guest_profile/avatar").delete() }
        runCatching { File(context.filesDir, "guest_profile/avatar.new").delete() }
    }
}
