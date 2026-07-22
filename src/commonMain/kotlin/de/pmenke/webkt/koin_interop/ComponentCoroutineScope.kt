package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.lifecycle.Lifetime
import de.pmenke.webkt.log.Logger
import kotlinx.coroutines.*

private val LOG = Logger("de.pmenke.webkt.koin_interop.ComponentCoroutineScope")

/**
 * A [CoroutineScope] tied to a component's internal lifetime.
 *
 * The owning Koin scope remains a compatibility trigger for closing that lifetime, but coroutine
 * ownership itself is independent from Koin.
 *
 * Uncaught exceptions from coroutines launched in this scope are logged with a reference to the component's
 * [dom element][Component.currentElement], to allow for easy identification of the source via browser developer tools.
 */
class ComponentCoroutineScope internal constructor(lifetime: Lifetime, componentRef: WeakReference<Component>)
    : CoroutineScope by CoroutineScope(lifetime.coroutineContext + exceptionHandler(componentRef))

// Keep only a weak component reference here. The coroutine scope can outlive the component
// until its lifetime owner closes, and must not defeat the finalization fallback in the meantime.
private fun exceptionHandler(componentRef: WeakReference<Component>) = CoroutineExceptionHandler { _, exception ->
    LOG.error("Unhandled exception in coroutine-scope, owned by",
        componentRef.deref()?.currentElement,
        exception.stackTraceToString(),
        exception)
}
