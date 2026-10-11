package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moxw — the rules behind "every local tool is on, and the page only configures the resident ones".
 *
 * The interesting property is the negative one: a stored `localTools` payload from before the
 * redefinition must not be able to narrow the surface, whatever it names. `effective` is the only
 * place that happens, and `ToolSurfaceResolver` is its only caller, so this file is where that
 * promise is actually kept.
 */
class LocalToolGatesTest {

    @Test
    fun `every gated group is a real group, named once`() {
        assertEquals(LocalToolGates.gated.size, LocalToolGates.gated.distinct().size)
        assertTrue(LocalToolGates.gated.all { it in LocalToolGroups.all })
    }

    @Test
    fun `always-on is exactly the complement of gated`() {
        assertEquals(
            LocalToolGroups.all.filterNot { it in LocalToolGates.gated },
            LocalToolGates.alwaysOn,
        )
        assertTrue(LocalToolGates.alwaysOn.none { LocalToolGates.isGated(it) })
    }

    @Test
    fun `an empty stored list still yields every always-on group`() {
        assertEquals(LocalToolGates.alwaysOn, LocalToolGates.effective(emptyList()))
    }

    @Test
    fun `a stored group that is not gated changes nothing`() {
        // Exactly the shape of a payload written before the redefinition: a handful of ordinary
        // groups "enabled". None of them may narrow the surface, and none may add anything either.
        assertEquals(
            LocalToolGates.alwaysOn,
            LocalToolGates.effective(listOf(LocalToolOption.TimeInfo, LocalToolOption.Battery)),
        )
    }

    @Test
    fun `a stored gated group is honoured`() {
        val effective = LocalToolGates.effective(listOf(LocalToolOption.Termux))
        assertTrue(effective.contains(LocalToolOption.Termux))
        assertEquals(LocalToolGates.alwaysOn.size + 1, effective.size)
    }

    @Test
    fun `duplicates collapse and the order is the catalogue's`() {
        val effective = LocalToolGates.effective(
            listOf(LocalToolOption.Termux, LocalToolOption.Termux, LocalToolOption.Shizuku),
        )
        assertEquals(effective.distinct(), effective)
        assertEquals(LocalToolGroups.order(effective), effective)
    }

    @Test
    fun `gated choices drop everything else`() {
        assertEquals(
            listOf(LocalToolOption.Termux),
            LocalToolGates.gatedChoices(listOf(LocalToolOption.Battery, LocalToolOption.Termux)),
        )
        // A page that reads back the stale payload must show the same surface as an empty one.
        assertEquals(
            LocalToolGates.effective(emptyList()),
            LocalToolGates.effective(
                LocalToolGates.gatedChoices(listOf(LocalToolOption.Battery, LocalToolOption.Sensors)),
            ),
        )
    }

    @Test
    fun `only the prerequisites that can be missing are gated`() {
        assertTrue(LocalToolGates.gated.isNotEmpty())
        assertTrue(LocalToolGates.isGated(LocalToolOption.Termux))
        assertTrue(LocalToolGates.isGated(LocalToolOption.ScreenAutomation))
        // An ordinary runtime permission is not a reason to hide a group: it is one system dialog,
        // and Settings -> Permissions already owns the granting.
        assertFalse(LocalToolGates.isGated(LocalToolOption.Battery))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Files))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Contacts))
        assertFalse(LocalToolGates.isGated(LocalToolOption.Location))
    }
}
