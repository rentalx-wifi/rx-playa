package com.rentalx.playa.playback

import java.text.Normalizer
import java.util.Locale

/**
 * One text key for "the same song", independent of the script a provider chose.
 *
 * The same recording arrives from YouTube Music in Latin and from Yandex or
 * Spotify in Cyrillic often enough that a literal title comparison treated them
 * as different tracks: the queue then played one song twice, once per platform,
 * and a station could repeat its seed. Folding the Cyrillic alphabet to a Latin
 * skeleton makes "Группа крови" and "Gruppa Krovi" meet in the middle, and
 * dropping the featuring clause makes "Song (feat. X)" equal to "Song".
 *
 * Only a lossy skeleton is produced - it is a matching key, never display text.
 */
object TrackText {

    /** Featured-artist clauses that do not change which recording this is. */
    private val FEATURING = Regex(
        """\s*[(\[]\s*(?:feat|ft|featuring|prod|with)\b[^)\]]*[)\]]""" +
            """|\s+(?:feat|ft|featuring)\.?\s+[^,(\[]+$""",
        RegexOption.IGNORE_CASE,
    )

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Punctuation, bracketed decoration and casing removed; scripts still distinct. */
    fun titleKey(raw: String): String = foldScript(stripFeaturing(raw))
        .lowercase(Locale.ROOT)
        .replace(SEPARATORS, " ")
        .trim()

    fun stripFeaturing(raw: String): String = FEATURING.replace(raw, " ")

    /**
     * A Latin skeleton of the text.
     *
     * Accents are stripped with NFD and the Cyrillic letters are mapped to the
     * transliteration both Yandex and YouTube users usually type. Anything else
     * is passed through untouched, so an unmapped alphabet simply behaves as it
     * did before.
     */
    fun foldScript(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
        val out = StringBuilder(decomposed.length + 8)
        decomposed.forEach { ch ->
            val mapped = CYRILLIC[ch]
            if (mapped != null) out.append(mapped) else out.append(ch)
        }
        return out.toString()
    }

    private val COMBINING_MARKS = Regex("\\p{M}+")

    private val CYRILLIC: Map<Char, String> = buildMap {
        put('а', "a"); put('б', "b"); put('в', "v"); put('г', "g")
        put('д', "d"); put('е', "e"); put('ё', "e"); put('ж', "zh")
        put('з', "z"); put('и', "i"); put('й', "i"); put('к', "k")
        put('л', "l"); put('м', "m"); put('н', "n"); put('о', "o")
        put('п', "p"); put('р', "r"); put('с', "s"); put('т', "t")
        put('у', "u"); put('ф', "f"); put('х', "kh"); put('ц', "ts")
        put('ч', "ch"); put('ш', "sh"); put('щ', "shch"); put('ъ', "")
        put('ы', "y"); put('ь', ""); put('э', "e"); put('ю', "iu")
        put('я', "ia"); put('і', "i"); put('ї', "i"); put('є', "e")
        put('ґ', "g"); put('ў', "u")
    }
}
