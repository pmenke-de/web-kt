package de.pmenke.webkt.dom_interop

import de.pmenke.webkt.dom_interop.DomUtil.joinClasses
import kotlin.test.Test
import kotlin.test.assertEquals

class DomUtilTest {
    @Test
    fun joinsOnlyNonBlankClasses() {
        assertEquals("", joinClasses())
        assertEquals("", joinClasses("  ", null))
        assertEquals("one two", joinClasses("one", "", null, "two"))
    }
}
