package com.rentalx.playa

import com.rentalx.playa.data.vk.VkPlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class VkPlaylistImportTest {
    @Test
    fun `plain track list accepts Unicode separators and keeps duplicate recordings`() {
        val tracks = VkPlaylistImporter.parse(
            """
            # My VK export
            Artist-one — Title — Live
            Artist two - Other title
            Artist-one — Title — Live
            """.trimIndent(),
        )
        assertEquals(listOf("Artist-one", "Artist two", "Artist-one"), tracks.map { it.artist })
        assertEquals(listOf("Title — Live", "Other title", "Title — Live"), tracks.map { it.title })
    }

    @Test
    fun `CSV supports reversed headers quoted commas and escaped quotes`() {
        val tracks = VkPlaylistImporter.parse(
            "title,artist\n" +
                "\"A title, with \"\"quotes\"\"\",\"Artist, duo\"\n" +
                "\"Another title\",\"Other artist\"",
        )
        assertEquals("A title, with \"quotes\"", tracks.first().title)
        assertEquals("Artist, duo", tracks.first().artist)
        assertEquals("Other artist", tracks.last().artist)
    }

    @Test
    fun `CSV without headers does not mistake a title edition for a plain text separator`() {
        val tracks = VkPlaylistImporter.parse("\"Artist\",\"Title — Live\"")
        assertEquals("Artist", tracks.single().artist)
        assertEquals("Title — Live", tracks.single().title)
    }

    @Test
    fun `Russian CSV columns and semicolon delimiters are accepted`() {
        val tracks = VkPlaylistImporter.parse("Исполнитель;Название" + System.lineSeparator() + "Артист;Песня")
        assertEquals("Артист", tracks.single().artist)
        assertEquals("Песня", tracks.single().title)
    }

    @Test
    fun `VK JSON export keeps duration and album metadata`() {
        val tracks = VkPlaylistImporter.parse(
            """
            {"response":{"items":[{"artist":"Artist","title":"Song","duration":185,"album":{"title":"Album"}}]}}
            """.trimIndent(),
        )
        assertEquals("Artist", tracks.single().artist)
        assertEquals("Song", tracks.single().title)
        assertEquals(185_000L, tracks.single().durationMs)
        assertEquals("Album", tracks.single().album)
    }

    @Test
    fun `more than one hundred rows remain complete and ordered`() {
        val text = (0 until 137).joinToString(System.lineSeparator()) { "Artist — Song $it" }
        val tracks = VkPlaylistImporter.parse(0xFEFF.toChar() + VkPlaylistImporter.readText(StringReader(text)))
        assertEquals(137, tracks.size)
        assertEquals((0 until 137).map { "Song $it" }, tracks.map { it.title })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed rows reject the list instead of silently dropping metadata`() {
        VkPlaylistImporter.parse("Artist — Song" + System.lineSeparator() + "Missing artist/title separator")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `JSON without an artist cannot silently turn into a different recording`() {
        VkPlaylistImporter.parse("""[{"title":"Song"}]""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `file reading is bounded before parsing`() {
        VkPlaylistImporter.readText(StringReader("x".repeat(4 * 1024 * 1024 + 1)))
    }
}
