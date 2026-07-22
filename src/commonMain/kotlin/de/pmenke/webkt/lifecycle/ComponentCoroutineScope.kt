package de.pmenke.webkt.lifecycle

import de.pmenke.webkt.Component
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.log.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

private val LOG = Logger("de.pmenke.webkt.lifecycle.ComponentCoroutineScope")

/**
 * A [CoroutineScope] tied to a component's internal [Lifetime].
 *
 * Uncaught exceptions are logged with a reference to the component's
 * [DOM element][Component.currentElement], allowing the source to be identified in browser developer tools.
 */
internal class ComponentCoroutineScope(lifetime: Lifetime, componentRef: WeakReference<Component>) :
    CoroutineScope by CoroutineScope(lifetime.coroutineContext + exceptionHandler(componentRef))

// Keep only a weak component reference here. The scope may remain independently reachable until
// its lifetime closes and must not defeat the component's finalization fallback in the meantime.
private fun exceptionHandler(componentRef: WeakReference<Component>) = CoroutineExceptionHandler { _, exception ->
    LOG.error(
        "Unhandled exception in coroutine scope owned by",
        componentRef.deref()?.currentElement,
        exception.stackTraceToString(),
        exception,
    )
}
