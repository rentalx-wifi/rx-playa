package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.InputStream

/**
 * The folder the listener picked for downloads, and everything that has to be
 * true before a file may be put in it.
 *
 * [DownloadStore] answers "where do downloads go" for a destination this app
 * can name: Music, or its own storage. This is the other half — a folder chosen
 * through the system picker, which the app reaches only through the tree uri
 * the grant came with. Both exist because they answer different wishes: the
 * built-in destination is the one that works with no decision made, and the
 * picked one is for a card, a USB stick or a synced folder that no path of
 * ours reaches.
 *
 * Everything here assumes the folder is not ours and can stop being ours at any
 * moment — the card is pulled, the folder is deleted in a file manager, the
 * grant is revoked with the app's storage. None of those may fail a download or
 * crash a screen: [current] answers with the built-in destination instead and
 * records that it did, so the settings row can say so. What is already in the
 * folder stays there; nothing here moves or deletes a saved track.
 */
object DownloadFolder {

    private const val TAG = "BitChord"

    /**
     * The file the write test creates and deletes. A dot-name so a device that
     * kills the app between the two leaves something recognisably internal
     * rather than a stray `test.txt` in somebody's music folder.
     */
    private const val PROBE_NAME = ".bitchord-write-test"

    /** `text/plain` because every provider takes it; the probe is never read. */
    private const val PROBE_MIME = "text/plain"

    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** Provider calls one measurement may make — see [tally]. */
    private const val TALLY_LIMIT = 2000

    /**
     * Set when a folder that was chosen turned out to be gone and downloads
     * quietly went back to the built-in destination.
     *
     * Kept here rather than derived from the setting because clearing the
     * setting is exactly what happens: without this, "the folder disappeared"
     * and "no folder was ever chosen" would look identical to the settings row,
     * and the one thing the listener needs to be told is which of the two it is.
     * In memory only — it is a note about what just happened, not a fact about
     * the device worth carrying across launches.
     */
    private val _fellBack = MutableStateFlow(false)
    val fellBack: StateFlow<Boolean> = _fellBack.asStateFlow()

    /**
     * The tree uri already verified writable in this process.
     *
     * The check costs the provider a document creation and a deletion, and
     * [current] is asked three times per download — so a three-hundred-track
     * album would otherwise litter the listener's folder with nine hundred
     * probe files. Verification is therefore per process and dropped the moment
     * a copy fails, which is the only evidence that the earlier answer has gone
     * stale.
     */
    @Volatile
    private var verified: String? = null

    /** Where downloads land when no folder was chosen: `Music/RX Playa`, as a path reads. */
    val defaultLabel: String get() = "${Environment.DIRECTORY_MUSIC}/${DownloadStore.FOLDER}"

    /**
     * The folder new downloads go into, or null for the built-in destination.
     *
     * Also the place a vanished folder is noticed: a stored uri that no longer
     * resolves, or that the provider will not take a file into, is cleared
     * here, so every later download uses the built-in destination instead of
     * failing at the write.
     */
    fun current(context: Context): Uri? {
        val stored = AppSettings.downloadTreeUri.value
        if (stored.isBlank()) return null
        val tree = Uri.parse(stored)
        if (stored == verified) return tree
        if (writable(context, tree)) {
            verified = stored
            return tree
        }
        forget(context, tree)
        return null
    }

    /**
     * Make [tree] the destination, if it can be written to.
     *
     * The grant is taken before the test, since the test runs through it: a
     * provider that hands back a uri but no write permission fails at the first
     * write either way, and doing it in this order means the answer covers the
     * permission as well as the folder.
     *
     * @return false when the folder can't be written to. The previous
     *   destination is then untouched — a folder that turns out to be read-only
     *   must not leave the app with nowhere to put a download.
     */
    fun select(context: Context, tree: Uri): Boolean {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "no lasting grant for $tree: ${it.message}") }
        if (!writable(context, tree)) {
            // The grant was taken before the probe, and a folder that refuses
            // writes must not leave the app holding permission to it.
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            return false
        }
        verified = tree.toString()
        AppSettings.setDownloadTreeUri(tree.toString())
        _fellBack.value = false
        return true
    }

    /**
     * Go back to the built-in destination.
     *
     * The grant is given up rather than kept, so Android stops counting this app
     * among those holding access to that folder. Files already in it are not
     * touched — see [adopt] for why nothing here moves them.
     */
    fun reset(context: Context) {
        val stored = AppSettings.downloadTreeUri.value
        AppSettings.setDownloadTreeUri("")
        verified = null
        _fellBack.value = false
        if (stored.isBlank()) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(stored),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "could not drop the grant for $stored: ${it.message}") }
    }

    /**
     * Whether a file can actually be written into [tree].
     *
     * Asked by writing, because every cheaper question lies somewhere: a grant
     * can be held over a folder that was deleted, a volume can be full, a card
     * can be out while its tree uri still parses, and a cloud folder can be
     * offline. The probe is created and removed at once; on a folder this app
     * has not written to in this process it is the only cost.
     */
    fun writable(context: Context, tree: Uri): Boolean = runCatching {
        val root = DocumentFile.fromTreeUri(context, tree)
        if (root == null || !root.isDirectory || !root.canWrite()) return false
        val probe = root.createFile(PROBE_MIME, PROBE_NAME) ?: return false
        probe.delete()
        true
    }.getOrElse {
        Log.w(TAG, "cannot write to $tree: ${it.message}")
        false
    }

    /**
     * A file already in the chosen folder under [name], or null.
     *
     * [Downloads] normally answers "do I have this track?" from its own record,
     * but that record lives in this app's data and the folder does not: cleared
     * app data leaves the files and forgets them. Asking the folder itself is
     * what stops the next download writing a second copy beside the first.
     */
    fun existing(context: Context, name: String): Uri? {
        val tree = current(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, tree)?.findFile(name)?.uri }
            .onFailure { Log.w(TAG, "could not look for $name in $tree: ${it.message}") }
            .getOrNull()
    }

    /**
     * Copy a finished download into the chosen folder.
     *
     * Called before the built-in destination is published, so the track ends up
     * in exactly one place: here, or where it was written. That ordering is why
     * a failure needs no cleanup and no retry — the caller still holds the
     * whole file and commits it where it is.
     *
     * @return the content uri of the copy, or null to keep the original. Null
     *   also means the folder may have gone away, which is checked and acted on
     *   before returning.
     */
    fun adopt(context: Context, source: Uri, name: String, mimeType: String): Uri? {
        val tree = current(context) ?: return null
        val document = runCatching {
            DocumentFile.fromTreeUri(context, tree)?.createFile(mimeType, name)
        }.onFailure { Log.w(TAG, "could not create $name in $tree: ${it.message}") }.getOrNull()
        if (document != null && copy(context, document, source)) return document.uri

        // A name already in the folder needs no handling: the provider renames
        // the new file rather than replacing the one that is there, and a
        // duplicate is better than deleting a file this app did not create.
        if (document != null) runCatching { document.delete() }
        verified = null
        if (!writable(context, tree)) forget(context, tree)
        return null
    }

    /**
     * How many bytes the destination holds right now.
     *
     * Measured rather than tallied from [Downloads], because the folder belongs
     * to the listener and they may add to it or clear it out with a file
     * manager; a number counting only this app's own downloads would be wrong
     * the moment they did.
     */
    fun usageBytes(context: Context, tree: Uri?): Long =
        if (tree != null) treeBytes(context, tree) else DownloadStore.usedBytes(context)

    private fun copy(context: Context, target: DocumentFile, source: Uri): Boolean = runCatching {
        val input = open(context, source)
        if (input == null) {
            Log.w(TAG, "no bytes to copy from $source")
            return false
        }
        val output = context.contentResolver.openOutputStream(target.uri, "w")
        if (output == null) {
            input.close()
            return false
        }
        input.use { from -> output.use { to -> from.copyTo(to, COPY_BUFFER_BYTES) } }
        true
    }.getOrElse {
        Log.w(TAG, "copy into ${target.uri} failed: ${it.message}")
        false
    }

    /** [source] is a content uri or a plain file, depending on where the download was written. */
    private fun open(context: Context, source: Uri): InputStream? = runCatching {
        if (source.scheme == "file") {
            source.path?.let { FileInputStream(it) }
        } else {
            context.contentResolver.openInputStream(source)
        }
    }.getOrNull()

    private fun treeBytes(context: Context, tree: Uri): Long = runCatching {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return 0L
        tally(root, Budget(TALLY_LIMIT))
    }.getOrDefault(0L)

    private class Budget(var left: Int)

    /**
     * Sum of the documents under [document], stopped by [budget].
     *
     * Every entry is a query to the provider, so a folder holding a library of
     * thousands of files could otherwise keep the settings row computing for
     * seconds. Stopped, the number reads low — which is the right way to be
     * wrong for a figure that only says "this much is in there".
     */
    private fun tally(document: DocumentFile, budget: Budget): Long {
        if (budget.left <= 0) return 0L
        val children = runCatching { document.listFiles() }.getOrNull() ?: return 0L
        var total = 0L
        for (child in children) {
            if (budget.left-- <= 0) break
            total += if (child.isDirectory) tally(child, budget) else child.length()
        }
        return total
    }

    /**
     * Give the folder up, saying why.
     *
     * Reached only from evidence that it can no longer be written to, so the
     * grant is dropped too — an unusable folder holding a permission this app
     * asked for is worse than no folder at all.
     */
    private fun forget(context: Context, tree: Uri) {
        Log.w(TAG, "$tree is no longer usable; new downloads go to $defaultLabel")
        AppSettings.setDownloadTreeUri("")
        verified = null
        _fellBack.value = true
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
