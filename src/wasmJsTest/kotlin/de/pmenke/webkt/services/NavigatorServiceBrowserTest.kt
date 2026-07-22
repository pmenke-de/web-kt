package de.pmenke.webkt.services

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.url.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NavigatorServiceBrowserTest {
    private val originalHref = window.location.href

    @AfterTest
    fun restoreLocation() {
        window.history.replaceState(null, "", originalHref)
    }

    @Test
    fun eachBrowserInstanceRemovesItsOwnPopStateListenerExactlyOnce() {
        val first = NavigatorService()
        val second = NavigatorService()
        try {
            val firstPathBeforeClose = first.path.value
            val firstHashBeforeClose = first.hash.value
            val policy = NavigationPolicy(URL(document.baseURI).pathname)

            first.close()
            first.close()
            assertEquals(firstPathBeforeClose, first.path.value, "close changed path state")
            window.history.pushState(null, "", "/navigator-listener-test?step=one#first")
            assertEquals(firstPathBeforeClose, first.path.value, "pushState changed closed path state")
            window.dispatchEvent(PopStateEvent("popstate"))

            assertEquals(firstPathBeforeClose, first.path.value, "popstate reached closed navigator")
            assertEquals(firstHashBeforeClose, first.hash.value)
            assertEquals(policy.applicationPath(window.location.pathname), second.path.value)
            assertEquals("#first", second.hash.value)

            val secondPathBeforeClose = second.path.value
            val secondHashBeforeClose = second.hash.value
            second.close()
            window.history.pushState(null, "", "/navigator-listener-test-after-close?step=two#second")
            window.dispatchEvent(PopStateEvent("popstate"))

            assertEquals(secondPathBeforeClose, second.path.value)
            assertEquals(secondHashBeforeClose, second.hash.value)
            assertNotEquals(policy.applicationPath(window.location.pathname), second.path.value)
        } finally {
            runCatching { first.close() }
            runCatching { second.close() }
            window.history.replaceState(null, "", originalHref)
        }
    }

    @Test
    fun browserClicksUseNestedAnchorsPreserveQueriesAndStopInterceptingAfterClose() {
        val policy = NavigationPolicy(URL(document.baseURI).pathname)
        val target = policy.target("navigator-click-test")
        val anchor = document.createElement("a") as HTMLAnchorElement
        val nestedTarget = document.createElement("span")
        anchor.appendChild(nestedTarget)
        document.body!!.appendChild(anchor)
        val service = NavigatorService()

        try {
            anchor.href = window.location.origin + target.pathname + "?mode=full#details"
            val eligibleClick = MouseEvent(
                "click",
                MouseEventInit(bubbles = true, cancelable = true, button = 0),
            )

            nestedTarget.dispatchEvent(eligibleClick)

            assertEquals(true, eligibleClick.defaultPrevented)
            assertEquals(target.pathname, window.location.pathname)
            assertEquals("?mode=full", window.location.search)
            assertEquals("#details", window.location.hash)
            assertEquals(policy.applicationPath(target.pathname), service.path.value)
            assertEquals("#details", service.hash.value)

            anchor.href = window.location.href
            val modifiedClick = MouseEvent(
                "click",
                MouseEventInit(bubbles = true, cancelable = true, button = 0, ctrlKey = true),
            )
            nestedTarget.dispatchEvent(modifiedClick)
            assertEquals(false, modifiedClick.defaultPrevented)

            service.close()
            val afterCloseClick = MouseEvent(
                "click",
                MouseEventInit(bubbles = true, cancelable = true, button = 0),
            )
            nestedTarget.dispatchEvent(afterCloseClick)
            assertEquals(false, afterCloseClick.defaultPrevented)
        } finally {
            runCatching { service.close() }
            anchor.remove()
            window.history.replaceState(null, "", originalHref)
        }
    }
}
