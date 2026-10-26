package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tree is emitted as tab-separated lines. A cell that contains a tab would split its own
 * record into two, and a node_id carrying the depth suffix must still parse — both are cheap to
 * get wrong and expensive to debug on a phone.
 */
class NodeLineFormatTest {

    @Test
    fun `short class name drops the package`() {
        assertEquals("Button", shortClassName("android.widget.Button"))
        assertEquals("HeaderView0", shortClassName("com.example.ui.HeaderView0"))
        assertEquals("Button", shortClassName("Button"))
        assertEquals("", shortClassName(null))
        assertEquals("", shortClassName(""))
    }

    @Test
    fun `a cell never contains a tab or a newline`() {
        assertEquals("a b", cell("a\tb"))
        assertEquals("a b", cell("a\nb"))
        assertEquals("a b", cell("a\rb"))
        assertEquals("plain", cell("plain"))
        assertEquals("", cell(null))
        assertEquals("", cell(""))
    }

    @Test
    fun `parseNodeId tolerates the depth suffix`() {
        assertEquals(42 to 7, parseNodeId("42:7@3"))
        assertEquals(42 to 7, parseNodeId("42:7"))
        assertNull(parseNodeId("42@3"))
        assertNull(parseNodeId("42:0@3"))
    }
}
