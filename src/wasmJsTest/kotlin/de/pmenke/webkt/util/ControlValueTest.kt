package de.pmenke.webkt.util

import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlValueTest {
    @Test
    fun synchronizesDomChangesAndCanBeUnbound() {
        val input = document.createElement("input") as HTMLInputElement
        val control = ControlValue("initial")
        control.bind(input, input::value)

        assertEquals("initial", input.value)
        assertEquals(false, control.dirty)

        input.value = "from-dom"
        input.dispatchEvent(Event("input"))
        assertEquals("from-dom", control.value)
        assertEquals(true, control.dirty)

        control.unbind()
        input.value = "after-unbind"
        input.dispatchEvent(Event("input"))
        assertEquals("from-dom", control.value)
    }
}
