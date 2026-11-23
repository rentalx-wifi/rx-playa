package com.rentalx.playa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconAccentTest {

    @Test
    fun `a colour chosen by hand outranks the icon`() {
        assertEquals(
            0xFFE91E63,
            resolvedAccent(manual = 0xFFE91E63, followsIcon = true, iconAccent = 0xFF22E3C8),
        )
        assertEquals(
            0xFFE91E63,
            resolvedAccent(manual = 0xFFE91E63, followsIcon = false, iconAccent = 0xFF22E3C8),
        )
    }

    @Test
    fun `the icon supplies the accent only while following is on`() {
        assertEquals(
            0xFF22E3C8,
            resolvedAccent(manual = null, followsIcon = true, iconAccent = 0xFF22E3C8),
        )
        assertNull(resolvedAccent(manual = null, followsIcon = false, iconAccent = 0xFF22E3C8))
    }

    @Test
    fun `an icon without an opinion leaves the theme alone`() {
        assertNull(resolvedAccent(manual = null, followsIcon = true, iconAccent = null))
    }

    @Test
    fun `every family holds the icons the screen offers, and each alias is used once`() {
        val aliases = AppIcon.entries.map { it.alias }
        assertEquals("two icons sharing an alias would fight over one launcher entry", aliases.size, aliases.toSet().size)
        IconFamily.entries.forEach { family ->
            assertTrue("${family.name} has no icons", AppIcon.inFamily(family).isNotEmpty())
            AppIcon.inFamily(family).forEach { assertEquals(family, it.family) }
        }
        assertEquals("every icon belongs to exactly one family", AppIcon.entries.size, IconFamily.entries.sumOf { AppIcon.inFamily(it).size })
    }

    @Test
    fun `coloured icons carry a palette, the monochrome marks do not`() {
        assertNotNull(AppIcon.NeonCyan.accent)
        assertNotNull(AppIcon.LegacyAccent.accent)
        assertNull(AppIcon.Default.accent)
        assertNull(AppIcon.Monochrome.accent)
    }
}
