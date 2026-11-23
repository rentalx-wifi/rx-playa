package com.rentalx.playa.data.settings

import android.content.Context
import java.io.File

/**
 * Renames the settings files written before the rebrand.
 *
 * Every store in the app was called `bitchord_*` and is now called `playa_*`,
 * and SharedPreferences finds a store by its file name alone: without this the
 * app would open an empty one next to a full old one and look like it had
 * forgotten every setting, playlist and account.
 *
 * Runs before anything reads a setting, from
 * [com.rentalx.playa.PlayaApplication.onCreate], and moves the files rather
 * than copying their entries: the XML is Android's own format, and renaming it
 * keeps the write-behind state it carries.
 *
 * Nothing here can rescue a build that changed its package id — a different
 * application has a different data directory — so this covers an install over
 * the same id, and the backup export/import covers the rest.
 */
object LegacyPrefs {

    private const val OLD_PREFIX = "bitchord_"
    private const val NEW_PREFIX = "playa_"
    private const val SUFFIX = ".xml"

    fun migrate(context: Context) {
        val directory = File(context.dataDir, "shared_prefs")
        val files = directory.listFiles() ?: return
        for (old in files) {
            val name = old.name
            if (!name.startsWith(OLD_PREFIX) || !name.endsWith(SUFFIX)) continue
            val target = File(directory, NEW_PREFIX + name.removePrefix(OLD_PREFIX))
            // Never overwrite: a file this build has already written is newer
            // than the one it replaced, and lost settings are worse than old ones.
            if (target.exists()) continue
            runCatching { old.renameTo(target) }
        }
    }
}
