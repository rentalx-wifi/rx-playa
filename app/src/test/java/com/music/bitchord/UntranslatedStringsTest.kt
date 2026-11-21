package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strings that are still copied English text in a non-English locale.
 *
 * The two translation issues (#16, #17) are a standing debt: eighteen locales
 * carry 30 to 80 English strings each, and translating them all was explicitly
 * out of scope. What this test holds is the *edge*: the set of English strings
 * per locale cannot grow quietly. A new key that arrives untranslated — or an
 * English text copied into a locale that had translated it — fails here, and the
 * fix is either to translate it or to record it in the snapshot on purpose.
 *
 * Snapshots live in `app/src/test/resources/untranslated/<locale>.txt`, one key
 * per line. Regenerating one is a deliberate act: it says "this key stays
 * English for now", which is exactly the decision a silent fallback never made.
 *
 * The rule matches the one the issues measured with: a string at least 20
 * characters long, containing a space, and without a format placeholder — short
 * labels and words legitimately coincide across languages, and a placeholder
 * makes two strings differ for reasons that have nothing to do with translation.
 */
class UntranslatedStringsTest {

    companion object {
        private const val MIN_LENGTH = 20

        private val projectRoot: File by lazy { File(".").canonicalFile }

        private val resDir: File by lazy {
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }

        private val snapshotDir: File by lazy {
            if (File(projectRoot, "app/src/test/resources").exists()) {
                File(projectRoot, "app/src/test/resources/untranslated")
            } else {
                File(projectRoot, "src/test/resources/untranslated")
            }
        }

        private fun parse(file: File): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        /** Every translatable `<string>` of a catalogue, by name. */
        private fun strings(file: File): Map<String, String> {
            val doc = parse(file)
            val nodes = doc.getElementsByTagName("string")
            val values = mutableMapOf<String, String>()
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                if (name.isNotEmpty()) values[name] = element.textContent.orEmpty()
            }
            return values
        }

        private fun looksEnglish(value: String): Boolean =
            value.length >= MIN_LENGTH && value.contains(' ') && !value.contains('%')
    }

    private val english: Map<String, String> by lazy {
        strings(File(resDir, "values/strings.xml"))
    }

    /** The English strings this test tracks: long enough and worded to be text, not a label. */
    private val tracked: Map<String, String> by lazy {
        english.filterValues(::looksEnglish)
    }

    private fun qualifier(tag: String): String = when (tag) {
        "zh-CN" -> "zh"
        "zh-TW" -> "zh-rTW"
        else -> tag
    }

    private fun copiedEnglish(qualifier: String): Set<String> {
        val file = File(resDir, "values-$qualifier/strings.xml")
        assertTrue("values-$qualifier/strings.xml must exist", file.exists())
        val locale = strings(file)
        return tracked.keys.filterTo(sortedSetOf()) { name -> locale[name] == tracked[name] }
    }

    @Test
    fun `no locale gained an English string since the snapshot`() {
        val failures = mutableListOf<String>()
        for (language in SUPPORTED_LANGUAGES) {
            if (language.tag == "en") continue
            val locale = qualifier(language.tag)
            val snapshotFile = File(snapshotDir, "$locale.txt")
            assertTrue(
                "Snapshot $snapshotFile is missing: record the locale's English strings on purpose",
                snapshotFile.exists(),
            )
            val recorded = snapshotFile.readLines().filter(String::isNotBlank).toSet()
            val current = copiedEnglish(locale)
            val added = current - recorded
            val removed = recorded - current
            if (added.isNotEmpty()) {
                failures += "values-$locale: ${added.size} newly English string(s): ${added.sorted()}"
            }
            if (removed.isNotEmpty()) {
                failures += "values-$locale: ${removed.size} translated since the snapshot, " +
                    "remove them from $locale.txt: ${removed.sorted()}"
            }
        }
        assertEquals(
            "Untranslated strings moved. Translate them, or record them in " +
                "app/src/test/resources/untranslated/ deliberately:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }
}
