package me.rerere.rikkahub.ui.pages.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionDescriptionCollapseTest {

    @Test
    fun `short description stays three lines with no toggle`() {
        val layout = MissionDescriptionCollapse.layout(expanded = false, overflows = false)
        assertEquals(MissionDescriptionCollapse.COLLAPSED_LINES, layout.maxLines)
        assertFalse(layout.showToggle)
    }

    @Test
    fun `overflowing description offers the toggle`() {
        val layout = MissionDescriptionCollapse.layout(expanded = false, overflows = true)
        assertEquals(MissionDescriptionCollapse.COLLAPSED_LINES, layout.maxLines)
        assertTrue(layout.showToggle)
    }

    @Test
    fun `expanded description is unbounded and can be collapsed again`() {
        val layout = MissionDescriptionCollapse.layout(expanded = true, overflows = false)
        assertEquals(Int.MAX_VALUE, layout.maxLines)
        assertTrue(layout.showToggle)
    }
}
